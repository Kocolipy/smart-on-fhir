/**
 * The session's FHIRcast: connecting a release to its hub — resuming the
 * subscription the tab kept, or subscribing from scratch — and the one
 * connection the session holds, a module-level singleton rather than any
 * component's state, so it lasts the session whichever page is shown (ADR
 * 0016, "The connection's lifetime"). Every piece of the FHIRcast module's
 * state lives here and nowhere else: the connection the tab keeps
 * ({@link keeper}), every connection to tear down ({@link toTearDown}), and
 * the snapshot the card watches. {@link endFhircast} resets all of it, which
 * is what `test/setup.ts` calls after every test.
 */

import { instantMillis } from "../time";
import { watchers } from "../watchers";
import { Connection, type ConnectionOperations, type SmartToken, browserClock } from "./connection";
import {
  type FhircastConnectionState,
  type FhircastHandlers,
  HTTP_SCHEMES,
  type Hub,
  onLoopback,
  type PatientEvent,
  type Subscription,
} from "./model";
import { open } from "./notifications";
import { HubFailure, subscribe, unsubscribe } from "./protocol";
import type { FhircastRelease } from "./release";
import {
  clearFhircastStorage,
  keepTokenExpiry,
  keptTokenExpiry,
  persist,
  readStored,
} from "./storage";
import { tearDown } from "./teardown";

/**
 * How connecting came out: `connected`, staying connected until `close`;
 * `unavailable`, for anything the hub did or did not do; `blocked`, when the
 * browser kept the tab off the local network because the clinician refused
 * Chrome's Local Network Access prompt; or `ended`, when the SMART access
 * token's expiry passed before a socket was confirmed — already past, asking
 * the hub nothing.
 */
export type FhircastOutcome =
  | { kind: "connected"; close: () => void }
  | { kind: "unavailable" }
  | { kind: "blocked" }
  | { kind: "ended" };

/**
 * The connection the tab keeps: the one whose subscription {@link persist}
 * writes. A connection a later one has replaced keeps nothing.
 */
let keeper: Connection | null = null;

/**
 * Every connection this tab made since its session's FHIRcast last ended —
 * closed ones too, since their subscriptions may still stand — for
 * {@link endFhircast} to tear down.
 */
const toTearDown = new Set<Connection>();

const CONNECTION_UNAVAILABLE: FhircastOutcome = { kind: "unavailable" };

const CONNECTION_ENDED: FhircastOutcome = { kind: "ended" };

/**
 * A connection to `hub` with the SMART access token `token`, over the hub protocol, the socket and the browser's
 * clock, resuming `resumed` if any: the {@link keeper} from now on, and torn
 * down when the session's FHIRcast ends.
 */
function newKeeper(
  hub: Hub,
  token: SmartToken,
  handlers: Required<FhircastHandlers>,
  resumed: Subscription | null,
): Connection {
  const operations: ConnectionOperations = {
    clock: browserClock,
    keep: (subscription) => {
      if (keeper === connection) persist({ hub, subscription });
    },
    open,
    subscribe,
    unsubscribe,
  };
  const connection = new Connection(hub, token, handlers, operations, resumed);
  toTearDown.add(connection);
  keeper = connection;
  return connection;
}

/** Whether `kept` is the hub, and the topic, that `hub` names. */
const sameHub = (kept: Hub, hub: Hub): boolean =>
  kept.hubUrl === hub.hubUrl && kept.hubTopic === hub.hubTopic;

/**
 * Connects to `release`'s hub with its SMART access token, as
 * {@link connectFhircast} does, resuming the subscription the tab kept when
 * it belongs to the same hub and topic, and otherwise forgetting it; the tab
 * keeps the token's expiry either way. It reaches the connection without
 * waiting on anything, so a connection is torn down whenever the session's
 * FHIRcast ends. The connection ending with its token, before a socket is
 * confirmed or as the hub refuses, is `ended`.
 */
async function connectFor(
  release: FhircastRelease,
  handlers: Required<FhircastHandlers>,
): Promise<FhircastOutcome> {
  const hub: Hub = { hubTopic: release.hubTopic, hubUrl: release.hubUrl };
  const token: SmartToken = {
    accessToken: release.accessToken,
    expiresAt: instantMillis(release.expiresAt),
  };
  const stored = readStored();
  const resumed = stored !== null && sameHub(stored.hub, hub) ? stored.subscription : null;
  if (resumed === null) clearFhircastStorage();
  keepTokenExpiry(token.expiresAt);
  const connection = newKeeper(hub, token, handlers, resumed);
  const connected = await connection.connect().catch((failure: unknown) => ({ failure }));
  if (connection.ended) return CONNECTION_ENDED;
  if (typeof connected !== "boolean") throw connected.failure;
  return connected
    ? { close: () => connection.close(), kind: "connected" }
    : CONNECTION_UNAVAILABLE;
}

/** The permission Chrome's Local Network Access prompt answers. */
const LOCAL_NETWORK_ACCESS = "local-network-access";

/**
 * Whether the browser refused this tab the local network: the clinician
 * declined Chrome's Local Network Access prompt. Any browser without the
 * permission says nothing, and the hub is merely unavailable.
 */
async function blockedByBrowser(): Promise<boolean> {
  try {
    const status = await navigator.permissions.query({
      name: LOCAL_NETWORK_ACCESS as PermissionName,
    });
    return status.state === "denied";
  } catch {
    return false;
  }
}

/** The one generic outcome a failure to connect is: `blocked` when the browser kept the tab off the hub. */
async function failedOutcome(failure: unknown): Promise<FhircastOutcome> {
  if (failure instanceof HubFailure && failure.unreachable && (await blockedByBrowser())) {
    return { kind: "blocked" };
  }
  return CONNECTION_UNAVAILABLE;
}

/**
 * Connects to the hub `release` names, with its SMART access token; any
 * failure along the way is one generic outcome. The subscription the tab
 * kept, for the same hub and topic, is resumed; one for another is
 * forgotten; and with none to resume it subscribes from scratch. The
 * connection is torn down when the session's FHIRcast ends, whatever its
 * outcome.
 */
export async function connectFhircast(
  release: FhircastRelease,
  options: FhircastHandlers = {},
): Promise<FhircastOutcome> {
  if (!onLoopback(release.hubUrl, HTTP_SCHEMES)) return CONNECTION_UNAVAILABLE;
  const handlers: Required<FhircastHandlers> = {
    onConnection: options.onConnection ?? (() => undefined),
    onEvent: options.onEvent ?? (() => undefined),
  };
  try {
    return await connectFor(release, handlers);
  } catch (failure) {
    return failedOutcome(failure);
  }
}

/**
 * Where the session's FHIRcast connection has got to: on its way
 * (`connecting`), any {@link FhircastConnectionState}, or how connecting
 * failed (`unavailable`, `blocked`; see {@link FhircastOutcome}).
 */
export type FhircastStatus = "connecting" | FhircastConnectionState | "unavailable" | "blocked";

/**
 * What the session's FHIRcast connection shows: its status, and the last
 * event the hub sent it — `null` until one arrives, and again from each start.
 */
export interface FhircastSnapshot {
  status: FhircastStatus;
  last: PatientEvent | null;
}

/** Asks the backend for the release, through whatever seam the caller holds; `null` for none. */
export type FhircastReleaseSource = () => Promise<FhircastRelease | null>;

const NOT_STARTED: FhircastSnapshot = { last: null, status: "connecting" };

/**
 * The session's one FHIRcast connection: the attempt under way — where its
 * release comes from, and how to close what it connected — or `null` before
 * the first start and once the session's FHIRcast has ended
 * ({@link endFhircast}).
 */
let attempt: { source: FhircastReleaseSource; close: () => void } | null = null;

let snapshot: FhircastSnapshot = NOT_STARTED;

/** Whoever is watching what the connection shows. */
const watching = watchers();

/** Whoever is following the events the connection hears, each handed every one. */
const following = watchers<PatientEvent>();

/** `next` replaces what the connection shows, and every watcher hears of it. */
function show(next: Partial<FhircastSnapshot>) {
  snapshot = { ...snapshot, ...next };
  watching.changed();
}

/**
 * Whether the SMART access token the tab was last released has passed its
 * expiry, as the tab kept it: the backend refuses to release a token past its
 * expiry, so a reload or Retry it releases nothing to then is ended, not
 * unavailable. `false` with no expiry kept.
 */
function releasedTokenExpired(): boolean {
  const expiresAt = keptTokenExpiry();
  return expiresAt !== null && expiresAt <= Date.now();
}

/**
 * One attempt from the start: the release from `source`, then
 * {@link connectFhircast}, with no last event; with no release, `ended` once
 * the token the tab was last released has expired, otherwise `unavailable`. Whatever it hears once a later
 * attempt, or the end of the session, has replaced it is not shown, and a
 * connection it opens then is closed at once.
 */
function begin(source: FhircastReleaseSource) {
  const current = { close: () => undefined as void, source };
  attempt = current;
  const live = () => attempt === current;
  show({ last: null, status: "connecting" });
  void (async () => {
    const released = await source();
    if (!live()) return;
    if (released === null) {
      show({ status: releasedTokenExpired() ? "ended" : "unavailable" });
      return;
    }
    const outcome = await connectFhircast(released, {
      onConnection: (status) => {
        if (live()) show({ status });
      },
      onEvent: (last) => {
        if (!live()) return;
        show({ last });
        following.changed(last);
      },
    });
    if (outcome.kind === "connected") current.close = outcome.close;
    if (!live()) {
      current.close();
      return;
    }
    show({ status: outcome.kind });
  })();
}

/**
 * Starts the session's FHIRcast connection, with the release from `source`.
 * Idempotent: once started, a start changes nothing until the session's
 * FHIRcast ends ({@link endFhircast}) — not a remount, not a navigation.
 */
export function startFhircast(source: FhircastReleaseSource): void {
  if (attempt === null) begin(source);
}

/**
 * Connects again from the start — a fresh release, then the subscription the
 * tab kept resumed, or a fresh one — with no last event, closing the
 * connection before. Nothing before the first start.
 */
export function retryFhircast(): void {
  if (attempt === null) return;
  attempt.close();
  begin(attempt.source);
}

/** What the session's FHIRcast connection shows now: the same value until it changes. */
export function fhircastSnapshot(): FhircastSnapshot {
  return snapshot;
}

/** Calls `watcher` whenever what the connection shows changes; answers how to stop. */
export const watchFhircast: (watcher: () => void) => () => void = watching.watch;

/**
 * Hands `follower` every event the session's connection hears from now on,
 * as it is shown — none an attempt since replaced, or a session since ended,
 * hears; answers how to stop.
 */
export const followFhircastEvents: (follower: (event: PatientEvent) => void) => () => void =
  following.watch;

/**
 * Ends the session's FHIRcast: stops the session's connection, so the next
 * session's start connects anew, and forgets everything connecting kept at
 * once ({@link clearFhircastStorage}). Then it tears down every connection
 * the tab made in this page load, with the SMART access token each holds
 * (see {@link tearDown}). A subscription a page before a reload kept that no
 * connection here resumed is forgotten alone: the token it was made with is
 * not kept. Never fails: every step is best-effort and bounded, so it settles
 * within a few seconds however the hub behaves.
 */
export async function endFhircast(): Promise<void> {
  attempt = null;
  show(NOT_STARTED);
  keeper = null;
  clearFhircastStorage();
  const ending = [...toTearDown];
  toTearDown.clear();
  await tearDown(await Promise.all(ending.map((connection) => connection.teardown())));
}

/**
 * The session's FHIRcast: connecting a release to its hub — resuming what the
 * tab kept, or registering from scratch — and the one connection the session
 * holds, a module-level singleton rather than any component's state, so it
 * lasts the session whichever page is shown (ADR 0016, "The connection's
 * lifetime"). Every piece of the FHIRcast module's state lives here and
 * nowhere else: the connection the tab keeps ({@link keeper}), every
 * connection to tear down ({@link toTearDown}), the session generation, and
 * the snapshot the card watches. {@link endFhircast} resets all of it, which
 * is what `test/setup.ts` calls after every test.
 */

import { watchers } from "../watchers";
import { Connection, type ConnectionOperations, systemClock } from "./connection";
import {
  type Client,
  type FhircastConnectionState,
  type FhircastHandlers,
  HTTP_SCHEMES,
  onLoopback,
  type PatientEvent,
  type Stored,
  type Subscription,
} from "./model";
import { open } from "./notifications";
import {
  ClientRefused,
  deleteRegistration,
  HubFailure,
  hubToken,
  registerClient,
  subscribe,
} from "./protocol";
import type { FhircastRelease } from "./release";
import { clearFhircastStorage, persist, readStored } from "./storage";
import { cancel, tearDown, teardownOf } from "./teardown";

/**
 * How connecting came out: `connected`, staying connected until `close`;
 * `unavailable`, for anything the hub did or did not do; or
 * `blocked`, when the browser kept the tab off the local network because the
 * clinician refused Chrome's Local Network Access prompt.
 */
export type FhircastOutcome =
  { kind: "connected"; close: () => void } | { kind: "unavailable" } | { kind: "blocked" };

/**
 * The connection the tab keeps: the one whose registration and subscription
 * {@link persist} writes. A connection a later one has replaced keeps nothing.
 */
let keeper: Connection | null = null;

/**
 * Every connection this tab registered or resumed at a hub since its
 * session's FHIRcast last ended — closed ones too, since their registrations
 * still stand — for {@link endFhircast} to tear down.
 */
const toTearDown = new Set<Connection>();

/**
 * The generation of the session's FHIRcast in this tab, replaced each time
 * it ends: a connection begun under a generation since replaced belongs to a
 * session already over.
 */
let sessionGeneration: object = {};

const CONNECTION_UNAVAILABLE: FhircastOutcome = { kind: "unavailable" };

/**
 * A connection for `client`, over the hub protocol, the socket and the
 * browser's clock, whose subscriptions the tab keeps while it is the
 * {@link keeper}.
 */
function connectionFor(
  client: Client,
  handlers: Required<FhircastHandlers>,
  resumed: Subscription | null = null,
): Connection {
  const operations: ConnectionOperations = {
    cancel,
    clock: systemClock,
    hubToken,
    keep: (subscription) => {
      if (keeper === connection) persist({ client, subscription });
    },
    open,
    subscribe,
  };
  const connection = new Connection(client, handlers, operations, resumed);
  return connection;
}

/** `connection`'s outcome: connected, staying so until closed, once a socket was confirmed. */
const outcomeOf = (connection: Connection, confirmed: boolean): FhircastOutcome =>
  confirmed ? { close: () => connection.close(), kind: "connected" } : CONNECTION_UNAVAILABLE;

/**
 * Resumes the Hub Registration the tab kept, as {@link Connection.resume}
 * does; `refused` when the hub refuses its client at the token endpoint,
 * the registration then deleted, best-effort, and forgotten.
 */
async function resume(
  stored: Stored,
  handlers: Required<FhircastHandlers>,
): Promise<FhircastOutcome | "refused"> {
  const connection = connectionFor(stored.client, handlers, stored.subscription);
  toTearDown.add(connection);
  keeper = connection;
  try {
    return outcomeOf(connection, await connection.resume());
  } catch (failure) {
    if (!(failure instanceof ClientRefused)) throw failure;
    connection.close();
    if (toTearDown.delete(connection)) {
      clearFhircastStorage();
      if (stored.client.management !== null) await deleteRegistration(stored.client.management);
    }
    return "refused";
  }
}

/**
 * Connects from scratch: discovery, a fresh key registered with the release's
 * SMART access token, then steps 3 to 5. Torn down at once, and `unavailable`,
 * when the session's FHIRcast ended while registering.
 */
async function connectAnew(
  release: FhircastRelease,
  handlers: Required<FhircastHandlers>,
  begun: object,
): Promise<FhircastOutcome> {
  const client = await registerClient(release);
  const connection = connectionFor(client, handlers);
  if (sessionGeneration !== begun) {
    await tearDown([await connection.teardown()]);
    return CONNECTION_UNAVAILABLE;
  }
  toTearDown.add(connection);
  keeper = connection;
  persist({ client, subscription: null });
  return outcomeOf(connection, await connection.subscribe());
}

/** Whether `stored` belongs to the hub and topic `release` names. */
const sameHub = ({ client: { hub } }: Stored, release: FhircastRelease): boolean =>
  hub.hubUrl === release.hubUrl && hub.hubTopic === release.hubTopic;

/**
 * What the tab kept, made use of: resumed ({@link resume}) when it belongs to
 * `release`'s hub and topic; otherwise forgotten, and torn down when there is
 * any. `null` once nothing was resumed — for none kept, one for another hub
 * or topic, or a client the hub refuses — so that the tab connects anew.
 */
async function resumeKept(
  stored: Stored | null,
  release: FhircastRelease,
  handlers: Required<FhircastHandlers>,
): Promise<FhircastOutcome | null> {
  if (stored !== null && sameHub(stored, release)) {
    const resumed = await resume(stored, handlers);
    return resumed === "refused" ? null : resumed;
  }
  clearFhircastStorage();
  if (stored !== null) await tearDown([teardownOf(stored)]);
  return null;
}

/**
 * Connects to `release`'s hub, as {@link connectFhircast} does, for the
 * session's FHIRcast generation `begun`: `unavailable` once that has ended.
 */
async function connectFor(
  release: FhircastRelease,
  handlers: Required<FhircastHandlers>,
  begun: object,
): Promise<FhircastOutcome> {
  const stored = await readStored();
  if (sessionGeneration !== begun) return CONNECTION_UNAVAILABLE;
  const resumed = await resumeKept(stored, release, handlers);
  if (resumed !== null) return resumed;
  if (sessionGeneration !== begun) return CONNECTION_UNAVAILABLE;
  return connectAnew(release, handlers, begun);
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
 * Connects to the hub `release` names; any failure along the way is one
 * generic outcome. The Hub Registration the tab kept, for the same hub and
 * topic, is resumed ({@link resume}); one for another is torn down and
 * forgotten; and with none to resume, or a client the hub refuses, it
 * connects from scratch ({@link connectAnew}). Once registered or resumed,
 * the connection is torn down when the session's FHIRcast ends, whatever its
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
    return await connectFor(release, handlers, sessionGeneration);
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
 * One attempt from the start: the release from `source`, then
 * {@link connectFhircast}, with no last event. Whatever it hears once a later
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
      show({ status: "unavailable" });
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
 * Connects again from the start — a fresh release, then the Hub Registration
 * the tab kept resumed, or a fresh one — with no last event, closing the
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
 * once ({@link clearFhircastStorage}). Then it tears down, side by side, every
 * connection the tab registered or resumed in this page load, and what the
 * tab kept — a registration a page before a reload left, even one this page
 * never resumed (see {@link tearDown}). Never fails: every step is
 * best-effort and bounded, so it settles within a few seconds however the hub
 * behaves.
 */
export async function endFhircast(): Promise<void> {
  attempt = null;
  show(NOT_STARTED);
  sessionGeneration = {};
  keeper = null;
  const kept = readStored();
  clearFhircastStorage();
  const ending = [...toTearDown];
  toTearDown.clear();
  const stored = await kept;
  const teardowns = await Promise.all(ending.map((connection) => connection.teardown()));
  await tearDown(stored === null ? teardowns : [...teardowns, teardownOf(stored)]);
}

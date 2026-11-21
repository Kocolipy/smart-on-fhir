/**
 * The session's FHIRcast: connecting a release to its hub — resuming the
 * subscription the tab kept, or subscribing from scratch — and the one
 * connection the session holds, a module-level singleton rather than any
 * component's state, so it lasts the session whichever page is shown (ADR
 * 0016, "The connection's lifetime"). Every piece of the FHIRcast module's
 * state lives here and nowhere else: the attempt under way, every connection
 * to tear down ({@link toTearDown}), and the snapshot the card watches, whose
 * status is the one the connection reports. {@link endFhircast} resets all of
 * it, tearing down at the hub as it does, which is what `test/setup.ts` calls
 * after every test.
 */

import { instantMillis } from "../time";
import { watchers } from "../watchers";
import { browserClientStore } from "./client-key";
import { Connection, type ConnectionTerms, type Teardown } from "./connection";
import { heldClientHubToken, hubToken } from "./hub-token";
import {
  type ConnectionListener,
  type FhircastStatus,
  HTTP_SCHEMES,
  type Hub,
  onLoopback,
  type PatientEvent,
} from "./model";
import { type HubCredentials, UNSUBSCRIBE_TIMEOUT_MS, unsubscribe } from "./protocol";
import { carriesToken, type FhircastRelease } from "./release";
import { clearFhircastStorage, readStored } from "./storage";

/**
 * Every connection this tab made since its session's FHIRcast last ended —
 * closed ones too, since their subscriptions may still stand — for
 * {@link endFhircast} to tear down.
 */
const toTearDown = new Set<Connection>();

/** Whether `kept` is the hub, and the topic, that `hub` names. */
const sameHub = (kept: Hub, hub: Hub): boolean =>
  kept.hubUrl === hub.hubUrl && kept.hubTopic === hub.hubTopic;

/**
 * The SMART access token `release` carries while its expiry is still ahead
 * on the tab's clock; `null` once it has passed, and for a release of the
 * hub alone.
 */
function liveAccessToken(release: FhircastRelease): string | null {
  if (!carriesToken(release)) return null;
  return instantMillis(release.expiresAt) > Date.now() ? release.accessToken : null;
}

/**
 * A connection to `release`'s hub, started, its status and events told to
 * `listener`: made with the hub tokens obtained for the browser profile's
 * dynamic client — registered with the release's SMART access token, while it
 * is unexpired, when the profile holds none or the hub has forgotten it —
 * and lasting the session, the SMART access token's expiry included. Its
 * teardown obtains its hub token for the client the profile kept, registering
 * none and forgetting none. It resumes the subscription the tab kept when it belongs to the same
 * hub and topic, and otherwise forgets it. It is torn down when the session's
 * FHIRcast ends, whatever it came to.
 */
function connect(release: FhircastRelease, listener: ConnectionListener): Connection {
  const hub: Hub = { hubTopic: release.hubTopic, hubUrl: release.hubUrl };
  const credentials = (): HubCredentials => ({
    accessToken: liveAccessToken(release),
    hub,
    softwareId: release.softwareId,
  });
  const terms: ConnectionTerms = {
    obtainHubToken: () => hubToken(credentials(), browserClientStore),
    teardownHubToken: () => heldClientHubToken(hub, browserClientStore),
  };
  const stored = readStored();
  const resumed = stored !== null && sameHub(stored.hub, hub) ? stored.subscription : null;
  if (resumed === null) clearFhircastStorage();
  const connection = new Connection(hub, terms, listener, resumed);
  toTearDown.add(connection);
  void connection.start();
  return connection;
}

/**
 * What the session's FHIRcast connection shows: its status, and the last
 * event the hub sent it — `null` until one arrives, and again from each start.
 */
interface FhircastSnapshot {
  status: FhircastStatus;
  last: PatientEvent | null;
}

/** Asks the backend for the release, through whatever seam the caller holds; `null` for none. */
export type FhircastReleaseSource = () => Promise<FhircastRelease | null>;

const NOT_STARTED: FhircastSnapshot = { last: null, status: "connecting" };

/**
 * The session's one FHIRcast connection: the attempt under way — where its
 * release comes from, and how to close the connection it made — or `null` before
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
 * One attempt from the start: the release from `source`, then a connection to
 * its hub, `connecting` with no last event until the connection says
 * otherwise — whether or not the release still carries the SMART access
 * token, since the profile's dynamic client obtains hub tokens without it;
 * `unavailable`, asking the hub nothing, with no release, and for a hub off
 * `http(s)` on `127.0.0.1`. Whatever it hears once a later attempt, or the
 * end of the session, has replaced it is not shown: its connection is closed
 * then, and reports nothing more, and an event the teardown's socket still
 * hears is dropped.
 */
function begin(source: FhircastReleaseSource) {
  const current = { close: () => undefined as void, source };
  attempt = current;
  const live = () => attempt === current;
  show({ last: null, status: "connecting" });
  void (async () => {
    const released = await source();
    if (!live()) return;
    if (released === null || !onLoopback(released.hubUrl, HTTP_SCHEMES)) {
      show({ status: "unavailable" });
      return;
    }
    const connection = connect(released, {
      event: (last) => {
        if (!live()) return;
        show({ last });
        following.changed(last);
      },
      // A connection reports nothing once closed, and a later attempt, or the
      // end of the session, closes this one: every status it reports is live.
      status: (status) => show({ status }),
    });
    current.close = () => connection.close();
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
 * connection before at once, connected or not. Nothing before the first start.
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
 * Unsubscribes each of `endpoints` side by side, with the hub token
 * `teardown` hands over — the one its connection holds, or a fresh one once
 * that has expired; none, when no fresh one is to be had.
 */
async function cancelled(teardown: Teardown, endpoints: readonly string[]): Promise<void> {
  let token: string;
  try {
    token = await teardown.token();
  } catch {
    // No hub token to unsubscribe with: the leases end the subscriptions at the hub.
    return;
  }
  await Promise.all(endpoints.map((endpoint) => unsubscribe(teardown.hub, endpoint, token)));
}

/** `work`, given up once {@link UNSUBSCRIBE_TIMEOUT_MS} have passed, whichever is first. */
async function withinBound(work: Promise<unknown>): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const givenUp = new Promise<void>((settle) => {
    timer = setTimeout(settle, UNSUBSCRIBE_TIMEOUT_MS);
  });
  await Promise.race([work, givenUp]);
  clearTimeout(timer);
}

/**
 * Ends the session's FHIRcast: stops the session's connection, so the next
 * session's start connects anew, and forgets everything connecting kept in
 * the tab at once ({@link clearFhircastStorage}). Then it tears down every
 * connection the tab made in this page load: every endpoint they name
 * unsubscribed, side by side and once however many name it — with the hub
 * token of the last that does, the newest the tab obtained, or a fresh one
 * for the client the profile kept once that one has expired — and only then
 * every socket closed. A subscription a page before a reload kept that no
 * connection here resumed is forgotten alone: the token it was made with is
 * not kept. It keeps the profile's dynamic client: the next session in this
 * browser profile connects with it. Never fails, and settles within the
 * unsubscription's 3 seconds however the hub behaves, a fresh hub token and
 * a subscription moved off still being unsubscribed included (ADR 0011).
 */
export async function endFhircast(): Promise<void> {
  attempt = null;
  show(NOT_STARTED);
  clearFhircastStorage();
  const ending = [...toTearDown];
  toTearDown.clear();
  const teardowns = ending.map((connection) => connection.teardown());
  const byEndpoint = new Map<string, Teardown>();
  for (const teardown of teardowns) {
    for (const endpoint of teardown.endpoints) byEndpoint.set(endpoint, teardown);
  }
  const byTeardown = new Map<Teardown, string[]>();
  for (const [endpoint, teardown] of byEndpoint) {
    byTeardown.set(teardown, [...(byTeardown.get(teardown) ?? []), endpoint]);
  }
  // The subscriptions moved off finish unsubscribing first, within the same
  // bound: a hub that leaves them unanswered holds nothing up past it.
  await withinBound(
    Promise.all(teardowns.map(({ retired }) => retired)).then(() =>
      Promise.all([...byTeardown].map(([teardown, endpoints]) => cancelled(teardown, endpoints))),
    ),
  );
  for (const { close } of teardowns) close();
}

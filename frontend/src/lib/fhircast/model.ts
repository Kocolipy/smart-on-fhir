/**
 * The FHIRcast module's model: the vocabulary every other FHIRcast module
 * reads and writes with, and imports from here rather than from each other —
 * the hub a connection belongs to and its Hub Subscriptions as the tab holds
 * them, the events a caller is handed, and the loopback rule every URL a hub
 * names is held to. It owns no state and makes no request.
 */

/**
 * The two events the subscription asks for, by their FHIRcast STU2 names,
 * which are case-insensitive: a notification naming either in any case is
 * that event, and is handed on under this spelling.
 */
export const PATIENT_EVENTS = ["patient-open", "patient-close"] as const;

/**
 * One event notification the hub sent, as far as it is shown: the event, the
 * notification's `timestamp` as the hub wrote it, and the Patient FHIR id from
 * its `context` — `null` when the context names none within
 * `[A-Za-z0-9\-.]{1,64}`. Nothing else of the context is kept.
 */
export interface PatientEvent {
  event: (typeof PATIENT_EVENTS)[number];
  timestamp: string;
  patientId: string | null;
}

/**
 * Where the session's FHIRcast connection has got to, the one status it
 * reports: `connecting` from each start; then `connected`, or how connecting
 * failed — `unavailable`, for anything the hub did or did not do, or
 * `blocked`, when the browser kept the tab off the local network because the
 * clinician refused Chrome's Local Network Access prompt. Once connected,
 * `reconnecting` when its socket drops, the hub denies the live Hub
 * Subscription or a renewal fails, `connected` again when a retry wins it
 * back, and `disconnected` when the retries run out. `ended` is final, and
 * means one thing: the hub has forgotten the browser profile's dynamic client
 * and the tab cannot register one again — no SMART access token is left to
 * register with, or registering once more failed — so only a fresh launch
 * from Epic connects again.
 */
export type FhircastStatus =
  | "connecting"
  | "connected"
  | "reconnecting"
  | "disconnected"
  | "unavailable"
  | "blocked"
  | "ended";

/** What a connection tells whoever made it: each status it moves to, and each event it hears. */
export interface ConnectionListener {
  /** Called each time the connection's status changes. */
  status: (status: FhircastStatus) => void;
  /** Called with each valid event notification, once it is acknowledged. */
  event: (event: PatientEvent) => void;
}

/** The one host the hub, and everything it names, may be on. */
const LOOPBACK = "127.0.0.1";

export const HTTP_SCHEMES = ["http:", "https:"];

export const WS_SCHEMES = ["ws:", "wss:"];

/** Whether `url` is a URL of one of `schemes` on `127.0.0.1`, with no credentials in it. */
export function onLoopback(url: string, schemes: readonly string[]): boolean {
  if (!URL.canParse(url)) return false;
  const parsed = new URL(url);
  return (
    schemes.includes(parsed.protocol) &&
    parsed.hostname === LOOPBACK &&
    parsed.username === "" &&
    parsed.password === ""
  );
}

/**
 * The member `key` of `value`, or `undefined` when `value` is no object or has
 * no such member: a lenient read, for where an absence is an answer and not a
 * malformed message.
 */
export function memberOf(value: unknown, key: string): unknown {
  return Reflect.get(Object(value), key);
}

/**
 * A lifetime a hub stated, in seconds: a positive number, or `null` for
 * anything else, so that a hub that says nothing usable is never renewed by.
 */
export function secondsIn(value: unknown): number | null {
  return typeof value === "number" && value > 0 ? value : null;
}

/** The hub a connection belongs to, and the topic it subscribes on there. */
export interface Hub {
  hubUrl: string;
  hubTopic: string;
}

/**
 * A Hub Subscription the hub confirmed a socket on: its channel endpoint, when
 * its lease runs out, and when the hub token it was made with expires, on
 * `Date.now()`'s clock — `null` for a lifetime the hub did not state. The hub
 * holds the subscription only while both last.
 */
export interface Subscription {
  endpoint: string;
  leaseExpiresAt: number | null;
  hubTokenExpiresAt: number | null;
}

/** What the tab keeps: its hub, and its one live Hub Subscription. */
export interface Stored {
  hub: Hub;
  subscription: Subscription;
}

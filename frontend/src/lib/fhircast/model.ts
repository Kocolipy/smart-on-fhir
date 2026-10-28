/**
 * The FHIRcast module's model: the vocabulary every other FHIRcast module
 * reads and writes with, and imports from here rather than from each other —
 * what a Hub Registration and its subscriptions are as the tab holds them, the
 * events a caller is handed, and the loopback rule every URL a hub names is
 * held to. It owns no state and makes no request.
 */

/** The two events the subscription asks for, as this module names them. */
export const PATIENT_EVENTS = ["Patient-open", "Patient-close"] as const;

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
 * Where a connection that was connected has got to: `reconnecting` once its
 * socket drops or a renewal fails, `connected` again when a retry wins it
 * back, and `disconnected` when the retries run out.
 */
export type FhircastConnectionState = "connected" | "reconnecting" | "disconnected";

/** What a caller hears of a connection. */
export interface FhircastHandlers {
  /** Called each time a connected connection is lost, won back or given up. */
  onConnection?: (state: FhircastConnectionState) => void;
  /** Called with each valid event notification, once it is acknowledged. */
  onEvent?: (event: PatientEvent) => void;
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

/** The key the tab signs its assertions with: ECDSA on P-384, for ES384. */
export const ES384 = { name: "ECDSA", namedCurve: "P-384" } as const;

/** The hub a connection belongs to, and the topic it subscribes on there. */
export interface Hub {
  hubUrl: string;
  hubTopic: string;
}

/**
 * The private key this tab signs its assertions with, the `kid` its public
 * half is registered under, and the key exported, as the tab keeps it.
 */
export interface SigningKey {
  keyId: string;
  privateKey: CryptoKey;
  jwk: JsonWebKey;
}

/**
 * What RFC 7592 manages the registration by: its `registration_client_uri`,
 * held to `127.0.0.1`, and the `registration_access_token` that authorizes it.
 */
export interface RegistrationManagement {
  uri: string;
  token: string;
}

/** The registration the hub answered: the dynamic `client_id`, and how to delete it, if the hub says. */
export interface Registration {
  clientId: string;
  management: RegistrationManagement | null;
}

/**
 * A Hub Registration, as the tab holds it: the hub and topic it belongs to,
 * the dynamic `client_id` and the key it signs as, the token and revocation
 * endpoints it was registered beside, and how to delete it, if the hub said.
 */
export interface Client extends Registration {
  hub: Hub;
  signing: SigningKey;
  tokenEndpoint: string;
  /** RFC 7009's, which tearing down revokes Hub Tokens at: `null` when the hub names none. */
  revocation: string | null;
}

/** A Hub Token, and how long the hub says it lasts: `null` when it does not say. */
export interface HubToken {
  token: string;
  lifetime: number | null;
}

/**
 * A Hub Token the tab was granted, and the channel endpoint subscribed with
 * it: `null` until the hub answered one.
 */
export interface Granted {
  endpoint: string | null;
  hubToken: string;
}

/**
 * A Hub Subscription the hub confirmed a socket on: its channel endpoint, the
 * Hub Token it was made with, and when each of the two runs out, on
 * `Date.now()`'s clock — `null` for a lifetime the hub did not say.
 */
export interface Subscription extends Granted {
  endpoint: string;
  hubTokenExpiresAt: number | null;
  leaseExpiresAt: number | null;
}

/** What the tab keeps: a Hub Registration, and its one live Hub Subscription, if any. */
export interface Stored {
  client: Client;
  subscription: Subscription | null;
}

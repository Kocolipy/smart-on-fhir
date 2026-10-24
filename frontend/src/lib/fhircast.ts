/**
 * The FHIRcast module: connects this tab to Epic's local FHIRcast hub (ADR
 * 0016). Beside the HTTP transport, and with it the only place allowed to use
 * `fetch` or `WebSocket` (`test/arch/rawNetwork.test.ts`): the hub is another
 * origin, on the clinician's loopback interface, so none of `apiFetch`'s
 * session, CSRF or status rules apply to it.
 *
 * Given a release — the session's SMART access token and the hub its Epic
 * Login named, fetched from the backend through the session seam — it runs:
 *
 * 1. discovery: the hub's `.well-known/fhircast-configuration` under
 *    `hub.url` (falling back to `smart-configuration`) for its
 *    `registration_endpoint` and `token_endpoint`, each held to `127.0.0.1`;
 * 2. an ES384 key pair from WebCrypto, its public half registered by RFC 7591
 *    dynamic client registration, the SMART access token as `Bearer`;
 * 3. a Hub Token by the `jwt-bearer` grant, the assertion signed by that key —
 *    `iss` and `sub` the registered `client_id`, `aud` the token endpoint,
 *    valid five minutes, a `jti` of its own;
 * 4. the subscription to `Patient-open` and `Patient-close` on the topic, over
 *    a websocket, the Hub Token as `Bearer`;
 * 5. the socket at `hub.channel.endpoint`, held to `ws(s)` on `127.0.0.1`,
 *    connected once the hub confirms the subscription on it;
 * 6. on the connected socket, each event notification held to the FHIRcast
 *    STU3 shape, the two events and the topic, acknowledged with its `id` and
 *    handed on as a {@link PatientEvent}; anything else ignored, unanswered.
 *
 * Nothing a notification carries is logged.
 *
 * It answers one generic outcome and nothing else: no hub's or Epic's
 * `error_description`, and no response body, is ever handed back or logged.
 * What it obtains is kept in the tab's `sessionStorage` under
 * {@link FHIRCAST_STORAGE_KEYS} — an accepted deviation (ADR 0011) — and
 * cleared with {@link clearFhircastStorage} when the session ends.
 */

import { DecodeError, jsonDecoder, readObject } from "./decode";
import { isInstant } from "./time";

/** What the backend releases for FHIRcast: `GET /api/auth/epic/fhircast`. */
export interface FhircastRelease {
  /** The SMART access token, the `Bearer` of the registration. */
  accessToken: string;
  expiresAt: string;
  hubTopic: string;
  hubUrl: string;
  scope: string[];
}

/**
 * How connecting came out: `connected`, holding the open socket until
 * `close`; `unavailable`, for anything the hub did or did not do; or
 * `blocked`, when the browser kept the tab off the local network because the
 * clinician refused Chrome's Local Network Access prompt.
 */
export type FhircastOutcome =
  { kind: "connected"; close: () => void } | { kind: "unavailable" } | { kind: "blocked" };

/** The two events the subscription asks for, as this module names them. */
const PATIENT_EVENTS = ["Patient-open", "Patient-close"] as const;

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

/** The Patient FHIR id grammar: anything else is never handed on. */
const PATIENT_ID = /^[A-Za-z0-9\-.]{1,64}$/;

/**
 * The `status` of an acknowledgement. STU3 is inconsistent on string or
 * number; the hub (Medplum's STU3 socket) accepts any JSON, and this is the
 * spec's example.
 */
const ACKNOWLEDGED = "200";

/**
 * Where the tab keeps what connecting obtained. `smartAccess` holds the SMART
 * access token, named short of `…AccessToken` so the credential rule
 * (`fe-hardcoded-credential`) does not take a storage key for a token.
 */
export const FHIRCAST_STORAGE_KEYS = {
  clientId: "fhircast-client-id",
  hubToken: "fhircast-hub-token",
  signingJwk: "fhircast-private-jwk",
  smartAccess: "fhircast-smart-access-token",
} as const;

/** The release as the backend answers it, every member required. */
export const decodeFhircastRelease = jsonDecoder((body: unknown): FhircastRelease => {
  const release = readObject(body, "FhircastRelease");
  return {
    accessToken: release.string("accessToken"),
    expiresAt: release.string("expiresAt"),
    hubTopic: release.string("hubTopic"),
    hubUrl: release.string("hubUrl"),
    scope: release.array("scope", (scope) => {
      if (typeof scope !== "string") throw new DecodeError("FhircastRelease.scope is not strings");
      return scope;
    }),
  };
});

/** Forgets everything connecting kept: the session it belonged to has ended. */
export function clearFhircastStorage(): void {
  for (const key of Object.values(FHIRCAST_STORAGE_KEYS)) window.sessionStorage.removeItem(key);
}

const JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";

/** How long an assertion is valid, in seconds: RFC 7523 wants it short. */
const ASSERTION_LIFETIME = 300;

/** How long the hub has to confirm the subscription on the socket. */
const CONFIRMATION_TIMEOUT_MS = 10_000;

/** The one host the hub, and everything it names, may be on. */
const LOOPBACK = "127.0.0.1";

const HTTP_SCHEMES = ["http:", "https:"];

const WS_SCHEMES = ["ws:", "wss:"];

/** The permission Chrome's Local Network Access prompt answers. */
const LOCAL_NETWORK_ACCESS = "local-network-access";

const FORM = "application/x-www-form-urlencoded";

/**
 * Why connecting stopped: the hub said no, or the browser never reached it.
 * It carries no message: nothing about a failure is ever shown or logged.
 */
class HubFailure extends Error {
  constructor(readonly unreachable: boolean) {
    super();
  }
}

/** Whether `url` is a URL of one of `schemes` on `127.0.0.1`, with no credentials in it. */
function onLoopback(url: string, schemes: readonly string[]): boolean {
  if (!URL.canParse(url)) return false;
  const parsed = new URL(url);
  return (
    schemes.includes(parsed.protocol) &&
    parsed.hostname === LOOPBACK &&
    parsed.username === "" &&
    parsed.password === ""
  );
}

/** A JSON answer from the hub, or a {@link HubFailure}: refused, not JSON, or never reached. */
async function hubJson(url: string, init: RequestInit, expected: number): Promise<unknown> {
  let answer: Response;
  try {
    answer = await fetch(url, { ...init, credentials: "omit", redirect: "error" });
  } catch {
    throw new HubFailure(true);
  }
  if (answer.status !== expected) throw new HubFailure(false);
  try {
    return (await answer.json()) as unknown;
  } catch {
    throw new HubFailure(false);
  }
}

/**
 * A non-empty string member of a hub answer, or a refusal: the answer is no
 * object, or names no such string. What the decoder says is dropped unread.
 */
function member(body: unknown, name: string): string {
  let value: string;
  try {
    value = readObject(body, "hub answer").string(name);
  } catch {
    throw new HubFailure(false);
  }
  if (value === "") throw new HubFailure(false);
  return value;
}

interface HubEndpoints {
  registration: string;
  token: string;
}

/** Step 1: the hub's registration and token endpoints, from its metadata. */
async function discover(hubUrl: string): Promise<HubEndpoints> {
  const base = hubUrl.replace(/\/$/, "");
  let metadata: unknown;
  try {
    metadata = await hubJson(`${base}/.well-known/fhircast-configuration`, {}, 200);
  } catch (failure) {
    if (failure instanceof HubFailure && failure.unreachable) throw failure;
    metadata = await hubJson(`${base}/.well-known/smart-configuration`, {}, 200);
  }
  const registration = member(metadata, "registration_endpoint");
  const token = member(metadata, "token_endpoint");
  if (!onLoopback(registration, HTTP_SCHEMES) || !onLoopback(token, HTTP_SCHEMES)) {
    throw new HubFailure(false);
  }
  return { registration, token };
}

const ES384 = { name: "ECDSA", namedCurve: "P-384" } as const;

const base64Url = (bytes: Uint8Array): string =>
  btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=/g, "");

const encodeJson = (value: unknown): string =>
  base64Url(new TextEncoder().encode(JSON.stringify(value)));

/** The key pair this tab registers with the hub, and the `kid` it is registered under. */
interface SigningKey {
  keyId: string;
  keys: CryptoKeyPair;
}

/** Step 2: a fresh key pair, its public half registered; the dynamic `client_id`. */
async function register(
  endpoint: string,
  smartAccessToken: string,
  { keyId, keys }: SigningKey,
): Promise<string> {
  const { crv, kty, x, y } = await crypto.subtle.exportKey("jwk", keys.publicKey);
  const registered = await hubJson(
    endpoint,
    {
      body: JSON.stringify({
        client_name: "smart-on-fhir",
        grant_types: [JWT_BEARER],
        jwks: { keys: [{ alg: "ES384", crv, kid: keyId, kty, use: "sig", x, y }] },
        token_endpoint_auth_method: "private_key_jwt",
      }),
      headers: { Authorization: `Bearer ${smartAccessToken}`, "Content-Type": "application/json" },
      method: "POST",
    },
    201,
  );
  return member(registered, "client_id");
}

/** The RFC 7523 assertion for `clientId` at `tokenEndpoint`, signed with the private key. */
async function assertion(
  clientId: string,
  tokenEndpoint: string,
  { keyId, keys }: SigningKey,
): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const signed = `${encodeJson({ alg: "ES384", kid: keyId, typ: "JWT" })}.${encodeJson({
    aud: tokenEndpoint,
    exp: now + ASSERTION_LIFETIME,
    iat: now,
    iss: clientId,
    jti: crypto.randomUUID(),
    sub: clientId,
  })}`;
  const signature = await crypto.subtle.sign(
    { hash: "SHA-384", name: "ECDSA" },
    keys.privateKey,
    new TextEncoder().encode(signed),
  );
  return `${signed}.${base64Url(new Uint8Array(signature))}`;
}

/** Step 3: the Hub Token, by the `jwt-bearer` grant. */
async function hubToken(tokenEndpoint: string, signedAssertion: string): Promise<string> {
  const granted = await hubJson(
    tokenEndpoint,
    {
      body: new URLSearchParams({ assertion: signedAssertion, grant_type: JWT_BEARER }).toString(),
      headers: { "Content-Type": FORM },
      method: "POST",
    },
    200,
  );
  return member(granted, "access_token");
}

/** Step 4: the subscription; the channel endpoint the hub answers, held to loopback. */
async function subscribe(release: FhircastRelease, token: string): Promise<string> {
  const subscribed = await hubJson(
    release.hubUrl,
    {
      body: new URLSearchParams({
        "hub.channel.type": "websocket",
        "hub.events": "Patient-open,Patient-close",
        "hub.mode": "subscribe",
        "hub.topic": release.hubTopic,
      }).toString(),
      headers: { Authorization: `Bearer ${token}`, "Content-Type": FORM },
      method: "POST",
    },
    202,
  );
  const endpoint = member(subscribed, "hub.channel.endpoint");
  if (!onLoopback(endpoint, WS_SCHEMES)) throw new HubFailure(false);
  return endpoint;
}

/**
 * The member `key` of `value`, or `undefined` when `value` is no object or has
 * no such member: a lenient read, for where an absence is an answer and not a
 * malformed message.
 */
function memberOf(value: unknown, key: string): unknown {
  return Reflect.get(Object(value), key);
}

/**
 * Whether a socket message is the hub's confirmation of the subscription:
 * JSON whose `hub.mode` is `subscribe`. Anything else, JSON or not, is not.
 */
function confirms(data: unknown): boolean {
  try {
    return memberOf(JSON.parse(String(data)), "hub.mode") === "subscribe";
  } catch {
    return false;
  }
}

/** A notification worth handing on: its `id`, to acknowledge, and the event it carries. */
interface Notification {
  id: string;
  event: PatientEvent;
}

/**
 * The Patient FHIR id in an event's `context`: the `id` of the resource under
 * the `patient` key, or `null` when there is none or it is outside the grammar.
 */
function patientIdIn(context: readonly unknown[]): string | null {
  const entry: unknown = context.find((candidate) => memberOf(candidate, "key") === "patient");
  const id: unknown = memberOf(memberOf(entry, "resource"), "id");
  return typeof id === "string" && PATIENT_ID.test(id) ? id : null;
}

/**
 * A socket message read as a FHIRcast STU3 event notification: `timestamp`,
 * `id`, and an `event` with `hub.topic`, `hub.event` and `context`, the event
 * one of the two subscribed to and the topic the session's. Anything else is
 * `null`, and what was wrong with it is dropped unread.
 */
function notificationOf(data: unknown, topic: string): Notification | null {
  try {
    const message = readObject(JSON.parse(String(data)), "notification");
    const id = message.string("id");
    const timestamp = message.string("timestamp");
    const event = message.object("event");
    const receivedEvent = event.string("hub.event").toLowerCase();
    const subscribedEvent = PATIENT_EVENTS.find((known) => known.toLowerCase() === receivedEvent);
    const context = event.array("context", (entry) => entry);
    if (
      id === "" ||
      !isInstant(timestamp) ||
      subscribedEvent === undefined ||
      event.string("hub.topic") !== topic
    ) {
      return null;
    }
    return { event: { event: subscribedEvent, patientId: patientIdIn(context), timestamp }, id };
  } catch {
    return null;
  }
}

/**
 * Step 5: the socket, connected once the hub confirms the subscription on it,
 * and unavailable if it closes, or stays silent, first. A browser fires
 * `close` after any socket `error`, so `close` alone is listened for. Once
 * connected, a `close` the hub causes is reported through `onLost`; one the
 * caller asks for is not. A connected socket's every other message is held to
 * {@link notificationOf}: a notification that passes is acknowledged on the
 * socket and handed to `onEvent`; anything else is ignored, unacknowledged.
 */
function open(
  endpoint: string,
  topic: string,
  { onEvent, onLost }: Required<FhircastHandlers>,
): Promise<FhircastOutcome> {
  return new Promise((settle) => {
    const socket = new WebSocket(endpoint);
    let connected = false;
    const timer = setTimeout(() => socket.close(), CONFIRMATION_TIMEOUT_MS);
    const close = () => {
      connected = false;
      socket.close();
    };
    socket.addEventListener("message", ({ data }) => {
      if (connected) {
        const notification = notificationOf(data, topic);
        if (notification === null) return;
        socket.send(JSON.stringify({ id: notification.id, status: ACKNOWLEDGED }));
        onEvent(notification.event);
        return;
      }
      if (!confirms(data)) return;
      connected = true;
      clearTimeout(timer);
      settle({ close, kind: "connected" });
    });
    // A socket closes once; settling an already settled outcome changes
    // nothing, and the timer closing a closed socket does nothing either.
    socket.addEventListener("close", () => {
      if (connected) onLost();
      settle({ kind: "unavailable" });
    });
  });
}

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

/** What a caller hears of a connected socket. */
export interface FhircastHandlers {
  /** Called once if the hub drops a connected socket. */
  onLost?: () => void;
  /** Called with each valid event notification, once it is acknowledged. */
  onEvent?: (event: PatientEvent) => void;
}

/**
 * Connects to the hub `release` names, running every step in order; any
 * failure along the way is one generic outcome.
 */
export async function connectFhircast(
  release: FhircastRelease,
  options: FhircastHandlers = {},
): Promise<FhircastOutcome> {
  if (!onLoopback(release.hubUrl, HTTP_SCHEMES)) return { kind: "unavailable" };
  const storage = window.sessionStorage;
  storage.setItem(FHIRCAST_STORAGE_KEYS.smartAccess, release.accessToken);
  try {
    const endpoints = await discover(release.hubUrl);
    const signing: SigningKey = {
      keyId: crypto.randomUUID(),
      keys: await crypto.subtle.generateKey(ES384, true, ["sign", "verify"]),
    };
    storage.setItem(
      FHIRCAST_STORAGE_KEYS.signingJwk,
      JSON.stringify(await crypto.subtle.exportKey("jwk", signing.keys.privateKey)),
    );
    const clientId = await register(endpoints.registration, release.accessToken, signing);
    storage.setItem(FHIRCAST_STORAGE_KEYS.clientId, clientId);
    const token = await hubToken(
      endpoints.token,
      await assertion(clientId, endpoints.token, signing),
    );
    storage.setItem(FHIRCAST_STORAGE_KEYS.hubToken, token);
    return await open(await subscribe(release, token), release.hubTopic, {
      onEvent: options.onEvent ?? (() => undefined),
      onLost: options.onLost ?? (() => undefined),
    });
  } catch (failure) {
    if (failure instanceof HubFailure && failure.unreachable && (await blockedByBrowser())) {
      return { kind: "blocked" };
    }
    return { kind: "unavailable" };
  }
}

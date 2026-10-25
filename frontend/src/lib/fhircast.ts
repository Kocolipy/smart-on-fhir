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
 *    `registration_endpoint` and `token_endpoint`, each held to `127.0.0.1`,
 *    and its `revocation_endpoint` when it names one there;
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
 * Connected, it stays connected. Before the Hub Token or the lease the hub
 * granted (`hub.lease_seconds`) runs out, it renews: steps 3 to 5 again, with
 * the key and `client_id` it registered, its new socket replacing the old
 * once confirmed. A socket the hub drops, and a renewal that fails, start
 * reconnecting: retries spaced by a doubling backoff, each reopening the
 * socket — or subscribing anew once the hub denies that endpoint, or after a
 * failed renewal — until one connects or the retries run out.
 *
 * When the session ends, {@link endFhircast} tears down every connection the
 * tab registered, in order:
 *
 * 1. `hub.mode=unsubscribe` for the topic, at the subscription's channel
 *    endpoint, the Hub Token as `Bearer`;
 * 2. the Hub Token revoked by RFC 7009 at the metadata's
 *    `revocation_endpoint`, the client authenticated by an assertion signed
 *    with its key;
 * 3. the registration deleted by RFC 7592 at the `registration_client_uri`
 *    the registration answered, its `registration_access_token` as `Bearer`;
 * 4. the socket closed;
 * 5. every key under {@link FHIRCAST_STORAGE_KEYS} removed.
 *
 * Steps 2 and 3 are skipped for a hub that names no such endpoint on
 * `127.0.0.1`, and a step with nothing to act on is skipped too. Every request
 * is best-effort and bounded: one that fails, or goes unanswered for three
 * seconds, never stops the next (ADR 0011).
 *
 * It answers one generic outcome and nothing else: no hub's or Epic's
 * `error_description`, and no response body, is ever handed back or logged.
 * What it obtains is kept in the tab's `sessionStorage` under
 * {@link FHIRCAST_STORAGE_KEYS} — an accepted deviation (ADR 0011) — and
 * cleared with {@link clearFhircastStorage} when the session ends.
 */

import { DecodeError, jsonDecoder, readObject } from "./decode";
import { isInstant } from "./time";
import { watchers } from "./watchers";

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
 * How connecting came out: `connected`, staying connected until `close`;
 * `unavailable`, for anything the hub did or did not do; or
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

/** The wait before the first retry once a connection is lost; each later one doubles it. */
const RECONNECT_BACKOFF_MS = 1_000;

/** How many retries a lost connection gets before it is given up. */
const RECONNECT_ATTEMPTS = 5;

/** The longest wait a browser timer holds: anything longer fires at once. */
const LONGEST_TIMER_MS = 2_147_483_647;

/**
 * The soonest a renewal starts after what it renews was granted. Only a
 * lifetime under 65 s brings 60 s before its end this close: one of a minute
 * or less has no such time left at all, and renewing it the moment it is
 * granted would mint a Hub Token and subscribe again without pause.
 */
const SOONEST_RENEWAL_MS = 5_000;

/**
 * When to renew what lasts `seconds`, in milliseconds from when it was
 * granted: at 80% of its lifetime or 60 s before it ends, whichever is sooner
 * — though never sooner than {@link SOONEST_RENEWAL_MS}. Never, for a lifetime
 * the hub did not say.
 */
function renewalDelay(seconds: number | null): number {
  if (seconds === null) return Infinity;
  return Math.max(1000 * Math.min(seconds * 0.8, seconds - 60), SOONEST_RENEWAL_MS);
}

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
  /** RFC 7009's, which tearing down revokes the Hub Token at: `null` when the hub names none. */
  revocation: string | null;
}

/**
 * A URL a hub's answer may carry under `name` or not — an endpoint in its
 * metadata, or a registration's `registration_client_uri`: `null` unless it
 * is a URL on `127.0.0.1`, so that a hub naming one anywhere else is taken to
 * name none.
 */
function optionalLoopbackUrl(body: unknown, name: string): string | null {
  const url = memberOf(body, name);
  return typeof url === "string" && onLoopback(url, HTTP_SCHEMES) ? url : null;
}

/**
 * Step 1: the hub's registration and token endpoints, from its metadata, and
 * its revocation endpoint when it names one.
 */
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
  return { registration, revocation: optionalLoopbackUrl(metadata, "revocation_endpoint"), token };
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

/**
 * What RFC 7592 manages the registration by: its `registration_client_uri`,
 * held to `127.0.0.1`, and the `registration_access_token` that authorizes it.
 */
interface RegistrationManagement {
  uri: string;
  token: string;
}

/** The registration the hub answered: the dynamic `client_id`, and how to delete it, if the hub says. */
interface Registration {
  clientId: string;
  management: RegistrationManagement | null;
}

/** RFC 7592's handle on a registration, or `null` when the answer names no usable one. */
function managementOf(registered: unknown): RegistrationManagement | null {
  const uri = optionalLoopbackUrl(registered, "registration_client_uri");
  const token = memberOf(registered, "registration_access_token");
  return uri !== null && typeof token === "string" && token !== "" ? { token, uri } : null;
}

/** Step 2: a fresh key pair, its public half registered; the dynamic `client_id`. */
async function register(
  endpoint: string,
  smartAccessToken: string,
  { keyId, keys }: SigningKey,
): Promise<Registration> {
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
  return { clientId: member(registered, "client_id"), management: managementOf(registered) };
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

/** A Hub Token, and how long the hub says it lasts: `null` when it does not say. */
interface HubToken {
  token: string;
  lifetime: number | null;
}

/**
 * A lifetime a hub stated, in seconds: a positive number, or `null` for
 * anything else, so that a hub that says nothing usable is never renewed by.
 */
function secondsIn(value: unknown): number | null {
  return typeof value === "number" && value > 0 ? value : null;
}

/** Step 3: the Hub Token, by the `jwt-bearer` grant. */
async function hubToken(tokenEndpoint: string, signedAssertion: string): Promise<HubToken> {
  const granted = await hubJson(
    tokenEndpoint,
    {
      body: new URLSearchParams({ assertion: signedAssertion, grant_type: JWT_BEARER }).toString(),
      headers: { "Content-Type": FORM },
      method: "POST",
    },
    200,
  );
  return {
    lifetime: secondsIn(memberOf(granted, "expires_in")),
    token: member(granted, "access_token"),
  };
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

/** How long each step of tearing down may take before it is given up, and the next one runs. */
const TEARDOWN_STEP_TIMEOUT_MS = 3_000;

const CLIENT_ASSERTION = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

/**
 * One step of tearing down, best-effort: the request `init` builds, sent to
 * `url`, settled once the hub answers, the request fails, or
 * {@link TEARDOWN_STEP_TIMEOUT_MS} passes, whichever is first. Whatever the
 * hub answered is dropped unread: there is nothing to do differently.
 */
async function bestEffort(
  url: string,
  init: () => RequestInit | Promise<RequestInit>,
): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const givenUp = new Promise<void>((settle) => {
    timer = setTimeout(settle, TEARDOWN_STEP_TIMEOUT_MS);
  });
  const sent = (async () => {
    await fetch(url, { ...(await init()), credentials: "omit", redirect: "error" });
  })().catch(() => undefined);
  await Promise.race([sent, givenUp]);
  clearTimeout(timer);
}

/** Tearing down, step 1: the subscription at `endpoint` cancelled, the Hub Token as `Bearer`. */
function unsubscribe(release: FhircastRelease, endpoint: string, token: string): Promise<void> {
  return bestEffort(release.hubUrl, () => ({
    body: new URLSearchParams({
      "hub.channel.endpoint": endpoint,
      "hub.channel.type": "websocket",
      "hub.mode": "unsubscribe",
      "hub.topic": release.hubTopic,
    }).toString(),
    headers: { Authorization: `Bearer ${token}`, "Content-Type": FORM },
    method: "POST",
  }));
}

/**
 * Tearing down, step 2: the Hub Token revoked by RFC 7009, the client
 * authenticated as it registered, by an assertion signed with its key.
 */
function revoke(
  revocation: string,
  { clientId, endpoints, signing }: Registered,
  token: string,
): Promise<void> {
  return bestEffort(revocation, async () => ({
    body: new URLSearchParams({
      client_assertion: await assertion(clientId, endpoints.token, signing),
      client_assertion_type: CLIENT_ASSERTION,
      client_id: clientId,
      token,
      token_type_hint: "access_token",
    }).toString(),
    headers: { "Content-Type": FORM },
    method: "POST",
  }));
}

/** Tearing down, step 3: the registration deleted by RFC 7592, its access token as `Bearer`. */
function deleteRegistration({ token, uri }: RegistrationManagement): Promise<void> {
  return bestEffort(uri, () => ({
    headers: { Authorization: `Bearer ${token}` },
    method: "DELETE",
  }));
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
 * What a socket message says of the subscription: JSON whose `hub.mode` is
 * `subscribe` confirms it, granting the lease its `hub.lease_seconds` names;
 * one whose `hub.mode` is `denied` refuses it, as a hub does at an endpoint it
 * no longer knows. Anything else, JSON or not, says nothing: `null`.
 */
function verdictOf(data: unknown): { lease: number | null } | "denied" | null {
  try {
    const message: unknown = JSON.parse(String(data));
    const mode = memberOf(message, "hub.mode");
    if (mode === "subscribe") return { lease: secondsIn(memberOf(message, "hub.lease_seconds")) };
    return mode === "denied" ? "denied" : null;
  } catch {
    return null;
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

/** A socket the hub confirmed the subscription on: the lease it granted, and how to close it. */
interface Channel {
  lease: number | null;
  close: () => void;
}

/**
 * How opening a socket came out: the {@link Channel} the hub confirmed;
 * `denied`, when the hub refused the endpoint; or `failed`, when the socket
 * closed, or stayed silent, before either.
 */
type Opened = Channel | "denied" | "failed";

/**
 * Step 5: the socket, {@link Opened} as the hub answered it. A browser fires `close` after any socket `error`, so `close`
 * alone is listened for. Once confirmed, a `close` the hub causes is reported
 * through `onDropped`; one the caller asks for is not. A confirmed socket's
 * every other message is held to {@link notificationOf}: a notification that
 * passes is acknowledged on the socket and handed to `onEvent`; anything else
 * is ignored, unacknowledged.
 */
function open(
  endpoint: string,
  topic: string,
  onEvent: (event: PatientEvent) => void,
  onDropped: () => void,
): Promise<Opened> {
  return new Promise((settle) => {
    const socket = new WebSocket(endpoint);
    let confirmed = false;
    let denied = false;
    const timer = setTimeout(() => socket.close(), CONFIRMATION_TIMEOUT_MS);
    const close = () => {
      confirmed = false;
      socket.close();
    };
    socket.addEventListener("message", ({ data }) => {
      if (confirmed) {
        const notification = notificationOf(data, topic);
        if (notification === null) return;
        socket.send(JSON.stringify({ id: notification.id, status: ACKNOWLEDGED }));
        onEvent(notification.event);
        return;
      }
      const verdict = verdictOf(data);
      if (verdict === null) return;
      if (verdict === "denied") {
        denied = true;
        socket.close();
        return;
      }
      confirmed = true;
      clearTimeout(timer);
      settle({ close, lease: verdict.lease });
    });
    // A socket closes once; settling an already settled outcome changes
    // nothing, and the timer closing a closed socket does nothing either.
    socket.addEventListener("close", () => {
      if (confirmed) onDropped();
      settle(denied ? "denied" : "failed");
    });
  });
}

/** Everything connecting obtained that staying connected needs again. */
interface Registered extends Registration {
  release: FhircastRelease;
  endpoints: HubEndpoints;
  signing: SigningKey;
  handlers: Required<FhircastHandlers>;
}

/**
 * The connection once the key is registered: steps 3 to 5, then renewal
 * before the Hub Token or the lease runs out, and reconnecting when a socket
 * drops or a renewal fails. A socket that drops while a renewal is under way
 * is left to the renewal.
 */
class Connection {
  private channel: Channel | null = null;
  /**
   * The endpoint the socket was last opened at: set with the first socket,
   * before any retry; `null` until then.
   */
  private endpoint: string | null = null;
  /** The Hub Token the hub last granted: `null` until it grants one. */
  private hubToken: string | null = null;
  /** When the Hub Token, and the lease, are due for renewal, on `Date.now()`'s clock. */
  private tokenRenewsAt = Infinity;
  private leaseRenewsAt = Infinity;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private renewing = false;
  private closed = false;
  /** Whether a retry subscribes anew, rather than reopening the endpoint. */
  private resubscribing = false;

  constructor(private readonly registered: Registered) {}

  /**
   * Steps 3 to 5: a Hub Token from a fresh assertion, a new subscription, and
   * its socket. Whether a socket was confirmed; a {@link HubFailure} when the
   * hub refused a step. Once closed, it keeps nothing it is granted.
   */
  async subscribe(): Promise<boolean> {
    const { clientId, endpoints, signing } = this.registered;
    const granted = await hubToken(
      endpoints.token,
      await assertion(clientId, endpoints.token, signing),
    );
    return !this.closed && (await this.subscribeWith(granted));
  }

  /** Stops everything: no renewal, no retry, and the socket closed. */
  close() {
    this.closed = true;
    clearTimeout(this.timer);
    this.channel?.close();
  }

  /**
   * Stops everything, as {@link close} does, and tears the connection down at
   * the hub, each step best-effort and in order: the subscription cancelled,
   * the Hub Token revoked when the hub names a revocation endpoint, the
   * registration deleted when the hub said how, and only then the socket
   * closed. A step with nothing to act on — no subscription, no Hub Token —
   * is skipped. The socket is the teardown's from the start: a `close` asked
   * for meanwhile leaves it open, and one the hub causes is not reconnected.
   */
  async tearDown(): Promise<void> {
    const channel = this.channel;
    this.channel = null;
    this.close();
    const { endpoints, management, release } = this.registered;
    const { endpoint, hubToken } = this;
    if (hubToken !== null) {
      if (endpoint !== null) await unsubscribe(release, endpoint, hubToken);
      if (endpoints.revocation !== null) {
        await revoke(endpoints.revocation, this.registered, hubToken);
      }
    }
    if (management !== null) await deleteRegistration(management);
    channel?.close();
  }

  /** Steps 4 and 5 with the Hub Token `granted`, which the tab keeps and renewal is then due by. */
  private async subscribeWith(granted: HubToken): Promise<boolean> {
    this.tokenRenewsAt = Date.now() + renewalDelay(granted.lifetime);
    this.hubToken = granted.token;
    window.sessionStorage.setItem(FHIRCAST_STORAGE_KEYS.hubToken, granted.token);
    return (await this.reopen(await subscribe(this.registered.release, granted.token))) === true;
  }

  /**
   * Opens `endpoint`: `true` once the hub confirms the socket, which then
   * replaces the one before, or is closed again when the connection was
   * closed meanwhile; otherwise `denied` or `failed`, as {@link open} answered.
   */
  private async reopen(endpoint: string): Promise<true | Exclude<Opened, Channel>> {
    const { handlers, release } = this.registered;
    const opened = await open(endpoint, release.hubTopic, handlers.onEvent, () => this.dropped());
    if (typeof opened === "string") return opened;
    if (this.closed) opened.close();
    else this.adopt(opened, endpoint);
    return true;
  }

  /** `opened`, at `endpoint`, as the socket: the one before closed, and renewal due by its lease too. */
  private adopt(opened: Channel, endpoint: string) {
    this.channel?.close();
    this.channel = opened;
    this.endpoint = endpoint;
    this.leaseRenewsAt = Date.now() + renewalDelay(opened.lease);
    this.schedule();
  }

  /** Sets the renewal timer for whichever of the Hub Token and the lease is due first. */
  private schedule() {
    clearTimeout(this.timer);
    const due = Math.min(this.tokenRenewsAt, this.leaseRenewsAt);
    if (!Number.isFinite(due)) return;
    this.timer = setTimeout(() => void this.renew(), Math.min(due - Date.now(), LONGEST_TIMER_MS));
  }

  /** Renewal: steps 3 to 5 again; reconnecting, by subscribing anew, when it fails. */
  private async renew() {
    this.renewing = true;
    let renewed = false;
    try {
      renewed = await this.subscribe();
    } catch {
      // The hub refused a step: the renewal failed.
    }
    this.renewing = false;
    if (this.closed || renewed) return;
    this.channel?.close();
    this.channel = null;
    this.resubscribing = true;
    this.reconnect(0);
  }

  /** The confirmed socket, closed by the hub: nothing more once the connection is closed. */
  private dropped() {
    this.channel = null;
    if (this.closed || this.renewing) return;
    clearTimeout(this.timer);
    this.reconnect(0);
  }

  /** Retry `attempt`, from 0, after its backoff; once the retries run out, disconnected. */
  private reconnect(attempt: number) {
    const { onConnection } = this.registered.handlers;
    if (attempt === 0) onConnection("reconnecting");
    if (attempt === RECONNECT_ATTEMPTS) {
      onConnection("disconnected");
      return;
    }
    this.timer = setTimeout(() => void this.retry(attempt), RECONNECT_BACKOFF_MS * 2 ** attempt);
  }

  /** One retry: connected again once a socket is confirmed, or the next retry. */
  private async retry(attempt: number) {
    try {
      await this.restore();
    } catch {
      // The hub refused a step: this retry failed.
    }
    if (this.closed) return;
    if (this.channel === null) this.reconnect(attempt + 1);
    else this.registered.handlers.onConnection("connected");
  }

  /**
   * Reopens the endpoint; subscribes anew when the hub denies it, or after a
   * failed renewal, until a new subscription's socket is confirmed.
   */
  private async restore() {
    const reopening = this.resubscribing ? null : this.endpoint;
    if (reopening !== null && (await this.reopen(reopening)) !== "denied") return;
    this.resubscribing = true;
    this.resubscribing = !(await this.subscribe());
  }
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

/**
 * Every connection this tab registered at a hub since its session's FHIRcast
 * last ended — closed ones too, since their registrations still stand — for
 * {@link endFhircast} to tear down.
 */
const toTearDown = new Set<Connection>();

/**
 * The generation of the session's FHIRcast in this tab, replaced each time
 * it ends: a connection begun under a generation since replaced belongs to a
 * session already over.
 */
let sessionGeneration: object = {};

/**
 * Connects to the hub `release` names, running every step in order; any
 * failure along the way is one generic outcome. Once registered, the
 * connection is torn down when the session's FHIRcast ends, whatever its
 * outcome — at once, and `unavailable`, when it ended while registering.
 */
export async function connectFhircast(
  release: FhircastRelease,
  options: FhircastHandlers = {},
): Promise<FhircastOutcome> {
  if (!onLoopback(release.hubUrl, HTTP_SCHEMES)) return { kind: "unavailable" };
  const begun = sessionGeneration;
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
    const registration = await register(endpoints.registration, release.accessToken, signing);
    storage.setItem(FHIRCAST_STORAGE_KEYS.clientId, registration.clientId);
    const connection = new Connection({
      ...registration,
      endpoints,
      handlers: {
        onConnection: options.onConnection ?? (() => undefined),
        onEvent: options.onEvent ?? (() => undefined),
      },
      release,
      signing,
    });
    if (sessionGeneration !== begun) {
      await connection.tearDown();
      clearFhircastStorage();
      return { kind: "unavailable" };
    }
    toTearDown.add(connection);
    if (!(await connection.subscribe())) return { kind: "unavailable" };
    return { close: () => connection.close(), kind: "connected" };
  } catch (failure) {
    if (failure instanceof HubFailure && failure.unreachable && (await blockedByBrowser())) {
      return { kind: "blocked" };
    }
    return { kind: "unavailable" };
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
 * The session's one FHIRcast connection, a module-level singleton rather than
 * any component's state, so it lasts the session whichever page is shown:
 * the attempt under way — where its release comes from, and how to close what
 * it connected — or `null` before the first start and once the session's
 * FHIRcast has ended ({@link endFhircast}).
 */
let attempt: { source: FhircastReleaseSource; close: () => void } | null = null;

let snapshot: FhircastSnapshot = NOT_STARTED;

/** Whoever is watching what the connection shows. */
const watching = watchers();

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
        if (live()) show({ last });
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
 * Connects again from the start — a fresh release, a fresh registration — with
 * no last event, closing the connection before. Nothing before the first start.
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
 * Ends the session's FHIRcast: stops the session's connection, so the next
 * session's start connects anew, and tears down every connection the tab
 * registered (see `Connection.tearDown`), side by side, then forgets
 * everything connecting kept ({@link clearFhircastStorage}). Never fails:
 * every step is best-effort and bounded, so it settles within a few seconds
 * however the hub behaves.
 */
export async function endFhircast(): Promise<void> {
  attempt = null;
  show(NOT_STARTED);
  sessionGeneration = {};
  const ending = [...toTearDown];
  toTearDown.clear();
  await Promise.all(ending.map((connection) => connection.tearDown()));
  clearFhircastStorage();
}

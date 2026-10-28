/**
 * The FHIRcast hub protocol: every HTTP request the tab makes of the hub, and
 * nothing else. Discovery (`.well-known/fhircast-configuration`), RFC 7591
 * registration with the SMART access token, the ES384 assertion and the
 * `jwt-bearer` Hub Token, the subscription, and the three requests tearing
 * down makes — the unsubscription, RFC 7009 revocation and RFC 7592 deletion.
 * Every URL a hub names is held to `127.0.0.1` ({@link onLoopback}). Beside the
 * HTTP transport, one of the modules allowed raw `fetch`
 * (`test/arch/rawNetwork.test.ts`).
 *
 * A hub answer is read for what the protocol needs and nothing else: what is
 * wrong with one is a {@link HubFailure}, carrying no message, and no hub's
 * `error_description` or body is ever handed back or logged.
 */

import { readObject } from "../decode";
import {
  type Client,
  ES384,
  HTTP_SCHEMES,
  type Hub,
  type HubToken,
  memberOf,
  onLoopback,
  type Registration,
  type RegistrationManagement,
  secondsIn,
  type SigningKey,
  WS_SCHEMES,
} from "./model";
import type { FhircastRelease } from "./release";

const JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";

/** How long an assertion is valid, in seconds: RFC 7523 wants it short. */
const ASSERTION_LIFETIME = 300;

const FORM = "application/x-www-form-urlencoded";

/**
 * Why connecting stopped: the hub said no, or the browser never reached it.
 * It carries no message: nothing about a failure is ever shown or logged.
 */
export class HubFailure extends Error {
  /** `unreachable`: the browser never reached the hub; `status`: what it answered, when it answered unexpectedly. */
  constructor(
    readonly unreachable: boolean,
    readonly status = 0,
  ) {
    super();
  }
}

/**
 * The token endpoint refused the client itself — RFC 6749 §5.2's `400` or
 * `401` — as a hub does a registration it no longer holds.
 */
export class ClientRefused extends HubFailure {
  constructor() {
    super(false);
  }
}

/** The statuses RFC 6749 §5.2 refuses a token request with. */
const TOKEN_REFUSALS = [400, 401];

/** A JSON answer from the hub, or a {@link HubFailure}: refused, not JSON, or never reached. */
async function hubJson(url: string, init: RequestInit, expected: number): Promise<unknown> {
  let answer: Response;
  try {
    answer = await fetch(url, { ...init, credentials: "omit", redirect: "error" });
  } catch {
    throw new HubFailure(true);
  }
  if (answer.status !== expected) throw new HubFailure(false, answer.status);
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
export async function discover(hubUrl: string): Promise<HubEndpoints> {
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

const base64Url = (bytes: Uint8Array): string =>
  btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=/g, "");

const encodeJson = (value: unknown): string =>
  base64Url(new TextEncoder().encode(JSON.stringify(value)));

/** RFC 7592's handle on a registration, or `null` when the answer names no usable one. */
function managementOf(registered: unknown): RegistrationManagement | null {
  const uri = optionalLoopbackUrl(registered, "registration_client_uri");
  const token = memberOf(registered, "registration_access_token");
  return uri !== null && typeof token === "string" && token !== "" ? { token, uri } : null;
}

/** Step 2: a fresh key pair's public half registered, under `keyId`; the dynamic `client_id`. */
export async function register(
  endpoint: string,
  smartAccessToken: string,
  keyId: string,
  publicKey: CryptoKey,
): Promise<Registration> {
  const { crv, kty, x, y } = await crypto.subtle.exportKey("jwk", publicKey);
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

/**
 * Steps 1 and 2 for `release`: its hub's endpoints discovered, a fresh ES384
 * key pair generated, and its public half registered with the release's SMART
 * access token — the Hub Registration, as the tab holds it.
 */
export async function registerClient(release: FhircastRelease): Promise<Client> {
  const endpoints = await discover(release.hubUrl);
  const keyId = crypto.randomUUID();
  const keys = await crypto.subtle.generateKey(ES384, true, ["sign", "verify"]);
  const registration = await register(
    endpoints.registration,
    release.accessToken,
    keyId,
    keys.publicKey,
  );
  return {
    ...registration,
    hub: { hubTopic: release.hubTopic, hubUrl: release.hubUrl },
    revocation: endpoints.revocation,
    signing: {
      jwk: await crypto.subtle.exportKey("jwk", keys.privateKey),
      keyId,
      privateKey: keys.privateKey,
    },
    tokenEndpoint: endpoints.token,
  };
}

/** The RFC 7523 assertion for `clientId` at `tokenEndpoint`, signed with the private key. */
async function assertion(
  clientId: string,
  tokenEndpoint: string,
  { keyId, privateKey }: SigningKey,
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
    privateKey,
    new TextEncoder().encode(signed),
  );
  return `${signed}.${base64Url(new Uint8Array(signature))}`;
}

/**
 * Step 3: a Hub Token for `client`, by the `jwt-bearer` grant from a fresh
 * assertion; a {@link ClientRefused} when the token endpoint refuses the client.
 */
export async function hubToken({ clientId, signing, tokenEndpoint }: Client): Promise<HubToken> {
  const signedAssertion = await assertion(clientId, tokenEndpoint, signing);
  let granted: unknown;
  try {
    granted = await hubJson(
      tokenEndpoint,
      {
        body: new URLSearchParams({
          assertion: signedAssertion,
          grant_type: JWT_BEARER,
        }).toString(),
        headers: { "Content-Type": FORM },
        method: "POST",
      },
      200,
    );
  } catch (failure) {
    if (failure instanceof HubFailure && TOKEN_REFUSALS.includes(failure.status)) {
      throw new ClientRefused();
    }
    throw failure;
  }
  return {
    lifetime: secondsIn(memberOf(granted, "expires_in")),
    token: member(granted, "access_token"),
  };
}

/** Step 4: the subscription; the channel endpoint the hub answers, held to loopback. */
export async function subscribe(hub: Hub, token: string): Promise<string> {
  const subscribed = await hubJson(
    hub.hubUrl,
    {
      body: new URLSearchParams({
        "hub.channel.type": "websocket",
        "hub.events": "Patient-open,Patient-close",
        "hub.mode": "subscribe",
        "hub.topic": hub.hubTopic,
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
export function unsubscribe(hub: Hub, endpoint: string, token: string): Promise<void> {
  return bestEffort(hub.hubUrl, () => ({
    body: new URLSearchParams({
      "hub.channel.endpoint": endpoint,
      "hub.channel.type": "websocket",
      "hub.mode": "unsubscribe",
      "hub.topic": hub.hubTopic,
    }).toString(),
    headers: { Authorization: `Bearer ${token}`, "Content-Type": FORM },
    method: "POST",
  }));
}

/**
 * Tearing down, step 2: the Hub Token revoked by RFC 7009, the client
 * authenticated as it registered, by an assertion signed with its key.
 */
export function revoke(
  revocation: string,
  { clientId, signing, tokenEndpoint }: Client,
  token: string,
): Promise<void> {
  return bestEffort(revocation, async () => ({
    body: new URLSearchParams({
      client_assertion: await assertion(clientId, tokenEndpoint, signing),
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
export function deleteRegistration({ token, uri }: RegistrationManagement): Promise<void> {
  return bestEffort(uri, () => ({
    headers: { Authorization: `Bearer ${token}` },
    method: "DELETE",
  }));
}

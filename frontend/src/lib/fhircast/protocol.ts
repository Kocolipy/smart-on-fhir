/**
 * The FHIRcast hub protocol: every HTTP request the tab makes of the hub, and
 * nothing else — the hub's configuration, the registration of a dynamic
 * client with the SMART access token as `Bearer`, the hub token for that
 * client's assertion, and the subscription and the unsubscription that
 * cancels one, each with the hub token as `Bearer`. Every URL a hub names is
 * held to `127.0.0.1` ({@link onLoopback}), and the hub's own endpoints sit
 * beneath its `hub.url`. Beside the HTTP transport, one of the modules allowed
 * raw `fetch` (`test/arch/rawNetwork.test.ts`).
 *
 * A hub answer is read for what the protocol needs and nothing else: what is
 * wrong with one is a refusal — a {@link HubFailure}, carrying no message, or
 * a body that is no JSON — and no hub's `error_description` or body is ever
 * handed back or logged.
 */

import type { PublicJwk } from "./client-key";
import { type Hub, memberOf, onLoopback, PATIENT_EVENTS, WS_SCHEMES } from "./model";

const FORM = "application/x-www-form-urlencoded";

/** What obtaining a hub token takes: the hub, the release's software id and its SMART access token. */
export interface HubCredentials {
  hub: Hub;
  softwareId: string;
  /** The SMART access token: the `Bearer` of a registration, and of nothing else. */
  accessToken: string;
}

/**
 * Why connecting stopped: the hub said no, or the browser never reached it.
 * It carries no message: nothing about a failure is ever shown or logged.
 */
export class HubFailure extends Error {
  /** `unreachable`: the browser never reached the hub. */
  constructor(readonly unreachable: boolean) {
    super();
  }
}

/**
 * How long a subscription may take before it is given up: the same ten
 * seconds the hub has to confirm it on the socket
 * (`CONFIRMATION_TIMEOUT_MS`), since both wait on the hub to accept it.
 */
const SUBSCRIBE_TIMEOUT_MS = 10_000;

/**
 * A JSON answer from the hub, within `timeoutMs`: a {@link HubFailure} when
 * the browser never reached it, it answered another status, or it had not
 * answered by then — the request then aborted, so nothing of it lingers and
 * no answer after it is read — and a rejection, read no further, when its
 * body is no JSON or still unread by then.
 */
async function hubJson(
  url: string,
  init: RequestInit,
  expected: number,
  timeoutMs: number,
): Promise<unknown> {
  const giveUp = new AbortController();
  const timer = setTimeout(() => giveUp.abort(), timeoutMs);
  try {
    let answer: Response;
    try {
      answer = await fetch(url, {
        ...init,
        credentials: "omit",
        redirect: "error",
        signal: giveUp.signal,
      });
    } catch {
      // Given up, the hub's silence: no refusal of the browser's.
      throw new HubFailure(!giveUp.signal.aborted);
    }
    if (answer.status !== expected) throw new HubFailure(false);
    return await answer.json();
  } finally {
    clearTimeout(timer);
  }
}

/**
 * How long each request on the way to a hub token may take before it is
 * given up: the configuration, the registration and the token request each
 * have the ten seconds a subscription has.
 */
const AUTHORIZATION_TIMEOUT_MS = SUBSCRIBE_TIMEOUT_MS;

/** The URL of the hub's own endpoint `name`, beneath `hub`'s `hub.url`. */
const beneath = (hub: Hub, name: string): string => `${hub.hubUrl.replace(/\/+$/, "")}/${name}`;

/** The non-empty string `body` holds under `key`; a refusal for anything else. */
function stringIn(body: unknown, key: string): string {
  const value = memberOf(body, key);
  if (typeof value !== "string" || value === "") throw new HubFailure(false);
  return value;
}

/** What the hub's configuration states: the audience a client assertion names, and whether it is read-only. */
export interface HubConfiguration {
  audience: string;
  readOnly: boolean;
}

/**
 * The hub's configuration (`GET <hub.url>/subspace-configuration`): its
 * `audience`, and `isReadOnly`, `"1"` or `"0"`. Anything else is a refusal.
 */
export async function configuration(hub: Hub): Promise<HubConfiguration> {
  const stated = await hubJson(
    beneath(hub, "subspace-configuration"),
    { method: "GET" },
    200,
    AUTHORIZATION_TIMEOUT_MS,
  );
  const isReadOnly = memberOf(stated, "isReadOnly");
  if (isReadOnly !== "0" && isReadOnly !== "1") throw new HubFailure(false);
  return { audience: stringIn(stated, "audience"), readOnly: isReadOnly === "1" };
}

/**
 * The registration of a dynamic client at `credentials`' hub (`POST
 * <hub.url>/register`): the release's `softwareId` as `software_id` and
 * `publicJwk` as the one key of its `jwks`, the SMART access token as
 * `Bearer`; the `client_id` the hub answers.
 */
export async function register(
  { accessToken, hub, softwareId }: HubCredentials,
  publicJwk: PublicJwk,
): Promise<string> {
  const registered = await hubJson(
    beneath(hub, "register"),
    {
      body: JSON.stringify({ jwks: { keys: [publicJwk] }, software_id: softwareId }),
      headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
      method: "POST",
    },
    200,
    AUTHORIZATION_TIMEOUT_MS,
  );
  return stringIn(registered, "client_id");
}

/** RFC 7523: the `client_assertion_type` of a JWT client assertion. */
const JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

/**
 * A hub token (`POST <hub.url>/token`): the `client_credentials` grant, the
 * dynamic client authenticated by its `jwt-bearer` client `assertion`; the
 * `access_token` the hub answers.
 */
export async function requestHubToken(hub: Hub, assertion: string): Promise<string> {
  const issued = await hubJson(
    beneath(hub, "token"),
    {
      body: new URLSearchParams({
        client_assertion: assertion,
        client_assertion_type: JWT_BEARER,
        grant_type: "client_credentials",
      }).toString(),
      headers: { "Content-Type": FORM },
      method: "POST",
    },
    200,
    AUTHORIZATION_TIMEOUT_MS,
  );
  return stringIn(issued, "access_token");
}

/**
 * The subscription to `patient-open` and `patient-close` on `hub`'s topic,
 * over a websocket, the hub token `token` as `Bearer`; the channel
 * endpoint the hub answers, a string held to `ws(s)` on loopback. A hub that
 * has not answered within {@link SUBSCRIBE_TIMEOUT_MS} has refused it.
 */
export async function subscribe(hub: Hub, token: string): Promise<string> {
  const subscribed = await hubJson(
    hub.hubUrl,
    {
      body: new URLSearchParams({
        "hub.channel.type": "websocket",
        "hub.events": PATIENT_EVENTS.join(","),
        "hub.mode": "subscribe",
        "hub.topic": hub.hubTopic,
      }).toString(),
      headers: { Authorization: `Bearer ${token}`, "Content-Type": FORM },
      method: "POST",
    },
    202,
    SUBSCRIBE_TIMEOUT_MS,
  );
  const endpoint = memberOf(subscribed, "hub.channel.endpoint");
  if (typeof endpoint !== "string" || !onLoopback(endpoint, WS_SCHEMES)) {
    throw new HubFailure(false);
  }
  return endpoint;
}

/** How long an unsubscription may take before it is given up. */
const UNSUBSCRIBE_TIMEOUT_MS = 3_000;

/**
 * The Hub Subscription at `endpoint` cancelled, `hub.mode=unsubscribe` for the
 * topic, the hub token `token` as `Bearer` — best-effort: settled
 * once the hub answers, the request fails, or {@link UNSUBSCRIBE_TIMEOUT_MS}
 * passes, whichever is first. Whatever the hub answered is dropped unread:
 * there is nothing to do differently.
 */
export async function unsubscribe(hub: Hub, endpoint: string, token: string): Promise<void> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const givenUp = new Promise<void>((settle) => {
    timer = setTimeout(settle, UNSUBSCRIBE_TIMEOUT_MS);
  });
  const sent = fetch(hub.hubUrl, {
    body: new URLSearchParams({
      "hub.channel.endpoint": endpoint,
      "hub.channel.type": "websocket",
      "hub.mode": "unsubscribe",
      "hub.topic": hub.hubTopic,
    }).toString(),
    credentials: "omit",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": FORM },
    method: "POST",
    redirect: "error",
  }).then(
    () => undefined,
    () => undefined,
  );
  await Promise.race([sent, givenUp]);
  clearTimeout(timer);
}

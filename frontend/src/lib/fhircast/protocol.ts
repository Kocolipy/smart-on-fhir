/**
 * The FHIRcast hub protocol: every HTTP request the tab makes of the hub, and
 * nothing else — the subscription, and the unsubscription that cancels one,
 * each with the SMART access token as `Bearer`. Every URL a hub names is held
 * to `127.0.0.1` ({@link onLoopback}). Beside the HTTP transport, one of the
 * modules allowed raw `fetch` (`test/arch/rawNetwork.test.ts`).
 *
 * A hub answer is read for what the protocol needs and nothing else: what is
 * wrong with one is a refusal — a {@link HubFailure}, carrying no message, or
 * a body that is no JSON — and no hub's `error_description` or body is ever
 * handed back or logged.
 */

import { type Hub, memberOf, onLoopback, WS_SCHEMES } from "./model";

const FORM = "application/x-www-form-urlencoded";

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
 * A JSON answer from the hub: a {@link HubFailure} when the browser never
 * reached it or it answered another status, and a rejection, read no further,
 * when its body is no JSON.
 */
async function hubJson(url: string, init: RequestInit, expected: number): Promise<unknown> {
  let answer: Response;
  try {
    answer = await fetch(url, { ...init, credentials: "omit", redirect: "error" });
  } catch {
    throw new HubFailure(true);
  }
  if (answer.status !== expected) throw new HubFailure(false);
  return answer.json();
}

/**
 * The subscription to `Patient-open` and `Patient-close` on `hub`'s topic,
 * over a websocket, the SMART access token `token` as `Bearer`; the channel
 * endpoint the hub answers, a string held to `ws(s)` on loopback.
 */
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
 * topic, the SMART access token `token` as `Bearer` — best-effort: settled
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

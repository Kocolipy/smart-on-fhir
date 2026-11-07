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
 * The subscription to `Patient-open` and `Patient-close` on `hub`'s topic,
 * over a websocket, the SMART access token `token` as `Bearer`; the channel
 * endpoint the hub answers, a string held to `ws(s)` on loopback. A hub that
 * has not answered within {@link SUBSCRIBE_TIMEOUT_MS} has refused it.
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

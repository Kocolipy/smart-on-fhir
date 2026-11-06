import { afterEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  endpointOf,
  HUB_URL,
  type HubAnswer,
  hubOnly,
  type HubOptions,
  requestsSince,
} from "../fake-hub.testHelpers";
import { connectFhircast, endFhircast, FHIRCAST_STORAGE_KEYS } from ".";
import {
  eventually,
  form,
  held,
  RELEASE,
  routes,
  SMART_ACCESS_TOKEN,
  throughout,
} from "./fhircast.testHelpers";

// `endFhircast`: the teardown at the hub, in order, and what it does when the
// unsubscription fails, or the connection is still subscribing.

describe("endFhircast", () => {
  /** Connected to a hub answering `options`; the requests the hub was sent from here on. */
  async function connected(options: HubOptions = {}) {
    const hub = hubOnly(options);
    const reported = vi.fn();
    const outcome = await connectFhircast(RELEASE, { onConnection: reported });
    expect(outcome.kind).toBe("connected");
    return { hub, outcome, reported, since: requestsSince(hub) };
  }

  const UNSUBSCRIBE = `POST ${HUB_URL}`;

  /** What the tab still keeps under each FHIRcast key. */
  const kept = () =>
    Object.values(FHIRCAST_STORAGE_KEYS).map((key) => window.sessionStorage.getItem(key));

  /** Each endpoint the hub was asked to unsubscribe, in order. */
  const unsubscribedEndpoints = (requests: ReturnType<ReturnType<typeof requestsSince>>) =>
    requests.map(({ body }) => form(body)["hub.channel.endpoint"]);

  it("unsubscribes, and asks the hub for nothing else", async () => {
    const { since } = await connected();

    await endFhircast();

    expect(routes(since())).toEqual([UNSUBSCRIBE]);
  });

  it("unsubscribes from the topic at its channel endpoint, the SMART access token as Bearer", async () => {
    const { since } = await connected();

    await endFhircast();

    const [unsubscription] = since();
    expect({ ...unsubscription, body: form(unsubscription.body) }).toMatchObject({
      authorization: `Bearer ${SMART_ACCESS_TOKEN}`,
      body: {
        "hub.channel.endpoint": CHANNEL_ENDPOINT,
        "hub.channel.type": "websocket",
        "hub.mode": "unsubscribe",
        "hub.topic": "topic-1",
      },
      contentType: "application/x-www-form-urlencoded",
      credentials: "omit",
      redirect: "error",
    });
  });

  it("closes the socket only once the unsubscription is answered", async () => {
    const unsubscription = held();
    const { hub, since } = await connected({ unsubscription: unsubscription.answer });

    const ending = endFhircast();
    await eventually(() => expect(since()).toHaveLength(1));
    const openWhileUnsubscribing = hub.openSockets();
    unsubscription.give({ body: {}, status: 202 });
    await ending;

    expect([openWhileUnsubscribing, hub.openSockets()]).toEqual([1, 0]);
  });

  it("then leaves no FHIRcast key in sessionStorage", async () => {
    await connected();

    await endFhircast();

    expect(kept()).toEqual([null]);
  });

  it("tears down nothing twice", async () => {
    const { since } = await connected();
    await endFhircast();
    const once = since().length;

    await endFhircast();

    expect(since()).toHaveLength(once);
  });

  it("tears down a connection Home already closed", async () => {
    const { outcome, since } = await connected();
    if (outcome.kind === "connected") outcome.close();

    await endFhircast();

    expect(routes(since())).toEqual([UNSUBSCRIBE]);
  });

  it("unsubscribes a first connection whose socket closed unconfirmed, as well as its retry's", async () => {
    const hub = hubOnly({ socket: ["closes", "confirms"] });
    expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    expect((await connectFhircast(RELEASE)).kind).toBe("connected");
    const since = requestsSince(hub);

    await endFhircast();

    expect(unsubscribedEndpoints(since())).toEqual([endpointOf(1), endpointOf(2)]);
  });

  it("unsubscribes a subscription two connections share once", async () => {
    const { outcome, since } = await connected();
    if (outcome.kind === "connected") outcome.close();
    // The tab's one subscription, resumed by the connection after.
    expect((await connectFhircast(RELEASE)).kind).toBe("connected");

    await endFhircast();

    expect(unsubscribedEndpoints(since())).toEqual([CHANNEL_ENDPOINT]);
  });

  it("unsubscribes the shared subscription with the SMART access token of the connection after", async () => {
    const { outcome, since } = await connected();
    if (outcome.kind === "connected") outcome.close();
    const after = { ...RELEASE, accessToken: `smart-${crypto.randomUUID()}` };
    expect((await connectFhircast(after)).kind).toBe("connected");

    await endFhircast();

    expect(since().map(({ authorization }) => authorization)).toEqual([
      `Bearer ${after.accessToken}`,
    ]);
  });

  describe("an unsubscription that fails", () => {
    it.each<[string, HubAnswer]>([
      ["is never reached", "unreachable"],
      ["is refused", { body: { error: "invalid_request", error_description: "x" }, status: 400 }],
      ["fails", { status: 500, text: "<html>down</html>" }],
    ])("still closes the socket when it %s", async (_name, unsubscription) => {
      const { hub, since } = await connected({ unsubscription });

      await endFhircast();

      expect([routes(since()), hub.openSockets()]).toEqual([[UNSUBSCRIBE], 0]);
    });

    it("stops none of the others", async () => {
      const hub = hubOnly({ socket: ["closes", "confirms"], unsubscription: "unreachable" });
      await connectFhircast(RELEASE);
      await connectFhircast(RELEASE);
      const since = requestsSince(hub);

      await endFhircast();

      expect(unsubscribedEndpoints(since())).toEqual([endpointOf(1), endpointOf(2)]);
    });

    it("still leaves no FHIRcast key in sessionStorage", async () => {
      await connected({ unsubscription: "unreachable" });

      await endFhircast();

      expect(kept()).toEqual([null]);
    });

    describe("on the clock", () => {
      afterEach(() => {
        vi.useRealTimers();
      });

      it("is given up after 3 seconds unanswered, and the socket closed then, not before", async () => {
        const never = new Promise<HubAnswer>(() => undefined);
        const { hub, since } = await connected({ unsubscription: never });
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });

        let settled = false;
        void endFhircast().then(() => (settled = true));
        await eventually(() => expect(since()).toHaveLength(1));
        await vi.advanceTimersByTimeAsync(2_999);
        const openBefore = hub.openSockets();
        await vi.advanceTimersByTimeAsync(1);
        await eventually(() => expect(settled).toBe(true));

        expect([openBefore, hub.openSockets()]).toEqual([1, 0]);
      });
    });
  });

  describe("a connection still subscribing when the session's FHIRcast ends", () => {
    /** Connecting, its subscription held until the hub answers it; settled once ended and answered. */
    async function endedWhileSubscribing() {
      const subscription = held();
      const hub = hubOnly({ subscription: subscription.answer });
      const connecting = connectFhircast(RELEASE);
      await eventually(() => expect(hub.requestsTo(HUB_URL)).toHaveLength(1));
      await endFhircast();
      const since = requestsSince(hub);
      subscription.give({ body: { "hub.channel.endpoint": CHANNEL_ENDPOINT }, status: 202 });
      const outcome = await connecting;
      return { hub, outcome, since };
    }

    it("is unavailable, opening no socket", async () => {
      const { hub, outcome } = await endedWhileSubscribing();

      expect([outcome.kind, hub.sockets()]).toEqual(["unavailable", []]);
    });

    it("unsubscribes from the endpoint the hub then answers, with the SMART access token", async () => {
      const { since } = await endedWhileSubscribing();

      await eventually(() =>
        expect(
          since().map(({ authorization, body }) => [
            form(body)["hub.mode"],
            form(body)["hub.channel.endpoint"],
            authorization,
          ]),
        ).toEqual([["unsubscribe", CHANNEL_ENDPOINT, `Bearer ${SMART_ACCESS_TOKEN}`]]),
      );
    });

    it("leaves no FHIRcast key in sessionStorage", async () => {
      await endedWhileSubscribing();

      await throughout(() => expect(kept()).toEqual([null]));
    });
  });

  describe("while tearing down", () => {
    it("reconnects nothing when the hub closes the socket", async () => {
      const unsubscription = held();
      const { hub, reported, since } = await connected({ unsubscription: unsubscription.answer });

      const ending = endFhircast();
      await eventually(() => expect(since()).toHaveLength(1));
      hub.dropSockets();
      unsubscription.give({ body: {}, status: 202 });
      await ending;

      expect([reported.mock.calls, hub.sockets()]).toEqual([[], [CHANNEL_ENDPOINT]]);
    });

    it("keeps the socket open for the teardown when Home closes the connection", async () => {
      const unsubscription = held();
      const { hub, outcome, since } = await connected({ unsubscription: unsubscription.answer });

      const ending = endFhircast();
      await eventually(() => expect(since()).toHaveLength(1));
      if (outcome.kind === "connected") outcome.close();
      const openAfterClose = hub.openSockets();
      unsubscription.give({ body: {}, status: 202 });
      await ending;

      expect(openAfterClose).toBe(1);
    });
  });
});

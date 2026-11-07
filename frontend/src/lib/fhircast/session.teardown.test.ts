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
import {
  endFhircast,
  FHIRCAST_STORAGE_KEYS,
  type FhircastReleaseSource,
  fhircastSnapshot,
  retryFhircast,
  startFhircast,
} from ".";
import {
  eventually,
  form,
  held,
  RELEASE,
  releasing,
  routes,
  SMART_ACCESS_TOKEN,
  startedWith,
  statusesFromNow,
  throughout,
} from "./fhircast.testHelpers";

// `endFhircast`: the teardown at the hub, in order, and what it does when the
// unsubscription fails, or the connection is still subscribing.

describe("endFhircast", () => {
  /** Connected to a hub answering `options`; the requests the hub was sent from here on. */
  async function connected(options: HubOptions = {}) {
    const hub = hubOnly(options);
    expect(await startedWith()).toBe("connected");
    return { hub, since: requestsSince(hub) };
  }

  /** Retried, once the retry's connection has left `connecting`: the status it shows then. */
  async function retried() {
    retryFhircast();
    await eventually(() => expect(fhircastSnapshot().status).not.toBe("connecting"));
    return fhircastSnapshot().status;
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

    expect(kept()).toEqual([null, null]);
  });

  it("tears down nothing twice", async () => {
    const { since } = await connected();
    await endFhircast();
    const once = since().length;

    await endFhircast();

    expect(since()).toHaveLength(once);
  });

  it("tears down a connection a retry already closed", async () => {
    const hub = hubOnly();
    // The retry's release never comes: the retry closes the connection, and makes none.
    const source = vi
      .fn<FhircastReleaseSource>()
      .mockResolvedValueOnce(RELEASE)
      .mockReturnValue(new Promise(() => undefined));
    startFhircast(source);
    await eventually(() => expect(fhircastSnapshot().status).toBe("connected"));
    retryFhircast();
    const since = requestsSince(hub);

    await endFhircast();

    expect(routes(since())).toEqual([UNSUBSCRIBE]);
  });

  it("unsubscribes a first connection whose socket closed unconfirmed, as well as its retry's", async () => {
    const hub = hubOnly({ socket: ["closes", "confirms"] });
    expect(await startedWith()).toBe("unavailable");
    expect(await retried()).toBe("connected");
    const since = requestsSince(hub);

    await endFhircast();

    expect(unsubscribedEndpoints(since())).toEqual([endpointOf(1), endpointOf(2)]);
  });

  it("unsubscribes a subscription two connections share once", async () => {
    const { since } = await connected();
    // The tab's one subscription, resumed by the retry's connection.
    expect(await retried()).toBe("connected");

    await endFhircast();

    expect(unsubscribedEndpoints(since())).toEqual([CHANNEL_ENDPOINT]);
  });

  it("unsubscribes the shared subscription with the SMART access token of the connection after", async () => {
    const hub = hubOnly();
    const after = { ...RELEASE, accessToken: `smart-${crypto.randomUUID()}` };
    startFhircast(
      vi.fn<FhircastReleaseSource>().mockResolvedValueOnce(RELEASE).mockResolvedValue(after),
    );
    await eventually(() => expect(fhircastSnapshot().status).toBe("connected"));
    expect(await retried()).toBe("connected");
    const since = requestsSince(hub);

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
      await startedWith();
      await retried();
      const since = requestsSince(hub);

      await endFhircast();

      expect(unsubscribedEndpoints(since())).toEqual([endpointOf(1), endpointOf(2)]);
    });

    it("still leaves no FHIRcast key in sessionStorage", async () => {
      await connected({ unsubscription: "unreachable" });

      await endFhircast();

      expect(kept()).toEqual([null, null]);
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
    /** Connecting, its subscription held until the hub answers it; answered once ended. */
    async function endedWhileSubscribing() {
      const subscription = held();
      const hub = hubOnly({ subscription: subscription.answer });
      startFhircast(releasing);
      await eventually(() => expect(hub.requestsTo(HUB_URL)).toHaveLength(1));
      await endFhircast();
      const since = requestsSince(hub);
      subscription.give({ body: { "hub.channel.endpoint": CHANNEL_ENDPOINT }, status: 202 });
      return { hub, since };
    }

    it("shows nothing of it, opening no socket", async () => {
      const { hub } = await endedWhileSubscribing();

      await throughout(() =>
        expect([fhircastSnapshot().status, hub.sockets()]).toEqual(["connecting", []]),
      );
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

      await throughout(() => expect(kept()).toEqual([null, null]));
    });
  });

  describe("while tearing down", () => {
    it("reconnects nothing when the hub closes the socket", async () => {
      const unsubscription = held();
      const { hub, since } = await connected({ unsubscription: unsubscription.answer });
      const statuses = statusesFromNow();

      const ending = endFhircast();
      await eventually(() => expect(since()).toHaveLength(1));
      hub.dropSockets();
      unsubscription.give({ body: {}, status: 202 });
      await ending;

      expect([statuses(), hub.sockets()]).toEqual([["connecting"], [CHANNEL_ENDPOINT]]);
    });

    it("reports nothing when the hub denies the subscription on the socket", async () => {
      const unsubscription = held();
      const { hub, since } = await connected({ unsubscription: unsubscription.answer });
      const statuses = statusesFromNow();

      const ending = endFhircast();
      await eventually(() => expect(since()).toHaveLength(1));
      hub.denySockets("access token expired");
      unsubscription.give({ body: {}, status: 202 });
      await ending;

      expect(statuses()).toEqual(["connecting"]);
    });
  });
});

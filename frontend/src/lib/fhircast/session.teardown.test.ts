import { afterEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CONFIGURATION_URL,
  endpointOf,
  HUB_AUDIENCE,
  HUB_TOKEN,
  HUB_URL,
  type HubAnswer,
  hubOnly,
  type HubOptions,
  hubTokenOf,
  requestsSince,
  TOKEN_URL,
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
    requests
      .filter(({ body }) => form(body)["hub.mode"] === "unsubscribe")
      .map(({ body }) => form(body)["hub.channel.endpoint"]);

  it("unsubscribes, and asks the hub for nothing else", async () => {
    const { since } = await connected();

    await endFhircast();

    expect(routes(since())).toEqual([UNSUBSCRIBE]);
  });

  it("unsubscribes from the topic at its channel endpoint, the hub token as Bearer", async () => {
    const { since } = await connected();

    await endFhircast();

    const [unsubscription] = since();
    expect({ ...unsubscription, body: form(unsubscription.body) }).toMatchObject({
      authorization: `Bearer ${HUB_TOKEN}`,
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

  it("unsubscribes a subscription two connections shared once, though the retry's connection moved off it", async () => {
    // The retry's connection finds the kept endpoint denied, and moves to a new
    // subscription: the one the connection before held is unsubscribed then.
    const hub = hubOnly({ socket: ["confirms", "denies", "confirms"] });
    const since = requestsSince(hub);
    expect(await startedWith()).toBe("connected");
    expect(await retried()).toBe("connected");
    await eventually(() => expect(unsubscribedEndpoints(since())).toHaveLength(1));

    await endFhircast();

    expect(unsubscribedEndpoints(since())).toEqual([endpointOf(1), endpointOf(2)]);
  });

  it("unsubscribes a subscription a retry abandoned before its socket was confirmed once, as well as the retry's", async () => {
    let confirm = () => undefined as void;
    const hub = hubOnly({ hold: [new Promise((resolve) => (confirm = resolve)), null] });
    const since = requestsSince(hub);
    startFhircast(releasing);
    await eventually(() => expect(hub.sockets()).toHaveLength(1));
    // The retry closes the first connection while its socket is still opening.
    expect(await retried()).toBe("connected");
    confirm();

    await endFhircast();

    expect(unsubscribedEndpoints(since())).toEqual([endpointOf(1), endpointOf(2)]);
  });

  it("unsubscribes the shared subscription with the hub token of the connection after", async () => {
    const hub = await connected().then(({ hub }) => hub);
    // The retry's connection obtains a hub token of its own: the second.
    expect(await retried()).toBe("connected");
    const since = requestsSince(hub);

    await endFhircast();

    expect(since().map(({ authorization }) => authorization)).toEqual([`Bearer ${hubTokenOf(2)}`]);
  });

  it("unsubscribes at each hub the tab subscribed at with the newest hub token obtained there", async () => {
    const hub = hubOnly();
    const elsewhere = "http://127.0.0.1:8104/fhircast/STU3";
    // The retry's release names another hub: its connection subscribes there anew.
    const source = vi
      .fn<FhircastReleaseSource>()
      .mockResolvedValueOnce(RELEASE)
      .mockResolvedValue({ ...RELEASE, hubUrl: elsewhere });
    startFhircast(source);
    await eventually(() => expect(fhircastSnapshot().status).toBe("connected"));
    expect(await retried()).toBe("connected");
    const since = requestsSince(hub);

    await endFhircast();

    expect(since().map(({ authorization, url }) => [url, authorization])).toEqual([
      [HUB_URL, `Bearer ${HUB_TOKEN}`],
      [elsewhere, `Bearer ${hubTokenOf(2)}`],
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

      it("is given up after the same 3 seconds while a subscription moved off is still being unsubscribed", async () => {
        const never = new Promise<HubAnswer>(() => undefined);
        // The retry's connection finds the kept endpoint denied, and moves to a
        // new subscription: the one before is being unsubscribed, unanswered.
        const hub = hubOnly({ socket: ["confirms", "denies", "confirms"], unsubscription: never });
        expect(await startedWith()).toBe("connected");
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
        expect(await retried()).toBe("connected");

        let settled = false;
        void endFhircast().then(() => (settled = true));
        await vi.advanceTimersByTimeAsync(2_999);
        const before = [settled, hub.openSockets()];
        await vi.advanceTimersByTimeAsync(1);
        await eventually(() => expect(settled).toBe(true));

        expect([before, hub.openSockets()]).toEqual([[false, 1], 0]);
      });

      it("is given up after the same 3 seconds for a subscription two connections share", async () => {
        const hub = hubOnly({ unsubscription: new Promise<HubAnswer>(() => undefined) });
        expect(await startedWith()).toBe("connected");
        // The tab's one subscription, resumed by the retry's connection.
        expect(await retried()).toBe("connected");
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });

        let settled = false;
        void endFhircast().then(() => (settled = true));
        await eventually(() => expect(unsubscribedEndpoints(hub.requests())).toHaveLength(1));
        await vi.advanceTimersByTimeAsync(2_999);
        const before = [settled, hub.openSockets()];
        await vi.advanceTimersByTimeAsync(1);
        await eventually(() => expect(settled).toBe(true));

        expect([before, hub.openSockets()]).toEqual([[false, 1], 0]);
      });
    });
  });

  it("unsubscribes with a hub token whose lifetime the hub did not state, obtaining none", async () => {
    const { since } = await connected({ hubTokenSeconds: null });

    await endFhircast();

    expect([routes(since()), since()[0]?.authorization]).toEqual([
      [UNSUBSCRIBE],
      `Bearer ${HUB_TOKEN}`,
    ]);
  });

  it("keeps the profile's dynamic client", async () => {
    const { hub } = await connected();

    await endFhircast();

    expect([...(hub.profile.store("fhircast-clients", "clients")?.keys() ?? [])]).toEqual([
      HUB_AUDIENCE,
    ]);
  });

  describe("once the hub token the connection holds has expired", () => {
    /** When the clock starts: the hub token the connection obtains then lasts 200 s. */
    const CONNECTED_AT = Date.UTC(2026, 9, 24, 2, 0, 0);

    /**
     * Connected to a hub answering `options` whose hub tokens last 200 s —
     * on a retry, once `retrying` — the clock then moved past that without its
     * timers running, as a workstation waking from sleep.
     */
    async function connectedPastTheHubToken(options: HubOptions = {}, retrying = false) {
      vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
      vi.setSystemTime(CONNECTED_AT);
      const hub = hubOnly({ hubTokenSeconds: 200, ...options });
      startFhircast(releasing);
      await eventually(() => expect(hub.sockets()).toHaveLength(1));
      await vi.advanceTimersByTimeAsync(0);
      if (retrying) {
        await eventually(() => expect(fhircastSnapshot().status).toBe("unavailable"));
        retryFhircast();
        await eventually(() => expect(hub.sockets()).toHaveLength(2));
        await vi.advanceTimersByTimeAsync(0);
      }
      await eventually(() => expect(fhircastSnapshot().status).toBe("connected"));
      vi.setSystemTime(CONNECTED_AT + 200_000);
      return { hub, since: requestsSince(hub) };
    }

    /** Ends the session's FHIRcast; answers whether it has settled, as the clock moves on. */
    function ending() {
      let settled = false;
      void endFhircast().then(() => (settled = true));
      return () => settled;
    }

    afterEach(() => {
      vi.useRealTimers();
    });

    it("obtains a fresh one for the client the profile kept, registering none, and unsubscribes with it", async () => {
      const { since } = await connectedPastTheHubToken();

      const settled = ending();
      await vi.advanceTimersByTimeAsync(0);
      await eventually(() => expect(settled()).toBe(true));

      expect([routes(since()), since()[2]?.authorization]).toEqual([
        [`GET ${CONFIGURATION_URL}`, `POST ${TOKEN_URL}`, UNSUBSCRIBE],
        `Bearer ${hubTokenOf(2)}`,
      ]);
    });

    it("obtains one fresh one, shared by every subscription it unsubscribes", async () => {
      // The first connection's socket closes unconfirmed; the retry's subscribes anew.
      const { since } = await connectedPastTheHubToken({ socket: ["closes", "confirms"] }, true);

      const settled = ending();
      await vi.advanceTimersByTimeAsync(0);
      await eventually(() => expect(settled()).toBe(true));

      expect(routes(since())).toEqual([
        `GET ${CONFIGURATION_URL}`,
        `POST ${TOKEN_URL}`,
        UNSUBSCRIBE,
        UNSUBSCRIBE,
      ]);
    });

    it("keeps the profile's dynamic client", async () => {
      const { hub } = await connectedPastTheHubToken();

      const settled = ending();
      await vi.advanceTimersByTimeAsync(0);
      await eventually(() => expect(settled()).toBe(true));

      expect(hub.profile.store("fhircast-clients", "clients")?.has(HUB_AUDIENCE)).toBe(true);
    });

    it("keeps the profile's dynamic client, registering none, when the hub answers that it has forgotten it", async () => {
      const { hub, since } = await connectedPastTheHubToken({
        token: [
          { body: { access_token: HUB_TOKEN, expires_in: 200 }, status: 200 },
          { body: { error: "INVALID-CLIENT" }, status: 401 },
        ],
      });

      const settled = ending();
      await vi.advanceTimersByTimeAsync(0);
      await eventually(() => expect(settled()).toBe(true));

      expect([
        routes(since()),
        hub.profile.store("fhircast-clients", "clients")?.has(HUB_AUDIENCE),
      ]).toEqual([[`GET ${CONFIGURATION_URL}`, `POST ${TOKEN_URL}`], true]);
    });

    it("unsubscribes nothing, still closing the socket, when the hub refuses the fresh one", async () => {
      const { hub, since } = await connectedPastTheHubToken({
        token: [
          { body: { access_token: HUB_TOKEN, expires_in: 200 }, status: 200 },
          { status: 500 },
        ],
      });

      const settled = ending();
      await vi.advanceTimersByTimeAsync(0);
      await eventually(() => expect(settled()).toBe(true));

      expect([routes(since()), hub.openSockets()]).toEqual([
        [`GET ${CONFIGURATION_URL}`, `POST ${TOKEN_URL}`],
        0,
      ]);
    });

    it("settles 3 seconds on when the hub leaves the fresh one unanswered, closing the socket then", async () => {
      const token = held();
      const { hub } = await connectedPastTheHubToken({
        token: [{ body: { access_token: HUB_TOKEN, expires_in: 200 }, status: 200 }, token.answer],
      });

      const settled = ending();
      await eventually(() => expect(hub.requestsTo(TOKEN_URL)).toHaveLength(2));
      await vi.advanceTimersByTimeAsync(2_999);
      const before = [settled(), hub.openSockets()];
      await vi.advanceTimersByTimeAsync(1);
      await eventually(() => expect(settled()).toBe(true));

      expect([before, hub.openSockets()]).toEqual([[false, 1], 0]);
    });

    it("settles within the same 3 seconds, the fresh one and the unsubscription together", async () => {
      const token = held();
      const { hub } = await connectedPastTheHubToken({
        token: [{ body: { access_token: HUB_TOKEN, expires_in: 200 }, status: 200 }, token.answer],
        unsubscription: new Promise<HubAnswer>(() => undefined),
      });

      const settled = ending();
      await eventually(() => expect(hub.requestsTo(TOKEN_URL)).toHaveLength(2));
      await vi.advanceTimersByTimeAsync(2_000);
      token.give({ body: { access_token: hubTokenOf(2), expires_in: 200 }, status: 200 });
      await eventually(() => expect(hub.requestsTo(HUB_URL)).toHaveLength(2));
      await vi.advanceTimersByTimeAsync(999);
      const before = settled();
      await vi.advanceTimersByTimeAsync(1);

      await eventually(() => expect([before, settled()]).toEqual([false, true]));
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

    it("unsubscribes from the endpoint the hub then answers, with the hub token", async () => {
      const { since } = await endedWhileSubscribing();

      await eventually(() =>
        expect(
          since().map(({ authorization, body }) => [
            form(body)["hub.mode"],
            form(body)["hub.channel.endpoint"],
            authorization,
          ]),
        ).toEqual([["unsubscribe", CHANNEL_ENDPOINT, `Bearer ${HUB_TOKEN}`]]),
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
      hub.denySockets("hub token expired");
      unsubscription.give({ body: {}, status: 202 });
      await ending;

      expect(statuses()).toEqual(["connecting"]);
    });
  });
});

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  endpointOf,
  type FakeHub,
  HUB_URL,
  type HubAnswer,
  hubOnly,
  type HubOptions,
  requestsSince,
} from "../fake-hub.testHelpers";
import { endFhircast, fhircastSnapshot, retryFhircast, startFhircast } from ".";
import {
  eventually,
  followed,
  held,
  keptState,
  PATIENT_OPEN,
  realSetTimeout,
  RELEASE,
  releasing,
  routes,
  SMART_ACCESS_TOKEN,
  startedWith,
  statusesFromNow,
  throughout,
} from "./fhircast.testHelpers";

// The session's FHIRcast connection once connected, on a fake clock: renewal,
// reconnecting after the socket drops, holding one live subscription at a
// time, a hub that leaves a subscription unanswered, and ending with the
// SMART access token.

/** Each `hub.mode` request `hub` was sent at the hub URL in `mode`, in order. */
const modeAt = (hub: FakeHub, mode: "subscribe" | "unsubscribe") =>
  hub.requestsTo(HUB_URL).filter(({ body }) => new URLSearchParams(body).get("hub.mode") === mode);

/** Each subscription `hub` was asked for, in order. */
const subscriptionsAt = (hub: FakeHub) => modeAt(hub, "subscribe");

/** Each unsubscription the hub was sent: its endpoint, and its Bearer. */
const unsubscriptions = (hub: FakeHub) =>
  modeAt(hub, "unsubscribe").map(({ authorization, body }) => [
    new URLSearchParams(body).get("hub.channel.endpoint"),
    authorization,
  ]);

/** An unsubscription of the `n`th subscription's endpoint, as {@link unsubscriptions} reads it. */
const unsubscribedFrom = (n: number) => [endpointOf(n), `Bearer ${SMART_ACCESS_TOKEN}`];

/** The hub's answer to a subscription: the `n`th channel endpoint. */
const answeredEndpoint = (n: number): HubAnswer => ({
  body: { "hub.channel.endpoint": endpointOf(n) },
  status: 202,
});

const SECOND_ENDPOINT = endpointOf(2);

describe("the session's FHIRcast connection, staying connected", () => {
  /**
   * When the fake clock starts: a whole second, so that a time read within
   * the second it takes to connect falls in that same second.
   */
  const CONNECTED_AT = Date.UTC(2026, 9, 24, 2, 0, 0);

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
    vi.setSystemTime(CONNECTED_AT);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /**
   * Started with `release` at a hub answering `options`, on the fake clock,
   * once connected: every event it hands on, and every status it moves to
   * from there, recorded.
   */
  async function connectedOnTheClock(options: HubOptions = {}, release = RELEASE) {
    const hub = hubOnly(options);
    const events = followed();
    startFhircast(() => Promise.resolve(release));
    await eventually(() => expect(hub.sockets()).toHaveLength(1));
    await vi.advanceTimersByTimeAsync(0);
    await eventually(() => expect(fhircastSnapshot().status).toBe("connected"));
    return { events, hub, reported: statusesFromNow() };
  }

  /** A hub's answer that never comes: a subscription it leaves unanswered. */
  const NEVER = new Promise<HubAnswer>(() => undefined);

  /** Moves the clock `ms` on, then lets the hub confirm the socket the page has opened by then. */
  async function movedOnAfter(ms: number, hub: FakeHub, sockets: number) {
    await vi.advanceTimersByTimeAsync(ms);
    await eventually(() => expect(hub.sockets()).toHaveLength(sockets));
    await vi.advanceTimersByTimeAsync(0);
  }

  /** The event a Patient-open is handed on as. */
  const HANDED_OPEN = {
    event: "Patient-open",
    patientId: "patient-1",
    timestamp: "2026-10-24T02:00:05Z",
  };

  describe("renewal", () => {
    it.each<[string, number, number]>([
      ["80% into an hour's lease", 3600, 2_880_000],
      ["60 s before a 200 s lease expires", 200, 140_000],
      ["5 s into a lease of a minute, which has no time 60 s before it ends", 60, 5_000],
    ])("starts %s, and not before", async (_name, leaseSeconds, at) => {
      const { hub } = await connectedOnTheClock({ leaseSeconds });
      const subscribed = () => subscriptionsAt(hub).length;

      await vi.advanceTimersByTimeAsync(at - 1);
      expect(subscribed()).toBe(1);
      await vi.advanceTimersByTimeAsync(1);

      await eventually(() => expect([subscribed(), hub.sockets().length]).toEqual([2, 2]));
    });

    it.each<[string, unknown]>([
      ["names none", undefined],
      ["is zero", 0],
      ["is negative", -200],
      ["is no number", "200"],
    ])("never starts by a lease that %s", async (_name, leaseSeconds) => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: leaseSeconds as number });

      await vi.advanceTimersByTimeAsync(3_000_000_000);

      await throughout(() => expect(subscriptionsAt(hub)).toHaveLength(1));
    });

    it("waits out a lease longer than a browser timer holds", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 10_000_000 });

      await vi.advanceTimersByTimeAsync(2_147_483_646);

      await throughout(() => expect(subscriptionsAt(hub)).toHaveLength(1));
    });

    it("subscribes again with the same SMART access token", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await movedOnAfter(140_000, hub, 2);

      expect(subscriptionsAt(hub).map(({ authorization }) => authorization)).toEqual([
        `Bearer ${SMART_ACCESS_TOKEN}`,
        `Bearer ${SMART_ACCESS_TOKEN}`,
      ]);
    });

    it("subscribes again to the same events on the same topic", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await movedOnAfter(140_000, hub, 2);

      expect(Object.fromEntries(new URLSearchParams(subscriptionsAt(hub)[1].body))).toEqual({
        "hub.channel.type": "websocket",
        "hub.events": "Patient-open,Patient-close",
        "hub.mode": "subscribe",
        "hub.topic": "topic-1",
      });
    });

    it("renews again by the renewed subscription's lease", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await movedOnAfter(140_000, hub, 2);
      await movedOnAfter(140_000, hub, 3);

      expect(hub.sockets()).toEqual([1, 2, 3].map(endpointOf));
    });

    it("moves onto the renewed subscription's socket, closing the one before", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await movedOnAfter(140_000, hub, 2);

      expect([hub.sockets(), hub.openSockets()]).toEqual([[CHANNEL_ENDPOINT, SECOND_ENDPOINT], 1]);
    });

    it("keeps the renewed subscription in sessionStorage, and only that", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await movedOnAfter(140_000, hub, 2);

      expect(keptState()?.subscription).toEqual({
        endpoint: SECOND_ENDPOINT,
        leaseExpiresAt: CONNECTED_AT + 140_000 + 200_000,
      });
    });

    it("renews unseen, reporting nothing", async () => {
      const { hub, reported } = await connectedOnTheClock({ leaseSeconds: 200 });

      await movedOnAfter(140_000, hub, 2);

      expect(reported()).toEqual([]);
    });

    it("hands on events from the renewed socket", async () => {
      const { events, hub } = await connectedOnTheClock({ leaseSeconds: 200 });
      await movedOnAfter(140_000, hub, 2);

      hub.notify(PATIENT_OPEN);

      expect(events.mock.calls).toEqual([[HANDED_OPEN]]);
    });

    it("reconnects a renewed connection the hub then drops", async () => {
      const { hub, reported } = await connectedOnTheClock({ leaseSeconds: 200 });
      await movedOnAfter(140_000, hub, 2);

      hub.dropSockets();

      expect(reported()).toEqual(["reconnecting"]);
    });

    it("leaves a socket the hub drops mid-renewal to the renewal", async () => {
      const renewal = held();
      const { hub, reported } = await connectedOnTheClock({
        leaseSeconds: 200,
        subscription: [answeredEndpoint(1), renewal.answer],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(subscriptionsAt(hub)).toHaveLength(2));

      hub.dropSockets();
      renewal.give(answeredEndpoint(2));
      await eventually(() => expect(hub.sockets()).toHaveLength(2));
      await vi.advanceTimersByTimeAsync(0);

      expect([reported(), hub.openSockets()]).toEqual([[], 1]);
    });

    it.each<[string, HubOptions]>([
      [
        "the hub refuses its subscription",
        { leaseSeconds: 200, subscription: [answeredEndpoint(1), { status: 401 }] },
      ],
      ["its socket closes unconfirmed", { leaseSeconds: 200, socket: ["confirms", "closes"] }],
    ])("falls back to reconnecting when %s", async (_name, options) => {
      const { hub, reported } = await connectedOnTheClock(options);

      await vi.advanceTimersByTimeAsync(140_000);

      await eventually(() => expect(reported()).toEqual(["reconnecting"]));
      expect(hub.openSockets()).toBe(0);
    });

    it("retries a failed renewal by subscribing anew", async () => {
      const { hub, reported } = await connectedOnTheClock({
        leaseSeconds: 200,
        subscription: [answeredEndpoint(1), { status: 401 }, answeredEndpoint(2)],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(reported()).toContain("reconnecting"));

      await movedOnAfter(1_000, hub, 2);

      expect([reported(), hub.sockets()]).toEqual([
        ["reconnecting", "connected"],
        [CHANNEL_ENDPOINT, SECOND_ENDPOINT],
      ]);
    });

    it("renews no more once the session's FHIRcast has ended", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await endFhircast();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect(subscriptionsAt(hub)).toHaveLength(1);
    });

    it("keeps nothing a renewal under way is answered once a retry replaced it, and unsubscribes from it", async () => {
      const renewal = held();
      const { hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        subscription: [answeredEndpoint(1), renewal.answer],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(subscriptionsAt(hub)).toHaveLength(2));

      // The retry's connection resumes the tab's subscription, reopening its endpoint.
      retryFhircast();
      await eventually(() => expect(fhircastSnapshot().status).toBe("connected"));
      renewal.give(answeredEndpoint(2));
      await eventually(() => expect(unsubscriptions(hub)).toEqual([unsubscribedFrom(2)]));

      expect([(keptState()?.subscription as { endpoint: string }).endpoint, hub.sockets()]).toEqual(
        [CHANNEL_ENDPOINT, [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT]],
      );
    });
  });

  describe("reconnecting", () => {
    /** Connected, then dropped by the hub. */
    async function dropped(options: HubOptions = {}) {
      const connected = await connectedOnTheClock(options);
      connected.hub.dropSockets();
      return connected;
    }

    it("reopens the endpoint a second after the hub drops the socket, and not before", async () => {
      const { hub } = await dropped();

      await vi.advanceTimersByTimeAsync(999);
      expect(hub.sockets()).toHaveLength(1);
      await vi.advanceTimersByTimeAsync(1);

      expect(hub.sockets()).toEqual([CHANNEL_ENDPOINT, CHANNEL_ENDPOINT]);
    });

    it("reopens the endpoint asking the hub for nothing", async () => {
      const { hub } = await dropped();
      const since = requestsSince(hub);

      await vi.advanceTimersByTimeAsync(1_000);

      await throughout(() => expect(since()).toEqual([]));
    });

    it("reports connected once the reopened socket is confirmed", async () => {
      const { reported } = await dropped();

      await vi.advanceTimersByTimeAsync(1_000);

      expect(reported()).toEqual(["reconnecting", "connected"]);
    });

    it("hands on events from the reopened socket", async () => {
      const { events, hub } = await dropped();
      await vi.advanceTimersByTimeAsync(1_000);

      hub.notify(PATIENT_OPEN);

      expect(events.mock.calls).toEqual([[HANDED_OPEN]]);
    });

    it("spaces its retries by a backoff that doubles", async () => {
      const { hub } = await dropped({ socket: ["confirms", "closes"] });
      const opened: number[] = [];

      for (const backoff of [1_000, 2_000, 4_000, 8_000, 16_000]) {
        await vi.advanceTimersByTimeAsync(backoff - 1);
        opened.push(hub.sockets().length - 1);
        await vi.advanceTimersByTimeAsync(1);
        opened.push(hub.sockets().length - 1);
      }

      expect(opened).toEqual([0, 1, 1, 2, 2, 3, 3, 4, 4, 5]);
    });

    it("leaves the snapshot as it is while its retries fail, until the last", async () => {
      await dropped({ socket: ["confirms", "closes"] });
      const reconnecting = fhircastSnapshot();

      // Four retries fail, 1, 3, 7 and 15 s on; the fifth is due 31 s on.
      await vi.advanceTimersByTimeAsync(30_999);

      expect(fhircastSnapshot()).toBe(reconnecting);
    });

    it("gives up after five retries, reporting disconnected", async () => {
      const { hub, reported } = await dropped({ socket: ["confirms", "closes"] });

      await vi.advanceTimersByTimeAsync(7_200_000);

      expect([hub.sockets().length - 1, reported()]).toEqual([5, ["reconnecting", "disconnected"]]);
    });

    it("counts its retries afresh once a retry has connected", async () => {
      const { hub, reported } = await dropped({
        socket: ["confirms", "closes", "confirms", "closes"],
      });
      await vi.advanceTimersByTimeAsync(3_000);

      hub.dropSockets();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect([hub.sockets().length, reported()]).toEqual([
        8,
        ["reconnecting", "connected", "reconnecting", "disconnected"],
      ]);
    });

    it("subscribes anew when the hub says the endpoint is gone", async () => {
      const { hub } = await dropped({ socket: ["confirms", "denies", "confirms"] });

      await movedOnAfter(1_000, hub, 3);

      expect([hub.sockets(), hub.openSockets()]).toEqual([
        [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT, SECOND_ENDPOINT],
        1,
      ]);
    });

    it("subscribes anew with the SMART access token", async () => {
      const { hub } = await dropped({ socket: ["confirms", "denies", "confirms"] });

      await movedOnAfter(1_000, hub, 3);

      expect(subscriptionsAt(hub)[1].authorization).toBe(`Bearer ${SMART_ACCESS_TOKEN}`);
    });

    it("reports connected once the new subscription's socket is confirmed", async () => {
      const { hub, reported } = await dropped({ socket: ["confirms", "denies", "confirms"] });

      await movedOnAfter(1_000, hub, 3);

      expect(reported()).toEqual(["reconnecting", "connected"]);
    });

    it("takes the next retry when subscribing anew fails", async () => {
      const { hub, reported } = await dropped({
        socket: ["confirms", "denies"],
        subscription: [answeredEndpoint(1), { status: 401 }],
      });

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(subscriptionsAt(hub)).toHaveLength(2));
      await vi.advanceTimersByTimeAsync(2_000);
      await eventually(() => expect(subscriptionsAt(hub)).toHaveLength(3));

      expect([hub.sockets().length, reported()]).toEqual([2, ["reconnecting"]]);
    });

    it("retries no more once the session's FHIRcast has ended", async () => {
      const { hub } = await dropped();

      await endFhircast();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect(hub.sockets()).toHaveLength(1);
    });

    it("closes a socket its retry opens once the session's FHIRcast has ended", async () => {
      let confirm = () => undefined as void;
      const { hub, reported } = await dropped({
        hold: [null, new Promise((resolve) => (confirm = resolve))],
      });
      await vi.advanceTimersByTimeAsync(1_000);
      expect(hub.sockets()).toHaveLength(2);

      await endFhircast();
      confirm();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect([hub.openSockets(), hub.sockets().length, reported()]).toEqual([
        0,
        2,
        ["reconnecting", "connecting"],
      ]);
    });

    it("takes nothing but the hub's denial for the endpoint gone", async () => {
      const { hub } = await dropped({ socket: ["confirms", "says-otherwise"] });

      await vi.advanceTimersByTimeAsync(1_000);

      await throughout(() =>
        expect([hub.sockets().length, hub.requestsTo(HUB_URL).length]).toEqual([2, 1]),
      );
    });

    it("reopens the new subscription's endpoint when that socket drops in turn", async () => {
      const { hub } = await dropped({ socket: ["confirms", "denies", "confirms"] });
      await movedOnAfter(1_000, hub, 3);

      hub.dropSockets();
      await vi.advanceTimersByTimeAsync(1_000);

      expect(hub.sockets().slice(2)).toEqual([SECOND_ENDPOINT, SECOND_ENDPOINT]);
    });

    it("drops the renewal that was due once the socket drops", async () => {
      const { hub } = await dropped({ leaseSeconds: 200, socket: ["confirms", "closes"] });

      await vi.advanceTimersByTimeAsync(200_000);

      expect(subscriptionsAt(hub)).toHaveLength(1);
    });

    it("reconnects when the socket drops mid-renewal and the renewal then fails", async () => {
      const renewal = held();
      const { hub, reported } = await connectedOnTheClock({
        leaseSeconds: 200,
        subscription: [answeredEndpoint(1), renewal.answer],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(subscriptionsAt(hub)).toHaveLength(2));

      hub.dropSockets();
      renewal.give({ status: 401 });

      await eventually(() => expect(reported()).toEqual(["reconnecting"]));
    });

    it("reports no connection won back while retrying a failed renewal fails", async () => {
      const { hub, reported } = await connectedOnTheClock({
        leaseSeconds: 200,
        subscription: [answeredEndpoint(1), { status: 401 }],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(reported()).toContain("reconnecting"));

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(subscriptionsAt(hub)).toHaveLength(3));
      await vi.advanceTimersByTimeAsync(0);

      expect(reported()).toEqual(["reconnecting"]);
    });
  });

  describe("one live subscription", () => {
    /**
     * Ends the session's FHIRcast on the fake clock, moving it on until the
     * teardown settles: a hub that never answers holds each step its 3 s.
     */
    async function endedOnTheClock() {
      let settled = false;
      void endFhircast().then(() => (settled = true));
      while (!settled) {
        await vi.advanceTimersByTimeAsync(3_000);
        await new Promise((resolve) => realSetTimeout(resolve, 5));
      }
    }

    it("unsubscribes the endpoint a renewal moved off, with the SMART access token", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await movedOnAfter(140_000, hub, 2);

      await eventually(() => expect(unsubscriptions(hub)).toEqual([unsubscribedFrom(1)]));
    });

    it("unsubscribes from the topic over a websocket, and asks the hub for nothing else", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });
      const since = requestsSince(hub);

      await movedOnAfter(140_000, hub, 2);

      await eventually(() =>
        expect([routes(since()), new URLSearchParams(since()[1]?.body).get("hub.topic")]).toEqual([
          [`POST ${HUB_URL}`, `POST ${HUB_URL}`],
          "topic-1",
        ]),
      );
    });

    it("cancels nothing until the new subscription's socket is confirmed", async () => {
      let confirm = () => undefined as void;
      const { hub } = await connectedOnTheClock({
        hold: [null, new Promise((resolve) => (confirm = resolve))],
        leaseSeconds: 200,
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(2));

      await throughout(() => expect(unsubscriptions(hub)).toEqual([]));
      confirm();

      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));
    });

    it("cancels the subscription a resubscribe moved off once the hub denied its endpoint", async () => {
      const { hub } = await connectedOnTheClock({
        socket: ["confirms", "denies", "confirms"],
      });
      hub.dropSockets();

      await movedOnAfter(1_000, hub, 3);

      await eventually(() => expect(unsubscriptions(hub)).toEqual([unsubscribedFrom(1)]));
    });

    it("cancels nothing when it reopens the same subscription after a drop", async () => {
      const { hub, reported } = await connectedOnTheClock();
      hub.dropSockets();

      await vi.advanceTimersByTimeAsync(1_000);
      expect(reported()).toEqual(["reconnecting", "connected"]);

      await throughout(() => expect(unsubscriptions(hub)).toEqual([]));
    });

    it("unsubscribes nothing when the renewal is answered the same endpoint", async () => {
      const { hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        subscription: answeredEndpoint(1),
      });

      await movedOnAfter(140_000, hub, 2);

      await throughout(() => expect(unsubscriptions(hub)).toEqual([]));
    });

    it("unsubscribes both the subscription last confirmed and the one still opening when the session ends", async () => {
      let confirm = () => undefined as void;
      const { hub } = await connectedOnTheClock({
        hold: [null, new Promise((resolve) => (confirm = resolve))],
        leaseSeconds: 200,
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(2));

      await endFhircast();
      confirm();

      expect(unsubscriptions(hub)).toEqual([unsubscribedFrom(1), unsubscribedFrom(2)]);
    });

    it("unsubscribes a failed renewal's subscription, as well as the live one, each once, when the session ends", async () => {
      const { hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        socket: ["confirms", "denies", "confirms"],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(2));
      await movedOnAfter(1_000, hub, 3);
      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));

      await endFhircast();

      expect(unsubscriptions(hub)).toEqual([1, 3, 2].map(unsubscribedFrom));
    });

    it("unsubscribes every subscription a failed renewal and each failed retry was answered when the session ends", async () => {
      const { hub, reported } = await connectedOnTheClock({
        leaseSeconds: 200,
        socket: ["confirms", "closes"],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      while (!reported().includes("disconnected")) {
        await vi.advanceTimersByTimeAsync(1_000);
        await new Promise((resolve) => realSetTimeout(resolve, 5));
      }

      await endFhircast();

      expect(unsubscriptions(hub)).toEqual([1, 2, 3, 4, 5, 6, 7].map(unsubscribedFrom));
    });

    it.each<[string, HubAnswer]>([
      ["is never reached", "unreachable"],
      ["fails", { status: 500, text: "<html>down</html>" }],
    ])("stays connected when the unsubscription %s", async (_name, unsubscription) => {
      const { events, hub, reported } = await connectedOnTheClock({
        leaseSeconds: 200,
        unsubscription,
      });
      await movedOnAfter(140_000, hub, 2);
      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));

      hub.notify(PATIENT_OPEN);

      expect([events.mock.calls, reported(), hub.openSockets()]).toEqual([[[HANDED_OPEN]], [], 1]);
    });

    it("renews again on time while the hub leaves the last unsubscription unanswered", async () => {
      const never = new Promise<HubAnswer>(() => undefined);
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200, unsubscription: never });
      await movedOnAfter(140_000, hub, 2);

      await movedOnAfter(140_000, hub, 3);
      await endedOnTheClock();

      expect(hub.sockets()).toEqual([1, 2, 3].map(endpointOf));
    });

    it("unsubscribes the live subscription at the end of the session only once the unsubscription under way is done", async () => {
      const unsubscription = held();
      const { hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        unsubscription: unsubscription.answer,
      });
      await movedOnAfter(140_000, hub, 2);
      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));

      const ending = endFhircast();
      await throughout(() => expect(unsubscriptions(hub)).toHaveLength(1));
      unsubscription.give({ body: {}, status: 202 });
      await ending;

      expect(unsubscriptions(hub)).toEqual([unsubscribedFrom(1), unsubscribedFrom(2)]);
    });

    it("gives up an unsubscription the hub leaves unanswered after 3 seconds, and the next one runs", async () => {
      const never = new Promise<HubAnswer>(() => undefined);
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200, unsubscription: never });
      await movedOnAfter(140_000, hub, 2);
      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));

      void endFhircast();
      await vi.advanceTimersByTimeAsync(2_999);
      const before = unsubscriptions(hub).length;
      await vi.advanceTimersByTimeAsync(1);
      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(2));
      await endedOnTheClock();

      expect(before).toBe(1);
    });
  });

  describe("a subscription the hub leaves unanswered", () => {
    describe("on the first connection", () => {
      /** Started at a hub answering `options`, once it has been asked for the subscription. */
      async function subscribing(options: HubOptions) {
        const hub = hubOnly(options);
        startFhircast(releasing);
        await eventually(() => expect(subscriptionsAt(hub)).toHaveLength(1));
        return hub;
      }

      it("shows connecting for just under ten seconds", async () => {
        await subscribing({ subscription: NEVER });

        await vi.advanceTimersByTimeAsync(9_999);

        await throughout(() => expect(fhircastSnapshot().status).toBe("connecting"));
      });

      it("shows FHIRcast unavailable once given up, ten seconds on", async () => {
        await subscribing({ subscription: NEVER });

        await vi.advanceTimersByTimeAsync(10_000);

        await eventually(() => expect(fhircastSnapshot().status).toBe("unavailable"));
      });

      it("is unavailable, not blocked, though Local Network Access was refused", async () => {
        await subscribing({ localNetworkAccess: "denied", subscription: NEVER });

        await vi.advanceTimersByTimeAsync(10_000);

        await eventually(() => expect(fhircastSnapshot().status).toBe("unavailable"));
      });

      it("opens nothing for an answer the hub gives once it is given up", async () => {
        const subscription = held();
        const hub = await subscribing({ subscription: subscription.answer });
        await vi.advanceTimersByTimeAsync(10_000);
        await eventually(() => expect(fhircastSnapshot().status).toBe("unavailable"));

        subscription.give(answeredEndpoint(1));

        await throughout(() => expect([hub.sockets(), keptState()]).toEqual([[], null]));
      });
    });

    describe("on a renewal", () => {
      /** Connected on a 200 s lease, then renewing 140 s on at a hub answering `renewals` in turn. */
      async function renewingAt(renewals: (HubAnswer | Promise<HubAnswer>)[]) {
        const connected = await connectedOnTheClock({
          leaseSeconds: 200,
          subscription: [answeredEndpoint(1), ...renewals],
        });
        await vi.advanceTimersByTimeAsync(140_000);
        await eventually(() => expect(subscriptionsAt(connected.hub)).toHaveLength(2));
        return connected;
      }

      it("stays connected for just under ten seconds", async () => {
        const { reported } = await renewingAt([NEVER]);

        await vi.advanceTimersByTimeAsync(9_999);

        await throughout(() => expect(reported()).toEqual([]));
      });

      it("reconnects once given up, ten seconds on, closing the socket it renewed", async () => {
        const { hub, reported } = await renewingAt([NEVER]);

        await vi.advanceTimersByTimeAsync(10_000);

        await eventually(() =>
          expect([reported(), hub.openSockets()]).toEqual([["reconnecting"], 0]),
        );
      });

      it("is connected again by the retry, which subscribes anew", async () => {
        const { hub, reported } = await renewingAt([NEVER, answeredEndpoint(2)]);
        await vi.advanceTimersByTimeAsync(10_000);
        await eventually(() => expect(reported()).toEqual(["reconnecting"]));

        await movedOnAfter(1_000, hub, 2);

        expect([reported(), hub.sockets()]).toEqual([
          ["reconnecting", "connected"],
          [CHANNEL_ENDPOINT, SECOND_ENDPOINT],
        ]);
      });

      it("reconnects a socket the hub drops meanwhile once given up, rather than showing connected", async () => {
        const { hub, reported } = await renewingAt([NEVER]);
        hub.dropSockets();
        await vi.advanceTimersByTimeAsync(9_999);
        const whileRenewing = [...reported()];

        await vi.advanceTimersByTimeAsync(1);

        await eventually(() => expect([whileRenewing, reported()]).toEqual([[], ["reconnecting"]]));
      });

      it("shows disconnected once every retry's subscription is left unanswered too", async () => {
        const { hub, reported } = await renewingAt([NEVER]);

        // Each retry's subscription given up in turn, after its backoff.
        while (!reported().includes("disconnected")) {
          await vi.advanceTimersByTimeAsync(1_000);
          await new Promise((resolve) => realSetTimeout(resolve, 5));
        }

        expect([reported(), subscriptionsAt(hub).length]).toEqual([
          ["reconnecting", "disconnected"],
          7,
        ]);
      });
    });
  });

  describe("ending with the SMART access token", () => {
    /** A release whose SMART access token expires an hour after the clock starts. */
    const EXPIRING_IN_AN_HOUR = { ...RELEASE, expiresAt: "2026-10-24T03:00:00Z" };

    /** A release whose SMART access token expires `ms` after the clock starts. */
    const expiringAfter = (ms: number) => ({
      ...RELEASE,
      expiresAt: new Date(CONNECTED_AT + ms).toISOString(),
    });

    describe("once the hub denies the live subscription", () => {
      it("reports ended", async () => {
        const { hub, reported } = await connectedOnTheClock();

        hub.denySockets("access token expired");

        await eventually(() => expect(reported()).toEqual(["ended"]));
      });

      it("renews nothing and reconnects nothing", async () => {
        const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });
        const since = requestsSince(hub);

        hub.denySockets("access token expired");
        await vi.advanceTimersByTimeAsync(7_200_000);

        await throughout(() => expect([since(), hub.sockets().length]).toEqual([[], 1]));
      });

      it("ends though a renewal is under way, opening no socket for it", async () => {
        const renewal = held();
        const { hub, reported } = await connectedOnTheClock({
          leaseSeconds: 200,
          subscription: [answeredEndpoint(1), renewal.answer],
        });
        await vi.advanceTimersByTimeAsync(140_000);
        await eventually(() => expect(subscriptionsAt(hub)).toHaveLength(2));

        hub.denySockets("access token expired");
        renewal.give(answeredEndpoint(2));
        await vi.advanceTimersByTimeAsync(7_200_000);

        await throughout(() =>
          expect([reported(), hub.sockets()]).toEqual([["ended"], [CHANNEL_ENDPOINT]]),
        );
      });
    });

    describe("once its expiry passes, though the hub has said nothing", () => {
      it("reports ended at the expiry, and not before", async () => {
        const { reported } = await connectedOnTheClock({}, EXPIRING_IN_AN_HOUR);

        await vi.advanceTimersByTimeAsync(3_599_999);
        const before = [...reported()];
        await vi.advanceTimersByTimeAsync(1);

        expect([before, reported()]).toEqual([[], ["ended"]]);
      });

      it("closes the socket", async () => {
        const { hub } = await connectedOnTheClock({}, EXPIRING_IN_AN_HOUR);

        await vi.advanceTimersByTimeAsync(3_600_000);

        expect(hub.openSockets()).toBe(0);
      });

      it("renews nothing after it", async () => {
        // Renewals 1,600 s into each 2,000 s lease: two before the expiry, one due at 4,800 s.
        const { hub } = await connectedOnTheClock({ leaseSeconds: 2_000 }, EXPIRING_IN_AN_HOUR);
        await movedOnAfter(1_600_000, hub, 2);
        await movedOnAfter(1_600_000, hub, 3);

        await vi.advanceTimersByTimeAsync(3_600_000);

        await throughout(() => expect(subscriptionsAt(hub)).toHaveLength(3));
      });

      it("retries no more once it passes while reconnecting", async () => {
        const { hub, reported } = await connectedOnTheClock(
          { socket: ["confirms", "closes"] },
          expiringAfter(2_000),
        );
        hub.dropSockets();

        await vi.advanceTimersByTimeAsync(60_000);

        expect([hub.sockets().length, reported()]).toEqual([2, ["reconnecting", "ended"]]);
      });

      it("ends a connection already given up", async () => {
        const { hub, reported } = await connectedOnTheClock(
          { socket: ["confirms", "closes"] },
          EXPIRING_IN_AN_HOUR,
        );
        hub.dropSockets();

        await vi.advanceTimersByTimeAsync(3_600_000);

        expect(reported()).toEqual(["reconnecting", "disconnected", "ended"]);
      });

      it("waits out an expiry further off than a browser timer holds", async () => {
        const { reported } = await connectedOnTheClock({}, expiringAfter(3_000_000_000));

        await vi.advanceTimersByTimeAsync(2_999_999_999);
        const before = [...reported()];
        await vi.advanceTimersByTimeAsync(1);

        expect([before, reported()]).toEqual([[], ["ended"]]);
      });
    });

    it.each<[string, number]>([
      ["has passed", -1],
      ["is now", 0],
    ])("is ended, asking the hub nothing, when the expiry %s", async (_name, ms) => {
      const hub = hubOnly();

      const status = await startedWith(expiringAfter(ms));

      await throughout(() =>
        expect([status, hub.requests(), hub.sockets()]).toEqual(["ended", [], []]),
      );
    });

    it.each<[string, HubAnswer]>([
      ["answers it", answeredEndpoint(1)],
      ["refuses it", { status: 401 }],
    ])(
      "is ended when the expiry passes while it subscribes, and the hub then %s",
      async (_name, answer) => {
        const subscription = held();
        const hub = hubOnly({ subscription: subscription.answer });
        startFhircast(() => Promise.resolve(expiringAfter(1_000)));
        await eventually(() => expect(subscriptionsAt(hub)).toHaveLength(1));

        await vi.advanceTimersByTimeAsync(1_000);
        subscription.give(answer);

        await throughout(() =>
          expect([fhircastSnapshot().status, hub.sockets()]).toEqual(["ended", []]),
        );
      },
    );
  });
});

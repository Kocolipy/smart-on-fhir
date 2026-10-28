import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CLIENT_ID,
  endpointOf,
  type FakeHub,
  HUB_TOKEN,
  HUB_URL,
  hubTokenOf,
  type HubAnswer,
  hubOnly,
  type HubOptions,
  REGISTRATION_CLIENT_URI,
  REGISTRATION_ENDPOINT,
  requestsSince,
  REVOCATION_ENDPOINT,
  TOKEN_ENDPOINT,
} from "../fake-hub.testHelpers";
import { connectFhircast, endFhircast } from ".";
import {
  assertionOf,
  eventually,
  held,
  keptState,
  PATIENT_OPEN,
  realSetTimeout,
  RELEASE,
  throughout,
} from "./fhircast.testHelpers";

// `connectFhircast` once connected, on a fake clock: renewal, reconnecting
// after the socket drops, and holding one live subscription at a time.

/** Each `hub.mode` request `hub` was sent at the hub URL in `mode`, in order. */
const modeAt = (hub: FakeHub, mode: "subscribe" | "unsubscribe") =>
  hub.requestsTo(HUB_URL).filter(({ body }) => new URLSearchParams(body).get("hub.mode") === mode);

/** Each subscription `hub` was asked for, in order. */
const subscriptionsAt = (hub: FakeHub) => modeAt(hub, "subscribe");

describe("connectFhircast, staying connected", () => {
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

  /** Connected to a hub answering `options`, on the fake clock, with what it reports recorded. */
  async function connectedOnTheClock(options: HubOptions = {}) {
    const hub = hubOnly(options);
    const reported = vi.fn();
    const events = vi.fn();
    const connecting = connectFhircast(RELEASE, { onConnection: reported, onEvent: events });
    await eventually(() => expect(hub.sockets()).toHaveLength(1));
    await vi.advanceTimersByTimeAsync(0);
    const outcome = await connecting;
    expect(outcome.kind).toBe("connected");
    return { events, hub, outcome, reported };
  }

  /** The event a Patient-open is handed on as. */
  const HANDED_OPEN = {
    event: "Patient-open",
    patientId: "patient-1",
    timestamp: "2026-10-24T02:00:05Z",
  };

  /** A token answer: a Hub Token lasting `seconds`. */
  const lasting = (seconds: number): HubAnswer => ({
    body: { access_token: `hub-${seconds}`, expires_in: seconds, token_type: "Bearer" },
    status: 200,
  });

  describe("renewal", () => {
    it.each<[string, HubOptions, number]>([
      ["80% into an hour's Hub Token", { token: lasting(3600) }, 2_880_000],
      ["60 s before a 200 s Hub Token expires", { token: lasting(200) }, 140_000],
      ["80% into an hour's lease", { leaseSeconds: 3600, token: lasting(7200) }, 2_880_000],
      ["60 s before a 200 s lease expires", { leaseSeconds: 200, token: lasting(3600) }, 140_000],
      ["60 s before a 100 s Hub Token expires", { token: lasting(100) }, 40_000],
      ["60 s before a 100 s lease expires", { leaseSeconds: 100, token: lasting(3600) }, 40_000],
      [
        "5 s into a Hub Token of a minute, which has no time 60 s before it ends",
        { token: lasting(60) },
        5_000,
      ],
      [
        "5 s into a lease of a minute, which has no time 60 s before it ends",
        { leaseSeconds: 60, token: lasting(3600) },
        5_000,
      ],
    ])("starts %s, and not before", async (_name, options, at) => {
      const { hub } = await connectedOnTheClock(options);
      const minted = () => hub.requestsTo(TOKEN_ENDPOINT).length;

      await vi.advanceTimersByTimeAsync(at - 1);
      expect(minted()).toBe(1);
      await vi.advanceTimersByTimeAsync(1);
      await eventually(() => expect([minted(), hub.sockets().length]).toEqual([2, 2]));

      // Renewal signs its assertion the moment it starts.
      const { iat } = assertionOf(hub.requestsTo(TOKEN_ENDPOINT)[1].body).claims;
      expect(iat).toBe((CONNECTED_AT + at) / 1000);
    });

    it.each<[string, unknown]>([
      ["names none", undefined],
      ["is zero", 0],
      ["is negative", -200],
      ["is no number", "200"],
    ])("never starts by a Hub Token lifetime that %s", async (_name, lifetime) => {
      const { hub } = await connectedOnTheClock({
        token: { body: { access_token: HUB_TOKEN, expires_in: lifetime }, status: 200 },
      });

      await vi.advanceTimersByTimeAsync(3_000_000_000);

      await throughout(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(1));
    });

    it("never starts by a lease that is no number", async () => {
      const { hub } = await connectedOnTheClock({
        leaseSeconds: "200" as unknown as number,
        token: { body: { access_token: HUB_TOKEN }, status: 200 },
      });

      await vi.advanceTimersByTimeAsync(3_000_000_000);

      await throughout(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(1));
    });

    it("waits out a lifetime longer than a browser timer holds", async () => {
      const { hub } = await connectedOnTheClock({ token: lasting(10_000_000) });

      await vi.advanceTimersByTimeAsync(2_147_483_646);

      await throughout(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(1));
    });

    /** Moves the clock `ms` on, then lets the hub confirm the socket the page has opened by then. */
    async function renewedAfter(ms: number, hub: FakeHub, sockets: number) {
      await vi.advanceTimersByTimeAsync(ms);
      await eventually(() => expect(hub.sockets()).toHaveLength(sockets));
      await vi.advanceTimersByTimeAsync(0);
    }

    it("mints every renewal's Hub Token with an assertion of a jti of its own", async () => {
      const { hub } = await connectedOnTheClock({ token: lasting(200) });

      await renewedAfter(140_000, hub, 2);
      await renewedAfter(140_000, hub, 3);

      const jtis = hub
        .requestsTo(TOKEN_ENDPOINT)
        .map((request) => assertionOf(request.body).claims.jti);
      expect(new Set(jtis).size).toBe(3);
    });

    it("signs a renewal's fresh five-minute assertion with the key and client it registered", async () => {
      const { hub } = await connectedOnTheClock({ token: lasting(200) });

      await renewedAfter(140_000, hub, 2);

      const { claims, header } = assertionOf(hub.requestsTo(TOKEN_ENDPOINT)[1].body);
      const registered = JSON.parse(hub.requestTo(REGISTRATION_ENDPOINT).body) as {
        jwks: { keys: { kid: string }[] };
      };
      expect([
        header.kid,
        claims.iss,
        (claims.exp as number) - (claims.iat as number),
        hub.requestsTo(REGISTRATION_ENDPOINT).length,
      ]).toEqual([registered.jwks.keys[0].kid, CLIENT_ID, 300, 1]);
    });

    it("subscribes again with the renewed Hub Token", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await renewedAfter(140_000, hub, 2);

      expect(subscriptionsAt(hub).map(({ authorization }) => authorization)).toEqual([
        `Bearer ${HUB_TOKEN}`,
        `Bearer ${HUB_TOKEN}-2`,
      ]);
    });

    it("moves onto the renewed subscription's socket, closing the one before", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await renewedAfter(140_000, hub, 2);

      expect([hub.sockets(), hub.openSockets()]).toEqual([
        [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT.replace(/1$/, "2")],
        1,
      ]);
    });

    it("keeps the renewed subscription in sessionStorage, and only that", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await renewedAfter(140_000, hub, 2);

      expect(keptState()?.subscription).toEqual({
        endpoint: CHANNEL_ENDPOINT.replace(/1$/, "2"),
        hubToken: `${HUB_TOKEN}-2`,
        hubTokenExpiresAt: CONNECTED_AT + 140_000 + 3_600_000,
        leaseExpiresAt: CONNECTED_AT + 140_000 + 200_000,
      });
    });

    it("renews unseen, reporting nothing", async () => {
      const { hub, reported } = await connectedOnTheClock({ leaseSeconds: 200 });

      await renewedAfter(140_000, hub, 2);

      expect(reported.mock.calls).toEqual([]);
    });

    it("hands on events from the renewed socket", async () => {
      const { events, hub } = await connectedOnTheClock({ leaseSeconds: 200 });
      await renewedAfter(140_000, hub, 2);

      hub.notify(PATIENT_OPEN);

      expect(events.mock.calls).toEqual([[HANDED_OPEN]]);
    });

    it("reconnects a renewed connection the hub then drops", async () => {
      const { hub, reported } = await connectedOnTheClock({ leaseSeconds: 200 });
      await renewedAfter(140_000, hub, 2);

      hub.dropSockets();

      expect(reported.mock.calls).toEqual([["reconnecting"]]);
    });

    it("leaves a socket the hub drops mid-renewal to the renewal", async () => {
      const renewal = held();
      const { hub, reported } = await connectedOnTheClock({
        token: [lasting(200), renewal.answer],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(2));

      hub.dropSockets();
      renewal.give(lasting(200));
      await eventually(() => expect(hub.sockets()).toHaveLength(2));
      await vi.advanceTimersByTimeAsync(0);

      expect([reported.mock.calls, hub.openSockets()]).toEqual([[], 1]);
    });

    it.each<[string, HubOptions]>([
      ["the hub refuses its Hub Token", { token: [lasting(200), { status: 400 }] }],
      ["its socket closes unconfirmed", { socket: ["confirms", "closes"], token: lasting(200) }],
    ])("falls back to reconnecting when %s", async (_name, options) => {
      const { hub, reported } = await connectedOnTheClock(options);

      await vi.advanceTimersByTimeAsync(140_000);

      await eventually(() => expect(reported.mock.calls).toEqual([["reconnecting"]]));
      expect(hub.openSockets()).toBe(0);
    });

    it("retries a failed renewal by subscribing anew", async () => {
      const { hub, reported } = await connectedOnTheClock({
        token: [lasting(200), { status: 400 }, lasting(200)],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(reported).toHaveBeenCalledWith("reconnecting"));

      await renewedAfter(1_000, hub, 2);

      expect([reported.mock.calls, hub.sockets()]).toEqual([
        [["reconnecting"], ["connected"]],
        [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT.replace(/1$/, "2")],
      ]);
    });

    it("renews no more once closed", async () => {
      const { hub, outcome } = await connectedOnTheClock({ token: lasting(200) });

      if (outcome.kind === "connected") outcome.close();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(1);
    });

    it("keeps nothing a renewal under way is granted once closed", async () => {
      const renewal = held();
      const { hub, outcome } = await connectedOnTheClock({
        token: [lasting(200), renewal.answer],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(2));

      if (outcome.kind === "connected") outcome.close();
      renewal.give({
        body: { access_token: `${HUB_TOKEN}-renewed`, expires_in: 200 },
        status: 200,
      });
      await vi.advanceTimersByTimeAsync(0);

      expect([
        (keptState()?.subscription as { hubToken: string }).hubToken,
        hub.requestsTo(HUB_URL).length,
      ]).toEqual(["hub-200", 1]);
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

    it("reports connected once the reopened socket is confirmed", async () => {
      const { reported } = await dropped();

      await vi.advanceTimersByTimeAsync(1_000);

      expect(reported.mock.calls).toEqual([["reconnecting"], ["connected"]]);
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

    it("gives up after five retries, reporting disconnected", async () => {
      const { hub, reported } = await dropped({ socket: ["confirms", "closes"] });

      await vi.advanceTimersByTimeAsync(7_200_000);

      expect([hub.sockets().length - 1, reported.mock.calls]).toEqual([
        5,
        [["reconnecting"], ["disconnected"]],
      ]);
    });

    it("counts its retries afresh once a retry has connected", async () => {
      const { hub, reported } = await dropped({
        socket: ["confirms", "closes", "confirms", "closes"],
      });
      await vi.advanceTimersByTimeAsync(3_000);

      hub.dropSockets();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect([hub.sockets().length, reported.mock.calls]).toEqual([
        8,
        [["reconnecting"], ["connected"], ["reconnecting"], ["disconnected"]],
      ]);
    });

    it("subscribes anew when the hub says the endpoint is gone", async () => {
      const { hub } = await dropped({ socket: ["confirms", "denies", "confirms"] });

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(3));
      await vi.advanceTimersByTimeAsync(0);

      expect([hub.sockets(), hub.openSockets()]).toEqual([
        [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT, CHANNEL_ENDPOINT.replace(/1$/, "2")],
        1,
      ]);
    });

    it("subscribes anew with a fresh Hub Token", async () => {
      const { hub } = await dropped({ socket: ["confirms", "denies", "confirms"] });

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(subscriptionsAt(hub)).toHaveLength(2));

      await eventually(() => expect(hub.sockets()).toHaveLength(3));

      expect(subscriptionsAt(hub)[1].authorization).toBe(`Bearer ${HUB_TOKEN}-2`);
    });

    it("reports connected once the new subscription's socket is confirmed", async () => {
      const { hub, reported } = await dropped({ socket: ["confirms", "denies", "confirms"] });

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(3));
      await vi.advanceTimersByTimeAsync(0);

      expect(reported.mock.calls).toEqual([["reconnecting"], ["connected"]]);
    });

    it("takes the next retry when subscribing anew fails", async () => {
      const { hub, reported } = await dropped({
        socket: ["confirms", "denies"],
        token: [lasting(3600), { status: 400 }],
      });

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(2));
      await vi.advanceTimersByTimeAsync(2_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(3));

      expect([hub.sockets().length, reported.mock.calls]).toEqual([2, [["reconnecting"]]]);
    });

    it("retries no more once closed", async () => {
      const { hub, outcome } = await dropped();

      if (outcome.kind === "connected") outcome.close();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect(hub.sockets()).toHaveLength(1);
    });

    it("closes a socket its retry opens once closed", async () => {
      let confirm = () => undefined as void;
      const { hub, outcome, reported } = await dropped({
        hold: [null, new Promise((resolve) => (confirm = resolve))],
      });
      await vi.advanceTimersByTimeAsync(1_000);
      expect(hub.sockets()).toHaveLength(2);

      if (outcome.kind === "connected") outcome.close();
      confirm();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect([hub.openSockets(), hub.sockets().length, reported.mock.calls]).toEqual([
        0,
        2,
        [["reconnecting"]],
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
      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(3));
      await vi.advanceTimersByTimeAsync(0);

      hub.dropSockets();
      await vi.advanceTimersByTimeAsync(1_000);

      expect(hub.sockets().slice(2)).toEqual([
        CHANNEL_ENDPOINT.replace(/1$/, "2"),
        CHANNEL_ENDPOINT.replace(/1$/, "2"),
      ]);
    });

    it("drops the renewal that was due once the socket drops", async () => {
      const { hub } = await dropped({ socket: ["confirms", "closes"], token: lasting(200) });

      await vi.advanceTimersByTimeAsync(200_000);

      expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(1);
    });

    it("reconnects when the socket drops mid-renewal and the renewal then fails", async () => {
      const renewal = held();
      const { hub, reported } = await connectedOnTheClock({
        token: [lasting(200), renewal.answer],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(2));

      hub.dropSockets();
      renewal.give({ status: 400 });

      await eventually(() => expect(reported.mock.calls).toEqual([["reconnecting"]]));
    });

    it("reports no connection won back while retrying a failed renewal fails", async () => {
      const { hub, reported } = await connectedOnTheClock({
        token: [lasting(200), { status: 400 }],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(reported).toHaveBeenCalledWith("reconnecting"));

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(3));
      await vi.advanceTimersByTimeAsync(0);

      expect(reported.mock.calls).toEqual([["reconnecting"]]);
    });
  });
  describe("one live subscription", () => {
    const SECOND_ENDPOINT = CHANNEL_ENDPOINT.replace(/1$/, "2");

    /** Moves the clock `ms` on, then lets the hub confirm the socket the page has opened by then. */
    async function movedOnAfter(ms: number, hub: FakeHub, sockets: number) {
      await vi.advanceTimersByTimeAsync(ms);
      await eventually(() => expect(hub.sockets()).toHaveLength(sockets));
      await vi.advanceTimersByTimeAsync(0);
    }

    /** Each unsubscription the hub was sent: its endpoint, and its Bearer. */
    const unsubscriptions = (hub: FakeHub) =>
      modeAt(hub, "unsubscribe").map(({ authorization, body }) => [
        new URLSearchParams(body).get("hub.channel.endpoint"),
        authorization,
      ]);

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

    /** Each Hub Token the hub was asked to revoke. */
    const revoked = (hub: FakeHub) =>
      hub.requestsTo(REVOCATION_ENDPOINT).map(({ body }) => new URLSearchParams(body).get("token"));

    it("unsubscribes the endpoint a renewal moved off, with the Hub Token it was made with", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await movedOnAfter(140_000, hub, 2);

      await eventually(() =>
        expect(unsubscriptions(hub)).toEqual([[CHANNEL_ENDPOINT, `Bearer ${HUB_TOKEN}`]]),
      );
    });

    it("then revokes that Hub Token, and nothing else", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });
      const since = requestsSince(hub);

      await movedOnAfter(140_000, hub, 2);

      await eventually(() =>
        expect([since().map(({ method, url }) => `${method} ${url}`), revoked(hub)]).toEqual([
          [
            `POST ${TOKEN_ENDPOINT}`,
            `POST ${HUB_URL}`,
            `POST ${HUB_URL}`,
            `POST ${REVOCATION_ENDPOINT}`,
          ],
          [HUB_TOKEN],
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

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(3));
      await vi.advanceTimersByTimeAsync(0);

      await eventually(() =>
        expect([unsubscriptions(hub), revoked(hub)]).toEqual([
          [[CHANNEL_ENDPOINT, `Bearer ${HUB_TOKEN}`]],
          [HUB_TOKEN],
        ]),
      );
    });

    it("cancels nothing when it reopens the same subscription after a drop", async () => {
      const { hub, reported } = await connectedOnTheClock();
      hub.dropSockets();

      await vi.advanceTimersByTimeAsync(1_000);
      expect(reported.mock.calls).toEqual([["reconnecting"], ["connected"]]);

      await throughout(() => expect([unsubscriptions(hub), revoked(hub)]).toEqual([[], []]));
    });

    it("revokes the Hub Token, but unsubscribes nothing, when the renewal is answered the same endpoint", async () => {
      const { hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        subscription: {
          body: { "hub.channel.endpoint": CHANNEL_ENDPOINT },
          status: 202,
        },
      });

      await movedOnAfter(140_000, hub, 2);

      await eventually(() => expect(revoked(hub)).toEqual([HUB_TOKEN]));
      expect(unsubscriptions(hub)).toEqual([]);
    });

    it("only unsubscribes for a hub that names no revocation endpoint", async () => {
      const { hub } = await connectedOnTheClock({
        fhircastConfiguration: {
          body: {
            registration_endpoint: REGISTRATION_ENDPOINT,
            token_endpoint: TOKEN_ENDPOINT,
          },
          status: 200,
        },
        leaseSeconds: 200,
      });

      const fetchedBefore = vi.mocked(fetch).mock.calls.length;

      await movedOnAfter(140_000, hub, 2);

      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));
      // The renewal's Hub Token and subscription, then the unsubscription, and nothing anywhere else.
      await throughout(() =>
        expect(
          vi
            .mocked(fetch)
            .mock.calls.slice(fetchedBefore)
            .map(([url]) => String(url)),
        ).toEqual([TOKEN_ENDPOINT, HUB_URL, HUB_URL]),
      );
    });

    it("cancels both the subscription last confirmed and the one still opening when the session ends", async () => {
      let confirm = () => undefined as void;
      const { hub } = await connectedOnTheClock({
        hold: [null, new Promise((resolve) => (confirm = resolve))],
        leaseSeconds: 200,
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(2));

      await endFhircast();
      confirm();

      expect([unsubscriptions(hub), revoked(hub)]).toEqual([
        [
          [CHANNEL_ENDPOINT, `Bearer ${HUB_TOKEN}`],
          [SECOND_ENDPOINT, `Bearer ${HUB_TOKEN}-2`],
        ],
        [HUB_TOKEN, `${HUB_TOKEN}-2`],
      ]);
    });

    it("cancels a failed renewal's subscription and Hub Token, as well as the live one's, each once, when the session ends", async () => {
      const { hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        socket: ["confirms", "denies", "confirms"],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(2));
      await movedOnAfter(1_000, hub, 3);
      await eventually(() => expect(revoked(hub)).toHaveLength(1));

      await endFhircast();

      expect([unsubscriptions(hub), revoked(hub)]).toEqual([
        [1, 3, 2].map((n) => [endpointOf(n), `Bearer ${hubTokenOf(n)}`]),
        [1, 3, 2].map(hubTokenOf),
      ]);
    });

    it("cancels every subscription and Hub Token a failed renewal and each failed retry was granted when the session ends", async () => {
      const { hub, reported } = await connectedOnTheClock({
        leaseSeconds: 200,
        socket: ["confirms", "closes"],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      while (!reported.mock.calls.some(([state]) => state === "disconnected")) {
        await vi.advanceTimersByTimeAsync(1_000);
        await new Promise((resolve) => realSetTimeout(resolve, 5));
      }

      await endFhircast();

      const attempts = [1, 2, 3, 4, 5, 6, 7];
      expect([unsubscriptions(hub), revoked(hub)]).toEqual([
        attempts.map((n) => [endpointOf(n), `Bearer ${hubTokenOf(n)}`]),
        attempts.map(hubTokenOf),
      ]);
    });

    it.each<[string, HubAnswer]>([
      ["is never reached", "unreachable"],
      ["fails", { status: 500, text: "<html>down</html>" }],
    ])(
      "still revokes, and stays connected, when the unsubscription %s",
      async (_name, unsubscription) => {
        const { events, hub, reported } = await connectedOnTheClock({
          leaseSeconds: 200,
          revocation: unsubscription,
          unsubscription,
        });
        await movedOnAfter(140_000, hub, 2);
        await eventually(() => expect(revoked(hub)).toEqual([HUB_TOKEN]));

        hub.notify(PATIENT_OPEN);

        expect([events.mock.calls, reported.mock.calls, hub.openSockets()]).toEqual([
          [[HANDED_OPEN]],
          [],
          1,
        ]);
      },
    );

    it("gives up a step the hub leaves unanswered after 3 seconds, and stays connected", async () => {
      const never = new Promise<HubAnswer>(() => undefined);
      const { events, hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        revocation: never,
        unsubscription: never,
      });
      await movedOnAfter(140_000, hub, 2);
      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));

      await vi.advanceTimersByTimeAsync(2_999);
      const revokedBefore = revoked(hub).length;
      await vi.advanceTimersByTimeAsync(1);
      await eventually(() => expect(revoked(hub)).toHaveLength(1));
      hub.notify(PATIENT_OPEN);
      const handed = [...events.mock.calls];
      await endedOnTheClock();

      expect([revokedBefore, handed]).toEqual([0, [[HANDED_OPEN]]]);
    });

    it("renews again on time while the hub leaves the last cancel unanswered", async () => {
      const never = new Promise<HubAnswer>(() => undefined);
      const { hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        unsubscription: never,
      });
      await movedOnAfter(140_000, hub, 2);

      await movedOnAfter(140_000, hub, 3);
      await endedOnTheClock();

      expect(hub.sockets()).toEqual([
        CHANNEL_ENDPOINT,
        SECOND_ENDPOINT,
        CHANNEL_ENDPOINT.replace(/1$/, "3"),
      ]);
    });

    it("deletes the registration at the end of the session only once the cancel under way is done", async () => {
      const unsubscription = held();
      const { hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        unsubscription: unsubscription.answer,
      });
      await movedOnAfter(140_000, hub, 2);
      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));

      const ending = endFhircast();
      await throughout(() => expect(hub.requestsTo(REGISTRATION_CLIENT_URI)).toEqual([]));
      unsubscription.give({ body: {}, status: 202 });
      await ending;

      expect([
        unsubscriptions(hub),
        revoked(hub),
        hub.requestsTo(REGISTRATION_CLIENT_URI).length,
      ]).toEqual([
        [
          [CHANNEL_ENDPOINT, `Bearer ${HUB_TOKEN}`],
          [SECOND_ENDPOINT, `Bearer ${HUB_TOKEN}-2`],
        ],
        [HUB_TOKEN, `${HUB_TOKEN}-2`],
        1,
      ]);
    });
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  fakeHub,
  HUB_URL,
  hubOnly,
  type HubOptions,
} from "../fake-hub.testHelpers";
import { fakeBackend } from "../fake-backend.testHelpers";
import { connectFhircast } from ".";
import { HubFailure, subscribe } from "./protocol";
import { RELEASE, routes, SMART_ACCESS_TOKEN } from "./fhircast.testHelpers";

// The hub protocol, through `connectFhircast`, step by step: the subscription
// with the SMART access token, the socket at the endpoint it answers, and a
// hub out of reach. The event notifications on the socket are
// `notifications.test.ts`'s, and what the tab keeps `storage.test.ts`'s.

describe("connectFhircast", () => {
  it("connects once the socket confirms the subscription", async () => {
    hubOnly();

    const outcome = await connectFhircast(RELEASE);

    expect(outcome.kind).toBe("connected");
  });

  it("asks the hub for the subscription alone, then opens its socket", async () => {
    const hub = hubOnly();

    await connectFhircast(RELEASE);

    expect([routes(hub.requests()), hub.sockets()]).toEqual([
      [`POST ${HUB_URL}`],
      [CHANNEL_ENDPOINT],
    ]);
  });

  it.each([
    "http://10.0.0.1/hub",
    "http://localhost:8103/fhircast/STU3",
    "ws://127.0.0.1:8103/fhircast/STU3",
    "http://user@127.0.0.1:8103/fhircast/STU3",
    "http://:secret@127.0.0.1:8103/fhircast/STU3",
    "not a url",
  ])(
    "gives FHIRcast unavailable for a hub URL off http(s) on 127.0.0.1 (%s), asking it nothing",
    async (hubUrl) => {
      const hub = hubOnly();

      const outcome = await connectFhircast({ ...RELEASE, hubUrl });

      expect([outcome.kind, hub.requests()]).toEqual(["unavailable", []]);
    },
  );

  it("subscribes at an https hub URL on 127.0.0.1", async () => {
    const hubUrl = "https://127.0.0.1:9443/fhircast/STU3";
    const hub = hubOnly();

    const outcome = await connectFhircast({ ...RELEASE, hubUrl });

    expect([outcome.kind, routes(hub.requests())]).toEqual(["connected", [`POST ${hubUrl}`]]);
  });

  describe("the subscription", () => {
    it("subscribes to Patient-open and Patient-close on the topic over a websocket", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      expect(Object.fromEntries(new URLSearchParams(hub.requestTo(HUB_URL).body))).toEqual({
        "hub.channel.type": "websocket",
        "hub.events": "Patient-open,Patient-close",
        "hub.mode": "subscribe",
        "hub.topic": "topic-1",
      });
    });

    it("subscribes with the SMART access token as Bearer, as a form", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      expect(hub.requestTo(HUB_URL)).toMatchObject({
        authorization: `Bearer ${SMART_ACCESS_TOKEN}`,
        contentType: "application/x-www-form-urlencoded",
        method: "POST",
      });
    });

    it("opens the socket at the channel endpoint the hub answered", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      expect(hub.sockets()).toEqual([CHANNEL_ENDPOINT]);
    });

    it.each([
      // A fixture for the refusal: an endpoint off loopback is never connected to.
      // nosemgrep: javascript.lang.security.detect-insecure-websocket.detect-insecure-websocket
      "ws://192.168.1.20:8103/ws/fhircast/endpoint-1",
      "wss://hub.example.org/ws/fhircast/endpoint-1",
      "http://127.0.0.1:8103/ws/fhircast/endpoint-1",
      // A fixture for the refusal: an endpoint carrying credentials is never connected to.
      // nosemgrep: javascript.lang.security.detect-insecure-websocket.detect-insecure-websocket
      "ws://user@127.0.0.1:8103/ws/fhircast/endpoint-1",
      "not a url",
    ])(
      "refuses a channel endpoint off ws(s) on 127.0.0.1 (%s), opening nothing",
      async (endpoint) => {
        const hub = hubOnly({
          subscription: { body: { "hub.channel.endpoint": endpoint }, status: 202 },
        });

        const outcome = await connectFhircast(RELEASE);

        expect([outcome.kind, hub.sockets()]).toEqual(["unavailable", []]);
      },
    );

    it("accepts a wss endpoint on 127.0.0.1", async () => {
      hubOnly({
        subscription: {
          body: { "hub.channel.endpoint": "wss://127.0.0.1:9443/ws/fhircast/endpoint-1" },
          status: 202,
        },
      });

      expect((await connectFhircast(RELEASE)).kind).toBe("connected");
    });

    it.each<[string, HubOptions["subscription"]]>([
      ["refuses the subscription", { body: { error: "x" }, status: 403 }],
      [
        "answers 200 rather than 202",
        { body: { "hub.channel.endpoint": CHANNEL_ENDPOINT }, status: 200 },
      ],
      ["answers a subscription that is no JSON", { status: 202, text: "<html>hub</html>" }],
      ["answers JSON null", { body: null, status: 202 }],
      ["names no channel endpoint", { body: {}, status: 202 }],
      ["names an empty channel endpoint", { body: { "hub.channel.endpoint": "" }, status: 202 }],
      [
        "names a channel endpoint that is no string",
        { body: { "hub.channel.endpoint": 7 }, status: 202 },
      ],
      [
        "names a channel endpoint in a list, no string though it reads as one",
        { body: { "hub.channel.endpoint": [CHANNEL_ENDPOINT] }, status: 202 },
      ],
    ])("gives FHIRcast unavailable, opening nothing, when the hub %s", async (_name, answer) => {
      const hub = hubOnly({ subscription: answer });

      const outcome = await connectFhircast(RELEASE);

      expect([outcome.kind, hub.sockets()]).toEqual(["unavailable", []]);
    });

    it("gives FHIRcast unavailable when the socket closes before confirming", async () => {
      hubOnly({ socket: "closes" });

      expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    });

    it("reports no connection lost when it never had one", async () => {
      hubOnly({ socket: "closes" });
      const lost = vi.fn();

      await connectFhircast(RELEASE, { onConnection: lost });

      expect(lost).not.toHaveBeenCalled();
    });

    it("takes nothing but the hub's subscribe confirmation for one", async () => {
      hubOnly({ socket: "says-otherwise" });

      expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    });

    describe("on the hub's clock", () => {
      afterEach(() => {
        vi.useRealTimers();
      });

      it("gives FHIRcast unavailable when the hub stays silent for ten seconds", async () => {
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
        const hub = hubOnly({ socket: "silent" });
        const outcome = connectFhircast(RELEASE);
        await vi.waitFor(() => expect(hub.sockets()).toHaveLength(1));

        await vi.advanceTimersByTimeAsync(10_000);

        expect([(await outcome).kind, hub.openSockets()]).toEqual(["unavailable", 0]);
      });

      it("keeps a confirmed socket open past those ten seconds", async () => {
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
        const hub = hubOnly();
        const lost = vi.fn();
        const outcome = connectFhircast(RELEASE, { onConnection: lost });
        await vi.waitFor(() => expect(hub.sockets()).toHaveLength(1));
        await vi.advanceTimersByTimeAsync(0);
        expect((await outcome).kind).toBe("connected");

        await vi.advanceTimersByTimeAsync(10_000);

        expect([hub.openSockets(), lost.mock.calls.length]).toEqual([1, 0]);
      });
    });

    it("closes its socket when asked, reporting nothing lost", async () => {
      const hub = hubOnly();
      const lost = vi.fn();
      const outcome = await connectFhircast(RELEASE, { onConnection: lost });

      if (outcome.kind === "connected") outcome.close();

      expect([hub.openSockets(), lost.mock.calls]).toEqual([0, []]);
    });
  });

  describe("a hub the browser cannot reach", () => {
    it("is blocked by the browser when Local Network Access was refused", async () => {
      hubOnly({ localNetworkAccess: "denied", subscription: "unreachable" });

      expect((await connectFhircast(RELEASE)).kind).toBe("blocked");
    });

    it.each(["granted", "prompt"] as const)(
      "is unavailable when Local Network Access is %s",
      async (state) => {
        hubOnly({ localNetworkAccess: state, subscription: "unreachable" });

        expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
      },
    );

    it.each<[string, HubOptions]>([
      ["refuses the subscription", { subscription: { body: {}, status: 401 } }],
      ["answers a subscription that is no JSON", { subscription: { status: 202, text: "x" } }],
      ["names no channel endpoint", { subscription: { body: {}, status: 202 } }],
      [
        "names a channel endpoint off 127.0.0.1",
        // A fixture for the refusal: an endpoint off loopback is never connected to.
        // nosemgrep: javascript.lang.security.detect-insecure-websocket.detect-insecure-websocket
        { subscription: { body: { "hub.channel.endpoint": "ws://10.0.0.1/ws" }, status: 202 } },
      ],
    ])(
      "is unavailable, not blocked, when a reachable hub %s, Local Network Access refused or not",
      async (_name, options) => {
        hubOnly({ ...options, localNetworkAccess: "denied" });

        expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
      },
    );

    it("never sends a cookie to the hub, nor follows a redirect", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      expect(
        new Set(hub.requests().map(({ credentials, redirect }) => `${credentials} ${redirect}`)),
      ).toEqual(new Set(["omit error"]));
    });

    it("is unavailable in a browser that has no such permission", async () => {
      fakeBackend();
      fakeHub({ subscription: "unreachable" });
      vi.stubGlobal("navigator", { ...navigator, permissions: undefined });

      expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    });
  });
});

// The hub protocol's own requests, read without a session: each made of the
// fake hub directly, so nothing here needs the session's FHIRcast reset.

describe("the hub protocol", () => {
  const HUB = { hubTopic: RELEASE.hubTopic, hubUrl: HUB_URL };

  it("subscribes, answering the channel endpoint", async () => {
    hubOnly();

    expect(await subscribe(HUB, SMART_ACCESS_TOKEN)).toBe(CHANNEL_ENDPOINT);
  });

  it("refuses a channel endpoint off loopback", async () => {
    hubOnly({
      subscription: { body: { "hub.channel.endpoint": "ws://localhost:8103/ws" }, status: 202 },
    });

    await expect(subscribe(HUB, SMART_ACCESS_TOKEN)).rejects.toBeInstanceOf(HubFailure);
  });
});

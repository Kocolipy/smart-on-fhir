import { describe, expect, it, vi } from "vitest";

import { type FakeHub, hubOnly, requestsSince } from "../fake-hub.testHelpers";
import { FHIRCAST_STORAGE_KEYS } from ".";
import { keptState, routes } from "./fhircast.testHelpers";
import {
  AT_HUB,
  connectedBefore,
  reloaded,
  SECOND_ENDPOINT,
  startedOn,
} from "./reload.testHelpers";

// A reload over stored state that does not decode: taken for nothing kept,
// cleared, and the tab connected from scratch.

describe("a reload", () => {
  describe("stored state that does not decode", () => {
    /** What the page before kept, as `change` leaves it. */
    function keptAs(change: (kept: Record<string, Record<string, unknown>>) => void) {
      const kept = keptState() as Record<string, Record<string, unknown>>;
      change(kept);
      window.sessionStorage.setItem(FHIRCAST_STORAGE_KEYS.connection, JSON.stringify(kept));
    }

    it.each([
      ["no JSON", "{"],
      ["JSON null", "null"],
      ["a JSON list", "[]"],
      ["an empty object", "{}"],
    ])("is taken for none when it is %s, and cleared", async (_name, kept) => {
      const hub = hubOnly({ subscription: { status: 401 } });
      window.sessionStorage.setItem(FHIRCAST_STORAGE_KEYS.connection, kept);
      const since = requestsSince(hub);

      await startedOn(await reloaded(), "unavailable");

      expect([routes(since()), keptState()]).toEqual([[AT_HUB], null]);
    });

    it.each<[string, (kept: Record<string, Record<string, unknown>>) => void]>([
      ["no hubTopic", (kept) => delete kept.hubTopic],
      ["a hubTopic that is no string", (kept) => (kept.hubTopic = 7 as never)],
      ["no hubUrl", (kept) => delete kept.hubUrl],
      ["no subscription", (kept) => delete kept.subscription],
      ["a subscription that is no object", (kept) => (kept.subscription = 7 as never)],
      ["a subscription with no endpoint", (kept) => delete kept.subscription.endpoint],
      ["an endpoint that is no string", (kept) => (kept.subscription.endpoint = 7)],
      ["a subscription with no lease expiry", (kept) => delete kept.subscription.leaseExpiresAt],
      ["a lease expiry that is no instant", (kept) => (kept.subscription.leaseExpiresAt = "soon")],
    ])("is taken for none when it has %s: the tab subscribes anew", async (_name, change) => {
      const hub = await connectedBefore();
      keptAs(change);
      const since = requestsSince(hub);

      await startedOn(await reloaded());

      expect([routes(since()), hub.sockets().slice(1)]).toEqual([[AT_HUB], [SECOND_ENDPOINT]]);
    });

    /** Every URL the page sent a request to, or opened a socket at, off the hub's own. */
    const offTheHub = (hub: FakeHub) => [
      ...vi
        .mocked(fetch)
        .mock.calls.map(([url]) => String(url))
        .filter((url) => !url.startsWith("http://127.0.0.1:8103/")),
      ...hub.sockets().filter((url) => !url.startsWith("ws://127.0.0.1:8103/")),
    ];

    it.each<[string, (kept: Record<string, Record<string, unknown>>) => void]>([
      ["hubUrl", (kept) => (kept.hubUrl = "http://10.0.0.1/fhircast/STU3" as never)],
      [
        "subscription's endpoint",
        // A fixture for the refusal: an endpoint off loopback is never connected to.
        // nosemgrep: javascript.lang.security.detect-insecure-websocket.detect-insecure-websocket
        (kept) => (kept.subscription.endpoint = "ws://10.0.0.1/ws/fhircast/endpoint-1"),
      ],
      [
        "subscription's endpoint, as http",
        (kept) => (kept.subscription.endpoint = "http://127.0.0.1:8103/ws/fhircast/endpoint-1"),
      ],
    ])(
      "is taken for none when its %s is off 127.0.0.1, nothing sent there",
      async (_name, change) => {
        const hub = await connectedBefore();
        keptAs(change);
        const since = requestsSince(hub);
        const page = await reloaded();

        await startedOn(page);
        await page.endFhircast();

        expect([routes(since())[0], offTheHub(hub)]).toEqual([AT_HUB, []]);
      },
    );

    it("is torn down nowhere when the session ends, and cleared", async () => {
      const hub = await connectedBefore();
      keptAs((kept) => delete kept.hubTopic);
      const since = requestsSince(hub);

      await (await reloaded()).endFhircast();

      expect([since(), keptState()]).toEqual([[], null]);
    });
  });
});

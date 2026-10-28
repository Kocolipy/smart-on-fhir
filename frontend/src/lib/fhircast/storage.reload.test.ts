import { describe, expect, it, vi } from "vitest";

import { type FakeHub, hubOnly, requestsSince } from "../fake-hub.testHelpers";
import { FHIRCAST_STORAGE_KEYS } from ".";
import { keptState, routes } from "./fhircast.testHelpers";
import { connectedBefore, DISCOVER, REGISTER, reloaded, startedOn } from "./reload.testHelpers";

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
      const hub = hubOnly({ registration: { status: 401 } });
      window.sessionStorage.setItem(FHIRCAST_STORAGE_KEYS.connection, kept);
      const since = requestsSince(hub);

      await startedOn(await reloaded(), "unavailable");

      expect([routes(since()), keptState()]).toEqual([[DISCOVER, REGISTER], null]);
    });

    it.each<[string, (kept: Record<string, Record<string, unknown>>) => void]>([
      ["no clientId", (kept) => delete kept.clientId],
      ["a clientId that is no string", (kept) => (kept.clientId = 7 as never)],
      ["no hubTopic", (kept) => delete kept.hubTopic],
      ["no hubUrl", (kept) => delete kept.hubUrl],
      ["no keyId", (kept) => delete kept.keyId],
      ["no tokenEndpoint", (kept) => delete kept.tokenEndpoint],
      ["no revocationEndpoint", (kept) => delete kept.revocationEndpoint],
      ["a revocationEndpoint that is no string", (kept) => (kept.revocationEndpoint = 7 as never)],
      ["no registration", (kept) => delete kept.registration],
      ["a registration with no token", (kept) => delete kept.registration.token],
      ["a registration with no uri", (kept) => delete kept.registration.uri],
      ["no subscription", (kept) => delete kept.subscription],
      ["a subscription that is no object", (kept) => (kept.subscription = 7 as never)],
      ["a subscription with no endpoint", (kept) => delete kept.subscription.endpoint],
      ["a subscription with no Hub Token", (kept) => delete kept.subscription.hubToken],
      [
        "a subscription with no Hub Token expiry",
        (kept) => delete kept.subscription.hubTokenExpiresAt,
      ],
      [
        "a Hub Token expiry that is no instant",
        (kept) => (kept.subscription.hubTokenExpiresAt = "soon"),
      ],
      ["a subscription with no lease expiry", (kept) => delete kept.subscription.leaseExpiresAt],
      ["a lease expiry that is no instant", (kept) => (kept.subscription.leaseExpiresAt = "soon")],
      ["no signingJwk", (kept) => delete kept.signingJwk],
      ...(["crv", "d", "kty", "x", "y"] as const).map(
        (member): [string, (kept: Record<string, Record<string, unknown>>) => void] => [
          `a signingJwk with no ${member}`,
          (kept) => delete kept.signingJwk[member],
        ],
      ),
      ["a signingJwk on another curve", (kept) => (kept.signingJwk.crv = "P-256")],
      ["a signingJwk whose d is not its key's", (kept) => (kept.signingJwk.d = "AAAA")],
    ])("is taken for none when it has %s: the tab registers anew", async (_name, change) => {
      const hub = await connectedBefore();
      keptAs(change);
      const since = requestsSince(hub);

      await startedOn(await reloaded());

      expect(routes(since()).slice(0, 2)).toEqual([DISCOVER, REGISTER]);
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
      ["tokenEndpoint", (kept) => (kept.tokenEndpoint = "http://10.0.0.1/dcr/token" as never)],
      [
        "revocationEndpoint",
        (kept) => (kept.revocationEndpoint = "http://10.0.0.1/dcr/revoke" as never),
      ],
      [
        "registration's uri",
        (kept) => (kept.registration.uri = "http://10.0.0.1/dcr/register/client-1"),
      ],
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

        expect([routes(since()).slice(0, 2), offTheHub(hub)]).toEqual([[DISCOVER, REGISTER], []]);
      },
    );

    it("is torn down nowhere when the session ends, and cleared", async () => {
      const hub = await connectedBefore();
      keptAs((kept) => delete kept.clientId);
      const since = requestsSince(hub);

      await (await reloaded()).endFhircast();

      expect([since(), keptState()]).toEqual([[], null]);
    });
  });
});

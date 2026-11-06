import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { CHANNEL_ENDPOINT, HUB_URL, hubOnly } from "../fake-hub.testHelpers";
import { clearFhircastStorage, connectFhircast, FHIRCAST_STORAGE_KEYS } from ".";
import { keptState, RELEASE, SMART_ACCESS_TOKEN } from "./fhircast.testHelpers";

// `connectFhircast` and `clearFhircastStorage`: what the tab keeps of a
// connection in `sessionStorage`, and forgetting it.

/** The keys an earlier version of the module kept its connection under, one each. */
const RETIRED_KEYS = [
  "fhircast-client-id",
  "fhircast-hub-token",
  "fhircast-private-jwk",
  "fhircast-smart-access-token",
];

describe("connectFhircast", () => {
  describe("what the tab keeps", () => {
    it("never keeps the SMART access token", async () => {
      hubOnly();

      await connectFhircast(RELEASE);

      expect(
        Object.keys(window.sessionStorage).filter((key) =>
          window.sessionStorage.getItem(key)?.includes(SMART_ACCESS_TOKEN),
        ),
      ).toEqual([]);
    });

    describe("on the clock", () => {
      const CONNECTED_AT = Date.UTC(2026, 9, 24, 2, 0, 0);

      beforeEach(() => {
        vi.useFakeTimers({ toFake: ["Date"] });
        vi.setSystemTime(CONNECTED_AT);
      });

      afterEach(() => {
        vi.useRealTimers();
      });

      it("keeps its hub, and the subscription with when its lease runs out, and nothing else", async () => {
        hubOnly({ leaseSeconds: 600 });

        await connectFhircast(RELEASE);

        expect(keptState()).toEqual({
          hubTopic: "topic-1",
          hubUrl: HUB_URL,
          subscription: { endpoint: CHANNEL_ENDPOINT, leaseExpiresAt: CONNECTED_AT + 600_000 },
        });
      });

      it("keeps when the SMART access token expires, on Date.now()'s clock", async () => {
        hubOnly();

        await connectFhircast(RELEASE);

        // 2100-01-01T00:00:00Z, the release's expiry.
        expect(window.sessionStorage.getItem(FHIRCAST_STORAGE_KEYS.tokenExpiry)).toBe(
          "4102444800000",
        );
      });

      it("keeps no lease expiry the hub did not state", async () => {
        hubOnly();

        await connectFhircast(RELEASE);

        expect(keptState()).toMatchObject({ subscription: { leaseExpiresAt: null } });
      });
    });

    it.each([
      ["refuses the subscription", { subscription: { status: 401 } }],
      ["never confirms its socket", { socket: "closes" as const }],
    ])("keeps nothing when the hub %s", async (_name, options) => {
      hubOnly(options);

      await connectFhircast(RELEASE);

      expect(keptState()).toBeNull();
    });

    it("forgets it when cleared, and what an earlier version kept under keys of its own", async () => {
      hubOnly();
      await connectFhircast(RELEASE);
      for (const key of RETIRED_KEYS) window.sessionStorage.setItem(key, "kept");

      clearFhircastStorage();

      expect(
        [...Object.values(FHIRCAST_STORAGE_KEYS), ...RETIRED_KEYS].map((key) =>
          window.sessionStorage.getItem(key),
        ),
      ).toEqual([null, null, null, null, null, null]);
    });
  });
});

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  HUB_AUDIENCE,
  HUB_TOKEN,
  HUB_URL,
  hubOnly,
} from "../fake-hub.testHelpers";
import { clearFhircastStorage, FHIRCAST_STORAGE_KEYS } from ".";
import { keptState, SMART_ACCESS_TOKEN, startedWith } from "./fhircast.testHelpers";

// The session's FHIRcast and `clearFhircastStorage`: what the tab keeps of a
// connection in `sessionStorage`, and forgetting it.

describe("the session's FHIRcast connection", () => {
  describe("what the tab keeps", () => {
    it("never keeps the SMART access token", async () => {
      hubOnly();

      await startedWith();

      expect(
        Object.keys(window.sessionStorage).filter((key) =>
          window.sessionStorage.getItem(key)?.includes(SMART_ACCESS_TOKEN),
        ),
      ).toEqual([]);
    });

    it("never keeps the hub token, in the tab or in the browser profile", async () => {
      const hub = hubOnly();

      await startedWith();

      expect([
        Object.keys(window.sessionStorage).filter((key) =>
          window.sessionStorage.getItem(key)?.includes(HUB_TOKEN),
        ),
        JSON.stringify([
          ...(hub.profile.store("fhircast-clients", "clients")?.values() ?? []),
        ]).includes(HUB_TOKEN),
      ]).toEqual([[], false]);
    });

    it("keeps the dynamic client in the browser profile alone, under the hub's audience", async () => {
      const hub = hubOnly();

      await startedWith();

      expect([
        [...(hub.profile.store("fhircast-clients", "clients")?.keys() ?? [])],
        Object.values({ ...window.sessionStorage }).some((kept) =>
          String(kept).includes("client-1"),
        ),
      ]).toEqual([[HUB_AUDIENCE], false]);
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

      it("keeps its hub, and the subscription with when its lease and its hub token run out, and nothing else", async () => {
        hubOnly({ hubTokenSeconds: 900, leaseSeconds: 600 });

        await startedWith();

        expect(keptState()).toEqual({
          hubTopic: "topic-1",
          hubUrl: HUB_URL,
          subscription: {
            endpoint: CHANNEL_ENDPOINT,
            hubTokenExpiresAt: CONNECTED_AT + 900_000,
            leaseExpiresAt: CONNECTED_AT + 600_000,
          },
        });
      });

      it("keeps no lease expiry, nor hub token expiry, the hub did not state", async () => {
        hubOnly({ hubTokenSeconds: null });

        await startedWith();

        expect(keptState()).toMatchObject({
          subscription: { hubTokenExpiresAt: null, leaseExpiresAt: null },
        });
      });
    });

    it.each([
      ["refuses the subscription", { subscription: { status: 401 } }],
      ["never confirms its socket", { socket: "closes" as const }],
    ])("keeps nothing when the hub %s", async (_name, options) => {
      hubOnly(options);

      await startedWith();

      expect(keptState()).toBeNull();
    });

    it("forgets it when cleared", async () => {
      hubOnly();
      await startedWith();

      clearFhircastStorage();

      expect(
        Object.values(FHIRCAST_STORAGE_KEYS).map((key) => window.sessionStorage.getItem(key)),
      ).toEqual([null]);
    });
  });
});

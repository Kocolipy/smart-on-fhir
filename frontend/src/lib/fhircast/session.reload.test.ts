import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { CHANNEL_ENDPOINT, requestsSince } from "../fake-hub.testHelpers";
import { FHIRCAST_STORAGE_KEYS, type FhircastRelease } from ".";
import { eventually, keptState, RELEASE, routes, throughout } from "./fhircast.testHelpers";
import { connectedBefore, reloaded, SECOND_ENDPOINT, startedOn } from "./reload.testHelpers";

// A reload, as connecting a release takes it: a stored subscription for
// another hub or topic, given up for a connection from scratch; and a reload
// the backend releases nothing to, ended once the expiry the tab kept of the
// SMART access token has passed.

describe("a reload", () => {
  describe("a stored subscription for another hub or topic", () => {
    const ELSEWHERE = "http://127.0.0.1:8104/fhircast/STU3";

    it.each<[string, FhircastRelease]>([
      ["another topic", { ...RELEASE, hubTopic: "topic-2" }],
      ["another hub", { ...RELEASE, hubUrl: ELSEWHERE }],
    ])(
      "is forgotten, the tab subscribing anew at the release's hub alone, for %s",
      async (_name, release) => {
        const hub = await connectedBefore();
        const since = requestsSince(hub);

        await startedOn(await reloaded(), "connected", release);

        expect(routes(since())).toEqual([`POST ${release.hubUrl}`]);
      },
    );

    it("is never resumed against: its endpoint is not reopened", async () => {
      const hub = await connectedBefore();

      await startedOn(await reloaded(), "connected", { ...RELEASE, hubTopic: "topic-2" });

      expect(hub.sockets()).toEqual([CHANNEL_ENDPOINT, SECOND_ENDPOINT]);
    });

    it("is replaced by the new subscription, kept for the new topic", async () => {
      await connectedBefore();

      await startedOn(await reloaded(), "connected", { ...RELEASE, hubTopic: "topic-2" });

      expect(keptState()).toMatchObject({
        hubTopic: "topic-2",
        subscription: { endpoint: SECOND_ENDPOINT },
      });
    });

    it("is forgotten at once, though the new subscription fails", async () => {
      const hub = await connectedBefore({
        subscription: [
          { body: { "hub.channel.endpoint": CHANNEL_ENDPOINT }, status: 202 },
          { status: 403 },
        ],
      });
      const since = requestsSince(hub);

      await startedOn(await reloaded(), "unavailable", { ...RELEASE, hubTopic: "topic-2" });

      expect([routes(since()).length, keptState()]).toEqual([1, null]);
    });
  });

  describe("when the backend releases nothing", () => {
    const CONNECTED_AT = Date.UTC(2026, 9, 24, 2, 0, 0);
    /** A release whose SMART access token expires an hour after the clock starts. */
    const EXPIRES_AT = Date.UTC(2026, 9, 24, 3, 0, 0);
    const EXPIRING = { ...RELEASE, expiresAt: "2026-10-24T03:00:00Z" };

    beforeEach(() => {
      vi.useFakeTimers({ toFake: ["Date"] });
      vi.setSystemTime(CONNECTED_AT);
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    /** The page reloaded at `at`, its release source answering nothing, once it says `status`. */
    async function reloadedReleasingNothingAt(at: number, status: string) {
      vi.setSystemTime(at);
      const page = await reloaded();
      page.startFhircast(() => Promise.resolve(null));
      await eventually(() => expect(page.fhircastSnapshot().status).toBe(status));
    }

    it("shows ended, asking the hub nothing, once the expiry the page before was released has passed", async () => {
      const hub = await connectedBefore({}, EXPIRING);
      const since = requestsSince(hub);

      await reloadedReleasingNothingAt(EXPIRES_AT, "ended");

      await throughout(() => expect([since(), hub.sockets().length]).toEqual([[], 1]));
    });

    it("shows FHIRcast unavailable before that expiry", async () => {
      await connectedBefore({}, EXPIRING);

      await reloadedReleasingNothingAt(EXPIRES_AT - 1, "unavailable");
    });

    it.each([
      ["no number", JSON.stringify("0")],
      ["no JSON", "{"],
    ])("shows FHIRcast unavailable when the expiry kept is %s", async (_name, kept) => {
      await connectedBefore({}, EXPIRING);
      window.sessionStorage.setItem(FHIRCAST_STORAGE_KEYS.tokenExpiry, kept);

      await reloadedReleasingNothingAt(EXPIRES_AT, "unavailable");
    });
  });
});

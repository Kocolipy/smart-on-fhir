import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { CHANNEL_ENDPOINT, requestsSince } from "../fake-hub.testHelpers";
import { type FhircastRelease } from ".";
import {
  eventually,
  keptState,
  RELEASE,
  RELEASE_WITHOUT_TOKEN,
  routes,
  throughout,
} from "./fhircast.testHelpers";
import {
  AT_HUB,
  connectedBefore,
  HUB_TOKEN_AFTER_RELOAD_ROUTES,
  reloaded,
  SECOND_ENDPOINT,
  startedOn,
} from "./reload.testHelpers";

// A reload, as connecting a release takes it: a stored subscription for
// another hub or topic, given up for a connection from scratch; and a reload
// once the SMART access token has expired, which resumes with the client the
// profile kept.

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

        expect(routes(since())).toEqual([
          `GET ${release.hubUrl}/subspace-configuration`,
          `POST ${release.hubUrl}/token`,
          `POST ${release.hubUrl}`,
        ]);
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

      expect([routes(since()), keptState()]).toEqual([
        [...HUB_TOKEN_AFTER_RELOAD_ROUTES, AT_HUB],
        null,
      ]);
    });
  });

  describe("once the SMART access token has expired", () => {
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

    it.each<[string, FhircastRelease]>([
      ["a release of the hub alone", RELEASE_WITHOUT_TOKEN],
      ["a release whose SMART access token's expiry has passed", EXPIRING],
    ])(
      "resumes the stored subscription for %s, with a hub token for the client the profile kept, registering none",
      async (_name, release) => {
        const hub = await connectedBefore({ hubTokenSeconds: 7200 }, EXPIRING);
        const since = requestsSince(hub);
        vi.setSystemTime(EXPIRES_AT);

        await startedOn(await reloaded(), "connected", release);

        expect([routes(since()), hub.sockets()]).toEqual([
          HUB_TOKEN_AFTER_RELOAD_ROUTES,
          [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT],
        ]);
      },
    );

    it("shows FHIRcast unavailable, asking the hub nothing, when the backend releases nothing", async () => {
      const hub = await connectedBefore({}, EXPIRING);
      const since = requestsSince(hub);
      vi.setSystemTime(EXPIRES_AT);
      const page = await reloaded();

      page.startFhircast(() => Promise.resolve(null));

      await eventually(() => expect(page.fhircastSnapshot().status).toBe("unavailable"));
      await throughout(() => expect(since()).toEqual([]));
    });
  });
});

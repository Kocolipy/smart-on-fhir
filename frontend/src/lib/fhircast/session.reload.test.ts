import { describe, expect, it } from "vitest";

import { CHANNEL_ENDPOINT, requestsSince } from "../fake-hub.testHelpers";
import { type FhircastRelease } from ".";
import { keptState, RELEASE, routes } from "./fhircast.testHelpers";
import { connectedBefore, reloaded, SECOND_ENDPOINT, startedOn } from "./reload.testHelpers";

// A reload, as connecting a release takes it: a stored subscription for
// another hub or topic, given up for a connection from scratch.

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
});

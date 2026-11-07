import { describe, expect, it } from "vitest";

import { CHANNEL_ENDPOINT, requestsSince } from "../fake-hub.testHelpers";
import { type FhircastRelease } from ".";
import {
  eventually,
  form,
  keptState,
  RELEASE,
  releasing,
  routes,
  throughout,
} from "./fhircast.testHelpers";
import {
  AT_HUB,
  connectedBefore,
  reloaded,
  SECOND_ENDPOINT,
  startedOn,
} from "./reload.testHelpers";

// A reload, and then the end of the session: the stored subscription torn
// down at the hub by the connection that resumed it, with the SMART access
// token the reload's release carries.

/** The release a reload fetches: a SMART access token of its own, the same hub and topic. */
const RELEASED_AFTER_RELOAD: FhircastRelease = {
  ...RELEASE,
  accessToken: `smart-${crypto.randomUUID()}`,
};

describe("a reload", () => {
  describe("and then the end of the session", () => {
    it("unsubscribes at the stored endpoint, with the SMART access token the reload's release carries", async () => {
      const hub = await connectedBefore();
      const page = await reloaded();
      await startedOn(page, "connected", RELEASED_AFTER_RELOAD);
      const since = requestsSince(hub);

      await page.endFhircast();

      const [unsubscription] = since();
      expect([unsubscription.authorization, form(unsubscription.body)]).toEqual([
        `Bearer ${RELEASED_AFTER_RELOAD.accessToken}`,
        {
          "hub.channel.endpoint": CHANNEL_ENDPOINT,
          "hub.channel.type": "websocket",
          "hub.mode": "unsubscribe",
          "hub.topic": "topic-1",
        },
      ]);
    });

    it("tears down a resumed connection once, closing its socket", async () => {
      const hub = await connectedBefore();
      const page = await reloaded();
      await startedOn(page);
      const since = requestsSince(hub);

      await page.endFhircast();

      expect([routes(since()), hub.openSockets()]).toEqual([[AT_HUB], 0]);
    });

    it("forgets what the tab kept at once", async () => {
      await connectedBefore();
      const page = await reloaded();

      const ending = page.endFhircast();
      const keptOnceCalled = keptState();
      await ending;

      expect(keptOnceCalled).toBeNull();
    });

    it("forgets what the tab kept, asking the hub nothing, when no release came to resume it with", async () => {
      const hub = await connectedBefore();
      const since = requestsSince(hub);

      await (await reloaded()).endFhircast();

      expect([since(), keptState()]).toEqual([[], null]);
    });

    it("tears down a resumed connection from what it holds, once what the tab kept is forgotten", async () => {
      const hub = await connectedBefore();
      const page = await reloaded();
      await startedOn(page);
      page.clearFhircastStorage();
      const since = requestsSince(hub);

      await page.endFhircast();

      expect(routes(since())).toEqual([AT_HUB]);
    });

    it("resumes nothing when the session ends while the release is still being fetched", async () => {
      const hub = await connectedBefore();
      const page = await reloaded();
      let release = () => undefined as void;
      page.startFhircast(() => new Promise((resolve) => (release = () => resolve(RELEASE))));
      const since = requestsSince(hub);

      await page.endFhircast();
      release();

      await throughout(() => expect([since(), hub.sockets()]).toEqual([[], [CHANNEL_ENDPOINT]]));
    });

    it("tears down a connection still resuming once", async () => {
      let confirm = () => undefined as void;
      const hub = await connectedBefore({
        hold: [null, new Promise((resolve) => (confirm = resolve))],
      });
      const page = await reloaded();
      page.startFhircast(releasing);
      await eventually(() => expect(hub.sockets()).toHaveLength(2));
      const since = requestsSince(hub);

      await page.endFhircast();
      confirm();
      await eventually(() => expect(hub.openSockets()).toBe(0));

      await throughout(() => expect(routes(since())).toEqual([AT_HUB]));
    });

    it("tears down the subscription a resumed connection moved to, not the stored one twice", async () => {
      const hub = await connectedBefore({ socket: ["confirms", "denies", "confirms"] });
      const page = await reloaded();
      await startedOn(page);
      // The stored subscription, unsubscribed once the new one's socket is confirmed.
      await eventually(() => expect(hub.requests()).toHaveLength(3));
      const since = requestsSince(hub);

      await page.endFhircast();

      expect(since().map(({ body }) => form(body)["hub.channel.endpoint"])).toEqual([
        SECOND_ENDPOINT,
      ]);
    });
  });
});

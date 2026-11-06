import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  endpointOf,
  HUB_URL,
  patientNotification,
  requestsSince,
  type SocketBehaviour,
} from "../fake-hub.testHelpers";
import { eventually, keptState, RELEASE, releasing, routes } from "./fhircast.testHelpers";
import {
  AT_HUB,
  connectedBefore,
  reloaded,
  SECOND_ENDPOINT,
  startedOn,
} from "./reload.testHelpers";

// A reload, as the renewing connection takes it: the module loaded afresh over
// what the tab kept, reopening the stored subscription, or subscribing anew
// with the release's SMART access token once it cannot.

/** The release a reload fetches: a SMART access token of its own, the same hub and topic. */
const RELEASED_AFTER_RELOAD = { ...RELEASE, accessToken: `smart-${crypto.randomUUID()}` };

describe("a reload", () => {
  describe("with the stored lease unexpired", () => {
    it("reopens the stored channel endpoint, asking the hub for nothing", async () => {
      const hub = await connectedBefore();
      const since = requestsSince(hub);

      await startedOn(await reloaded());

      expect([routes(since()), hub.sockets()]).toEqual([[], [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT]]);
    });

    it("shows connecting until the hub confirms the reopened socket", async () => {
      let confirm = () => undefined as void;
      const hub = await connectedBefore({
        hold: [null, new Promise((resolve) => (confirm = resolve))],
      });
      const page = await reloaded();

      page.startFhircast(releasing);
      await eventually(() => expect(hub.sockets()).toHaveLength(2));
      const beforeConfirmed = page.fhircastSnapshot().status;
      confirm();

      await eventually(() =>
        expect([beforeConfirmed, page.fhircastSnapshot().status]).toEqual([
          "connecting",
          "connected",
        ]),
      );
    });

    it("hands on events from the reopened socket", async () => {
      const hub = await connectedBefore();
      const page = await reloaded();
      const follower = vi.fn();
      const stop = page.followFhircastEvents(follower);
      await startedOn(page);

      hub.notify(patientNotification("Patient-open"));
      stop();

      expect(follower.mock.calls).toEqual([
        [
          {
            event: "Patient-open",
            patientId: "patient-1",
            timestamp: "2026-10-24T02:00:05Z",
          },
        ],
      ]);
    });

    it("keeps the stored subscription as it was", async () => {
      await connectedBefore();
      const before = keptState()?.subscription;

      await startedOn(await reloaded());

      expect(keptState()?.subscription).toEqual(before);
    });

    describe("on the clock", () => {
      const CONNECTED_AT = Date.UTC(2026, 9, 24, 2, 0, 0);
      const RELOADED_AT = CONNECTED_AT + 100_000;

      beforeEach(() => {
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
        vi.setSystemTime(CONNECTED_AT);
      });

      afterEach(() => {
        vi.useRealTimers();
      });

      it("renews by the stored lease's expiry, 60 s before it, and not before", async () => {
        const hub = await connectedBefore({ leaseSeconds: 200 });
        vi.setSystemTime(RELOADED_AT);
        await startedOn(await reloaded());
        const since = requestsSince(hub);

        await vi.advanceTimersByTimeAsync(39_999);
        const subscribedBefore = routes(since());
        await vi.advanceTimersByTimeAsync(1);

        await eventually(() =>
          expect([subscribedBefore, routes(since())[0]]).toEqual([[], AT_HUB]),
        );
      });

      it("renews with the SMART access token the reload's release carries", async () => {
        const hub = await connectedBefore({ leaseSeconds: 200 });
        vi.setSystemTime(RELOADED_AT);
        await startedOn(await reloaded(), "connected", RELEASED_AFTER_RELOAD);
        const since = requestsSince(hub);

        await vi.advanceTimersByTimeAsync(40_000);

        await eventually(() =>
          expect(since()[0]?.authorization).toBe(`Bearer ${RELEASED_AFTER_RELOAD.accessToken}`),
        );
      });
    });
  });

  describe("once the stored lease has run out", () => {
    const CONNECTED_AT = Date.UTC(2026, 9, 24, 2, 0, 0);

    beforeEach(() => {
      vi.useFakeTimers({ toFake: ["Date"] });
      vi.setSystemTime(CONNECTED_AT);
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("subscribes anew, reopening nothing", async () => {
      const hub = await connectedBefore({ leaseSeconds: 200 });
      const since = requestsSince(hub);
      vi.setSystemTime(CONNECTED_AT + 200_000);

      await startedOn(await reloaded());

      expect([routes(since()).slice(0, 1), hub.sockets()]).toEqual([
        [AT_HUB],
        [CHANNEL_ENDPOINT, SECOND_ENDPOINT],
      ]);
    });

    it("subscribes anew with the SMART access token the reload's release carries", async () => {
      const hub = await connectedBefore({ leaseSeconds: 200 });
      const since = requestsSince(hub);
      vi.setSystemTime(CONNECTED_AT + 200_000);

      await startedOn(await reloaded(), "connected", RELEASED_AFTER_RELOAD);

      expect(since()[0]).toMatchObject({
        authorization: `Bearer ${RELEASED_AFTER_RELOAD.accessToken}`,
        url: HUB_URL,
      });
    });

    it("keeps the new subscription in place of the stored one", async () => {
      await connectedBefore({ leaseSeconds: 200 });
      vi.setSystemTime(CONNECTED_AT + 200_000);

      await startedOn(await reloaded());

      expect(keptState()).toMatchObject({ subscription: { endpoint: SECOND_ENDPOINT } });
    });
  });

  it.each<[string, SocketBehaviour]>([
    ["denies the stored endpoint", "denies"],
    ["closes the reopened socket unconfirmed", "closes"],
  ])("subscribes anew when the hub %s", async (_name, behaviour) => {
    const hub = await connectedBefore({ socket: ["confirms", behaviour, "confirms"] });
    const since = requestsSince(hub);

    await startedOn(await reloaded());

    expect([routes(since()).slice(0, 1), hub.sockets()]).toEqual([
      [AT_HUB],
      [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT, SECOND_ENDPOINT],
    ]);
  });

  it("is unavailable when subscribing anew fails, the stored subscription kept", async () => {
    await connectedBefore({
      socket: ["confirms", "denies"],
      subscription: [
        { body: { "hub.channel.endpoint": CHANNEL_ENDPOINT }, status: 202 },
        { status: 403 },
      ],
    });

    await startedOn(await reloaded(), "unavailable");

    expect(keptState()?.subscription).toMatchObject({ endpoint: endpointOf(1) });
  });
});

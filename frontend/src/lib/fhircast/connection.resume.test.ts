import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  endpointOf,
  HUB_URL,
  type HubRequest,
  patientNotification,
  requestsSince,
  type SocketBehaviour,
} from "../fake-hub.testHelpers";
import { eventually, keptState, releasing, routes } from "./fhircast.testHelpers";
import {
  AT_HUB,
  connectedBefore,
  HUB_TOKEN_AFTER_RELOAD,
  HUB_TOKEN_AFTER_RELOAD_ROUTES,
  reloaded,
  SECOND_ENDPOINT,
  startedOn,
} from "./reload.testHelpers";

// A reload, as the renewing connection takes it: the module loaded afresh over
// what the tab kept, a hub token obtained for the dynamic client the profile
// kept, then the stored subscription reopened, or a new one made with that
// hub token once it cannot.

/** The subscriptions and unsubscriptions among `requests`: everything sent to the hub URL itself. */
const atHub = (requests: readonly HubRequest[]) => requests.filter(({ url }) => url === HUB_URL);

describe("a reload", () => {
  describe("with the stored lease unexpired", () => {
    it("reopens the stored channel endpoint, asking the hub for a hub token alone", async () => {
      const hub = await connectedBefore();
      const since = requestsSince(hub);

      await startedOn(await reloaded());

      expect([routes(since()), hub.sockets()]).toEqual([
        HUB_TOKEN_AFTER_RELOAD_ROUTES,
        [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT],
      ]);
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

      hub.notify(patientNotification("patient-open"));
      stop();

      expect(follower.mock.calls).toEqual([
        [
          {
            event: "patient-open",
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

      it("renews with the hub token the reload obtained", async () => {
        const hub = await connectedBefore({ leaseSeconds: 200 });
        vi.setSystemTime(RELOADED_AT);
        await startedOn(await reloaded());
        const since = requestsSince(hub);

        await vi.advanceTimersByTimeAsync(40_000);

        await eventually(() =>
          expect(since()[0]?.authorization).toBe(`Bearer ${HUB_TOKEN_AFTER_RELOAD}`),
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

      expect([routes(since()).slice(0, 3), hub.sockets()]).toEqual([
        [...HUB_TOKEN_AFTER_RELOAD_ROUTES, AT_HUB],
        [CHANNEL_ENDPOINT, SECOND_ENDPOINT],
      ]);
    });

    it("subscribes anew with the hub token the reload obtained", async () => {
      const hub = await connectedBefore({ leaseSeconds: 200 });
      const since = requestsSince(hub);
      vi.setSystemTime(CONNECTED_AT + 200_000);

      await startedOn(await reloaded());

      expect(atHub(since())[0]?.authorization).toBe(`Bearer ${HUB_TOKEN_AFTER_RELOAD}`);
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

    expect([routes(since()).slice(0, 3), hub.sockets()]).toEqual([
      [...HUB_TOKEN_AFTER_RELOAD_ROUTES, AT_HUB],
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

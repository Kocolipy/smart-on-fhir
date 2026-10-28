import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CLIENT_ID,
  HUB_TOKEN,
  type HubOptions,
  patientNotification,
  REGISTRATION_ENDPOINT,
  requestsSince,
  type SocketBehaviour,
  TOKEN_ENDPOINT,
} from "../fake-hub.testHelpers";
import {
  assertionOf,
  eventually,
  keptState,
  releasing,
  routes,
  signedByRegisteredKey,
} from "./fhircast.testHelpers";
import {
  AT_HUB,
  connectedBefore,
  lasting,
  MINT,
  reloaded,
  SECOND_ENDPOINT,
  startedOn,
} from "./reload.testHelpers";

// A reload, as the renewing connection takes it: the module loaded afresh over
// what the tab kept, reopening the stored subscription, or subscribing anew
// with the stored client once it cannot.

describe("a reload", () => {
  describe("with the stored Hub Token and lease unexpired", () => {
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

      it.each<[string, HubOptions]>([
        ["Hub Token's", { token: lasting(200) }],
        ["lease's", { leaseSeconds: 200, token: lasting(3600) }],
      ])(
        "renews by the stored %s expiry, 60 s before it, and not before",
        async (_name, options) => {
          const hub = await connectedBefore(options);
          vi.setSystemTime(RELOADED_AT);
          await startedOn(await reloaded());
          const since = requestsSince(hub);

          await vi.advanceTimersByTimeAsync(39_999);
          const mintedBefore = routes(since());
          await vi.advanceTimersByTimeAsync(1);

          await eventually(() => expect([mintedBefore, routes(since())[0]]).toEqual([[], MINT]));
        },
      );
    });
  });

  describe("once the stored Hub Token or lease has run out", () => {
    const CONNECTED_AT = Date.UTC(2026, 9, 24, 2, 0, 0);

    beforeEach(() => {
      vi.useFakeTimers({ toFake: ["Date"] });
      vi.setSystemTime(CONNECTED_AT);
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it.each<[string, HubOptions, number]>([
      ["the Hub Token", { token: [lasting(200), lasting(3600, `${HUB_TOKEN}-2`)] }, 200_000],
      [
        "the lease",
        {
          leaseSeconds: 200,
          token: [lasting(3600), lasting(3600, `${HUB_TOKEN}-2`)],
        },
        200_000,
      ],
    ])(
      "mints a Hub Token and subscribes anew when %s has, registering nothing",
      async (_name, options, ranOutAfter) => {
        const hub = await connectedBefore(options);
        const since = requestsSince(hub);
        vi.setSystemTime(CONNECTED_AT + ranOutAfter);

        await startedOn(await reloaded());

        expect([routes(since()).slice(0, 2), hub.sockets()]).toEqual([
          [MINT, AT_HUB],
          [CHANNEL_ENDPOINT, SECOND_ENDPOINT],
        ]);
      },
    );

    it("signs the new Hub Token's assertion as the stored client, with the stored key", async () => {
      const hub = await connectedBefore({ token: lasting(200) });
      vi.setSystemTime(CONNECTED_AT + 200_000);

      await startedOn(await reloaded());

      const minted = hub.requestsTo(TOKEN_ENDPOINT)[1];
      expect([
        assertionOf(minted.body).claims.iss,
        assertionOf(minted.body).header.kid,
        await signedByRegisteredKey(hub, minted.body),
      ]).toEqual([CLIENT_ID, (keptState() as { keyId: string }).keyId, true]);
    });

    it("keeps the new subscription in place of the stored one", async () => {
      await connectedBefore({
        token: [lasting(200), lasting(3600, `${HUB_TOKEN}-2`)],
      });
      vi.setSystemTime(CONNECTED_AT + 200_000);

      await startedOn(await reloaded());

      expect(keptState()).toMatchObject({
        subscription: { endpoint: SECOND_ENDPOINT, hubToken: `${HUB_TOKEN}-2` },
      });
    });
  });

  it.each<[string, SocketBehaviour]>([
    ["denies the stored endpoint", "denies"],
    ["closes the reopened socket unconfirmed", "closes"],
  ])(
    "mints a Hub Token and subscribes anew when the hub %s, registering nothing",
    async (_name, behaviour) => {
      const hub = await connectedBefore({
        socket: ["confirms", behaviour, "confirms"],
      });
      const since = requestsSince(hub);

      await startedOn(await reloaded());

      expect([routes(since()).slice(0, 2), hub.sockets()]).toEqual([
        [MINT, AT_HUB],
        [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT, SECOND_ENDPOINT],
      ]);
    },
  );

  it("is unavailable when subscribing anew fails, the stored registration kept", async () => {
    const hub = await connectedBefore({
      socket: ["confirms", "denies"],
      subscription: [
        { body: { "hub.channel.endpoint": CHANNEL_ENDPOINT }, status: 202 },
        { status: 403 },
      ],
    });

    await startedOn(await reloaded(), "unavailable");

    expect([hub.requestsTo(REGISTRATION_ENDPOINT).length, keptState()?.clientId]).toEqual([
      1,
      CLIENT_ID,
    ]);
  });
});

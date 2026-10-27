import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CLIENT_ID,
  type FakeHub,
  HUB_TOKEN,
  HUB_URL,
  type HubAnswer,
  hubOnly,
  type HubOptions,
  patientNotification,
  REGISTRATION_ACCESS_TOKEN,
  REGISTRATION_CLIENT_URI,
  REGISTRATION_ENDPOINT,
  requestsSince,
  REVOCATION_ENDPOINT,
  type SocketBehaviour,
  TOKEN_ENDPOINT,
} from "./fake-hub.testHelpers";
import { connectFhircast, FHIRCAST_STORAGE_KEYS, type FhircastRelease } from "./fhircast";
import {
  assertionOf,
  DELETE,
  eventually,
  form,
  keptState,
  RELEASE,
  releasing,
  REVOKE,
  routes,
  signedByRegisteredKey,
  SMART_ACCESS_TOKEN,
  throughout,
} from "./fhircast.testHelpers";

// A reload: the module loaded afresh over what the tab kept, resuming the
// stored connection or starting over when it cannot.

describe("a reload", () => {
  /** The FHIRcast module, as a page loads it. */
  type Page = typeof import("./fhircast");

  /**
   * The tab after a reload: the FHIRcast module loaded afresh, holding nothing
   * in memory, over the sessionStorage the page before it left. Its session's
   * FHIRcast is ended once the test is over, as the page before's is.
   */
  async function reloaded(): Promise<Page> {
    vi.resetModules();
    const page = await import("./fhircast");
    onTestFinished(() => page.endFhircast());
    return page;
  }

  /**
   * Connected to a hub answering `options` in the page before the reload,
   * which then went as a reload takes a page: its socket closed, nothing torn
   * down at the hub.
   */
  async function connectedBefore(options: HubOptions = {}) {
    const hub = hubOnly(options);
    const outcome = await connectFhircast(RELEASE);
    expect(outcome.kind).toBe("connected");
    if (outcome.kind === "connected") outcome.close();
    return hub;
  }

  /** Started on `page` with `release`, once it says `status`. */
  async function startedOn(page: Page, status = "connected", release = RELEASE) {
    page.startFhircast(() => Promise.resolve(release));
    await eventually(() => expect(page.fhircastSnapshot().status).toBe(status));
  }

  const DISCOVER = `GET ${HUB_URL}/.well-known/fhircast-configuration`;
  const REGISTER = `POST ${REGISTRATION_ENDPOINT}`;
  const MINT = `POST ${TOKEN_ENDPOINT}`;
  /** A subscription, or its unsubscription: both are posted to the hub URL. */
  const AT_HUB = `POST ${HUB_URL}`;

  const SECOND_ENDPOINT = CHANNEL_ENDPOINT.replace(/1$/, "2");

  /** A token answer: a Hub Token named `token`, lasting `seconds`. */
  const lasting = (seconds: number, token = HUB_TOKEN): HubAnswer => ({
    body: { access_token: token, expires_in: seconds, token_type: "Bearer" },
    status: 200,
  });

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

  describe("when the hub refuses the stored client at the token endpoint", () => {
    it.each([400, 401])(
      "deletes the stored registration, then registers anew, on a %s",
      async (status) => {
        const hub = await connectedBefore({
          socket: ["confirms", "denies", "confirms"],
          token: [lasting(3600), { body: { error: "invalid_grant" }, status }, lasting(3600)],
        });
        const since = requestsSince(hub);

        await startedOn(await reloaded());

        expect(routes(since())).toEqual([MINT, DELETE, DISCOVER, REGISTER, MINT, AT_HUB]);
      },
    );

    it("registers anew with the release's SMART access token, under a key of its own", async () => {
      const hub = await connectedBefore({
        socket: ["confirms", "denies", "confirms"],
        token: [lasting(3600), { status: 400 }, lasting(3600)],
      });
      const before = keptState() as { keyId: string };

      await startedOn(await reloaded());

      const [, again] = hub.requestsTo(REGISTRATION_ENDPOINT);
      const registered = JSON.parse(again.body) as {
        jwks: { keys: { kid: string }[] };
      };
      expect([again.authorization, registered.jwks.keys[0].kid === before.keyId]).toEqual([
        `Bearer ${SMART_ACCESS_TOKEN}`,
        false,
      ]);
    });

    it("registers anew when the stored registration names no way to delete it", async () => {
      const hub = await connectedBefore({
        registration: { body: { client_id: CLIENT_ID }, status: 201 },
        socket: ["confirms", "denies", "confirms"],
        token: [lasting(3600), { status: 400 }, lasting(3600)],
      });
      const since = requestsSince(hub);

      await startedOn(await reloaded());

      expect(routes(since())).toEqual([MINT, DISCOVER, REGISTER, MINT, AT_HUB]);
    });

    it("deletes nothing twice, and registers nothing, once the session ended while it was refused", async () => {
      let refuse = () => undefined as void;
      const hub = await connectedBefore({
        socket: ["confirms", "denies"],
        token: [
          lasting(3600),
          new Promise<HubAnswer>((resolve) => (refuse = () => resolve({ status: 400 }))),
        ],
      });
      const page = await reloaded();
      page.startFhircast(releasing);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(2));
      const since = requestsSince(hub);

      await page.endFhircast();
      refuse();

      await throughout(() => expect(routes(since())).toEqual([AT_HUB, REVOKE, DELETE]));
    });

    it("is unavailable, registering nothing, when the token endpoint fails instead", async () => {
      const hub = await connectedBefore({
        socket: ["confirms", "denies"],
        token: [lasting(3600), { status: 500, text: "<html>down</html>" }],
      });

      await startedOn(await reloaded(), "unavailable");

      expect([hub.requestsTo(REGISTRATION_ENDPOINT).length, keptState()?.clientId]).toEqual([
        1,
        CLIENT_ID,
      ]);
    });
  });

  describe("a stored registration for another hub or topic", () => {
    const ELSEWHERE = "http://127.0.0.1:8104/fhircast/STU3";

    it.each<[string, FhircastRelease]>([
      ["another topic", { ...RELEASE, hubTopic: "topic-2" }],
      ["another hub", { ...RELEASE, hubUrl: ELSEWHERE }],
    ])("is torn down, then the tab registers anew, for %s", async (_name, release) => {
      const hub = await connectedBefore();
      const since = requestsSince(hub);

      await startedOn(await reloaded(), "connected", release);

      expect(routes(since())).toEqual([
        AT_HUB,
        REVOKE,
        DELETE,
        `GET ${release.hubUrl}/.well-known/fhircast-configuration`,
        REGISTER,
        MINT,
        `POST ${release.hubUrl}`,
      ]);
    });

    it("is unsubscribed from at its own topic, with its own Hub Token", async () => {
      const hub = await connectedBefore();
      const since = requestsSince(hub);

      await startedOn(await reloaded(), "connected", {
        ...RELEASE,
        hubTopic: "topic-2",
      });

      const [unsubscription] = since();
      expect([unsubscription.authorization, form(unsubscription.body)]).toEqual([
        `Bearer ${HUB_TOKEN}`,
        {
          "hub.channel.endpoint": CHANNEL_ENDPOINT,
          "hub.channel.type": "websocket",
          "hub.mode": "unsubscribe",
          "hub.topic": "topic-1",
        },
      ]);
    });

    it("is never resumed against: its endpoint is not reopened", async () => {
      const hub = await connectedBefore();

      await startedOn(await reloaded(), "connected", {
        ...RELEASE,
        hubTopic: "topic-2",
      });

      expect(hub.sockets()).toEqual([CHANNEL_ENDPOINT, SECOND_ENDPOINT]);
    });

    it("is forgotten, the new registration kept for the new topic", async () => {
      await connectedBefore();

      await startedOn(await reloaded(), "connected", {
        ...RELEASE,
        hubTopic: "topic-2",
      });

      expect(keptState()).toMatchObject({
        hubTopic: "topic-2",
        subscription: { endpoint: SECOND_ENDPOINT },
      });
    });
  });

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

  describe("and then the end of the session", () => {
    it("unsubscribes, revokes and deletes what the tab kept, though it never resumed it", async () => {
      const hub = await connectedBefore();
      const since = requestsSince(hub);

      await (await reloaded()).endFhircast();

      expect(routes(since())).toEqual([AT_HUB, REVOKE, DELETE]);
    });

    it("unsubscribes at the stored endpoint, with the stored Hub Token", async () => {
      const hub = await connectedBefore();
      const since = requestsSince(hub);

      await (await reloaded()).endFhircast();

      const [unsubscription] = since();
      expect([unsubscription.authorization, form(unsubscription.body)]).toEqual([
        `Bearer ${HUB_TOKEN}`,
        {
          "hub.channel.endpoint": CHANNEL_ENDPOINT,
          "hub.channel.type": "websocket",
          "hub.mode": "unsubscribe",
          "hub.topic": "topic-1",
        },
      ]);
    });

    it("revokes the stored Hub Token as the stored client, signed with the stored key", async () => {
      const hub = await connectedBefore();

      await (await reloaded()).endFhircast();

      const { body } = hub.requestTo(REVOCATION_ENDPOINT);
      const { client_assertion: signed, client_id, token } = form(body);
      expect([
        client_id,
        token,
        assertionOf(new URLSearchParams({ assertion: signed }).toString()).claims.aud,
        await signedByRegisteredKey(hub, body, "client_assertion"),
      ]).toEqual([CLIENT_ID, HUB_TOKEN, TOKEN_ENDPOINT, true]);
    });

    it("deletes the stored registration with its registration access token", async () => {
      const hub = await connectedBefore();

      await (await reloaded()).endFhircast();

      expect(hub.requestTo(REGISTRATION_CLIENT_URI)).toMatchObject({
        authorization: `Bearer ${REGISTRATION_ACCESS_TOKEN}`,
        method: "DELETE",
      });
    });

    it("forgets what the tab kept at once", async () => {
      await connectedBefore();
      const page = await reloaded();

      const ending = page.endFhircast();
      const keptOnceCalled = keptState();
      await ending;

      expect(keptOnceCalled).toBeNull();
    });

    it("tears down a registration with no subscription stored by deleting it alone", async () => {
      const hub = hubOnly({ token: { status: 400 } });
      await connectFhircast(RELEASE);
      const since = requestsSince(hub);

      await (await reloaded()).endFhircast();

      expect(routes(since())).toEqual([DELETE]);
    });

    it("skips what the hub named no way to do", async () => {
      const hub = await connectedBefore({
        fhircastConfiguration: {
          body: {
            registration_endpoint: REGISTRATION_ENDPOINT,
            token_endpoint: TOKEN_ENDPOINT,
          },
          status: 200,
        },
        registration: { body: { client_id: CLIENT_ID }, status: 201 },
      });
      const since = requestsSince(hub);

      await (await reloaded()).endFhircast();

      expect(routes(since())).toEqual([AT_HUB]);
    });

    it("tears down a resumed connection from what it holds, once what the tab kept is forgotten", async () => {
      const hub = await connectedBefore();
      const page = await reloaded();
      await startedOn(page);
      page.clearFhircastStorage();
      const since = requestsSince(hub);

      await page.endFhircast();

      expect(routes(since())).toEqual([AT_HUB, REVOKE, DELETE]);
    });

    it("resumes nothing when the session ends while the tab's state is still being read", async () => {
      const hub = await connectedBefore();
      const page = await reloaded();
      // WebCrypto holds the first stored key it is handed until the test lets it go.
      let takeKey = () => undefined as void;
      const importKey = crypto.subtle.importKey.bind(crypto.subtle);
      const importing = vi
        .spyOn(crypto.subtle, "importKey")
        .mockImplementationOnce(async (...taking: Parameters<typeof importKey>) => {
          await new Promise<void>((resolve) => (takeKey = resolve));
          return importKey(...taking);
        });
      onTestFinished(() => importing.mockRestore());
      page.startFhircast(releasing);
      await eventually(() => expect(importing).toHaveBeenCalled());
      const since = requestsSince(hub);

      await page.endFhircast();
      takeKey();

      await throughout(() =>
        expect([routes(since()), hub.sockets()]).toEqual([
          [AT_HUB, REVOKE, DELETE],
          [CHANNEL_ENDPOINT],
        ]),
      );
    });

    it("tears down a resumed connection once, closing its socket", async () => {
      const hub = await connectedBefore();
      const page = await reloaded();
      await startedOn(page);
      const since = requestsSince(hub);

      await page.endFhircast();

      expect([routes(since()), hub.openSockets()]).toEqual([[AT_HUB, REVOKE, DELETE], 0]);
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

      await throughout(() => expect(routes(since())).toEqual([AT_HUB, REVOKE, DELETE]));
    });

    it("tears down the subscription a resumed connection moved to, not the stored one twice", async () => {
      const hub = await connectedBefore({
        socket: ["confirms", "denies", "confirms"],
      });
      const page = await reloaded();
      await startedOn(page);
      // The stored subscription, cancelled once the new one's socket is confirmed.
      await eventually(() => expect(hub.requestsTo(REVOCATION_ENDPOINT)).toHaveLength(1));
      const since = requestsSince(hub);

      await page.endFhircast();

      expect(
        since().map(({ authorization, body, method, url }) =>
          method === "DELETE"
            ? DELETE
            : [url, authorization, form(body)["hub.channel.endpoint"] ?? form(body).token],
        ),
      ).toEqual([
        [HUB_URL, `Bearer ${HUB_TOKEN}-2`, SECOND_ENDPOINT],
        [REVOCATION_ENDPOINT, null, `${HUB_TOKEN}-2`],
        DELETE,
      ]);
    });
  });
});

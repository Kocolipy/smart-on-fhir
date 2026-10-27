import { afterEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CLIENT_ID,
  HUB_TOKEN,
  HUB_URL,
  type HubAnswer,
  hubOnly,
  type HubOptions,
  REGISTRATION_ACCESS_TOKEN,
  REGISTRATION_CLIENT_URI,
  REGISTRATION_ENDPOINT,
  requestsSince,
  REVOCATION_ENDPOINT,
  TOKEN_ENDPOINT,
} from "./fake-hub.testHelpers";
import { connectFhircast, endFhircast, FHIRCAST_STORAGE_KEYS } from "./fhircast";
import {
  assertionOf,
  DELETE,
  eventually,
  form,
  held,
  RELEASE,
  REVOKE,
  routes,
  signedByRegisteredKey,
} from "./fhircast.testHelpers";

// `endFhircast`: the teardown at the hub, in order, and what it does when a
// hub cannot do every step, a step fails, or the connection is still registering.

describe("endFhircast", () => {
  /**
   * Connected to a hub answering `options`; the requests the hub was sent
   * from here on, and the URL of every request the page sent anywhere but the
   * hub's origin.
   */
  async function connected(options: HubOptions = {}) {
    const hub = hubOnly(options);
    const reported = vi.fn();
    const outcome = await connectFhircast(RELEASE, { onConnection: reported });
    expect(outcome.kind).toBe("connected");
    const since = requestsSince(hub);
    const hubOrigin = new URL(HUB_URL).origin;
    return {
      elsewhere: () =>
        vi
          .mocked(fetch)
          .mock.calls.map(([url]) => String(url))
          .filter((url) => !URL.canParse(url) || new URL(url).origin !== hubOrigin),
      hub,
      outcome,
      reported,
      since,
    };
  }

  const UNSUBSCRIBE = `POST ${HUB_URL}`;

  /** What the tab still keeps under each FHIRcast key. */
  const kept = () =>
    Object.values(FHIRCAST_STORAGE_KEYS).map((key) => window.sessionStorage.getItem(key));

  it("unsubscribes, revokes the Hub Token, then deletes the registration, in that order", async () => {
    const { since } = await connected();

    await endFhircast();

    expect(routes(since())).toEqual([UNSUBSCRIBE, REVOKE, DELETE]);
  });

  it("unsubscribes from the topic at its channel endpoint, the Hub Token as Bearer", async () => {
    const { since } = await connected();

    await endFhircast();

    const [unsubscription] = since();
    expect({ ...unsubscription, body: form(unsubscription.body) }).toMatchObject({
      authorization: `Bearer ${HUB_TOKEN}`,
      body: {
        "hub.channel.endpoint": CHANNEL_ENDPOINT,
        "hub.channel.type": "websocket",
        "hub.mode": "unsubscribe",
        "hub.topic": "topic-1",
      },
      contentType: "application/x-www-form-urlencoded",
      credentials: "omit",
      redirect: "error",
    });
  });

  it("revokes the Hub Token by RFC 7009, as the client it registered", async () => {
    const { hub } = await connected();

    await endFhircast();

    const revocation = hub.requestTo(REVOCATION_ENDPOINT);
    const parameters: Record<string, string> = form(revocation.body);
    delete parameters.client_assertion;
    expect([
      revocation.contentType,
      revocation.credentials,
      revocation.redirect,
      parameters,
    ]).toEqual([
      "application/x-www-form-urlencoded",
      "omit",
      "error",
      {
        client_assertion_type: "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        client_id: CLIENT_ID,
        token: HUB_TOKEN,
        token_type_hint: "access_token",
      },
    ]);
  });

  it("authenticates the revocation by an assertion naming the client, for the token endpoint", async () => {
    const { hub } = await connected();

    await endFhircast();

    const assertion = form(hub.requestTo(REVOCATION_ENDPOINT).body).client_assertion;
    const { aud, iss, sub } = assertionOf(new URLSearchParams({ assertion }).toString()).claims;
    expect({ aud, iss, sub }).toEqual({ aud: TOKEN_ENDPOINT, iss: CLIENT_ID, sub: CLIENT_ID });
  });

  it("signs the revocation's assertion with the key it registered", async () => {
    const { hub } = await connected();

    await endFhircast();

    const { body } = hub.requestTo(REVOCATION_ENDPOINT);
    expect(await signedByRegisteredKey(hub, body, "client_assertion")).toBe(true);
  });

  it("deletes the registration by RFC 7592, its registration access token as Bearer", async () => {
    const { hub } = await connected();

    await endFhircast();

    expect(hub.requestTo(REGISTRATION_CLIENT_URI)).toMatchObject({
      authorization: `Bearer ${REGISTRATION_ACCESS_TOKEN}`,
      credentials: "omit",
      method: "DELETE",
      redirect: "error",
    });
  });

  it("closes the socket only once the registration is deleted", async () => {
    const deletion = held();
    const { hub } = await connected({ deletion: deletion.answer });

    const ending = endFhircast();
    await eventually(() => expect(hub.requestsTo(REGISTRATION_CLIENT_URI)).toHaveLength(1));
    const openWhileDeleting = hub.openSockets();
    deletion.give({ status: 204 });
    await ending;

    expect([openWhileDeleting, hub.openSockets()]).toEqual([1, 0]);
  });

  it("then leaves no FHIRcast key in sessionStorage", async () => {
    await connected();

    await endFhircast();

    expect(kept()).toEqual([null]);
  });

  it("tears down nothing twice", async () => {
    const { since } = await connected();
    await endFhircast();
    const once = since().length;

    await endFhircast();

    expect(since()).toHaveLength(once);
  });

  it("tears down a connection Home already closed", async () => {
    const { outcome, since } = await connected();
    if (outcome.kind === "connected") outcome.close();

    await endFhircast();

    expect(routes(since())).toEqual([UNSUBSCRIBE, REVOKE, DELETE]);
  });

  it("revokes the Hub Token and deletes the registration of a connection the hub refused to subscribe", async () => {
    const hub = hubOnly({ subscription: { body: { error: "invalid_request" }, status: 400 } });
    expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    const since = requestsSince(hub);

    await endFhircast();

    expect(routes(since())).toEqual([REVOKE, DELETE]);
  });

  it("deletes the registration of a connection the hub granted no Hub Token", async () => {
    const hub = hubOnly({ token: { body: { error: "invalid_grant" }, status: 400 } });
    expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    const since = requestsSince(hub);

    await endFhircast();

    expect(routes(since())).toEqual([DELETE]);
  });

  describe("a hub that cannot do every step", () => {
    const METADATA = {
      registration_endpoint: REGISTRATION_ENDPOINT,
      token_endpoint: TOKEN_ENDPOINT,
    };

    it.each<[string, Record<string, unknown>]>([
      ["names no revocation endpoint", METADATA],
      ["names an empty one", { ...METADATA, revocation_endpoint: "" }],
      ["names one that is no string", { ...METADATA, revocation_endpoint: 7 }],
      [
        "names one in a list, no string though it reads as one",
        { ...METADATA, revocation_endpoint: [REVOCATION_ENDPOINT] },
      ],
      ["names one off 127.0.0.1", { ...METADATA, revocation_endpoint: "http://10.0.0.1/revoke" }],
    ])("skips the revocation when its metadata %s, sending it nowhere", async (_name, body) => {
      const { elsewhere, since } = await connected({
        fhircastConfiguration: { body, status: 200 },
      });

      await endFhircast();

      expect([routes(since()), elsewhere()]).toEqual([[UNSUBSCRIBE, DELETE], []]);
    });

    const REGISTERED = { client_id: CLIENT_ID, client_id_issued_at: 1 };
    const MANAGED = {
      ...REGISTERED,
      registration_access_token: REGISTRATION_ACCESS_TOKEN,
      registration_client_uri: REGISTRATION_CLIENT_URI,
    };

    it.each<[string, Record<string, unknown>]>([
      ["names no RFC 7592 management", REGISTERED],
      ["names no registration access token", { ...MANAGED, registration_access_token: undefined }],
      [
        "names an empty registration access token",
        { ...MANAGED, registration_access_token: String() },
      ],
      [
        "names a registration access token that is no string",
        { ...MANAGED, registration_access_token: 7 },
      ],
      ["names no registration_client_uri", { ...MANAGED, registration_client_uri: undefined }],
      [
        "names a registration_client_uri off 127.0.0.1",
        { ...MANAGED, registration_client_uri: "http://10.0.0.1/dcr/register/client-1" },
      ],
    ])(
      "skips the deletion when the registration's answer %s, sending it nowhere",
      async (_name, body) => {
        const { elsewhere, since } = await connected({ registration: { body, status: 201 } });

        await endFhircast();

        expect([routes(since()), elsewhere()]).toEqual([[UNSUBSCRIBE, REVOKE], []]);
      },
    );

    it("still unsubscribes, and closes the socket, when it can do neither", async () => {
      const { hub, since } = await connected({
        fhircastConfiguration: { body: METADATA, status: 200 },
        registration: { body: REGISTERED, status: 201 },
      });

      await endFhircast();

      expect([routes(since()), hub.openSockets()]).toEqual([[UNSUBSCRIBE], 0]);
    });
  });

  describe("a step that fails", () => {
    it.each<[string, HubAnswer]>([
      ["is never reached", "unreachable"],
      ["is refused", { body: { error: "invalid_request", error_description: "x" }, status: 400 }],
      ["fails", { status: 500, text: "<html>down</html>" }],
    ])("stops none after it when each %s", async (_name, failing) => {
      const { hub, since } = await connected({
        deletion: failing,
        revocation: failing,
        unsubscription: failing,
      });

      await endFhircast();

      expect([routes(since()), hub.openSockets()]).toEqual([[UNSUBSCRIBE, REVOKE, DELETE], 0]);
    });

    it("still leaves no FHIRcast key in sessionStorage", async () => {
      await connected({
        deletion: "unreachable",
        revocation: "unreachable",
        unsubscription: "unreachable",
      });

      await endFhircast();

      expect(kept()).toEqual([null]);
    });

    describe("on the clock", () => {
      afterEach(() => {
        vi.useRealTimers();
      });

      it("is given up after 3 seconds unanswered, and the next one runs", async () => {
        const unsubscription = held();
        const { hub } = await connected({ unsubscription: unsubscription.answer });
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });

        void endFhircast();
        await eventually(() => expect(hub.requestsTo(HUB_URL)).toHaveLength(2));
        await vi.advanceTimersByTimeAsync(2_999);
        const revokedBefore = hub.requestsTo(REVOCATION_ENDPOINT).length;
        await vi.advanceTimersByTimeAsync(1);
        await eventually(() => expect(hub.requestsTo(REVOCATION_ENDPOINT)).toHaveLength(1));

        expect(revokedBefore).toBe(0);
      });

      it("settles, the socket closed, however long every step goes unanswered", async () => {
        const never = new Promise<HubAnswer>(() => undefined);
        const { hub, since } = await connected({
          deletion: never,
          revocation: never,
          unsubscription: never,
        });
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });

        let settled = false;
        void endFhircast().then(() => (settled = true));
        for (let step = 1; step <= 3; step += 1) {
          await eventually(() => expect(since()).toHaveLength(step));
          await vi.advanceTimersByTimeAsync(3_000);
        }
        await eventually(() => expect(settled).toBe(true));

        expect(hub.openSockets()).toBe(0);
      });
    });
  });

  describe("a connection still registering when the session's FHIRcast ends", () => {
    /** Connecting, its registration held until `registered` is called; settled once ended and given. */
    async function endedWhileRegistering() {
      const registration = held();
      const hub = hubOnly({ registration: registration.answer });
      const connecting = connectFhircast(RELEASE);
      await eventually(() => expect(hub.requestsTo(REGISTRATION_ENDPOINT)).toHaveLength(1));
      await endFhircast();
      const since = requestsSince(hub);
      registration.give({
        body: {
          client_id: CLIENT_ID,
          registration_access_token: REGISTRATION_ACCESS_TOKEN,
          registration_client_uri: REGISTRATION_CLIENT_URI,
        },
        status: 201,
      });
      const outcome = await connecting;
      return { outcome, since };
    }

    it("is unavailable, its registration deleted once the hub answers it", async () => {
      const { outcome, since } = await endedWhileRegistering();

      expect([outcome.kind, routes(since())]).toEqual(["unavailable", [DELETE]]);
    });

    it("leaves no FHIRcast key in sessionStorage", async () => {
      await endedWhileRegistering();

      expect(kept()).toEqual([null]);
    });
  });

  describe("while tearing down", () => {
    it("reconnects nothing when the hub closes the socket", async () => {
      const unsubscription = held();
      const { hub, reported } = await connected({ unsubscription: unsubscription.answer });

      const ending = endFhircast();
      await eventually(() => expect(hub.requestsTo(HUB_URL)).toHaveLength(2));
      hub.dropSockets();
      unsubscription.give({ body: {}, status: 202 });
      await ending;

      expect([reported.mock.calls, hub.sockets()]).toEqual([[], [CHANNEL_ENDPOINT]]);
    });

    it("keeps the socket open for the teardown when Home closes the connection", async () => {
      const unsubscription = held();
      const { hub, outcome } = await connected({ unsubscription: unsubscription.answer });

      const ending = endFhircast();
      await eventually(() => expect(hub.requestsTo(HUB_URL)).toHaveLength(2));
      if (outcome.kind === "connected") outcome.close();
      const openAfterClose = hub.openSockets();
      unsubscription.give({ body: {}, status: 202 });
      await ending;

      expect(openAfterClose).toBe(1);
    });
  });
});

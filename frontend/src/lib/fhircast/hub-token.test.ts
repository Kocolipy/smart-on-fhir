import { createPublicKey, verify } from "node:crypto";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  CONFIGURATION_URL,
  type FakeHub,
  HUB_AUDIENCE,
  HUB_URL,
  type HubAnswer,
  hubOnly,
  type HubOptions,
  hubTokenOf,
  REGISTRATION_URL,
  TOKEN_URL,
} from "../fake-hub.testHelpers";
import { memoryClientStore } from "./client-store.testHelpers";
import { type ClientStore, type DynamicClient, newClientKey } from "./client-key";
import { eventually, held, routes, SMART_ACCESS_TOKEN } from "./fhircast.testHelpers";
import { ClientLost, hubToken } from "./hub-token";
import { type HubCredentials, HubFailure } from "./protocol";

// The hub token a connection subscribes with: the hub's configuration, the
// browser profile's dynamic client for its audience — or a new registration,
// made with the SMART access token, under the profile's lock — and the
// token endpoint's answer to that client's assertion. The store is handed in
// (`memoryClientStore`); the key and its signatures are Node's own WebCrypto.

const CREDENTIALS = {
  accessToken: SMART_ACCESS_TOKEN,
  hub: { hubTopic: "topic-1", hubUrl: HUB_URL },
  softwareId: "epic-client-id",
};

/** {@link CREDENTIALS} once the SMART access token is gone: the release carries none, or it has expired. */
const WITHOUT_TOKEN = { ...CREDENTIALS, accessToken: null };

/** The hub token for `credentials`, from a hub answering `options`, the profile's store `store`. */
async function obtained(
  options: HubOptions = {},
  store: ClientStore = memoryClientStore(),
  credentials: HubCredentials = CREDENTIALS,
) {
  const hub = hubOnly(options);
  const { expiresAt, token } = await hubToken(credentials, store);
  return { expiresAt, hub, store, token };
}

/** The refusal `hubToken` rejects with, from a hub answering `options`; what the hub was sent. */
async function refused(
  options: HubOptions,
  store: ClientStore = memoryClientStore(),
  credentials: HubCredentials = CREDENTIALS,
) {
  const hub = hubOnly(options);
  const failure = await hubToken(credentials, store).then(
    () => "obtained",
    (rejected: unknown) => rejected,
  );
  return { failure, hub };
}

/** A client another tab of the profile registered, its key a real one. */
async function anotherTabsClient(clientId = "client-of-another-tab"): Promise<DynamicClient> {
  const { privateKey, publicJwk } = await newClientKey();
  return { clientId, kid: publicJwk.kid, privateKey };
}

/** The claims of the client assertion the token request at `hub` presented. */
function assertionClaims(hub: FakeHub): Record<string, unknown> {
  const assertion = new URLSearchParams(hub.requestTo(TOKEN_URL).body).get("client_assertion");
  const claims = (assertion ?? "").split(".")[1];
  return JSON.parse(Buffer.from(claims, "base64url").toString("utf8")) as Record<string, unknown>;
}

/** The JWK the registration at `hub` named. */
function registeredJwk(hub: FakeHub): Record<string, unknown> {
  const body = JSON.parse(hub.requestTo(REGISTRATION_URL).body) as {
    jwks: { keys: Record<string, unknown>[] };
  };
  return body.jwks.keys[0];
}

const json = (body: unknown, status = 200): HubAnswer => ({ body, status });

describe("a hub token, for a profile with no client", () => {
  it("is the one the token endpoint issues", async () => {
    expect((await obtained()).token).toBe("hub-token-1");
  });

  it("is reached by the configuration, a registration, then the token", async () => {
    const { hub } = await obtained();

    expect(routes(hub.requests())).toEqual([
      `GET ${CONFIGURATION_URL}`,
      `POST ${REGISTRATION_URL}`,
      `POST ${TOKEN_URL}`,
    ]);
  });

  it("registers with the SMART access token as Bearer", async () => {
    const { hub } = await obtained();

    expect(hub.requestTo(REGISTRATION_URL).authorization).toBe(`Bearer ${SMART_ACCESS_TOKEN}`);
  });

  it("registers the release's software id and one public RSA key, as JSON", async () => {
    const { hub } = await obtained();
    const registration = hub.requestTo(REGISTRATION_URL);

    expect([registration.contentType, JSON.parse(registration.body)]).toEqual([
      "application/json",
      {
        jwks: {
          keys: [
            {
              alg: "RS384",
              e: "AQAB",
              kid: expect.any(String) as string,
              kty: "RSA",
              n: expect.any(String) as string,
              use: "sig",
            },
          ],
        },
        software_id: "epic-client-id",
      },
    ]);
  });

  it("keeps the client the hub registered, under the hub's audience", async () => {
    const { hub, store } = await obtained();
    const kept = await store.read(HUB_AUDIENCE);

    expect([kept?.clientId, kept?.kid, kept?.privateKey.extractable]).toEqual([
      "client-1",
      registeredJwk(hub).kid,
      false,
    ]);
  });

  it("asks for the token with an assertion the registered key signed, for the new client", async () => {
    const { hub } = await obtained();
    const assertion =
      new URLSearchParams(hub.requestTo(TOKEN_URL).body).get("client_assertion") ?? "";
    const [header, claims, signature] = assertion.split(".");
    const publicKey = createPublicKey({ format: "jwk", key: registeredJwk(hub) });

    expect([
      verify(
        "sha384",
        Buffer.from(`${header}.${claims}`),
        publicKey,
        Buffer.from(signature, "base64url"),
      ),
      assertionClaims(hub).iss,
    ]).toEqual([true, "client-1"]);
  });
});

describe("a hub token, for a profile that holds a client", () => {
  it("uses the client kept for the hub's audience and registers none", async () => {
    const store = memoryClientStore();
    await store.keep(HUB_AUDIENCE, await anotherTabsClient());
    const { hub } = await obtained({}, store);

    expect([routes(hub.requests()), assertionClaims(hub).iss]).toEqual([
      [`GET ${CONFIGURATION_URL}`, `POST ${TOKEN_URL}`],
      "client-of-another-tab",
    ]);
  });

  it("registers anew for a hub of another audience", async () => {
    const store = memoryClientStore();
    await store.keep("urn:another-hub", await anotherTabsClient());
    const { hub } = await obtained({}, store);

    expect(hub.requestsTo(REGISTRATION_URL)).toHaveLength(1);
  });

  it("uses a client another tab kept while this one waited for the lock", async () => {
    const store = memoryClientStore();
    const keptMeanwhile = await anotherTabsClient();
    const waited: ClientStore = {
      ...store,
      exclusively: async (audience, task) => {
        await store.keep(audience, keptMeanwhile);
        return store.exclusively(audience, task);
      },
    };
    const { hub } = await obtained({}, waited);

    expect([hub.requestsTo(REGISTRATION_URL).length, assertionClaims(hub).iss]).toEqual([
      0,
      "client-of-another-tab",
    ]);
  });

  it("registers once for two tabs connecting at the same time", async () => {
    const hub = hubOnly();
    const store = memoryClientStore();

    const tokens = await Promise.all([hubToken(CREDENTIALS, store), hubToken(CREDENTIALS, store)]);

    expect([
      tokens.map(({ token }) => token).sort(),
      hub.requestsTo(REGISTRATION_URL).length,
    ]).toEqual([["hub-token-1", "hub-token-2"], 1]);
  });
});

describe("a hub token, with no SMART access token", () => {
  it("is obtained for the client the profile kept, registering none", async () => {
    const store = memoryClientStore();
    await store.keep(HUB_AUDIENCE, await anotherTabsClient());
    const { hub, token } = await obtained({}, store, WITHOUT_TOKEN);

    expect([token, routes(hub.requests())]).toEqual([
      "hub-token-1",
      [`GET ${CONFIGURATION_URL}`, `POST ${TOKEN_URL}`],
    ]);
  });

  it("is lost with no client kept, asking the hub nothing beyond its configuration", async () => {
    const { failure, hub } = await refused({}, memoryClientStore(), WITHOUT_TOKEN);

    expect([failure instanceof ClientLost, routes(hub.requests())]).toEqual([
      true,
      [`GET ${CONFIGURATION_URL}`],
    ]);
  });
});

describe("a hub token's expiry", () => {
  /** 2026-10-24T02:00:00Z. */
  const NOW = Date.UTC(2026, 9, 24, 2, 0, 0);

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(NOW);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("is when the token answer's expires_in runs out", async () => {
    expect((await obtained()).expiresAt).toBe(Date.UTC(2026, 9, 24, 3, 0, 0));
  });

  it.each<[string, unknown]>([
    ["names none", undefined],
    ["is zero", 0],
    ["is no number", "3600"],
  ])("is none when the token answer's expires_in %s", async (_name, expiresIn) => {
    const { expiresAt } = await obtained({
      token: json({ access_token: hubTokenOf(1), expires_in: expiresIn }),
    });

    expect(expiresAt).toBeNull();
  });
});

describe("a hub token, once the hub has forgotten the profile's client", () => {
  const INVALID_CLIENT = json({ error: "INVALID-CLIENT" }, 401);
  /** The token endpoint forgetting the first client it is asked for, then issuing tokens. */
  const FORGETS_THE_FIRST: HubOptions = {
    token: [INVALID_CLIENT, json({ access_token: hubTokenOf(2), expires_in: 3600 })],
  };

  /** A store holding the client another tab registered, which the hub has forgotten. */
  async function storeHoldingAClient(): Promise<ClientStore> {
    const store = memoryClientStore();
    await store.keep(HUB_AUDIENCE, await anotherTabsClient());
    return store;
  }

  /** The `iss` of each client assertion the token requests at `hub` presented, in order. */
  const assertedClients = (hub: FakeHub) =>
    hub.requestsTo(TOKEN_URL).map(({ body }) => {
      const assertion = new URLSearchParams(body).get("client_assertion") ?? "";
      const claims = Buffer.from(assertion.split(".")[1], "base64url").toString("utf8");
      return (JSON.parse(claims) as { iss: string }).iss;
    });

  it("registers once more with the SMART access token, and is the new client's", async () => {
    const { hub, token } = await obtained(FORGETS_THE_FIRST, await storeHoldingAClient());

    expect([token, routes(hub.requests()), assertedClients(hub)]).toEqual([
      "hub-token-2",
      [
        `GET ${CONFIGURATION_URL}`,
        `POST ${TOKEN_URL}`,
        `POST ${REGISTRATION_URL}`,
        `POST ${TOKEN_URL}`,
      ],
      ["client-of-another-tab", "client-1"],
    ]);
  });

  it("registers once more with the SMART access token as Bearer", async () => {
    const { hub } = await obtained(FORGETS_THE_FIRST, await storeHoldingAClient());

    expect(hub.requestTo(REGISTRATION_URL).authorization).toBe(`Bearer ${SMART_ACCESS_TOKEN}`);
  });

  it("keeps the client registered once more in place of the forgotten one", async () => {
    const { store } = await obtained(FORGETS_THE_FIRST, await storeHoldingAClient());

    expect((await store.read(HUB_AUDIENCE))?.clientId).toBe("client-1");
  });

  it("uses the client another tab registered once more meanwhile, registering none", async () => {
    const store = await storeHoldingAClient();
    const registeredMeanwhile = await anotherTabsClient("client-registered-meanwhile");
    const waited: ClientStore = {
      ...store,
      exclusively: async (audience, task) => {
        await store.keep(audience, registeredMeanwhile);
        return store.exclusively(audience, task);
      },
    };
    const { hub } = await obtained(FORGETS_THE_FIRST, waited);

    expect([hub.requestsTo(REGISTRATION_URL).length, assertedClients(hub)]).toEqual([
      0,
      ["client-of-another-tab", "client-registered-meanwhile"],
    ]);
  });

  it.each<[string, HubOptions, HubCredentials]>([
    ["with no SMART access token", FORGETS_THE_FIRST, WITHOUT_TOKEN],
    [
      "at a read-only hub",
      { ...FORGETS_THE_FIRST, configuration: json({ audience: HUB_AUDIENCE, isReadOnly: "1" }) },
      CREDENTIALS,
    ],
  ])(
    "is lost %s, registering nothing and forgetting the client",
    async (_name, options, credentials) => {
      const store = await storeHoldingAClient();
      const { failure, hub } = await refused(options, store, credentials);

      expect([
        failure instanceof ClientLost,
        hub.requestsTo(REGISTRATION_URL).length,
        await store.read(HUB_AUDIENCE),
      ]).toEqual([true, 0, null]);
    },
  );

  it.each<[string, HubOptions]>([
    ["registering once more is refused", { ...FORGETS_THE_FIRST, registration: json({}, 401) }],
    [
      "registering once more is never answered",
      { ...FORGETS_THE_FIRST, registration: "unreachable" },
    ],
    ["the hub forgets the client registered once more too", { token: INVALID_CLIENT }],
  ])("is lost when %s, keeping no client", async (_name, options) => {
    const store = await storeHoldingAClient();
    const { failure } = await refused(options, store);

    expect([failure instanceof ClientLost, await store.read(HUB_AUDIENCE)]).toEqual([true, null]);
  });

  it("is lost when the hub forgets the client registered once more too, keeping the client another tab kept meanwhile", async () => {
    const store = await storeHoldingAClient();
    const keptMeanwhile = await anotherTabsClient("client-kept-meanwhile");
    let locks = 0;
    const secondLockWaited: ClientStore = {
      ...store,
      exclusively: async (audience, task) => {
        locks += 1;
        if (locks === 2) await store.keep(audience, keptMeanwhile);
        return store.exclusively(audience, task);
      },
    };
    const { failure } = await refused({ token: INVALID_CLIENT }, secondLockWaited);

    expect([failure instanceof ClientLost, (await store.read(HUB_AUDIENCE))?.clientId]).toEqual([
      true,
      "client-kept-meanwhile",
    ]);
  });

  it("is lost when the hub forgets the client registered once more too, and another tab forgot it meanwhile", async () => {
    const store = await storeHoldingAClient();
    let locks = 0;
    const secondLockWaited: ClientStore = {
      ...store,
      exclusively: async (audience, task) => {
        locks += 1;
        if (locks === 2) await store.forget(audience);
        return store.exclusively(audience, task);
      },
    };
    const { failure } = await refused({ token: INVALID_CLIENT }, secondLockWaited);

    expect([failure instanceof ClientLost, await store.read(HUB_AUDIENCE)]).toEqual([true, null]);
  });

  it("is a hub failure, not lost, when the token request after registering once more is refused otherwise, the new client kept", async () => {
    const store = await storeHoldingAClient();
    const { failure } = await refused({ token: [INVALID_CLIENT, json({}, 500)] }, store);

    expect([failure, (await store.read(HUB_AUDIENCE))?.clientId]).toEqual([
      new HubFailure(false),
      "client-1",
    ]);
  });

  it("is a hub failure, not lost, when the token request is refused otherwise, and the client is kept", async () => {
    const store = await storeHoldingAClient();
    const { failure } = await refused({ token: json({ error: "invalid_grant" }, 400) }, store);

    expect([failure, (await store.read(HUB_AUDIENCE))?.clientId]).toEqual([
      new HubFailure(false),
      "client-of-another-tab",
    ]);
  });
});

describe("a hub token, at a read-only hub", () => {
  const READ_ONLY: HubOptions = {
    configuration: json({ audience: HUB_AUDIENCE, isReadOnly: "1" }),
  };

  it("uses the client kept for the hub's audience", async () => {
    const store = memoryClientStore();
    await store.keep(HUB_AUDIENCE, await anotherTabsClient());
    const { hub, token } = await obtained(READ_ONLY, store);

    expect([token, routes(hub.requests())]).toEqual([
      "hub-token-1",
      [`GET ${CONFIGURATION_URL}`, `POST ${TOKEN_URL}`],
    ]);
  });

  it("is refused with no client kept, and registers none", async () => {
    const { failure, hub } = await refused(READ_ONLY);

    expect([failure, routes(hub.requests())]).toEqual([
      new HubFailure(false),
      [`GET ${CONFIGURATION_URL}`],
    ]);
  });
});

describe("the token request", () => {
  /** 2026-10-24T02:00:00Z, in whole seconds. */
  const NOW_SECONDS = 1_792_807_200;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(NOW_SECONDS * 1000);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("is a client_credentials grant with a jwt-bearer client assertion, and no Bearer", async () => {
    const { hub } = await obtained();
    const request = hub.requestTo(TOKEN_URL);
    const form = Object.fromEntries(new URLSearchParams(request.body));

    expect([request.authorization, request.contentType, Object.keys(form).sort(), form]).toEqual([
      null,
      "application/x-www-form-urlencoded",
      ["client_assertion", "client_assertion_type", "grant_type"],
      expect.objectContaining({
        client_assertion_type: "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        grant_type: "client_credentials",
      }) as unknown,
    ]);
  });

  it("asserts the client to the hub's audience, for four minutes", async () => {
    const { hub } = await obtained();

    expect(assertionClaims(hub)).toEqual(
      expect.objectContaining({
        aud: HUB_AUDIENCE,
        exp: NOW_SECONDS + 240,
        iss: "client-1",
        sub: "client-1",
      }),
    );
  });
});

describe("every request of the hub on the way to a hub token", () => {
  it("is sent without credentials and refuses a redirect", async () => {
    const { hub } = await obtained();

    expect(hub.requests().map(({ credentials, redirect }) => [credentials, redirect])).toEqual([
      ["omit", "error"],
      ["omit", "error"],
      ["omit", "error"],
    ]);
  });

  it.each(["/", "//"])(
    "reaches beneath a hub URL that ends in %s without doubling a slash",
    async (ending) => {
      const hub = hubOnly();

      await hubToken(
        { ...CREDENTIALS, hub: { ...CREDENTIALS.hub, hubUrl: `${HUB_URL}${ending}` } },
        memoryClientStore(),
      );

      expect(routes(hub.requests())[0]).toBe(`GET ${CONFIGURATION_URL}`);
    },
  );
});

describe("a refusal on the way to a hub token", () => {
  it.each<[string, HubOptions]>([
    ["the configuration is refused", { configuration: json({ error: "server_error" }, 500) }],
    ["the configuration names no audience", { configuration: json({ isReadOnly: "0" }) }],
    [
      "the configuration's audience is empty",
      { configuration: json({ audience: "", isReadOnly: "0" }) },
    ],
    [
      "the configuration's audience is no string",
      { configuration: json({ audience: 7, isReadOnly: "0" }) },
    ],
    ["the configuration states no isReadOnly", { configuration: json({ audience: HUB_AUDIENCE }) }],
    [
      'the configuration\'s isReadOnly is neither "0" nor "1"',
      { configuration: json({ audience: HUB_AUDIENCE, isReadOnly: "false" }) },
    ],
    [
      "the registration is refused for the token",
      { registration: json({ error: "invalid_token" }, 401) },
    ],
    [
      "the registration is refused as read-only",
      { registration: json({ error: "access_denied" }, 403) },
    ],
    [
      "the registration is refused for its metadata",
      { registration: json({ error: "invalid_client_metadata" }, 400) },
    ],
    ["the registration names no client_id", { registration: json({}) }],
    ["the registration's client_id is empty", { registration: json({ client_id: "" }) }],
    ["the token request is refused", { token: json({ error: "server_error" }, 500) }],
    ["the token answer names no access_token", { token: json({ token_type: "bearer" }) }],
    [
      "the token answer's access_token is empty",
      { token: { status: 200, text: '{"access_token":""}' } },
    ],
  ])("is a hub failure when %s", async (_name, options) => {
    expect((await refused(options)).failure).toEqual(new HubFailure(false));
  });

  it.each<[string, HubOptions]>([
    ["the configuration", { configuration: { status: 200, text: "not json" } }],
    ["the registration", { registration: { status: 200, text: "not json" } }],
    ["the token", { token: { status: 200, text: "not json" } }],
  ])("rejects when %s answers no JSON", async (_name, options) => {
    expect((await refused(options)).failure).toBeInstanceOf(Error);
  });

  it("is an unreachable hub when the browser never reached the configuration", async () => {
    expect((await refused({ configuration: "unreachable" })).failure).toEqual(new HubFailure(true));
  });

  it("keeps no client when the registration is refused", async () => {
    const store = memoryClientStore();
    await refused({ registration: json({ error: "access_denied" }, 403) }, store);

    expect(await store.read(HUB_AUDIENCE)).toBeNull();
  });

  it("keeps the registered client when only the token is refused", async () => {
    const store = memoryClientStore();
    await refused({ token: json({ error: "server_error" }, 500) }, store);

    expect((await store.read(HUB_AUDIENCE))?.clientId).toBe("client-1");
  });
});

describe("a hub that leaves a request unanswered", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it.each<[string, string, (answer: Promise<HubAnswer>) => HubOptions]>([
    ["the configuration", CONFIGURATION_URL, (answer) => ({ configuration: answer })],
    ["the registration", REGISTRATION_URL, (answer) => ({ registration: answer })],
    ["the token", TOKEN_URL, (answer) => ({ token: answer })],
  ])("has refused %s once ten seconds pass, and not before", async (_name, url, unanswered) => {
    const hub = hubOnly(unanswered(held().answer));
    let settled: unknown = "pending";
    void hubToken(CREDENTIALS, memoryClientStore()).catch(
      (failure: unknown) => (settled = failure),
    );
    await eventually(() => expect(hub.requestsTo(url)).toHaveLength(1));

    await vi.advanceTimersByTimeAsync(9_999);
    const before = settled;
    await vi.advanceTimersByTimeAsync(1);

    expect([before, settled]).toEqual(["pending", new HubFailure(false)]);
  });
});

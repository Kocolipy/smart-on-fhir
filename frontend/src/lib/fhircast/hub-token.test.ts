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
  REGISTRATION_URL,
  TOKEN_URL,
} from "../fake-hub.testHelpers";
import { memoryClientStore } from "./client-store.testHelpers";
import { type ClientStore, type DynamicClient, newClientKey } from "./client-key";
import { eventually, held, routes, SMART_ACCESS_TOKEN } from "./fhircast.testHelpers";
import { hubToken } from "./hub-token";
import { HubFailure } from "./protocol";

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

/** The hub token for {@link CREDENTIALS}, from a hub answering `options`, the profile's store `store`. */
async function obtained(options: HubOptions = {}, store: ClientStore = memoryClientStore()) {
  const hub = hubOnly(options);
  const token = await hubToken(CREDENTIALS, store);
  return { hub, store, token };
}

/** The refusal `hubToken` rejects with, from a hub answering `options`; what the hub was sent. */
async function refused(options: HubOptions, store: ClientStore = memoryClientStore()) {
  const hub = hubOnly(options);
  const failure = await hubToken(CREDENTIALS, store).then(
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

    expect([[...tokens].sort(), hub.requestsTo(REGISTRATION_URL).length]).toEqual([
      ["hub-token-1", "hub-token-2"],
      1,
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
    ["the token request is refused", { token: json({ error: "INVALID-CLIENT" }, 401) }],
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

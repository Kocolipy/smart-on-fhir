import { afterEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CLIENT_ID,
  fakeHub,
  HUB_TOKEN,
  HUB_URL,
  type HubAnswer,
  hubOnly,
  type HubOptions,
  REGISTRATION_ENDPOINT,
  REVOCATION_ENDPOINT,
  TOKEN_ENDPOINT,
} from "../fake-hub.testHelpers";
import { fakeBackend } from "../fake-backend.testHelpers";
import { clearFhircastStorage, connectFhircast } from ".";
import { type Client, ES384 } from "./model";
import { ClientRefused, discover, HubFailure, hubToken, register, subscribe } from "./protocol";
import {
  assertionOf,
  RELEASE,
  signedByRegisteredKey,
  SMART_ACCESS_TOKEN,
} from "./fhircast.testHelpers";

// The hub protocol, through `connectFhircast`, step by step: discovery,
// registration, the Hub Token, the subscription, and a hub out of reach. The
// event notifications on the socket are `notifications.test.ts`'s, and what the
// tab keeps `storage.test.ts`'s.

const JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";

describe("connectFhircast", () => {
  it("connects once the socket confirms the subscription", async () => {
    hubOnly();

    const outcome = await connectFhircast(RELEASE);

    expect(outcome.kind).toBe("connected");
  });

  describe("discovery", () => {
    it("reads the endpoints from the hub's fhircast-configuration", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      expect(hub.requests()[0]).toMatchObject({
        method: "GET",
        url: `${HUB_URL}/.well-known/fhircast-configuration`,
      });
    });

    it.each<[string, HubAnswer]>([
      ["has no fhircast-configuration", { status: 404 }],
      [
        "answers a fhircast-configuration that is no JSON",
        { status: 200, text: "<html>hub</html>" },
      ],
    ])(
      "falls back to the smart-configuration when the hub %s",
      async (_name, fhircastConfiguration) => {
        hubOnly({
          fhircastConfiguration,
          smartConfiguration: {
            body: { registration_endpoint: REGISTRATION_ENDPOINT, token_endpoint: TOKEN_ENDPOINT },
            status: 200,
          },
        });

        expect((await connectFhircast(RELEASE)).kind).toBe("connected");
      },
    );

    it.each([
      ["registration_endpoint", "http://192.168.1.20:8103/dcr/register"],
      ["registration_endpoint", "http://localhost:8103/dcr/register"],
      ["token_endpoint", "https://fhir.epic.example/oauth2/token"],
      ["token_endpoint", "ws://127.0.0.1:8103/dcr/token"],
      ["registration_endpoint", "http://user@127.0.0.1:8103/dcr/register"],
      ["token_endpoint", "http://:secret@127.0.0.1:8103/dcr/token"],
      ["registration_endpoint", "not a url"],
    ])("gives FHIRcast unavailable for a %s off 127.0.0.1 (%s)", async (member, url) => {
      const hub = hubOnly({
        fhircastConfiguration: {
          body: {
            registration_endpoint: REGISTRATION_ENDPOINT,
            token_endpoint: TOKEN_ENDPOINT,
            [member]: url,
          },
          status: 200,
        },
      });

      const outcome = await connectFhircast(RELEASE);

      expect([outcome.kind, hub.requests().length]).toEqual(["unavailable", 1]);
    });

    it("follows https endpoints on 127.0.0.1", async () => {
      const hub = hubOnly({
        fhircastConfiguration: {
          body: {
            registration_endpoint: "https://127.0.0.1:9443/dcr/register",
            token_endpoint: "https://127.0.0.1:9443/dcr/token",
          },
          status: 200,
        },
      });

      const outcome = await connectFhircast(RELEASE);

      expect([
        outcome.kind,
        hub
          .requests()
          .map((request) => request.url)
          .slice(1, 3),
      ]).toEqual([
        "connected",
        ["https://127.0.0.1:9443/dcr/register", "https://127.0.0.1:9443/dcr/token"],
      ]);
    });

    it("reads the metadata under a hub URL that ends in a slash", async () => {
      const hub = hubOnly();

      await connectFhircast({ ...RELEASE, hubUrl: `${HUB_URL}/` });

      expect(hub.requests()[0].url).toBe(`${HUB_URL}/.well-known/fhircast-configuration`);
    });

    it.each([
      ["metadata that is no JSON", { status: 200, text: "<html>hub</html>" }],
      ["metadata that is JSON null", { body: null, status: 200 }],
      [
        "metadata naming an empty endpoint",
        { body: { registration_endpoint: "", token_endpoint: TOKEN_ENDPOINT }, status: 200 },
      ],
      [
        "metadata naming an endpoint that is no string",
        { body: { registration_endpoint: 7, token_endpoint: TOKEN_ENDPOINT }, status: 200 },
      ],
    ])("gives FHIRcast unavailable for %s, registering nothing", async (_name, metadata) => {
      const hub = hubOnly({ fhircastConfiguration: metadata });

      const outcome = await connectFhircast(RELEASE);

      expect([outcome.kind, hub.requests().some(({ method }) => method === "POST")]).toEqual([
        "unavailable",
        false,
      ]);
    });

    it("gives FHIRcast unavailable for metadata naming no endpoints", async () => {
      hubOnly({ fhircastConfiguration: { body: {}, status: 200 } });

      expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    });

    it.each(["http://10.0.0.1/hub", "not a url"])(
      "gives FHIRcast unavailable for a hub URL off 127.0.0.1 (%s), asking it nothing",
      async (hubUrl) => {
        const hub = hubOnly();

        const outcome = await connectFhircast({ ...RELEASE, hubUrl });

        expect([outcome.kind, hub.requests()]).toEqual(["unavailable", []]);
      },
    );
  });

  describe("registration", () => {
    it("registers by DCR with the SMART access token as Bearer", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      expect(hub.requestTo(REGISTRATION_ENDPOINT)).toMatchObject({
        authorization: `Bearer ${SMART_ACCESS_TOKEN}`,
        contentType: "application/json",
        method: "POST",
      });
    });

    it("registers one public ES384 key for the jwt-bearer grant", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      const metadata = JSON.parse(hub.requestTo(REGISTRATION_ENDPOINT).body) as {
        client_name: string;
        grant_types: string[];
        jwks: { keys: Record<string, unknown>[] };
        token_endpoint_auth_method: string;
      };
      expect({
        clientName: metadata.client_name,
        grantTypes: metadata.grant_types,
        key: Object.keys(metadata.jwks.keys[0]).sort(),
        keys: metadata.jwks.keys.length,
        method: metadata.token_endpoint_auth_method,
        type: [metadata.jwks.keys[0].kty, metadata.jwks.keys[0].crv, metadata.jwks.keys[0].alg],
      }).toEqual({
        clientName: "smart-on-fhir",
        grantTypes: [JWT_BEARER],
        key: ["alg", "crv", "kid", "kty", "use", "x", "y"],
        keys: 1,
        method: "private_key_jwt",
        type: ["EC", "P-384", "ES384"],
      });
    });

    it.each([
      ["no client_id", { client_id_issued_at: 1 }],
      ["an empty client_id", { client_id: "" }],
    ])("asks for no Hub Token after a registration answering %s", async (_name, answer) => {
      const hub = hubOnly({ registration: { body: answer, status: 201 } });

      const outcome = await connectFhircast(RELEASE);

      expect([outcome.kind, hub.requests().map((request) => request.url)]).toEqual([
        "unavailable",
        [`${HUB_URL}/.well-known/fhircast-configuration`, REGISTRATION_ENDPOINT],
      ]);
    });

    it("gives FHIRcast unavailable when the hub refuses the registration", async () => {
      hubOnly({
        registration: { body: { error: "invalid_token", error_description: "x" }, status: 401 },
      });

      expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    });
  });

  describe("the Hub Token", () => {
    it("asks for it with the jwt-bearer grant", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      const request = hub.requestTo(TOKEN_ENDPOINT);
      expect([request.contentType, new URLSearchParams(request.body).get("grant_type")]).toEqual([
        "application/x-www-form-urlencoded",
        JWT_BEARER,
      ]);
    });

    it("signs an assertion naming the client as iss and sub, and the token endpoint as aud", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      const { claims, header } = assertionOf(hub.requestTo(TOKEN_ENDPOINT).body);
      expect([header.alg, claims.iss, claims.sub, claims.aud]).toEqual([
        "ES384",
        CLIENT_ID,
        CLIENT_ID,
        TOKEN_ENDPOINT,
      ]);
    });

    it("names the assertion a JWT, signed by the key it registered", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      const { header } = assertionOf(hub.requestTo(TOKEN_ENDPOINT).body);
      const registered = JSON.parse(hub.requestTo(REGISTRATION_ENDPOINT).body) as {
        jwks: { keys: { kid: string }[] };
      };
      expect([header.typ, header.kid]).toEqual(["JWT", registered.jwks.keys[0].kid]);
    });

    it.each([
      ["no access_token", { token_type: "Bearer" }],
      ["an access_token that is no string", { access_token: 7 }],
    ])("subscribes to nothing after a token answer with %s", async (_name, answer) => {
      const hub = hubOnly({ token: { body: answer, status: 200 } });

      const outcome = await connectFhircast(RELEASE);

      expect([outcome.kind, hub.requests().some((request) => request.url === HUB_URL)]).toEqual([
        "unavailable",
        false,
      ]);
    });

    it("makes the assertion valid for five minutes from now", async () => {
      const hub = hubOnly();
      const before = Math.floor(Date.now() / 1000);

      await connectFhircast(RELEASE);

      const { exp } = assertionOf(hub.requestTo(TOKEN_ENDPOINT).body).claims as { exp: number };
      expect(exp - before).toBeGreaterThanOrEqual(299);
      expect(exp - before).toBeLessThanOrEqual(301);
    });

    it("gives every assertion a jti of its own", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);
      // Forgotten, so the second connects from scratch rather than resuming the first.
      clearFhircastStorage();
      await connectFhircast(RELEASE);

      const jtis = hub
        .requests()
        .filter((request) => request.url === TOKEN_ENDPOINT)
        .map((request) => assertionOf(request.body).claims.jti);
      expect(new Set(jtis).size).toBe(2);
    });

    it("encodes the assertion as unpadded base64url, whatever the client_id holds", async () => {
      // `>` and `?` encode to `+` and `/` in plain base64, which base64url replaces.
      const clientId = "client>>>???>>?>?";
      const hub = hubOnly({ registration: { body: { client_id: clientId }, status: 201 } });

      await connectFhircast(RELEASE);

      const { assertion, claims } = assertionOf(hub.requestTo(TOKEN_ENDPOINT).body);
      expect([/^[\w-]+\.[\w-]+\.[\w-]+$/.test(assertion), claims.iss]).toEqual([true, clientId]);
    });

    it("signs the assertion with the key it registered", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      expect(await signedByRegisteredKey(hub, hub.requestTo(TOKEN_ENDPOINT).body)).toBe(true);
    });

    it("gives FHIRcast unavailable when the hub refuses the assertion", async () => {
      hubOnly({ token: { body: { error: "invalid_grant" }, status: 400 } });

      expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    });
  });

  describe("the subscription", () => {
    it("subscribes to Patient-open and Patient-close on the topic over a websocket", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      expect(Object.fromEntries(new URLSearchParams(hub.requestTo(HUB_URL).body))).toEqual({
        "hub.channel.type": "websocket",
        "hub.events": "Patient-open,Patient-close",
        "hub.mode": "subscribe",
        "hub.topic": "topic-1",
      });
    });

    it("subscribes with the Hub Token as Bearer, as a form", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      expect(hub.requestTo(HUB_URL)).toMatchObject({
        authorization: `Bearer ${HUB_TOKEN}`,
        contentType: "application/x-www-form-urlencoded",
        method: "POST",
      });
    });

    it("opens the socket at the channel endpoint the hub answered", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      expect(hub.sockets()).toEqual([CHANNEL_ENDPOINT]);
    });

    it.each([
      // A fixture for the refusal: an endpoint off loopback is never connected to.
      // nosemgrep: javascript.lang.security.detect-insecure-websocket.detect-insecure-websocket
      "ws://192.168.1.20:8103/ws/fhircast/endpoint-1",
      "wss://hub.example.org/ws/fhircast/endpoint-1",
      "http://127.0.0.1:8103/ws/fhircast/endpoint-1",
      "not a url",
    ])(
      "refuses a channel endpoint off ws(s) on 127.0.0.1 (%s), opening nothing",
      async (endpoint) => {
        const hub = hubOnly({
          subscription: { body: { "hub.channel.endpoint": endpoint }, status: 202 },
        });

        const outcome = await connectFhircast(RELEASE);

        expect([outcome.kind, hub.sockets()]).toEqual(["unavailable", []]);
      },
    );

    it("accepts a wss endpoint on 127.0.0.1", async () => {
      hubOnly({
        subscription: {
          body: { "hub.channel.endpoint": "wss://127.0.0.1:9443/ws/fhircast/endpoint-1" },
          status: 202,
        },
      });

      expect((await connectFhircast(RELEASE)).kind).toBe("connected");
    });

    it("gives FHIRcast unavailable when the hub refuses the subscription", async () => {
      hubOnly({ subscription: { body: { error: "x" }, status: 403 } });

      expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    });

    it("gives FHIRcast unavailable when the socket closes before confirming", async () => {
      hubOnly({ socket: "closes" });

      expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    });

    it("reports no connection lost when it never had one", async () => {
      hubOnly({ socket: "closes" });
      const lost = vi.fn();

      await connectFhircast(RELEASE, { onConnection: lost });

      expect(lost).not.toHaveBeenCalled();
    });

    it("takes nothing but the hub's subscribe confirmation for one", async () => {
      hubOnly({ socket: "says-otherwise" });

      expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    });

    describe("on the hub's clock", () => {
      afterEach(() => {
        vi.useRealTimers();
      });

      it("gives FHIRcast unavailable when the hub stays silent for ten seconds", async () => {
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
        const hub = hubOnly({ socket: "silent" });
        const outcome = connectFhircast(RELEASE);
        await vi.waitFor(() => expect(hub.sockets()).toHaveLength(1));

        await vi.advanceTimersByTimeAsync(10_000);

        expect([(await outcome).kind, hub.openSockets()]).toEqual(["unavailable", 0]);
      });

      it("keeps a confirmed socket open past those ten seconds", async () => {
        vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
        const hub = hubOnly();
        const lost = vi.fn();
        const outcome = connectFhircast(RELEASE, { onConnection: lost });
        await vi.waitFor(() => expect(hub.sockets()).toHaveLength(1));
        await vi.advanceTimersByTimeAsync(0);
        expect((await outcome).kind).toBe("connected");

        await vi.advanceTimersByTimeAsync(10_000);

        expect([hub.openSockets(), lost.mock.calls.length]).toEqual([1, 0]);
      });
    });

    it("closes its socket when asked, reporting nothing lost", async () => {
      const hub = hubOnly();
      const lost = vi.fn();
      const outcome = await connectFhircast(RELEASE, { onConnection: lost });

      if (outcome.kind === "connected") outcome.close();

      expect([hub.openSockets(), lost.mock.calls]).toEqual([0, []]);
    });
  });

  describe("a hub the browser cannot reach", () => {
    it("is blocked by the browser when Local Network Access was refused", async () => {
      hubOnly({ fhircastConfiguration: "unreachable", localNetworkAccess: "denied" });

      expect((await connectFhircast(RELEASE)).kind).toBe("blocked");
    });

    it.each(["granted", "prompt"] as const)(
      "is unavailable when Local Network Access is %s",
      async (state) => {
        hubOnly({ fhircastConfiguration: "unreachable", localNetworkAccess: state });

        expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
      },
    );

    it.each<[string, HubOptions]>([
      ["refuses the registration", { registration: { body: {}, status: 401 } }],
      ["refuses the Hub Token", { token: { body: { error: "invalid_client" }, status: 401 } }],
      ["answers metadata that is no JSON", { fhircastConfiguration: { status: 200, text: "x" } }],
      ["names a member it should not", { fhircastConfiguration: { body: {}, status: 200 } }],
      [
        "names an empty member",
        {
          fhircastConfiguration: {
            body: { registration_endpoint: "", token_endpoint: TOKEN_ENDPOINT },
            status: 200,
          },
        },
      ],
      [
        "names endpoints off 127.0.0.1",
        {
          fhircastConfiguration: {
            body: { registration_endpoint: "http://10.0.0.1/r", token_endpoint: TOKEN_ENDPOINT },
            status: 200,
          },
        },
      ],
      [
        "names a channel endpoint off 127.0.0.1",
        // A fixture for the refusal: an endpoint off loopback is never connected to.
        // nosemgrep: javascript.lang.security.detect-insecure-websocket.detect-insecure-websocket
        { subscription: { body: { "hub.channel.endpoint": "ws://10.0.0.1/ws" }, status: 202 } },
      ],
    ])(
      "is unavailable, not blocked, when a reachable hub %s, Local Network Access refused or not",
      async (_name, options) => {
        hubOnly({ ...options, localNetworkAccess: "denied" });

        expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
      },
    );

    it("never sends a cookie to the hub, nor follows a redirect", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      expect(
        new Set(hub.requests().map(({ credentials, redirect }) => `${credentials} ${redirect}`)),
      ).toEqual(new Set(["omit error"]));
    });

    it("is unavailable in a browser that has no such permission", async () => {
      fakeBackend();
      fakeHub({ fhircastConfiguration: "unreachable" });
      vi.stubGlobal("navigator", { ...navigator, permissions: undefined });

      expect((await connectFhircast(RELEASE)).kind).toBe("unavailable");
    });
  });
});

// The hub protocol's own requests, read without a session: each made of the
// fake hub directly, so nothing here needs the session's FHIRcast reset.

describe("the hub protocol", () => {
  /** A Hub Registration the fake hub answers for, signing with a fresh key. */
  async function client(): Promise<Client> {
    const keys = await crypto.subtle.generateKey(ES384, true, ["sign", "verify"]);
    return {
      clientId: CLIENT_ID,
      hub: { hubTopic: RELEASE.hubTopic, hubUrl: HUB_URL },
      management: null,
      revocation: null,
      signing: {
        jwk: await crypto.subtle.exportKey("jwk", keys.privateKey),
        keyId: "key-1",
        privateKey: keys.privateKey,
      },
      tokenEndpoint: TOKEN_ENDPOINT,
    };
  }

  const metadata = (revocation: string): HubAnswer => ({
    body: {
      registration_endpoint: REGISTRATION_ENDPOINT,
      revocation_endpoint: revocation,
      token_endpoint: TOKEN_ENDPOINT,
    },
    status: 200,
  });

  it("discovers the registration, token and revocation endpoints", async () => {
    hubOnly();

    expect(await discover(HUB_URL)).toEqual({
      registration: REGISTRATION_ENDPOINT,
      revocation: REVOCATION_ENDPOINT,
      token: TOKEN_ENDPOINT,
    });
  });

  it("discovers no revocation endpoint the hub names off loopback", async () => {
    hubOnly({ fhircastConfiguration: metadata("http://localhost:8103/dcr/revoke") });

    expect((await discover(HUB_URL)).revocation).toBeNull();
  });

  it("registers no handle on a registration answered with no access token", async () => {
    hubOnly({ registration: { body: { client_id: CLIENT_ID }, status: 201 } });
    const { publicKey } = await crypto.subtle.generateKey(ES384, true, ["sign", "verify"]);

    expect(await register(REGISTRATION_ENDPOINT, SMART_ACCESS_TOKEN, "key-1", publicKey)).toEqual({
      clientId: CLIENT_ID,
      management: null,
    });
  });

  it("is granted a Hub Token, with the lifetime the hub says", async () => {
    hubOnly();

    expect(await hubToken(await client())).toEqual({ lifetime: 3600, token: HUB_TOKEN });
  });

  it("is granted a Hub Token of no lifetime when the hub says none", async () => {
    hubOnly({ token: { body: { access_token: HUB_TOKEN, token_type: "Bearer" }, status: 200 } });

    expect((await hubToken(await client())).lifetime).toBeNull();
  });

  it("is refused the client when the token endpoint answers 401", async () => {
    hubOnly({ token: { body: { error: "invalid_client" }, status: 401 } });

    await expect(hubToken(await client())).rejects.toBeInstanceOf(ClientRefused);
  });

  it("subscribes, answering the channel endpoint", async () => {
    hubOnly();

    expect(await subscribe((await client()).hub, HUB_TOKEN)).toBe(CHANNEL_ENDPOINT);
  });

  it("refuses a channel endpoint off loopback", async () => {
    hubOnly({
      subscription: { body: { "hub.channel.endpoint": "ws://localhost:8103/ws" }, status: 202 },
    });

    await expect(subscribe((await client()).hub, HUB_TOKEN)).rejects.toBeInstanceOf(HubFailure);
  });
});

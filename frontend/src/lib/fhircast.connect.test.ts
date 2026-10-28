import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CLIENT_ID,
  fakeHub,
  HUB_TOKEN,
  HUB_URL,
  type HubAnswer,
  hubOnly,
  type HubOptions,
  REGISTRATION_ACCESS_TOKEN,
  REGISTRATION_CLIENT_URI,
  REGISTRATION_ENDPOINT,
  REVOCATION_ENDPOINT,
  TOKEN_ENDPOINT,
} from "./fake-hub.testHelpers";
import { fakeBackend } from "./fake-backend.testHelpers";
import { clearFhircastStorage, connectFhircast, FHIRCAST_STORAGE_KEYS } from "./fhircast";
import {
  assertionOf,
  keptState,
  RELEASE,
  signedByRegisteredKey,
  SMART_ACCESS_TOKEN,
} from "./fhircast.testHelpers";

// `connectFhircast`, step by step: discovery, registration, the Hub Token,
// the subscription, event notifications, a hub out of reach, and what the tab
// keeps of a connection.

const JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";

/** The keys an earlier version of the module kept its connection under, one each. */
const RETIRED_KEYS = [
  "fhircast-client-id",
  "fhircast-hub-token",
  "fhircast-private-jwk",
  "fhircast-smart-access-token",
];

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

  describe("event notifications", () => {
    /** A notification as the hub sends it: `event` for Patient `patientId` on `topic`. */
    const notification = (
      event: string,
      { id = "event-1", patientId = "patient-1" as unknown, topic = "topic-1" } = {},
    ) => ({
      event: {
        context: [{ key: "patient", resource: { id: patientId, resourceType: "Patient" } }],
        "hub.event": event,
        "hub.topic": topic,
      },
      id,
      timestamp: "2026-10-24T02:00:05Z",
    });

    /** Connected to a hub, every event it hands on recorded. */
    async function connected() {
      const hub = hubOnly();
      const events = vi.fn();
      const outcome = await connectFhircast(RELEASE, { onEvent: events });
      expect(outcome.kind).toBe("connected");
      return { events, hub };
    }

    it("hands on a Patient-open with its timestamp and Patient id", async () => {
      const { events, hub } = await connected();

      hub.notify(notification("Patient-open"));

      expect(events.mock.calls).toEqual([
        [{ event: "Patient-open", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" }],
      ]);
    });

    it("hands on a Patient-close", async () => {
      const { events, hub } = await connected();

      hub.notify(notification("Patient-close"));

      expect(events.mock.calls).toEqual([
        [{ event: "Patient-close", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" }],
      ]);
    });

    it.each(["Patient-open", "Patient-close"])(
      "acknowledges a %s on the socket with its id",
      async (event) => {
        const { hub } = await connected();

        hub.notify(notification(event, { id: "event-7" }));

        expect(hub.received().map((sent) => JSON.parse(sent) as unknown)).toEqual([
          { id: "event-7", status: "200" },
        ]);
      },
    );

    it("hands on every event, in the order the hub sent them", async () => {
      const { events, hub } = await connected();

      hub.notify(notification("Patient-open", { id: "event-1" }));
      hub.notify(notification("Patient-close", { id: "event-2" }));

      expect(events.mock.calls.map(([handed]) => (handed as { event: string }).event)).toEqual([
        "Patient-open",
        "Patient-close",
      ]);
    });

    it("reads the event name whatever its case", async () => {
      const { events, hub } = await connected();

      hub.notify(notification("pATIENT-oPEN"));

      expect(events.mock.calls[0]).toEqual([
        { event: "Patient-open", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" },
      ]);
    });

    it("takes the Patient id from the context's patient entry", async () => {
      const { events, hub } = await connected();
      const open = notification("Patient-open");

      hub.notify({
        ...open,
        event: {
          ...open.event,
          context: [
            { key: "encounter", resource: { id: "encounter-1", resourceType: "Encounter" } },
            ...open.event.context,
          ],
        },
      });

      expect(events.mock.calls[0]).toEqual([
        { event: "Patient-open", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" },
      ]);
    });

    it.each([
      ["the grammar's every character", "Ab-9.z"],
      ["sixty-four characters", "a".repeat(64)],
    ])("hands on a Patient id of %s", async (_name, patientId) => {
      const { events, hub } = await connected();

      hub.notify(notification("Patient-open", { patientId }));

      expect(events.mock.calls[0]).toEqual([
        { event: "Patient-open", patientId, timestamp: "2026-10-24T02:00:05Z" },
      ]);
    });

    describe("a Patient id outside the grammar", () => {
      it.each<[string, unknown]>([
        ["sixty-five characters", "a".repeat(65)],
        ["empty", ""],
        ["holding a slash", "patient/1"],
        ["holding markup", "<b>Tan</b>"],
        ["holding an underscore", "patient_1"],
        ["a number", 7],
      ])("is omitted when it is %s, the event still handed on", async (_name, patientId) => {
        const { events, hub } = await connected();

        hub.notify(notification("Patient-open", { patientId }));

        expect(events.mock.calls).toEqual([
          [{ event: "Patient-open", patientId: null, timestamp: "2026-10-24T02:00:05Z" }],
        ]);
      });

      it("is still acknowledged", async () => {
        const { hub } = await connected();

        hub.notify(notification("Patient-open", { id: "event-3", patientId: "patient/1" }));

        expect(hub.received().map((sent) => JSON.parse(sent) as unknown)).toEqual([
          { id: "event-3", status: "200" },
        ]);
      });

      it.each<[string, unknown[]]>([
        ["names no patient", [{ key: "encounter", resource: { id: "encounter-1" } }]],
        ["is empty", []],
        ["has a patient entry with no resource", [{ key: "patient" }]],
      ])("is omitted when the context %s", async (_name, context) => {
        const { events, hub } = await connected();
        const open = notification("Patient-open");

        hub.notify({ ...open, event: { ...open.event, context } });

        expect(events.mock.calls).toEqual([
          [{ event: "Patient-open", patientId: null, timestamp: "2026-10-24T02:00:05Z" }],
        ]);
      });
    });

    describe("what is ignored, and not acknowledged", () => {
      const open = notification("Patient-open");
      const without = (key: string) =>
        Object.fromEntries(Object.entries(open).filter(([member]) => member !== key));
      const eventWithout = (key: string) => ({
        ...open,
        event: Object.fromEntries(Object.entries(open.event).filter(([member]) => member !== key)),
      });

      it.each<[string, unknown]>([
        ["no JSON", "not json"],
        ["JSON null", "null"],
        ["a JSON list", JSON.stringify([open])],
        ["no id", without("id")],
        ["an empty id", { ...open, id: "" }],
        ["an id that is no string", { ...open, id: 7 }],
        ["no timestamp", without("timestamp")],
        ["a timestamp that is no time", { ...open, timestamp: "yesterday" }],
        ["no event", without("event")],
        ["an event that is no object", { ...open, event: "Patient-open" }],
        ["no hub.topic", eventWithout("hub.topic")],
        ["no hub.event", eventWithout("hub.event")],
        ["no context", eventWithout("context")],
        ["a context that is no list", { ...open, event: { ...open.event, context: {} } }],
        ["the subscription confirmed again", { "hub.mode": "subscribe", "hub.topic": "topic-1" }],
        ["another topic", notification("Patient-open", { topic: "topic-2" })],
        ["the topic in another case", notification("Patient-open", { topic: "TOPIC-1" })],
        ["an event not subscribed to", notification("Encounter-open")],
        ["the hub's heartbeat", notification("heartbeat")],
      ])("a message with %s", async (_name, message) => {
        const { events, hub } = await connected();

        hub.notify(message);

        expect([events.mock.calls, hub.received()]).toEqual([[], []]);
      });
    });

    it("hands on nothing the hub sends before it confirms the subscription", async () => {
      let confirm = () => undefined as void;
      const hub = hubOnly({ hold: new Promise((resolve) => (confirm = resolve)) });
      const events = vi.fn();
      const outcome = connectFhircast(RELEASE, { onEvent: events });
      await vi.waitFor(() => expect(hub.sockets()).toHaveLength(1));

      hub.notify(notification("Patient-open"));
      confirm();
      await outcome;

      expect([events.mock.calls, hub.received()]).toEqual([[], []]);
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

  describe("what the tab keeps", () => {
    it("never keeps the SMART access token", async () => {
      hubOnly();

      await connectFhircast(RELEASE);

      expect(
        Object.keys(window.sessionStorage).filter((key) =>
          window.sessionStorage.getItem(key)?.includes(SMART_ACCESS_TOKEN),
        ),
      ).toEqual([]);
    });

    describe("on the clock", () => {
      const CONNECTED_AT = Date.UTC(2026, 9, 24, 2, 0, 0);

      beforeEach(() => {
        vi.useFakeTimers({ toFake: ["Date"] });
        vi.setSystemTime(CONNECTED_AT);
      });

      afterEach(() => {
        vi.useRealTimers();
      });

      it("keeps the registration, and the subscription with when its Hub Token and lease run out", async () => {
        hubOnly({ leaseSeconds: 600 });

        await connectFhircast(RELEASE);

        expect(keptState()).toMatchObject({
          clientId: CLIENT_ID,
          hubTopic: "topic-1",
          hubUrl: HUB_URL,
          registration: { token: REGISTRATION_ACCESS_TOKEN, uri: REGISTRATION_CLIENT_URI },
          revocationEndpoint: REVOCATION_ENDPOINT,
          subscription: {
            endpoint: CHANNEL_ENDPOINT,
            hubToken: HUB_TOKEN,
            hubTokenExpiresAt: CONNECTED_AT + 3_600_000,
            leaseExpiresAt: CONNECTED_AT + 600_000,
          },
          tokenEndpoint: TOKEN_ENDPOINT,
        });
      });

      it("keeps no expiry the hub did not state", async () => {
        hubOnly({ token: { body: { access_token: HUB_TOKEN }, status: 200 } });

        await connectFhircast(RELEASE);

        expect(keptState()).toMatchObject({
          subscription: { hubTokenExpiresAt: null, leaseExpiresAt: null },
        });
      });
    });

    it("keeps the private key it registered, under the kid it registered", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      const registered = JSON.parse(hub.requestTo(REGISTRATION_ENDPOINT).body) as {
        jwks: { keys: { kid: string; x: string }[] };
      };
      const { keyId, signingJwk } = keptState() as {
        keyId: string;
        signingJwk: JsonWebKey;
      };
      expect([keyId, signingJwk.kty, signingJwk.crv, signingJwk.x, typeof signingJwk.d]).toEqual([
        registered.jwks.keys[0].kid,
        "EC",
        "P-384",
        registered.jwks.keys[0].x,
        "string",
      ]);
    });

    it("keeps the registration before the hub grants a Hub Token, with no subscription", async () => {
      hubOnly({ token: { status: 400 } });

      await connectFhircast(RELEASE);

      expect(keptState()).toMatchObject({ clientId: CLIENT_ID, subscription: null });
    });

    it("keeps no registration the hub named no way to delete", async () => {
      hubOnly({ registration: { body: { client_id: CLIENT_ID }, status: 201 } });

      await connectFhircast(RELEASE);

      expect(keptState()).toMatchObject({ registration: null });
    });

    it("forgets it when cleared, and what an earlier version kept under keys of its own", async () => {
      hubOnly();
      await connectFhircast(RELEASE);
      for (const key of RETIRED_KEYS) window.sessionStorage.setItem(key, "kept");

      clearFhircastStorage();

      expect(
        [...Object.values(FHIRCAST_STORAGE_KEYS), ...RETIRED_KEYS].map((key) =>
          window.sessionStorage.getItem(key),
        ),
      ).toEqual([null, null, null, null, null]);
    });
  });
});

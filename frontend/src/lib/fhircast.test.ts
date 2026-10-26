import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CLIENT_ID,
  type FakeHub,
  fakeHub,
  HUB_TOKEN,
  HUB_URL,
  type HubAnswer,
  hubOnly,
  type HubRequest,
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
import { fakeBackend } from "./fake-backend.testHelpers";
import {
  clearFhircastStorage,
  connectFhircast,
  decodeFhircastRelease,
  endFhircast,
  FHIRCAST_STORAGE_KEYS,
  fhircastSnapshot,
  type FhircastRelease,
  followFhircastEvents,
  retryFhircast,
  startFhircast,
  watchFhircast,
} from "./fhircast";

const SMART_ACCESS_TOKEN = `smart-${crypto.randomUUID()}`;

/** The real `setTimeout`, taken before any test fakes the clock. */
const realSetTimeout = globalThis.setTimeout;

/** How long {@link eventually} retries, on the real clock: WebCrypto signs off the main thread. */
const EVENTUALLY_MS = 5_000;

/**
 * Retries `check` on the real clock until it passes. Unlike `vi.waitFor`,
 * which moves a fake clock on between tries, it leaves the fake clock where
 * it is, so a test times the hub's steps from exactly where it put the clock.
 */
async function eventually(check: () => void): Promise<void> {
  const giveUpAt = performance.now() + EVENTUALLY_MS;
  for (;;) {
    try {
      check();
      return;
    } catch (failure) {
      if (performance.now() > giveUpAt) throw failure;
    }
    await new Promise((resolve) => realSetTimeout(resolve, 5));
  }
}

/**
 * How long {@link throughout} watches, on the real clock: long enough for a
 * step the page has begun to sign, and so to reach the hub.
 */
const THROUGHOUT_MS = 500;

/**
 * Holds `check` to passing for a while on the real clock, leaving the fake
 * clock where it is: what a test asserts never happens is given the time a
 * signed step takes to happen, rather than checked before it could.
 */
async function throughout(check: () => void): Promise<void> {
  const stopAt = performance.now() + THROUGHOUT_MS;
  while (performance.now() < stopAt) {
    check();
    await new Promise((resolve) => realSetTimeout(resolve, 5));
  }
  check();
}

const RELEASE: FhircastRelease = {
  accessToken: SMART_ACCESS_TOKEN,
  expiresAt: "2026-10-24T10:00:00Z",
  hubTopic: "topic-1",
  hubUrl: HUB_URL,
  scope: ["fhircast/Patient-open.read", "openid"],
};

const JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";

/** What the FHIRcast module keeps in the tab, parsed: `null` for nothing. */
const keptState = (): Record<string, unknown> | null =>
  JSON.parse(window.sessionStorage.getItem(FHIRCAST_STORAGE_KEYS.connection) ?? "null") as Record<
    string,
    unknown
  > | null;

/** The keys an earlier version of the module kept its connection under, one each. */
const RETIRED_KEYS = [
  "fhircast-client-id",
  "fhircast-hub-token",
  "fhircast-private-jwk",
  "fhircast-smart-access-token",
];

/** Each `hub.mode` request `hub` was sent at the hub URL in `mode`, in order. */
const modeAt = (hub: FakeHub, mode: "subscribe" | "unsubscribe") =>
  hub.requestsTo(HUB_URL).filter(({ body }) => new URLSearchParams(body).get("hub.mode") === mode);

/** Each subscription `hub` was asked for, in order. */
const subscriptionsAt = (hub: FakeHub) => modeAt(hub, "subscribe");

const decodeSegment = (segment: string): Record<string, unknown> =>
  JSON.parse(atob(segment.replace(/-/g, "+").replace(/_/g, "/"))) as Record<string, unknown>;

/** The assertion the token request carried, split into its header, claims and signature. */
function assertionOf(body: string) {
  const assertion = new URLSearchParams(body).get("assertion") ?? "";
  const [header, claims, signature] = assertion.split(".");
  return { claims: decodeSegment(claims), header: decodeSegment(header), signature, assertion };
}

/**
 * Whether the assertion `body` carries, under `parameter`, is signed by the
 * key the page registered at `hub`.
 */
async function signedByRegisteredKey(
  hub: FakeHub,
  body: string,
  parameter = "assertion",
): Promise<boolean> {
  const assertion = new URLSearchParams(body).get(parameter) ?? "";
  const metadata = JSON.parse(hub.requestTo(REGISTRATION_ENDPOINT).body) as {
    jwks: { keys: JsonWebKey[] };
  };
  const key = await crypto.subtle.importKey(
    "jwk",
    metadata.jwks.keys[0],
    { name: "ECDSA", namedCurve: "P-384" },
    false,
    ["verify"],
  );
  const signed = assertion.slice(0, assertion.lastIndexOf("."));
  const signature = assertion.slice(assertion.lastIndexOf(".") + 1);
  const bytes = Uint8Array.from(atob(signature.replace(/-/g, "+").replace(/_/g, "/")), (char) =>
    char.charCodeAt(0),
  );
  return crypto.subtle.verify(
    { hash: "SHA-384", name: "ECDSA" },
    key,
    bytes,
    new TextEncoder().encode(signed),
  );
}

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

describe("connectFhircast, staying connected", () => {
  /**
   * When the fake clock starts: a whole second, so that a time read within
   * the second it takes to connect falls in that same second.
   */
  const CONNECTED_AT = Date.UTC(2026, 9, 24, 2, 0, 0);

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout", "Date"] });
    vi.setSystemTime(CONNECTED_AT);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** Connected to a hub answering `options`, on the fake clock, with what it reports recorded. */
  async function connectedOnTheClock(options: HubOptions = {}) {
    const hub = hubOnly(options);
    const reported = vi.fn();
    const events = vi.fn();
    const connecting = connectFhircast(RELEASE, { onConnection: reported, onEvent: events });
    await eventually(() => expect(hub.sockets()).toHaveLength(1));
    await vi.advanceTimersByTimeAsync(0);
    const outcome = await connecting;
    expect(outcome.kind).toBe("connected");
    return { events, hub, outcome, reported };
  }

  /** A Patient-open on the session's topic, and the event it is handed on as. */
  const OPEN = {
    event: {
      context: [{ key: "patient", resource: { id: "patient-1", resourceType: "Patient" } }],
      "hub.event": "Patient-open",
      "hub.topic": "topic-1",
    },
    id: "event-1",
    timestamp: "2026-10-24T02:00:05Z",
  };
  const HANDED_OPEN = {
    event: "Patient-open",
    patientId: "patient-1",
    timestamp: "2026-10-24T02:00:05Z",
  };

  /** A hub answer held back until the test gives it: what a step under way waits on. */
  function held() {
    let give: (answer: HubAnswer) => void = () => undefined;
    const answer = new Promise<HubAnswer>((resolve) => (give = resolve));
    return { answer, give: (given: HubAnswer) => give(given) };
  }

  /** A token answer: a Hub Token lasting `seconds`. */
  const lasting = (seconds: number): HubAnswer => ({
    body: { access_token: `hub-${seconds}`, expires_in: seconds, token_type: "Bearer" },
    status: 200,
  });

  describe("renewal", () => {
    it.each<[string, HubOptions, number]>([
      ["80% into an hour's Hub Token", { token: lasting(3600) }, 2_880_000],
      ["60 s before a 200 s Hub Token expires", { token: lasting(200) }, 140_000],
      ["80% into an hour's lease", { leaseSeconds: 3600, token: lasting(7200) }, 2_880_000],
      ["60 s before a 200 s lease expires", { leaseSeconds: 200, token: lasting(3600) }, 140_000],
      ["60 s before a 100 s Hub Token expires", { token: lasting(100) }, 40_000],
      ["60 s before a 100 s lease expires", { leaseSeconds: 100, token: lasting(3600) }, 40_000],
      [
        "5 s into a Hub Token of a minute, which has no time 60 s before it ends",
        { token: lasting(60) },
        5_000,
      ],
      [
        "5 s into a lease of a minute, which has no time 60 s before it ends",
        { leaseSeconds: 60, token: lasting(3600) },
        5_000,
      ],
    ])("starts %s, and not before", async (_name, options, at) => {
      const { hub } = await connectedOnTheClock(options);
      const minted = () => hub.requestsTo(TOKEN_ENDPOINT).length;

      await vi.advanceTimersByTimeAsync(at - 1);
      expect(minted()).toBe(1);
      await vi.advanceTimersByTimeAsync(1);
      await eventually(() => expect([minted(), hub.sockets().length]).toEqual([2, 2]));

      // Renewal signs its assertion the moment it starts.
      const { iat } = assertionOf(hub.requestsTo(TOKEN_ENDPOINT)[1].body).claims;
      expect(iat).toBe((CONNECTED_AT + at) / 1000);
    });

    it.each<[string, unknown]>([
      ["names none", undefined],
      ["is zero", 0],
      ["is negative", -200],
      ["is no number", "200"],
    ])("never starts by a Hub Token lifetime that %s", async (_name, lifetime) => {
      const { hub } = await connectedOnTheClock({
        token: { body: { access_token: HUB_TOKEN, expires_in: lifetime }, status: 200 },
      });

      await vi.advanceTimersByTimeAsync(3_000_000_000);

      await throughout(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(1));
    });

    it("never starts by a lease that is no number", async () => {
      const { hub } = await connectedOnTheClock({
        leaseSeconds: "200" as unknown as number,
        token: { body: { access_token: HUB_TOKEN }, status: 200 },
      });

      await vi.advanceTimersByTimeAsync(3_000_000_000);

      await throughout(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(1));
    });

    it("waits out a lifetime longer than a browser timer holds", async () => {
      const { hub } = await connectedOnTheClock({ token: lasting(10_000_000) });

      await vi.advanceTimersByTimeAsync(2_147_483_646);

      await throughout(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(1));
    });

    /** Moves the clock `ms` on, then lets the hub confirm the socket the page has opened by then. */
    async function renewedAfter(ms: number, hub: FakeHub, sockets: number) {
      await vi.advanceTimersByTimeAsync(ms);
      await eventually(() => expect(hub.sockets()).toHaveLength(sockets));
      await vi.advanceTimersByTimeAsync(0);
    }

    it("mints every renewal's Hub Token with an assertion of a jti of its own", async () => {
      const { hub } = await connectedOnTheClock({ token: lasting(200) });

      await renewedAfter(140_000, hub, 2);
      await renewedAfter(140_000, hub, 3);

      const jtis = hub
        .requestsTo(TOKEN_ENDPOINT)
        .map((request) => assertionOf(request.body).claims.jti);
      expect(new Set(jtis).size).toBe(3);
    });

    it("signs a renewal's fresh five-minute assertion with the key and client it registered", async () => {
      const { hub } = await connectedOnTheClock({ token: lasting(200) });

      await renewedAfter(140_000, hub, 2);

      const { claims, header } = assertionOf(hub.requestsTo(TOKEN_ENDPOINT)[1].body);
      const registered = JSON.parse(hub.requestTo(REGISTRATION_ENDPOINT).body) as {
        jwks: { keys: { kid: string }[] };
      };
      expect([
        header.kid,
        claims.iss,
        (claims.exp as number) - (claims.iat as number),
        hub.requestsTo(REGISTRATION_ENDPOINT).length,
      ]).toEqual([registered.jwks.keys[0].kid, CLIENT_ID, 300, 1]);
    });

    it("subscribes again with the renewed Hub Token", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await renewedAfter(140_000, hub, 2);

      expect(subscriptionsAt(hub).map(({ authorization }) => authorization)).toEqual([
        `Bearer ${HUB_TOKEN}`,
        `Bearer ${HUB_TOKEN}-2`,
      ]);
    });

    it("moves onto the renewed subscription's socket, closing the one before", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await renewedAfter(140_000, hub, 2);

      expect([hub.sockets(), hub.openSockets()]).toEqual([
        [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT.replace(/1$/, "2")],
        1,
      ]);
    });

    it("keeps the renewed subscription in sessionStorage, and only that", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await renewedAfter(140_000, hub, 2);

      expect(keptState()?.subscription).toEqual({
        endpoint: CHANNEL_ENDPOINT.replace(/1$/, "2"),
        hubToken: `${HUB_TOKEN}-2`,
        hubTokenExpiresAt: CONNECTED_AT + 140_000 + 3_600_000,
        leaseExpiresAt: CONNECTED_AT + 140_000 + 200_000,
      });
    });

    it("renews unseen, reporting nothing", async () => {
      const { hub, reported } = await connectedOnTheClock({ leaseSeconds: 200 });

      await renewedAfter(140_000, hub, 2);

      expect(reported.mock.calls).toEqual([]);
    });

    it("hands on events from the renewed socket", async () => {
      const { events, hub } = await connectedOnTheClock({ leaseSeconds: 200 });
      await renewedAfter(140_000, hub, 2);

      hub.notify(OPEN);

      expect(events.mock.calls).toEqual([[HANDED_OPEN]]);
    });

    it("reconnects a renewed connection the hub then drops", async () => {
      const { hub, reported } = await connectedOnTheClock({ leaseSeconds: 200 });
      await renewedAfter(140_000, hub, 2);

      hub.dropSockets();

      expect(reported.mock.calls).toEqual([["reconnecting"]]);
    });

    it("leaves a socket the hub drops mid-renewal to the renewal", async () => {
      const renewal = held();
      const { hub, reported } = await connectedOnTheClock({
        token: [lasting(200), renewal.answer],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(2));

      hub.dropSockets();
      renewal.give(lasting(200));
      await eventually(() => expect(hub.sockets()).toHaveLength(2));
      await vi.advanceTimersByTimeAsync(0);

      expect([reported.mock.calls, hub.openSockets()]).toEqual([[], 1]);
    });

    it.each<[string, HubOptions]>([
      ["the hub refuses its Hub Token", { token: [lasting(200), { status: 400 }] }],
      ["its socket closes unconfirmed", { socket: ["confirms", "closes"], token: lasting(200) }],
    ])("falls back to reconnecting when %s", async (_name, options) => {
      const { hub, reported } = await connectedOnTheClock(options);

      await vi.advanceTimersByTimeAsync(140_000);

      await eventually(() => expect(reported.mock.calls).toEqual([["reconnecting"]]));
      expect(hub.openSockets()).toBe(0);
    });

    it("retries a failed renewal by subscribing anew", async () => {
      const { hub, reported } = await connectedOnTheClock({
        token: [lasting(200), { status: 400 }, lasting(200)],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(reported).toHaveBeenCalledWith("reconnecting"));

      await renewedAfter(1_000, hub, 2);

      expect([reported.mock.calls, hub.sockets()]).toEqual([
        [["reconnecting"], ["connected"]],
        [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT.replace(/1$/, "2")],
      ]);
    });

    it("renews no more once closed", async () => {
      const { hub, outcome } = await connectedOnTheClock({ token: lasting(200) });

      if (outcome.kind === "connected") outcome.close();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(1);
    });

    it("keeps nothing a renewal under way is granted once closed", async () => {
      const renewal = held();
      const { hub, outcome } = await connectedOnTheClock({
        token: [lasting(200), renewal.answer],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(2));

      if (outcome.kind === "connected") outcome.close();
      renewal.give({
        body: { access_token: `${HUB_TOKEN}-renewed`, expires_in: 200 },
        status: 200,
      });
      await vi.advanceTimersByTimeAsync(0);

      expect([
        (keptState()?.subscription as { hubToken: string }).hubToken,
        hub.requestsTo(HUB_URL).length,
      ]).toEqual(["hub-200", 1]);
    });
  });

  describe("reconnecting", () => {
    /** Connected, then dropped by the hub. */
    async function dropped(options: HubOptions = {}) {
      const connected = await connectedOnTheClock(options);
      connected.hub.dropSockets();
      return connected;
    }

    it("reopens the endpoint a second after the hub drops the socket, and not before", async () => {
      const { hub } = await dropped();

      await vi.advanceTimersByTimeAsync(999);
      expect(hub.sockets()).toHaveLength(1);
      await vi.advanceTimersByTimeAsync(1);

      expect(hub.sockets()).toEqual([CHANNEL_ENDPOINT, CHANNEL_ENDPOINT]);
    });

    it("reports connected once the reopened socket is confirmed", async () => {
      const { reported } = await dropped();

      await vi.advanceTimersByTimeAsync(1_000);

      expect(reported.mock.calls).toEqual([["reconnecting"], ["connected"]]);
    });

    it("hands on events from the reopened socket", async () => {
      const { events, hub } = await dropped();
      await vi.advanceTimersByTimeAsync(1_000);

      hub.notify(OPEN);

      expect(events.mock.calls).toEqual([[HANDED_OPEN]]);
    });

    it("spaces its retries by a backoff that doubles", async () => {
      const { hub } = await dropped({ socket: ["confirms", "closes"] });
      const opened: number[] = [];

      for (const backoff of [1_000, 2_000, 4_000, 8_000, 16_000]) {
        await vi.advanceTimersByTimeAsync(backoff - 1);
        opened.push(hub.sockets().length - 1);
        await vi.advanceTimersByTimeAsync(1);
        opened.push(hub.sockets().length - 1);
      }

      expect(opened).toEqual([0, 1, 1, 2, 2, 3, 3, 4, 4, 5]);
    });

    it("gives up after five retries, reporting disconnected", async () => {
      const { hub, reported } = await dropped({ socket: ["confirms", "closes"] });

      await vi.advanceTimersByTimeAsync(7_200_000);

      expect([hub.sockets().length - 1, reported.mock.calls]).toEqual([
        5,
        [["reconnecting"], ["disconnected"]],
      ]);
    });

    it("counts its retries afresh once a retry has connected", async () => {
      const { hub, reported } = await dropped({
        socket: ["confirms", "closes", "confirms", "closes"],
      });
      await vi.advanceTimersByTimeAsync(3_000);

      hub.dropSockets();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect([hub.sockets().length, reported.mock.calls]).toEqual([
        8,
        [["reconnecting"], ["connected"], ["reconnecting"], ["disconnected"]],
      ]);
    });

    it("subscribes anew when the hub says the endpoint is gone", async () => {
      const { hub } = await dropped({ socket: ["confirms", "denies", "confirms"] });

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(3));
      await vi.advanceTimersByTimeAsync(0);

      expect([hub.sockets(), hub.openSockets()]).toEqual([
        [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT, CHANNEL_ENDPOINT.replace(/1$/, "2")],
        1,
      ]);
    });

    it("subscribes anew with a fresh Hub Token", async () => {
      const { hub } = await dropped({ socket: ["confirms", "denies", "confirms"] });

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(subscriptionsAt(hub)).toHaveLength(2));

      await eventually(() => expect(hub.sockets()).toHaveLength(3));

      expect(subscriptionsAt(hub)[1].authorization).toBe(`Bearer ${HUB_TOKEN}-2`);
    });

    it("reports connected once the new subscription's socket is confirmed", async () => {
      const { hub, reported } = await dropped({ socket: ["confirms", "denies", "confirms"] });

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(3));
      await vi.advanceTimersByTimeAsync(0);

      expect(reported.mock.calls).toEqual([["reconnecting"], ["connected"]]);
    });

    it("takes the next retry when subscribing anew fails", async () => {
      const { hub, reported } = await dropped({
        socket: ["confirms", "denies"],
        token: [lasting(3600), { status: 400 }],
      });

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(2));
      await vi.advanceTimersByTimeAsync(2_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(3));

      expect([hub.sockets().length, reported.mock.calls]).toEqual([2, [["reconnecting"]]]);
    });

    it("retries no more once closed", async () => {
      const { hub, outcome } = await dropped();

      if (outcome.kind === "connected") outcome.close();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect(hub.sockets()).toHaveLength(1);
    });

    it("closes a socket its retry opens once closed", async () => {
      let confirm = () => undefined as void;
      const { hub, outcome, reported } = await dropped({
        hold: [null, new Promise((resolve) => (confirm = resolve))],
      });
      await vi.advanceTimersByTimeAsync(1_000);
      expect(hub.sockets()).toHaveLength(2);

      if (outcome.kind === "connected") outcome.close();
      confirm();
      await vi.advanceTimersByTimeAsync(7_200_000);

      expect([hub.openSockets(), hub.sockets().length, reported.mock.calls]).toEqual([
        0,
        2,
        [["reconnecting"]],
      ]);
    });

    it("takes nothing but the hub's denial for the endpoint gone", async () => {
      const { hub } = await dropped({ socket: ["confirms", "says-otherwise"] });

      await vi.advanceTimersByTimeAsync(1_000);

      await throughout(() =>
        expect([hub.sockets().length, hub.requestsTo(HUB_URL).length]).toEqual([2, 1]),
      );
    });

    it("reopens the new subscription's endpoint when that socket drops in turn", async () => {
      const { hub } = await dropped({ socket: ["confirms", "denies", "confirms"] });
      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(3));
      await vi.advanceTimersByTimeAsync(0);

      hub.dropSockets();
      await vi.advanceTimersByTimeAsync(1_000);

      expect(hub.sockets().slice(2)).toEqual([
        CHANNEL_ENDPOINT.replace(/1$/, "2"),
        CHANNEL_ENDPOINT.replace(/1$/, "2"),
      ]);
    });

    it("drops the renewal that was due once the socket drops", async () => {
      const { hub } = await dropped({ socket: ["confirms", "closes"], token: lasting(200) });

      await vi.advanceTimersByTimeAsync(200_000);

      expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(1);
    });

    it("reconnects when the socket drops mid-renewal and the renewal then fails", async () => {
      const renewal = held();
      const { hub, reported } = await connectedOnTheClock({
        token: [lasting(200), renewal.answer],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(2));

      hub.dropSockets();
      renewal.give({ status: 400 });

      await eventually(() => expect(reported.mock.calls).toEqual([["reconnecting"]]));
    });

    it("reports no connection won back while retrying a failed renewal fails", async () => {
      const { hub, reported } = await connectedOnTheClock({
        token: [lasting(200), { status: 400 }],
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(reported).toHaveBeenCalledWith("reconnecting"));

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(3));
      await vi.advanceTimersByTimeAsync(0);

      expect(reported.mock.calls).toEqual([["reconnecting"]]);
    });
  });
  describe("one live subscription", () => {
    const SECOND_ENDPOINT = CHANNEL_ENDPOINT.replace(/1$/, "2");

    /** Moves the clock `ms` on, then lets the hub confirm the socket the page has opened by then. */
    async function movedOnAfter(ms: number, hub: FakeHub, sockets: number) {
      await vi.advanceTimersByTimeAsync(ms);
      await eventually(() => expect(hub.sockets()).toHaveLength(sockets));
      await vi.advanceTimersByTimeAsync(0);
    }

    /** Each unsubscription the hub was sent: its endpoint, and its Bearer. */
    const unsubscriptions = (hub: FakeHub) =>
      modeAt(hub, "unsubscribe").map(({ authorization, body }) => [
        new URLSearchParams(body).get("hub.channel.endpoint"),
        authorization,
      ]);

    /**
     * Ends the session's FHIRcast on the fake clock, moving it on until the
     * teardown settles: a hub that never answers holds each step its 3 s.
     */
    async function endedOnTheClock() {
      let settled = false;
      void endFhircast().then(() => (settled = true));
      while (!settled) {
        await vi.advanceTimersByTimeAsync(3_000);
        await new Promise((resolve) => realSetTimeout(resolve, 5));
      }
    }

    /** Each Hub Token the hub was asked to revoke. */
    const revoked = (hub: FakeHub) =>
      hub.requestsTo(REVOCATION_ENDPOINT).map(({ body }) => new URLSearchParams(body).get("token"));

    it("unsubscribes the endpoint a renewal moved off, with the Hub Token it was made with", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });

      await movedOnAfter(140_000, hub, 2);

      await eventually(() =>
        expect(unsubscriptions(hub)).toEqual([[CHANNEL_ENDPOINT, `Bearer ${HUB_TOKEN}`]]),
      );
    });

    it("then revokes that Hub Token, and nothing else", async () => {
      const { hub } = await connectedOnTheClock({ leaseSeconds: 200 });
      const since = requestsSince(hub);

      await movedOnAfter(140_000, hub, 2);

      await eventually(() =>
        expect([since().map(({ method, url }) => `${method} ${url}`), revoked(hub)]).toEqual([
          [
            `POST ${TOKEN_ENDPOINT}`,
            `POST ${HUB_URL}`,
            `POST ${HUB_URL}`,
            `POST ${REVOCATION_ENDPOINT}`,
          ],
          [HUB_TOKEN],
        ]),
      );
    });

    it("cancels nothing until the new subscription's socket is confirmed", async () => {
      let confirm = () => undefined as void;
      const { hub } = await connectedOnTheClock({
        hold: [null, new Promise((resolve) => (confirm = resolve))],
        leaseSeconds: 200,
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(2));

      await throughout(() => expect(unsubscriptions(hub)).toEqual([]));
      confirm();

      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));
    });

    it("cancels the subscription a resubscribe moved off once the hub denied its endpoint", async () => {
      const { hub } = await connectedOnTheClock({
        socket: ["confirms", "denies", "confirms"],
      });
      hub.dropSockets();

      await vi.advanceTimersByTimeAsync(1_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(3));
      await vi.advanceTimersByTimeAsync(0);

      await eventually(() =>
        expect([unsubscriptions(hub), revoked(hub)]).toEqual([
          [[CHANNEL_ENDPOINT, `Bearer ${HUB_TOKEN}`]],
          [HUB_TOKEN],
        ]),
      );
    });

    it("cancels nothing when it reopens the same subscription after a drop", async () => {
      const { hub, reported } = await connectedOnTheClock();
      hub.dropSockets();

      await vi.advanceTimersByTimeAsync(1_000);
      expect(reported.mock.calls).toEqual([["reconnecting"], ["connected"]]);

      await throughout(() => expect([unsubscriptions(hub), revoked(hub)]).toEqual([[], []]));
    });

    it("revokes the Hub Token, but unsubscribes nothing, when the renewal is answered the same endpoint", async () => {
      const { hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        subscription: {
          body: { "hub.channel.endpoint": CHANNEL_ENDPOINT },
          status: 202,
        },
      });

      await movedOnAfter(140_000, hub, 2);

      await eventually(() => expect(revoked(hub)).toEqual([HUB_TOKEN]));
      expect(unsubscriptions(hub)).toEqual([]);
    });

    it("only unsubscribes for a hub that names no revocation endpoint", async () => {
      const { hub } = await connectedOnTheClock({
        fhircastConfiguration: {
          body: {
            registration_endpoint: REGISTRATION_ENDPOINT,
            token_endpoint: TOKEN_ENDPOINT,
          },
          status: 200,
        },
        leaseSeconds: 200,
      });

      const fetchedBefore = vi.mocked(fetch).mock.calls.length;

      await movedOnAfter(140_000, hub, 2);

      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));
      // The renewal's Hub Token and subscription, then the unsubscription, and nothing anywhere else.
      await throughout(() =>
        expect(
          vi
            .mocked(fetch)
            .mock.calls.slice(fetchedBefore)
            .map(([url]) => String(url)),
        ).toEqual([TOKEN_ENDPOINT, HUB_URL, HUB_URL]),
      );
    });

    it("cancels both the subscription last confirmed and the one still opening when the session ends", async () => {
      let confirm = () => undefined as void;
      const { hub } = await connectedOnTheClock({
        hold: [null, new Promise((resolve) => (confirm = resolve))],
        leaseSeconds: 200,
      });
      await vi.advanceTimersByTimeAsync(140_000);
      await eventually(() => expect(hub.sockets()).toHaveLength(2));

      await endFhircast();
      confirm();

      expect([unsubscriptions(hub), revoked(hub)]).toEqual([
        [
          [CHANNEL_ENDPOINT, `Bearer ${HUB_TOKEN}`],
          [SECOND_ENDPOINT, `Bearer ${HUB_TOKEN}-2`],
        ],
        [HUB_TOKEN, `${HUB_TOKEN}-2`],
      ]);
    });

    it.each<[string, HubAnswer]>([
      ["is never reached", "unreachable"],
      ["fails", { status: 500, text: "<html>down</html>" }],
    ])(
      "still revokes, and stays connected, when the unsubscription %s",
      async (_name, unsubscription) => {
        const { events, hub, reported } = await connectedOnTheClock({
          leaseSeconds: 200,
          revocation: unsubscription,
          unsubscription,
        });
        await movedOnAfter(140_000, hub, 2);
        await eventually(() => expect(revoked(hub)).toEqual([HUB_TOKEN]));

        hub.notify(OPEN);

        expect([events.mock.calls, reported.mock.calls, hub.openSockets()]).toEqual([
          [[HANDED_OPEN]],
          [],
          1,
        ]);
      },
    );

    it("gives up a step the hub leaves unanswered after 3 seconds, and stays connected", async () => {
      const never = new Promise<HubAnswer>(() => undefined);
      const { events, hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        revocation: never,
        unsubscription: never,
      });
      await movedOnAfter(140_000, hub, 2);
      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));

      await vi.advanceTimersByTimeAsync(2_999);
      const revokedBefore = revoked(hub).length;
      await vi.advanceTimersByTimeAsync(1);
      await eventually(() => expect(revoked(hub)).toHaveLength(1));
      hub.notify(OPEN);
      const handed = [...events.mock.calls];
      await endedOnTheClock();

      expect([revokedBefore, handed]).toEqual([0, [[HANDED_OPEN]]]);
    });

    it("renews again on time while the hub leaves the last cancel unanswered", async () => {
      const never = new Promise<HubAnswer>(() => undefined);
      const { hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        unsubscription: never,
      });
      await movedOnAfter(140_000, hub, 2);

      await movedOnAfter(140_000, hub, 3);
      await endedOnTheClock();

      expect(hub.sockets()).toEqual([
        CHANNEL_ENDPOINT,
        SECOND_ENDPOINT,
        CHANNEL_ENDPOINT.replace(/1$/, "3"),
      ]);
    });

    it("deletes the registration at the end of the session only once the cancel under way is done", async () => {
      const unsubscription = held();
      const { hub } = await connectedOnTheClock({
        leaseSeconds: 200,
        unsubscription: unsubscription.answer,
      });
      await movedOnAfter(140_000, hub, 2);
      await eventually(() => expect(unsubscriptions(hub)).toHaveLength(1));

      const ending = endFhircast();
      await throughout(() => expect(hub.requestsTo(REGISTRATION_CLIENT_URI)).toEqual([]));
      unsubscription.give({ body: {}, status: 202 });
      await ending;

      expect([
        unsubscriptions(hub),
        revoked(hub),
        hub.requestsTo(REGISTRATION_CLIENT_URI).length,
      ]).toEqual([
        [
          [CHANNEL_ENDPOINT, `Bearer ${HUB_TOKEN}`],
          [SECOND_ENDPOINT, `Bearer ${HUB_TOKEN}-2`],
        ],
        [HUB_TOKEN, `${HUB_TOKEN}-2`],
        1,
      ]);
    });
  });
});

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

  /** Each request, as its method and URL. */
  const routes = (requests: readonly HubRequest[]) =>
    requests.map(({ method, url }) => `${method} ${url}`);

  const UNSUBSCRIBE = `POST ${HUB_URL}`;
  const REVOKE = `POST ${REVOCATION_ENDPOINT}`;
  const DELETE = `DELETE ${REGISTRATION_CLIENT_URI}`;

  /** A form body's parameters. */
  const form = (body: string) => Object.fromEntries(new URLSearchParams(body));

  /** What the tab still keeps under each FHIRcast key. */
  const kept = () =>
    Object.values(FHIRCAST_STORAGE_KEYS).map((key) => window.sessionStorage.getItem(key));

  /** A hub answer held back until the test gives it: a step under way waits on it. */
  function held() {
    let give: (answer: HubAnswer) => void = () => undefined;
    const answer = new Promise<HubAnswer>((resolve) => (give = resolve));
    return { answer, give: (given: HubAnswer) => give(given) };
  }

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

describe("the session's FHIRcast connection", () => {
  const releasing = () => Promise.resolve(RELEASE);

  /** What the connection shows, once it says connected. */
  const connectedNow = () => eventually(() => expect(fhircastSnapshot().status).toBe("connected"));

  /** A Patient-open on the release's topic, as the hub sends it. */
  const PATIENT_OPEN = {
    event: {
      context: [{ key: "patient", resource: { id: "patient-1", resourceType: "Patient" } }],
      "hub.event": "Patient-open",
      "hub.topic": "topic-1",
    },
    id: "event-1",
    timestamp: "2026-10-24T02:00:05Z",
  };

  it("connects anew on a start once the session's FHIRcast has ended", async () => {
    const hub = hubOnly();
    startFhircast(releasing);
    await connectedNow();
    await endFhircast();

    startFhircast(releasing);
    await connectedNow();

    expect(hub.requestsTo(REGISTRATION_ENDPOINT)).toHaveLength(2);
  });

  it("shows nothing of the ended session's connection to the next", async () => {
    const hub = hubOnly();
    startFhircast(releasing);
    await connectedNow();
    hub.notify(PATIENT_OPEN);

    await endFhircast();

    expect(fhircastSnapshot()).toEqual({ last: null, status: "connecting" });
  });

  it("asks for the release once however often it is started", async () => {
    hubOnly();
    const source = vi.fn(releasing);

    startFhircast(source);
    startFhircast(source);
    await connectedNow();

    expect(source).toHaveBeenCalledOnce();
  });

  it("closes the connection before when a retry connects again", async () => {
    const hub = hubOnly();
    startFhircast(releasing);
    await connectedNow();

    retryFhircast();
    await connectedNow();

    expect([hub.sockets().length, hub.openSockets()]).toEqual([2, 1]);
  });

  it("closes a connection a retry replaced while it was still connecting, once it connects", async () => {
    let confirmFirst = () => undefined as void;
    const hub = hubOnly({ hold: [new Promise((resolve) => (confirmFirst = resolve)), null] });
    startFhircast(releasing);
    await eventually(() => expect(hub.sockets()).toHaveLength(1));
    retryFhircast();
    await connectedNow();

    confirmFirst();

    await eventually(() => expect(hub.openSockets()).toBe(1));
  });

  it("keeps the subscription of the retry's connection, not of the one it replaced", async () => {
    let confirmFirst = () => undefined as void;
    const hub = hubOnly({ hold: [new Promise((resolve) => (confirmFirst = resolve)), null] });
    startFhircast(releasing);
    await eventually(() => expect(hub.sockets()).toHaveLength(1));
    retryFhircast();
    await connectedNow();

    confirmFirst();
    await eventually(() => expect(hub.openSockets()).toBe(1));

    expect(keptState()?.subscription).toMatchObject({
      endpoint: CHANNEL_ENDPOINT.replace(/1$/, "2"),
    });
  });

  it("resumes the tab's registration on a retry, registering nothing", async () => {
    const hub = hubOnly();
    startFhircast(releasing);
    await connectedNow();

    retryFhircast();
    await connectedNow();

    expect(hub.requestsTo(REGISTRATION_ENDPOINT)).toHaveLength(1);
  });

  it("connects again from the start on a retry after the hub refused", async () => {
    const hub = hubOnly({ registration: { status: 401 } });
    startFhircast(releasing);
    await eventually(() => expect(fhircastSnapshot().status).toBe("unavailable"));

    retryFhircast();

    await eventually(() => expect(hub.requestsTo(REGISTRATION_ENDPOINT)).toHaveLength(2));
  });

  it("shows no event the hub sends while the ended session's connection is torn down", async () => {
    const never = new Promise<HubAnswer>(() => undefined);
    const hub = hubOnly({ unsubscription: never });
    startFhircast(releasing);
    await connectedNow();
    void endFhircast();

    hub.notify(PATIENT_OPEN);

    expect(fhircastSnapshot().last).toBeNull();
  });

  it("shows nothing the ended session's connection says of a socket the hub drops as the session ends", async () => {
    const hub = hubOnly();
    startFhircast(releasing);
    await connectedNow();

    const ending = endFhircast();
    hub.dropSockets();
    await ending;

    expect(fhircastSnapshot().status).toBe("connecting");
  });

  it("tells a watcher what it shows, until the watcher stops watching", async () => {
    const hub = hubOnly();
    const watcher = vi.fn();
    const stop = watchFhircast(watcher);
    startFhircast(releasing);
    await connectedNow();
    stop();
    const heard = watcher.mock.calls.length;

    hub.notify(PATIENT_OPEN);

    expect([heard > 0, watcher.mock.calls.length]).toEqual([true, heard]);
  });

  it("hands a follower each event the hub sends", async () => {
    const hub = hubOnly();
    const follower = vi.fn();
    const stop = followFhircastEvents(follower);
    startFhircast(releasing);
    await connectedNow();

    hub.notify(PATIENT_OPEN);
    stop();

    expect(follower.mock.calls).toEqual([
      [{ event: "Patient-open", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" }],
    ]);
  });

  it("hands a follower nothing once it stops following", async () => {
    const hub = hubOnly();
    const follower = vi.fn();
    followFhircastEvents(follower)();
    startFhircast(releasing);
    await connectedNow();

    hub.notify(PATIENT_OPEN);

    expect(follower).not.toHaveBeenCalled();
  });

  it("hands a follower no event the hub sends while the ended session's connection is torn down", async () => {
    const never = new Promise<HubAnswer>(() => undefined);
    const hub = hubOnly({ unsubscription: never });
    const follower = vi.fn();
    const stop = followFhircastEvents(follower);
    startFhircast(releasing);
    await connectedNow();
    void endFhircast();

    hub.notify(PATIENT_OPEN);
    stop();

    expect(follower).not.toHaveBeenCalled();
  });

  it("connects nothing on a retry before any start", async () => {
    const hub = hubOnly();

    retryFhircast();
    await new Promise((resolve) => realSetTimeout(resolve, 0));

    expect([fhircastSnapshot(), hub.requests()]).toEqual([
      { last: null, status: "connecting" },
      [],
    ]);
  });
});

describe("a reload", () => {
  const releasing = () => Promise.resolve(RELEASE);

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

  /** Each request, as its method and URL. */
  const routes = (requests: readonly HubRequest[]) =>
    requests.map(({ method, url }) => `${method} ${url}`);

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
  const REVOKE = `POST ${REVOCATION_ENDPOINT}`;
  const DELETE = `DELETE ${REGISTRATION_CLIENT_URI}`;

  const SECOND_ENDPOINT = CHANNEL_ENDPOINT.replace(/1$/, "2");

  /** A form body's parameters. */
  const form = (body: string) => Object.fromEntries(new URLSearchParams(body));

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

describe("decodeFhircastRelease", () => {
  const decode = (body: unknown) => decodeFhircastRelease(Response.json(body));

  it("reads the release", async () => {
    expect(await decode(RELEASE)).toEqual(RELEASE);
  });

  it.each(["accessToken", "expiresAt", "hubUrl", "hubTopic"])(
    "refuses a release with no %s",
    async (key) => {
      await expect(decode({ ...RELEASE, [key]: undefined })).rejects.toThrow(
        `FhircastRelease.${key}`,
      );
    },
  );

  it("refuses a scope that is not a list of strings", async () => {
    await expect(decode({ ...RELEASE, scope: ["openid", 7] })).rejects.toThrow("scope");
  });
});

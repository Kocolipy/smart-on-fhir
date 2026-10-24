// The DCR shim, through its HTTP interface. Medplum is the system boundary, so
// each test runs the shim against a fake Medplum on loopback: it answers the
// super admin's login, introspects exactly one SMART access token as active,
// and stands in for the FHIRcast hub, accepting a subscribe only when it
// carries the super admin's token.
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, randomUUID, sign } from "node:crypto";
import { createServer, request as httpRequest } from "node:http";
import { after, before, beforeEach, describe, it } from "node:test";

import { createShim } from "../src/shim.mjs";

const PUBLIC_BASE = "http://127.0.0.1:8103/";
const TOKEN_ENDPOINT = "http://127.0.0.1:8103/dcr/token";
const SMART_TOKEN = "the-clinicians-smart-access-token";
const ADMIN = { email: "admin@example.com", password: "admin-password" };
const ADMIN_TOKEN = "the-super-admins-medplum-token";
const JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";
const WEBSOCKET_GREETING = "the hub's first message";

/** A fake Medplum: only what the shim calls, answering as Medplum 5 does. */
function fakeMedplum() {
  const server = createServer(async (req, res) => {
    const body = await readBody(req);
    const reply = (status, json) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(json));
    };
    const { pathname } = new URL(req.url, "http://medplum");
    if (req.method === "POST" && pathname === "/auth/login") {
      const login = JSON.parse(body);
      if (login.email === ADMIN.email && login.password === ADMIN.password) {
        return reply(200, { login: "admin-login", code: "admin-code" });
      }
      return reply(400, {
        issue: [{ details: { text: "Email or password is invalid" } }],
      });
    }
    if (req.method === "POST" && pathname === "/oauth2/token") {
      const form = new URLSearchParams(body);
      if (
        form.get("grant_type") === "authorization_code" &&
        form.get("code") === "admin-code"
      ) {
        return reply(200, {
          access_token: ADMIN_TOKEN,
          expires_in: 3600,
          token_type: "Bearer",
        });
      }
      return reply(400, { error: "invalid_request" });
    }
    if (req.method === "POST" && pathname === "/oauth2/introspect") {
      if (req.headers.authorization !== `Bearer ${ADMIN_TOKEN}`)
        return reply(401, {});
      const token = new URLSearchParams(body).get("token");
      return reply(
        200,
        token === SMART_TOKEN
          ? { active: true, scope: "fhircast/Patient-open.read" }
          : { active: false },
      );
    }
    if (
      req.method === "GET" &&
      pathname === "/fhircast/STU3/.well-known/fhircast-configuration"
    ) {
      return reply(200, {
        eventsSupported: ["Patient-open", "Patient-close"],
        websocketSupport: true,
        fhircastVersion: "STU3",
      });
    }
    if (req.method === "POST" && pathname === "/fhircast/STU3") {
      if (req.headers.authorization !== `Bearer ${ADMIN_TOKEN}`)
        return reply(401, { issue: [] });
      return reply(202, {
        "hub.channel.endpoint": "ws://127.0.0.1:8103/ws/fhircast/endpoint-1",
      });
    }
    if (req.method === "GET" && pathname === "/fhir/R4/Patient") {
      // Medplum's FHIR API, which only Medplum's own tokens open.
      if (req.headers.authorization !== `Bearer ${ADMIN_TOKEN}`)
        return reply(401, {});
      return reply(200, { resourceType: "Bundle", type: "searchset" });
    }
    reply(404, {});
  });
  // Medplum's FHIRcast websocket: the handshake, then one text frame, as the
  // hub's subscription confirmation would be.
  const websockets = new Set();
  const closeHttpConnections = server.closeAllConnections.bind(server);
  server.closeAllConnections = () => {
    closeHttpConnections();
    for (const socket of websockets) socket.destroy();
  };
  server.on("upgrade", (req, socket) => {
    websockets.add(socket);
    // As Medplum does: any upgrade but its websocket, h2c included, is a 404.
    if (req.url !== "/ws/fhircast/endpoint-1") {
      return socket.end("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
    }
    const accept = createHash("sha1")
      .update(
        `${req.headers["sec-websocket-key"]}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`,
      )
      .digest("base64");
    socket.write(
      "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
        `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
    );
    const text = Buffer.from(WEBSOCKET_GREETING);
    socket.write(Buffer.concat([Buffer.from([0x81, text.length]), text]));
  });
  return server;
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
  });
}

function listen(server) {
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () =>
      resolve(`http://127.0.0.1:${server.address().port}`),
    );
  });
}

function close(server) {
  return new Promise((resolve) => {
    server.closeAllConnections();
    server.close(() => resolve());
  });
}

/** An EC P-384 key pair, as the SPA generates with WebCrypto: the public half as a JWK. */
function clientKey() {
  const { privateKey, publicKey } = generateKeyPairSync("ec", {
    namedCurve: "P-384",
  });
  return { privateKey, jwk: publicKey.export({ format: "jwk" }) };
}

function base64url(value) {
  return Buffer.from(
    typeof value === "string" ? value : JSON.stringify(value),
  ).toString("base64url");
}

/** A compact ES384 JWS over `claims`, signed with `privateKey`. */
function es384(privateKey, claims, header = { alg: "ES384", typ: "JWT" }) {
  const input = `${base64url(header)}.${base64url(claims)}`;
  const signature = sign("sha384", Buffer.from(input), {
    key: privateKey,
    dsaEncoding: "ieee-p1363",
  });
  return `${input}.${signature.toString("base64url")}`;
}

describe("the FHIRcast DCR shim", () => {
  let medplum;
  let medplumUrl;
  let shim;
  let shimUrl;
  /** The shim's clock, in milliseconds; tests move it forward. */
  let now;

  before(async () => {
    medplum = fakeMedplum();
    medplumUrl = await listen(medplum);
  });

  after(async () => {
    await close(medplum);
  });

  async function startShim(options = {}) {
    if (shim) await close(shim);
    shim = createShim({
      publicBaseUrl: PUBLIC_BASE,
      medplumUrl,
      admin: ADMIN,
      tokenLifetimeSeconds: 3600,
      testState: true,
      now: () => now,
      ...options,
    });
    shimUrl = await listen(shim);
  }

  beforeEach(async () => {
    now = Date.UTC(2026, 9, 24, 9, 0, 0);
    await startShim();
  });

  after(async () => {
    if (shim) await close(shim);
  });

  async function state() {
    return (await fetch(`${shimUrl}/dcr/test/state`)).json();
  }

  function register(jwk, bearer = SMART_TOKEN) {
    return fetch(`${shimUrl}/dcr/register`, {
      method: "POST",
      headers: {
        ...(bearer === null ? {} : { Authorization: `Bearer ${bearer}` }),
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        client_name: "smart-on-fhir",
        grant_types: [JWT_BEARER],
        jwks: { keys: [jwk] },
        token_endpoint_auth_method: "private_key_jwt",
      }),
    });
  }

  describe("the hub's metadata", () => {
    it("adds the shim's endpoints to Medplum's fhircast-configuration under the hub URL", async () => {
      const answer = await fetch(
        `${shimUrl}/fhircast/STU3/.well-known/fhircast-configuration`,
      );

      assert.deepEqual(await answer.json(), {
        eventsSupported: ["Patient-open", "Patient-close"],
        websocketSupport: true,
        fhircastVersion: "STU3",
        registration_endpoint: "http://127.0.0.1:8103/dcr/register",
        token_endpoint: "http://127.0.0.1:8103/dcr/token",
        revocation_endpoint: "http://127.0.0.1:8103/dcr/revoke",
      });
    });
  });

  describe("browsers, which call the shim from the app's origin", () => {
    it("answers a preflight for its own endpoints, allowing any origin", async () => {
      const answer = await fetch(`${shimUrl}/dcr/register`, {
        method: "OPTIONS",
        headers: {
          Origin: "http://localhost:8081",
          "Access-Control-Request-Method": "POST",
          "Access-Control-Request-Headers": "authorization,content-type",
        },
      });

      assert.deepEqual(
        {
          status: answer.status,
          origin: answer.headers.get("access-control-allow-origin"),
          methods: answer.headers.get("access-control-allow-methods"),
          headers: answer.headers.get("access-control-allow-headers"),
        },
        {
          status: 204,
          origin: "*",
          methods: "GET, POST, DELETE",
          headers: "Authorization, Content-Type",
        },
      );
    });

    it("allows any origin on its own answers", async () => {
      const answer = await fetch(
        `${shimUrl}/fhircast/STU3/.well-known/fhircast-configuration`,
        { headers: { Origin: "http://localhost:8081" } },
      );

      assert.equal(
        answer.headers.get("access-control-allow-origin"),
        "*",
      );
    });
  });

  describe("RFC 7591 registration", () => {
    it("registers the posted key under a new client_id for a valid SMART access token", async () => {
      const { jwk } = clientKey();

      const answer = await register(jwk);

      assert.equal(answer.status, 201);
      const registered = await answer.json();
      const state = await (await fetch(`${shimUrl}/dcr/test/state`)).json();
      assert.deepEqual(
        state.registrations.map(({ client_id, jwk: stored }) => ({
          client_id,
          jwk: stored,
        })),
        [{ client_id: registered.client_id, jwk }],
      );
    });

    it("refuses a registration whose key is private, so no public key to verify with", async () => {
      const { privateKey } = clientKey();

      const answer = await register(privateKey.export({ format: "jwk" }));

      assert.deepEqual(
        { status: answer.status, registrations: (await state()).registrations },
        { status: 400, registrations: [] },
      );
    });

    it("refuses a registration that carries no bearer token", async () => {
      const answer = await register(clientKey().jwk, null);

      assert.deepEqual(
        { status: answer.status, registrations: (await state()).registrations },
        { status: 401, registrations: [] },
      );
    });

    it("refuses a registration whose bearer token Medplum does not call active", async () => {
      const answer = await register(
        clientKey().jwk,
        "a-token-medplum-never-issued",
      );

      assert.deepEqual(
        { status: answer.status, registrations: (await state()).registrations },
        { status: 401, registrations: [] },
      );
    });
  });

  /** A registered client: its client_id, its private key and its RFC 7592 access token. */
  async function registeredClient() {
    const { privateKey, jwk } = clientKey();
    const answer = await register(jwk);
    assert.equal(answer.status, 201, "registering the client");
    const { client_id, registration_access_token } = await answer.json();
    return {
      clientId: client_id,
      privateKey,
      registrationAccessToken: registration_access_token,
    };
  }

  /** The assertion the SPA signs: valid for five minutes from the shim's clock. */
  function assertionFor(
    client,
    overrides = {},
    privateKey = client.privateKey,
  ) {
    const issuedAt = Math.floor(now / 1000);
    return es384(privateKey, {
      iss: client.clientId,
      sub: client.clientId,
      aud: TOKEN_ENDPOINT,
      exp: issuedAt + 300,
      iat: issuedAt,
      jti: randomUUID(),
      ...overrides,
    });
  }

  function mint(assertion) {
    return fetch(`${shimUrl}/dcr/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: JWT_BEARER, assertion }),
    });
  }

  describe("the jwt-bearer grant", () => {
    it("issues a bearer token for an assertion signed by the registered key", async () => {
      const client = await registeredClient();

      const answer = await mint(assertionFor(client));

      const body = await answer.json();
      assert.deepEqual(
        {
          status: answer.status,
          token_type: body.token_type,
          expires_in: body.expires_in,
          hasToken: typeof body.access_token,
        },
        {
          status: 200,
          token_type: "Bearer",
          expires_in: 3600,
          hasToken: "string",
        },
      );
    });

    it("issues a token the hub accepts on subscribe", async () => {
      const client = await registeredClient();
      const { access_token } = await (await mint(assertionFor(client))).json();

      const answer = await subscribe(access_token);

      assert.equal(answer.status, 202);
    });

    it("refuses an assertion signed by a key other than the registered one", async () => {
      const client = await registeredClient();

      const answer = await mint(
        assertionFor(client, {}, clientKey().privateKey),
      );

      assert.deepEqual(await refusal(answer), {
        status: 400,
        error: "invalid_grant",
      });
    });

    it("refuses an assertion whose aud is not the token endpoint", async () => {
      const client = await registeredClient();

      const answer = await mint(
        assertionFor(client, { aud: "http://127.0.0.1:8103/oauth2/token" }),
      );

      assert.deepEqual(await refusal(answer), {
        status: 400,
        error: "invalid_grant",
      });
    });
    it("refuses an assertion whose exp has passed", async () => {
      const client = await registeredClient();

      const answer = await mint(
        assertionFor(client, { exp: Math.floor(now / 1000) - 1 }),
      );

      assert.deepEqual(await refusal(answer), {
        status: 400,
        error: "invalid_grant",
      });
    });
    it("refuses an assertion whose jti has been presented before", async () => {
      const client = await registeredClient();
      const assertion = assertionFor(client);
      assert.equal(
        (await mint(assertion)).status,
        200,
        "the first presentation",
      );

      const replayed = await mint(assertion);

      assert.deepEqual(await refusal(replayed), {
        status: 400,
        error: "invalid_grant",
      });
    });
    it("refuses an assertion whose sub is not its iss", async () => {
      const client = await registeredClient();

      const answer = await mint(assertionFor(client, { sub: randomUUID() }));

      assert.deepEqual(await refusal(answer), {
        status: 400,
        error: "invalid_grant",
      });
    });
  });

  /** A token endpoint refusal, as RFC 6749 §5.2 shapes it; never a token. */
  async function refusal(answer) {
    const body = await answer.json();
    return {
      status: answer.status,
      error: body.error,
      ...(body.access_token ? { access_token: "issued" } : {}),
    };
  }

  function revoke(token) {
    return fetch(`${shimUrl}/dcr/revoke`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ token, token_type_hint: "access_token" }),
    });
  }

  async function hubToken(client) {
    const answer = await mint(assertionFor(client));
    assert.equal(answer.status, 200, "minting a hub token");
    return (await answer.json()).access_token;
  }

  describe("RFC 7009 revocation", () => {
    it("revokes a hub token, which the hub then refuses", async () => {
      const token = await hubToken(await registeredClient());
      assert.equal((await revoke(token)).status, 200, "revoking it");

      const answer = await subscribe(token);

      assert.equal(answer.status, 401);
    });

    it("lists the revoked token in its test state", async () => {
      const token = await hubToken(await registeredClient());

      await revoke(token);

      assert.deepEqual((await state()).revokedTokens, [token]);
    });
  });

  function deleteRegistration(client, token = client.registrationAccessToken) {
    return fetch(`${shimUrl}/dcr/register/${client.clientId}`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${token}` },
    });
  }

  describe("RFC 7592 registration deletion", () => {
    it("deletes a registration, which can then get no token", async () => {
      const client = await registeredClient();
      assert.equal(
        (await deleteRegistration(client)).status,
        204,
        "deleting it",
      );

      const answer = await mint(assertionFor(client));

      assert.deepEqual(await refusal(answer), {
        status: 400,
        error: "invalid_grant",
      });
    });
  });

  describe("RFC 7592 registration deletion, for what it already issued", () => {
    it("refuses at the hub a token issued before the registration was deleted", async () => {
      const client = await registeredClient();
      const token = await hubToken(client);
      assert.equal(
        (await deleteRegistration(client)).status,
        204,
        "deleting it",
      );

      const answer = await subscribe(token);

      assert.equal(answer.status, 401);
    });

    it("keeps a registration whose deletion presents another token", async () => {
      const client = await registeredClient();

      const answer = await deleteRegistration(
        client,
        "not-its-registration-access-token",
      );

      assert.deepEqual(
        {
          status: answer.status,
          registered: (await state()).registrations.map(
            ({ client_id }) => client_id,
          ),
        },
        { status: 401, registered: [client.clientId] },
      );
    });
  });

  describe("the hub token lifetime", () => {
    it("issues tokens for the configured lifetime", async () => {
      await startShim({ tokenLifetimeSeconds: 60 });
      const client = await registeredClient();

      const answer = await mint(assertionFor(client));

      assert.equal((await answer.json()).expires_in, 60);
    });

    it("refuses at the hub a token whose configured lifetime has passed", async () => {
      await startShim({ tokenLifetimeSeconds: 60 });
      const token = await hubToken(await registeredClient());

      now += 61_000;
      const answer = await subscribe(token);

      assert.equal(answer.status, 401);
    });

    it("accepts at the hub a token still within its configured lifetime", async () => {
      await startShim({ tokenLifetimeSeconds: 60 });
      const token = await hubToken(await registeredClient());

      now += 59_000;
      const answer = await subscribe(token);

      assert.equal(answer.status, 202);
    });
  });

  describe("fronting Medplum", () => {
    it("exchanges a hub token on hub paths alone, so it opens nothing else of Medplum's", async () => {
      const token = await hubToken(await registeredClient());

      const answer = await fetch(`${shimUrl}/fhir/R4/Patient`, {
        headers: { Authorization: `Bearer ${token}` },
      });

      assert.equal(answer.status, 401);
    });

    it("does not exchange a hub token on a path that only climbs out of the hub", async () => {
      const token = await hubToken(await registeredClient());

      // Sent as written: fetch() would resolve the dot segments before sending.
      const status = await new Promise((resolve, reject) => {
        const sent = httpRequest(
          `${shimUrl}/fhircast/../fhir/R4/Patient`,
          {
            headers: { Authorization: `Bearer ${token}` },
            path: "/fhircast/../fhir/R4/Patient",
          },
          (answer) => {
            answer.resume();
            resolve(answer.statusCode);
          },
        );
        sent.on("error", reject);
        sent.end();
      });

      assert.equal(status, 401);
    });

    it("forwards to Medplum alone, whatever host an absolute request target names", async () => {
      const status = await new Promise((resolve, reject) => {
        const sent = httpRequest(
          shimUrl,
          {
            headers: { Authorization: `Bearer ${ADMIN_TOKEN}` },
            path: "http://elsewhere.invalid/fhir/R4/Patient",
          },
          (answer) => {
            answer.resume();
            resolve(answer.statusCode);
          },
        );
        sent.on("error", reject);
        sent.end();
      });

      assert.equal(status, 200);
    });

    it("answers a request offering an HTTP/2 upgrade as plain HTTP, as Java's client sends", async () => {
      const status = await new Promise((resolve, reject) => {
        const sent = httpRequest(
          `${shimUrl}/fhir/R4/Patient`,
          {
            headers: {
              Authorization: `Bearer ${ADMIN_TOKEN}`,
              Connection: "Upgrade, HTTP2-Settings",
              "HTTP2-Settings": "AAEAAEAAAAIAAAAAAAMAAAAAZAAEAP__AAAFAAQAAA",
              Upgrade: "h2c",
            },
          },
          (answer) => {
            answer.resume();
            resolve(answer.statusCode);
          },
        );
        sent.on("upgrade", () =>
          reject(new Error("the shim upgraded the connection")),
        );
        sent.on("error", reject);
        sent.end();
      });

      assert.equal(status, 200);
    });

    it("passes a Medplum access token to the hub untouched", async () => {
      const answer = await subscribe(ADMIN_TOKEN);

      assert.equal(answer.status, 202);
    });

    it(
      "carries a websocket at the hub's channel endpoint through to Medplum",
      { timeout: 5000 },
      async () => {
        const socket = new WebSocket(
          `${shimUrl.replace("http:", "ws:")}/ws/fhircast/endpoint-1`,
        );
        try {
          const first = await new Promise((resolve, reject) => {
            socket.addEventListener("message", ({ data }) => resolve(data));
            socket.addEventListener("error", () =>
              reject(new Error("the websocket failed")),
            );
          });

          assert.equal(first, WEBSOCKET_GREETING);
        } finally {
          socket.close();
        }
      },
    );
  });

  /** A FHIRcast STU3 websocket subscribe, at the hub URL the shim fronts. */
  function subscribe(hubToken) {
    return fetch(`${shimUrl}/fhircast/STU3`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${hubToken}`,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: new URLSearchParams({
        "hub.channel.type": "websocket",
        "hub.mode": "subscribe",
        "hub.topic": "a-topic",
        "hub.events": "Patient-open,Patient-close",
      }),
    });
  }
});

// The FHIRcast shim, through its HTTP interface. Medplum is the system
// boundary, so each test runs the shim against a fake Medplum on loopback: it
// answers the super admin's login, introspects the tokens a test names as
// active, and stands in for the FHIRcast hub, accepting a subscribe only when
// it carries the super admin's token, and recording every request that
// reaches it.
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, sign } from "node:crypto";
import { createServer, request as httpRequest } from "node:http";
import { after, before, beforeEach, describe, it } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";
import { gzipSync } from "node:zlib";

import { createShim } from "../src/shim.mjs";

const SMART_TOKEN = "the-clinicians-smart-access-token";
const ANOTHER_SMART_TOKEN = "another-clinicians-smart-access-token";
const ADMIN = { email: "admin@example.com", password: "admin-password" };
const ADMIN_TOKEN = "the-super-admins-medplum-token";
const WEBSOCKET_GREETING = "the hub's first message";
/** The stand-in's static client ID: the client Medplum issued the SMART access token to. */
const STATIC_CLIENT_ID = "the-stand-ins-static-client-id";
/** The hub's audience, as the stand-in's environment configures it. */
const HUB_AUDIENCE = "urn:example:fhircast-hub";
/** How long a hub token lasts, in seconds. */
const HUB_TOKEN_SECONDS = 600;
/** How often the shim checks its subscriptions' tokens: often, so a test sees a token's end at once. */
const TOKEN_CHECK_SECONDS = 0.05;

/**
 * Medplum's subscription confirmation, as its hub sends it on the socket:
 * every subscription granted an hour's lease. The topic is long enough that
 * the frame carries its length in 16 bits.
 */
const CONFIRMATION = {
  "hub.mode": "subscribe",
  "hub.topic": "a-topic-".repeat(16),
  "hub.events": "patient-open,patient-close",
  "hub.lease_seconds": 3600,
};

/** What Medplum's hub sends on a socket at an endpoint it does not know, before closing it. */
const DENIAL = {
  "hub.mode": "denied",
  "hub.topic": "",
  "hub.events": "",
  "hub.reason": "invalid endpoint",
};

/**
 * An event too long for a frame's 16-bit length, so its frame carries the
 * length in 64 bits.
 */
const LONG_EVENT = {
  id: "a-long-event",
  event: {
    "hub.event": "patient-open",
    context: [{ key: "padding", value: "x".repeat(70_000) }],
  },
};

/** An unmasked websocket text frame, as a server sends one, holding `text`. */
function textFrame(text) {
  const payload = Buffer.from(text);
  let length;
  if (payload.length < 126) length = Buffer.from([payload.length]);
  else if (payload.length < 65536)
    length = Buffer.from([126, payload.length >> 8, payload.length & 0xff]);
  else {
    length = Buffer.alloc(9);
    length[0] = 127;
    length.writeBigUInt64BE(BigInt(payload.length), 1);
  }
  return Buffer.concat([Buffer.from([0x81]), length, payload]);
}

/** A new RSA key pair: its private key, and its public half as a JWK. */
function rsaKey() {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", {
    modulusLength: 2048,
  });
  return { privateKey, jwk: publicKey.export({ format: "jwk" }) };
}

/** A JWT of `header` and `claims`, signed with `privateKey` over `hash`. */
function signedJwt(header, claims, privateKey, hash = "sha384") {
  const input = [header, claims]
    .map((part) => Buffer.from(JSON.stringify(part)).toString("base64url"))
    .join(".");
  const signature = sign(hash, Buffer.from(input), privateKey);
  return `${input}.${signature.toString("base64url")}`;
}

/** A fake Medplum: only what the shim calls, answering as Medplum 5 does. */
function fakeMedplum() {
  const server = createServer(async (req, res) => {
    const body = await readBody(req);
    const reply = (status, json) => {
      const answer = Buffer.from(JSON.stringify(json));
      // Compressed when the request offers gzip, as Medplum's compression()
      // answers one over a kilobyte; the fake compresses every one.
      if (/\bgzip\b/.test(req.headers["accept-encoding"] ?? "")) {
        res.writeHead(status, {
          "Content-Type": "application/json",
          "Content-Encoding": "gzip",
        });
        return res.end(gzipSync(answer));
      }
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(answer);
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
      server.introspected.push(token);
      return reply(200, server.introspections.get(token) ?? { active: false });
    }
    if (req.method === "POST" && pathname === "/fhircast/STU3") {
      const form = new URLSearchParams(body);
      server.hubRequests.push(form);
      if (req.headers.authorization !== `Bearer ${ADMIN_TOKEN}`)
        return reply(401, { issue: [] });
      // As Medplum answers both: a subscribe names its new endpoint, an
      // unsubscribe the endpoint it cancelled.
      return reply(202, {
        "hub.channel.endpoint":
          form.get("hub.mode") === "unsubscribe"
            ? form.get("hub.channel.endpoint")
            : `ws://127.0.0.1:8103/ws/fhircast/${server.subscriptionEndpoints.shift() ?? "endpoint-confirms"}`,
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
  // Medplum's FHIRcast websocket: the handshake, then text frames. At
  // `endpoint-1` that is a greeting; at `endpoint-confirms`, the hub's
  // subscription confirmation, granting Medplum's own hour's lease; at
  // `endpoint-long`, that confirmation and the long event; at
  // `endpoint-cut-short`, that confirmation and the start of a frame whose
  // rest never comes; at any other endpoint, the denial Medplum sends for one
  // it does not know, after which it closes. Every upgrade it is sent is
  // recorded in `upgrades`.
  const websockets = new Set();
  server.upgrades = [];
  /** token -> what introspection answers for it; every other token is inactive. */
  server.introspections = new Map();
  /** Every token introspection was asked about, in order. */
  server.introspected = [];
  /** The form of every request that reached the hub, in order. */
  server.hubRequests = [];
  /** The ids the next subscribes are answered with, in order; then `endpoint-confirms`. */
  server.subscriptionEndpoints = [];
  const closeHttpConnections = server.closeAllConnections.bind(server);
  server.closeAllConnections = () => {
    closeHttpConnections();
    for (const socket of websockets) socket.destroy();
  };
  server.on("upgrade", (req, socket) => {
    websockets.add(socket);
    server.upgrades.push({ url: req.url, headers: req.headers });
    // As Medplum does: any upgrade but its websocket, h2c included, is a 404.
    if (!req.url.startsWith("/ws/fhircast/")) {
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
    if (req.url === "/ws/fhircast/endpoint-1") {
      return socket.write(textFrame(WEBSOCKET_GREETING));
    }
    if (req.url === "/ws/fhircast/endpoint-confirms") {
      return socket.write(textFrame(JSON.stringify(CONFIRMATION)));
    }
    if (req.url === "/ws/fhircast/endpoint-long") {
      return socket.write(
        Buffer.concat([
          textFrame(JSON.stringify(CONFIRMATION)),
          textFrame(JSON.stringify(LONG_EVENT)),
        ]),
      );
    }
    if (req.url === "/ws/fhircast/endpoint-cut-short") {
      return socket.write(
        Buffer.concat([
          textFrame(JSON.stringify(CONFIRMATION)),
          textFrame("the rest of this never comes").subarray(0, 8),
        ]),
      );
    }
    socket.end(textFrame(JSON.stringify(DENIAL)));
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

describe("the FHIRcast shim", () => {
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
      medplumUrl,
      admin: ADMIN,
      leaseSeconds: 3600,
      tokenCheckSeconds: TOKEN_CHECK_SECONDS,
      hubAudience: HUB_AUDIENCE,
      hubTokenSeconds: HUB_TOKEN_SECONDS,
      testState: true,
      now: () => now,
      ...options,
    });
    shimUrl = await listen(shim);
  }

  beforeEach(async () => {
    now = Date.UTC(2026, 9, 24, 9, 0, 0);
    // Medplum's access tokens last an hour; introspection states each one's
    // `exp`, as Medplum's does.
    medplum.introspections = new Map([
      [
        SMART_TOKEN,
        {
          active: true,
          scope: "fhircast/patient-open.read",
          client_id: STATIC_CLIENT_ID,
          exp: now / 1000 + 3600,
        },
      ],
      [ADMIN_TOKEN, { active: true, exp: now / 1000 + 3600 }],
    ]);
    medplum.introspected = [];
    medplum.hubRequests = [];
    medplum.subscriptionEndpoints = [];
    await startShim();
  });

  after(async () => {
    if (shim) await close(shim);
  });

  async function state() {
    return (await fetch(`${shimUrl}/shim/test/state`)).json();
  }

  describe("fronting Medplum", () => {
    it("does not exchange a SMART access token on a path that only climbs out of the hub", async () => {
      // Sent as written: fetch() would resolve the dot segments before sending.
      const status = await new Promise((resolve, reject) => {
        const sent = httpRequest(
          `${shimUrl}/fhircast/../fhir/R4/Patient`,
          {
            headers: { Authorization: `Bearer ${SMART_TOKEN}` },
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

    it("accepts the super admin's own Medplum token on a publish at the hub, as E2E publishes with it", async () => {
      const answer = await publish(ADMIN_TOKEN);

      assert.deepEqual(
        { status: answer.status, reachedHub: medplum.hubRequests.length },
        { status: 202, reachedHub: 1 },
      );
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

  describe("a Medplum access token on the Hub Subscription", () => {
    it("refuses a live SMART access token on subscribe as invalid_token, without forwarding it", async () => {
      const answer = await subscribe(SMART_TOKEN);

      assert.deepEqual(
        {
          status: answer.status,
          error: (await answer.json()).error,
          reachedHub: medplum.hubRequests.length,
        },
        { status: 401, error: "invalid_token", reachedHub: 0 },
      );
    });

    it("refuses a live SMART access token on unsubscribe, without forwarding it", async () => {
      const answer = await unsubscribe(SMART_TOKEN, "endpoint-confirms");

      assert.deepEqual(
        { status: answer.status, reachedHub: medplum.hubRequests.length },
        { status: 401, reachedHub: 0 },
      );
    });

    it("refuses the super admin's own Medplum token on subscribe, without forwarding it", async () => {
      const answer = await subscribe(ADMIN_TOKEN);

      assert.deepEqual(
        { status: answer.status, reachedHub: medplum.hubRequests.length },
        { status: 401, reachedHub: 0 },
      );
    });

    it("refuses a token Medplum's introspection does not call active, without forwarding it", async () => {
      const answer = await subscribe("a-token-medplum-never-issued");

      assert.deepEqual(
        { status: answer.status, reachedHub: medplum.hubRequests.length },
        { status: 401, reachedHub: 0 },
      );
    });

    it("exchanges a SMART access token on hub paths alone, so it opens nothing else of Medplum's as the super admin", async () => {
      const answer = await fetch(`${shimUrl}/fhir/R4/Patient`, {
        headers: { Authorization: `Bearer ${SMART_TOKEN}` },
      });

      assert.equal(answer.status, 401);
    });
  });

  /**
   * A websocket to the shim at the channel endpoint `id`: the messages the
   * hub sends on it, parsed — the first, the `n`th, and, once it closes,
   * every one it got.
   */
  function channelSocket(id) {
    const socket = new WebSocket(
      `${shimUrl.replace("http:", "ws:")}/ws/fhircast/${id}`,
    );
    const messages = [];
    const arrivals = new Set();
    socket.addEventListener("message", ({ data }) => {
      messages.push(JSON.parse(data));
      for (const arrived of arrivals) arrived();
    });
    const nth = (n) =>
      new Promise((resolve, reject) => {
        const arrived = () => {
          if (messages.length >= n) resolve(messages[n - 1]);
        };
        arrivals.add(arrived);
        arrived();
        socket.addEventListener("error", () =>
          reject(new Error("the websocket failed")),
        );
      });
    const closed = new Promise((resolve) =>
      socket.addEventListener("close", () => resolve(messages)),
    );
    return { closed, first: nth(1), nth, socket };
  }

  /** The hub's confirmation at `endpoint-confirms`, as the shim passes it on: the whole default lease left. */
  const CONFIRMED = { ...CONFIRMATION, "hub.lease_seconds": 3600 };

  /** The FHIRcast denial the shim sends a subscription `subscribe()` made, once its token has expired. */
  const EXPIRED = {
    "hub.mode": "denied",
    "hub.topic": "a-topic",
    "hub.events": "patient-open,patient-close",
    "hub.reason": "access token expired",
  };

  /** Has the test-only drop close every socket at the channel endpoint `id`. */
  function drop(id) {
    return fetch(`${shimUrl}/shim/test/drop?endpoint=${id}`, {
      method: "POST",
    });
  }

  /** How many times Medplum's introspection has been asked about `token` in this test. */
  function introspectionsOf(token) {
    return medplum.introspected.filter((asked) => asked === token).length;
  }

  describe("a hub token's expiry", () => {
    it(
      "denies the subscription made with it on its open socket, then closes it as Medplum's hub does",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(await hubToken())).status, 202);
        const { closed, first, socket } = channelSocket("endpoint-confirms");
        const closing = new Promise((resolve) =>
          socket.addEventListener("close", ({ code }) => resolve(code)),
        );
        await first;

        now += HUB_TOKEN_SECONDS * 1000;

        assert.deepEqual(
          { messages: await closed, closeCode: await closing },
          // 1005: a close frame naming no status, as `ws`'s close() sends one.
          { messages: [CONFIRMED, EXPIRED], closeCode: 1005 },
        );
      },
    );

    it(
      "denies no subscription while it is live",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(await hubToken())).status, 202);
        const { closed, first } = channelSocket("endpoint-confirms");
        await first;

        // At least two of the hub's checks of its standing subscriptions.
        await sleep(TOKEN_CHECK_SECONDS * 3000);
        await drop("endpoint-confirms");

        assert.deepEqual(await closed, [CONFIRMED]);
      },
    );

    it(
      "sends its denial whole, holding back a frame Medplum has only begun",
      { timeout: 5000 },
      async () => {
        medplum.subscriptionEndpoints.push("endpoint-cut-short");
        assert.equal((await subscribe(await hubToken())).status, 202);
        const { closed, first } = channelSocket("endpoint-cut-short");
        await first;

        now += HUB_TOKEN_SECONDS * 1000;

        assert.deepEqual(await closed, [CONFIRMED, EXPIRED]);
      },
    );

    it(
      "denies none of the subscriptions made with another hub token",
      { timeout: 5000 },
      async () => {
        medplum.subscriptionEndpoints.push(
          "endpoint-confirms",
          "endpoint-long",
        );
        const expiring = await hubToken();
        now += 1000;
        const later = await hubToken();
        assert.equal((await subscribe(expiring)).status, 202);
        assert.equal((await subscribe(later)).status, 202);
        const expired = channelSocket("endpoint-confirms");
        const other = channelSocket("endpoint-long");
        await expired.first;
        await other.nth(2);

        now += HUB_TOKEN_SECONDS * 1000 - 1000;
        await expired.closed;
        // Ends the other subscription's socket, whatever it was sent before.
        await drop("endpoint-long");

        assert.deepEqual(await other.closed, [CONFIRMED, LONG_EVENT]);
      },
    );

    it(
      "has the hub deny the endpoint of a subscription made with it from then on",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(await hubToken())).status, 202);

        now += HUB_TOKEN_SECONDS * 1000;
        const { first, socket } = channelSocket("endpoint-confirms");
        try {
          assert.deepEqual(await first, DENIAL);
        } finally {
          socket.close();
        }
      },
    );

    it(
      "follows a subscription whose request offers a compressed answer, as a browser's does",
      { timeout: 5000 },
      async () => {
        assert.equal(
          (await subscribe(await hubToken(), { "Accept-Encoding": "gzip" }))
            .status,
          202,
        );

        now += HUB_TOKEN_SECONDS * 1000;
        const { first, socket } = channelSocket("endpoint-confirms");
        try {
          assert.deepEqual(await first, DENIAL);
        } finally {
          socket.close();
        }
      },
    );

    it(
      "leaves the endpoint of a subscription unsubscribed with it to Medplum once it expires",
      { timeout: 5000 },
      async () => {
        const token = await hubToken();
        assert.equal((await subscribe(token)).status, 202);
        assert.equal(
          (await unsubscribe(token, "endpoint-confirms")).status,
          202,
        );

        now += HUB_TOKEN_SECONDS * 1000;
        const { first, socket } = channelSocket("endpoint-confirms");
        try {
          assert.deepEqual(await first, CONFIRMED);
        } finally {
          socket.close();
        }
      },
    );
  });

  describe("the subscription's lease", () => {
    it(
      "states the configured lease in the hub's confirmation, the rest as Medplum wrote it",
      { timeout: 5000 },
      async () => {
        await startShim({ leaseSeconds: 60 });
        const { first, socket } = channelSocket("endpoint-confirms");
        try {
          assert.deepEqual(await first, {
            ...CONFIRMATION,
            "hub.lease_seconds": 60,
          });
        } finally {
          socket.close();
        }
      },
    );

    it(
      "states what is left of the lease when the endpoint is reopened",
      { timeout: 5000 },
      async () => {
        await startShim({ leaseSeconds: 60 });
        const opened = channelSocket("endpoint-confirms");
        await opened.first;
        opened.socket.close();

        now += 20_500;
        const reopened = channelSocket("endpoint-confirms");
        try {
          assert.equal((await reopened.first)["hub.lease_seconds"], 40);
        } finally {
          reopened.socket.close();
        }
      },
    );

    it(
      "closes the socket when the lease runs out",
      { timeout: 5000 },
      async () => {
        await startShim({ leaseSeconds: 0.2 });
        const { closed, first } = channelSocket("endpoint-confirms");
        await first;

        await closed;
      },
    );

    it(
      "has the hub deny an endpoint whose lease has run out",
      { timeout: 5000 },
      async () => {
        await startShim({ leaseSeconds: 60 });
        const opened = channelSocket("endpoint-confirms");
        await opened.first;
        opened.socket.close();

        now += 60_000;
        const reopened = channelSocket("endpoint-confirms");
        try {
          assert.deepEqual(await reopened.first, DENIAL);
        } finally {
          reopened.socket.close();
        }
      },
    );

    it(
      "lists an endpoint whose lease has run out in its test state",
      { timeout: 5000 },
      async () => {
        await startShim({ leaseSeconds: 60 });
        const opened = channelSocket("endpoint-confirms");
        await opened.first;
        opened.socket.close();

        now += 60_000;

        assert.deepEqual((await state()).endedLeases, ["endpoint-confirms"]);
      },
    );

    it(
      "lists no endpoint still leased as ended",
      { timeout: 5000 },
      async () => {
        await startShim({ leaseSeconds: 60 });
        const opened = channelSocket("endpoint-confirms");
        await opened.first;
        opened.socket.close();

        now += 59_000;

        assert.deepEqual((await state()).endedLeases, []);
      },
    );

    it(
      "passes on a hub message too long for a 16-bit frame length whole",
      { timeout: 5000 },
      async () => {
        const { nth, socket } = channelSocket("endpoint-long");
        try {
          assert.deepEqual(await nth(2), LONG_EVENT);
        } finally {
          socket.close();
        }
      },
    );

    it(
      "offers Medplum no websocket extension, so the hub's frames stay plain",
      { timeout: 5000 },
      async () => {
        const { first, socket } = channelSocket("endpoint-confirms");
        try {
          await first;
          assert.equal(
            medplum.upgrades.at(-1).headers["sec-websocket-extensions"],
            undefined,
          );
        } finally {
          socket.close();
        }
      },
    );
  });

  /** The test-only control that has `token` count as expired at once. */
  function expireAtOnce(token) {
    return fetch(`${shimUrl}/shim/test/expire`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ token }),
    });
  }

  describe("the test-only expiry", () => {
    it(
      "denies a hub token's subscriptions on their open sockets at once",
      { timeout: 5000 },
      async () => {
        const token = await hubToken();
        assert.equal((await subscribe(token)).status, 202);
        const { closed, first } = channelSocket("endpoint-confirms");
        await first;

        await expireAtOnce(token);

        assert.deepEqual(await closed, [CONFIRMED, EXPIRED]);
      },
    );

    it("has the hub refuse the hub token from then on, without forwarding it", async () => {
      const token = await hubToken();
      await expireAtOnce(token);

      const answer = await subscribe(token);

      assert.deepEqual(
        { status: answer.status, reachedHub: medplum.hubRequests.length },
        { status: 401, reachedHub: 0 },
      );
    });

    it("has the hub refuse a SMART access token's registration from then on", async () => {
      await expireAtOnce(SMART_TOKEN);

      const answer = await register(SMART_TOKEN, registration());

      assert.equal(answer.status, 401);
    });

    it(
      "has the hub deny the endpoint of a subscription made with it from then on",
      { timeout: 5000 },
      async () => {
        const token = await hubToken();
        assert.equal((await subscribe(token)).status, 202);
        await expireAtOnce(token);

        const { first, socket } = channelSocket("endpoint-confirms");
        try {
          assert.deepEqual(await first, DENIAL);
        } finally {
          socket.close();
        }
      },
    );
  });

  describe("the test-only drop", () => {
    it(
      "closes the sockets at a channel endpoint, as a network fault would",
      { timeout: 5000 },
      async () => {
        const { closed, first } = channelSocket("endpoint-confirms");
        await first;

        await fetch(`${shimUrl}/shim/test/drop?endpoint=endpoint-confirms`, {
          method: "POST",
        });

        await closed;
      },
    );
  });

  /** The test-only control that ends the lease of the channel endpoint `id` at once. */
  function endLeaseAtOnce(id) {
    return fetch(`${shimUrl}/shim/test/end-lease?endpoint=${id}`, {
      method: "POST",
    });
  }

  describe("the test-only lease end", () => {
    it(
      "closes the sockets at the channel endpoint, as the lease running out does",
      { timeout: 5000 },
      async () => {
        const { closed, first } = channelSocket("endpoint-confirms");
        await first;

        await endLeaseAtOnce("endpoint-confirms");

        await closed;
      },
    );

    it(
      "has the hub deny the endpoint and list its lease as ended from then on",
      { timeout: 5000 },
      async () => {
        const opened = channelSocket("endpoint-confirms");
        await opened.first;
        opened.socket.close();

        await endLeaseAtOnce("endpoint-confirms");

        const reopened = channelSocket("endpoint-confirms");
        try {
          assert.deepEqual(
            [await reopened.first, (await state()).endedLeases],
            [DENIAL, ["endpoint-confirms"]],
          );
        } finally {
          reopened.socket.close();
        }
      },
    );
  });

  describe("a browser tab of another origin", () => {
    /** The SPA's origin, as a browser names it on every request it sends the hub. */
    const SPA_ORIGIN = "http://localhost:5173";

    for (const [name, send] of [
      [
        "the configuration",
        () =>
          fetch(`${shimUrl}/fhircast/STU3/subspace-configuration`, {
            headers: { Origin: SPA_ORIGIN },
          }),
      ],
      [
        "a registration",
        () =>
          fetch(`${shimUrl}/fhircast/STU3/register`, {
            method: "POST",
            headers: {
              Authorization: `Bearer ${SMART_TOKEN}`,
              "Content-Type": "application/json",
              Origin: SPA_ORIGIN,
            },
            body: JSON.stringify(registration()),
          }),
      ],
      [
        "a token request",
        () =>
          fetch(`${shimUrl}/fhircast/STU3/token`, {
            method: "POST",
            headers: {
              "Content-Type": "application/x-www-form-urlencoded",
              Origin: SPA_ORIGIN,
            },
            body: new URLSearchParams({ grant_type: "client_credentials" }),
          }),
      ],
      [
        "a subscription it refuses",
        () => subscribe(SMART_TOKEN, { Origin: SPA_ORIGIN }),
      ],
    ]) {
      it(`may read the hub's answer to ${name}`, async () => {
        const answer = await send();

        assert.equal(answer.headers.get("access-control-allow-origin"), "*");
      });
    }

    for (const endpoint of ["subspace-configuration", "register", "token"]) {
      it(`is let send ${endpoint} its Bearer and JSON, from a private network or not`, async () => {
        const answer = await fetch(`${shimUrl}/fhircast/STU3/${endpoint}`, {
          method: "OPTIONS",
          headers: {
            Origin: SPA_ORIGIN,
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "authorization,content-type",
            "Access-Control-Request-Private-Network": "true",
          },
        });

        assert.deepEqual(
          {
            status: answer.status,
            origin: answer.headers.get("access-control-allow-origin"),
            methods: answer.headers.get("access-control-allow-methods"),
            headers: answer.headers.get("access-control-allow-headers"),
            privateNetwork: answer.headers.get(
              "access-control-allow-private-network",
            ),
          },
          {
            status: 204,
            origin: "*",
            methods: "GET, POST",
            headers: "Authorization, Content-Type",
            privateNetwork: "true",
          },
        );
      });
    }
  });

  describe("the hub's configuration", () => {
    it("states the hub's audience, and that it is not read-only", async () => {
      const answer = await fetch(
        `${shimUrl}/fhircast/STU3/subspace-configuration`,
      );

      assert.deepEqual(await answer.json(), {
        audience: HUB_AUDIENCE,
        isReadOnly: "0",
      });
    });

    it("states that it is read-only once the test-only control switches it on", async () => {
      await setReadOnly("1");

      const answer = await fetch(
        `${shimUrl}/fhircast/STU3/subspace-configuration`,
      );

      assert.equal((await answer.json()).isReadOnly, "1");
    });

    it("states that it is not read-only once the test-only control switches it off again", async () => {
      await setReadOnly("1");
      await setReadOnly("0");

      const answer = await fetch(
        `${shimUrl}/fhircast/STU3/subspace-configuration`,
      );

      assert.equal((await answer.json()).isReadOnly, "0");
    });
  });

  describe("the hub's registration", () => {
    it("registers a dynamic client for the static client's software_id and one RSA public key", async () => {
      const answer = await register(SMART_TOKEN, registration());
      const body = await answer.json();

      assert.deepEqual(
        { status: answer.status, clientId: typeof body.client_id },
        { status: 200, clientId: "string" },
      );
    });

    it("gives each registration a client_id of its own", async () => {
      const first = await (await register(SMART_TOKEN, registration())).json();
      const second = await (await register(SMART_TOKEN, registration())).json();

      assert.notEqual(first.client_id, second.client_id);
    });

    it("refuses a bearer that is not a live SMART access token", async () => {
      const answer = await register(
        "a-token-medplum-never-issued",
        registration(),
      );

      assert.deepEqual(
        { status: answer.status, body: await answer.json() },
        {
          status: 401,
          body: {
            error: "invalid_token",
            error_description: "the bearer is not a live SMART access token",
          },
        },
      );
    });

    it("refuses a SMART access token once its exp has passed", async () => {
      now += 3600_000;

      const answer = await register(SMART_TOKEN, registration());

      assert.equal(answer.status, 401);
    });

    it("refuses a request with no bearer", async () => {
      const answer = await fetch(`${shimUrl}/fhircast/STU3/register`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(registration()),
      });

      assert.equal(answer.status, 401);
    });

    it("refuses a software_id other than the static client ID", async () => {
      const answer = await register(SMART_TOKEN, {
        ...registration(),
        software_id: "another-client",
      });

      await assertRefused(answer, 400, "invalid_client_metadata");
    });

    it("refuses a SMART access token Medplum issued to no client", async () => {
      medplum.introspections.set(ANOTHER_SMART_TOKEN, {
        active: true,
        exp: now / 1000 + 3600,
      });

      const answer = await register(ANOTHER_SMART_TOKEN, registration());

      assert.equal(answer.status, 400);
    });

    it("refuses a jwks holding more than one key", async () => {
      const answer = await register(SMART_TOKEN, {
        ...registration(),
        jwks: { keys: [rsaKey().jwk, rsaKey().jwk] },
      });

      await assertRefused(answer, 400, "invalid_client_metadata");
    });

    it("refuses a jwks holding no key", async () => {
      const answer = await register(SMART_TOKEN, {
        ...registration(),
        jwks: { keys: [] },
      });

      assert.equal(answer.status, 400);
    });

    it("refuses a key that is not RSA", async () => {
      const { publicKey } = generateKeyPairSync("ec", { namedCurve: "P-384" });

      const answer = await register(SMART_TOKEN, {
        ...registration(),
        jwks: { keys: [publicKey.export({ format: "jwk" })] },
      });

      assert.equal(answer.status, 400);
    });

    it("refuses a key carrying its private part", async () => {
      const { privateKey } = generateKeyPairSync("rsa", {
        modulusLength: 2048,
      });

      const answer = await register(SMART_TOKEN, {
        ...registration(),
        jwks: { keys: [privateKey.export({ format: "jwk" })] },
      });

      assert.equal(answer.status, 400);
    });

    it("refuses a body that is not JSON", async () => {
      const answer = await fetch(`${shimUrl}/fhircast/STU3/register`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${SMART_TOKEN}`,
          "Content-Type": "application/json",
        },
        body: "not json",
      });

      assert.equal(answer.status, 400);
    });

    it("refuses every registration while the hub is read-only", async () => {
      await setReadOnly("1");

      const answer = await register(SMART_TOKEN, registration());

      await assertRefused(answer, 403, "access_denied");
    });

    it("forwards nothing of a registration to Medplum's hub", async () => {
      await register(SMART_TOKEN, registration());

      assert.equal(medplum.hubRequests.length, 0);
    });

    it("counts the registrations it has made in its test state", async () => {
      await register(SMART_TOKEN, registration());
      await register(SMART_TOKEN, registration());
      await register("a-token-medplum-never-issued", registration());

      assert.equal((await state()).registrations, 2);
    });
  });

  describe("the hub's token endpoint", () => {
    it("issues a hub token for a client assertion its registered key signed", async () => {
      const client = await registeredClient();

      const answer = await requestHubToken(assertionOf(client));
      const body = await answer.json();

      assert.deepEqual(
        {
          ...body,
          access_token: typeof body.access_token,
          status: answer.status,
        },
        {
          status: 200,
          access_token: "string",
          token_type: "bearer",
          expires_in: HUB_TOKEN_SECONDS,
          scope: "fhircast/patient-open.read fhircast/patient-close.read",
        },
      );
    });

    it("accepts an assertion whose exp is just under five minutes ahead", async () => {
      const client = await registeredClient();

      const answer = await requestHubToken(
        assertionOf(client, { exp: now / 1000 + 299 }),
      );

      assert.equal(answer.status, 200);
    });

    it("refuses an assertion whose exp is five minutes ahead", async () => {
      const client = await registeredClient();

      const answer = await requestHubToken(
        assertionOf(client, { exp: now / 1000 + 300 }),
      );

      await assertRefused(answer, 400, "invalid_grant");
    });

    it("refuses an assertion whose exp has passed", async () => {
      const client = await registeredClient();

      const answer = await requestHubToken(
        assertionOf(client, { exp: now / 1000 }),
      );

      assert.equal(answer.status, 400);
    });

    it("refuses an assertion whose jti has been used", async () => {
      const client = await registeredClient();
      const jti = "a-jti-used-once";
      assert.equal(
        (await requestHubToken(assertionOf(client, { jti }))).status,
        200,
      );

      const answer = await requestHubToken(assertionOf(client, { jti }));

      await assertRefused(answer, 400, "invalid_grant");
    });

    it("refuses a stale jti, used by an assertion that has since expired", async () => {
      const client = await registeredClient();
      const jti = "a-jti-gone-stale";
      assert.equal(
        (await requestHubToken(assertionOf(client, { jti }))).status,
        200,
      );
      now += 120_000;

      const answer = await requestHubToken(assertionOf(client, { jti }));

      await assertRefused(answer, 400, "invalid_grant");
    });

    it("refuses an assertion with no jti", async () => {
      const client = await registeredClient();

      const answer = await requestHubToken(
        assertionOf(client, { jti: undefined }),
      );

      assert.equal(answer.status, 400);
    });

    it("refuses an assertion signed with another algorithm than RS384", async () => {
      const client = await registeredClient();

      const answer = await requestHubToken(
        assertionOf(client, {}, { alg: "RS256", hash: "sha256" }),
      );

      await assertRefused(answer, 400, "invalid_grant");
    });

    it("refuses an assertion signed by a key other than the registered one", async () => {
      const client = await registeredClient();

      const answer = await requestHubToken(
        assertionOf(client, {}, { privateKey: rsaKey().privateKey }),
      );

      assert.equal(answer.status, 400);
    });

    it("refuses an assertion whose aud is not the hub's audience", async () => {
      const client = await registeredClient();

      const answer = await requestHubToken(
        assertionOf(client, { aud: "urn:example:another-hub" }),
      );

      await assertRefused(answer, 400, "invalid_grant");
    });

    it("refuses an assertion whose sub is not its iss", async () => {
      const client = await registeredClient();

      const answer = await requestHubToken(
        assertionOf(client, { sub: "another-client" }),
      );

      assert.equal(answer.status, 400);
    });

    it("refuses an assertion from a client it never registered as INVALID-CLIENT", async () => {
      const client = { clientId: "a-client-never-registered", key: rsaKey() };

      const answer = await requestHubToken(assertionOf(client));

      await assertRefused(answer, 401, "INVALID-CLIENT");
    });

    it("refuses an assertion that is not a JWT", async () => {
      const answer = await requestHubToken("not-a-jwt");

      assert.equal(answer.status, 400);
    });

    it("refuses another grant_type", async () => {
      const client = await registeredClient();

      const answer = await requestHubToken(assertionOf(client), {
        grant_type: "authorization_code",
      });

      await assertRefused(answer, 400, "unsupported_grant_type");
    });

    it("refuses another client_assertion_type", async () => {
      const client = await registeredClient();

      const answer = await requestHubToken(assertionOf(client), {
        client_assertion_type: "urn:example:another-type",
      });

      await assertRefused(answer, 400, "invalid_request");
    });
  });

  /** A hub token the hub's token endpoint issued to a newly registered client. */
  async function hubToken() {
    const answer = await requestHubToken(assertionOf(await registeredClient()));
    return (await answer.json()).access_token;
  }

  describe("a hub token at the hub", () => {
    it("subscribes, as the super admin", async () => {
      const answer = await subscribe(await hubToken());

      assert.deepEqual(
        { status: answer.status, reachedHub: medplum.hubRequests.length },
        { status: 202, reachedHub: 1 },
      );
    });

    it("is refused once its lifetime has passed, without forwarding it", async () => {
      const token = await hubToken();
      now += HUB_TOKEN_SECONDS * 1000;

      const answer = await subscribe(token);

      assert.deepEqual(
        { status: answer.status, reachedHub: medplum.hubRequests.length },
        { status: 401, reachedHub: 0 },
      );
    });

    it("is accepted until its lifetime has passed", async () => {
      const token = await hubToken();
      now += HUB_TOKEN_SECONDS * 1000 - 1000;

      const answer = await subscribe(token);

      assert.equal(answer.status, 202);
    });

    it("never reaches Medplum's introspection", async () => {
      const token = await hubToken();

      await subscribe(token);

      assert.equal(introspectionsOf(token), 0);
    });

    it("registers no client, being no SMART access token", async () => {
      const answer = await register(await hubToken(), registration());

      assert.equal(answer.status, 401);
    });

    it("opens nothing of Medplum's off the hub paths", async () => {
      const answer = await fetch(`${shimUrl}/fhir/R4/Patient`, {
        headers: { Authorization: `Bearer ${await hubToken()}` },
      });

      assert.equal(answer.status, 401);
    });
  });

  describe("the test-only client forgetting", () => {
    it("has the token endpoint answer INVALID-CLIENT for a forgotten client", async () => {
      const client = await registeredClient();
      await forgetClient(client.clientId);

      const answer = await requestHubToken(assertionOf(client));

      await assertRefused(answer, 401, "INVALID-CLIENT");
    });
  });

  describe("the test-only controls", () => {
    for (const [route, send] of [
      ["GET /shim/test/state", () => fetch(`${shimUrl}/shim/test/state`)],
      [
        "POST /shim/test/drop",
        () =>
          fetch(`${shimUrl}/shim/test/drop?endpoint=endpoint-confirms`, {
            method: "POST",
          }),
      ],
      ["POST /shim/test/end-lease", () => endLeaseAtOnce("endpoint-confirms")],
      ["POST /shim/test/expire", () => expireAtOnce(SMART_TOKEN)],
      ["POST /shim/test/read-only", () => setReadOnly("1")],
      ["POST /shim/test/forget-client", () => forgetClient("a-client")],
    ]) {
      it(`answers no ${route} unless test state is on`, async () => {
        await startShim({ testState: false });

        const answer = await send();

        assert.equal(answer.status, 404);
      });
    }
  });

  /** That the hub's `answer` is a refusal: `status`, with `error` as its OAuth error. */
  async function assertRefused(answer, status, error) {
    assert.deepEqual(
      { status: answer.status, error: (await answer.json()).error },
      { status, error },
    );
  }

  /** The test-only control that forgets the dynamic client `clientId`. */
  function forgetClient(clientId) {
    return fetch(`${shimUrl}/shim/test/forget-client`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ client_id: clientId }),
    });
  }

  /** A dynamic client the hub has registered: its `clientId`, and the `key` it registered. */
  async function registeredClient() {
    const key = rsaKey();
    const answer = await register(SMART_TOKEN, registration(key));
    assert.equal(answer.status, 200);
    return { clientId: (await answer.json()).client_id, key };
  }

  let jtis = 0;

  /**
   * A client assertion for `client`: RS384, signed by its key, `iss` and
   * `sub` its client_id, `aud` the hub's audience, `exp` a minute ahead, a
   * fresh `jti`; each of `claims` set over those (an `undefined` one left out),
   * and the header's `alg`, the hash and the key as `signing` gives them.
   */
  function assertionOf(client, claims = {}, signing = {}) {
    const payload = Object.fromEntries(
      Object.entries({
        iss: client.clientId,
        sub: client.clientId,
        aud: HUB_AUDIENCE,
        exp: now / 1000 + 60,
        jti: `jti-${(jtis += 1)}`,
        ...claims,
      }).filter(([, value]) => value !== undefined),
    );
    return signedJwt(
      { alg: signing.alg ?? "RS384", typ: "JWT" },
      payload,
      signing.privateKey ?? client.key.privateKey,
      signing.hash,
    );
  }

  /** A token request at the hub with the client assertion `assertion`, each of `form` set over the rest. */
  function requestHubToken(assertion, form = {}) {
    return fetch(`${shimUrl}/fhircast/STU3/token`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "client_credentials",
        client_assertion_type:
          "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        client_assertion: assertion,
        ...form,
      }),
    });
  }

  /** The test-only control that switches the hub's read-only mode: `isReadOnly` `"1"` on, `"0"` off. */
  function setReadOnly(isReadOnly) {
    return fetch(`${shimUrl}/shim/test/read-only`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ isReadOnly }),
    });
  }

  /** A registration's body: the static client's software_id and the public half of `key`. */
  function registration(key = rsaKey()) {
    return { software_id: STATIC_CLIENT_ID, jwks: { keys: [key.jwk] } };
  }

  /** A registration at the hub, `body` as JSON, with `bearer`. */
  function register(bearer, body) {
    return fetch(`${shimUrl}/fhircast/STU3/register`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${bearer}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
  }

  /** A FHIRcast event published to the hub URL the shim fronts, as JSON, with `bearer`. */
  function publish(bearer) {
    return fetch(`${shimUrl}/fhircast/STU3`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${bearer}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        timestamp: "2026-10-24T09:00:00Z",
        id: "an-event",
        event: {
          "hub.topic": "a-topic",
          "hub.event": "patient-open",
          context: [],
        },
      }),
    });
  }

  /** A FHIRcast websocket subscribe, at the hub URL the shim fronts, with `bearer` and any other `headers`. */
  function subscribe(bearer, headers = {}) {
    return fetch(`${shimUrl}/fhircast/STU3`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${bearer}`,
        "Content-Type": "application/x-www-form-urlencoded",
        ...headers,
      },
      body: new URLSearchParams({
        "hub.channel.type": "websocket",
        "hub.mode": "subscribe",
        "hub.topic": "a-topic",
        "hub.events": "patient-open,patient-close",
      }),
    });
  }

  /** A FHIRcast unsubscribe of the subscription at the channel endpoint `id`. */
  function unsubscribe(token, id) {
    return fetch(`${shimUrl}/fhircast/STU3`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: new URLSearchParams({
        "hub.channel.endpoint": `ws://127.0.0.1:8103/ws/fhircast/${id}`,
        "hub.channel.type": "websocket",
        "hub.mode": "unsubscribe",
        "hub.topic": "a-topic",
      }),
    });
  }
});

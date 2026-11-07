// The FHIRcast shim, through its HTTP interface. Medplum is the system
// boundary, so each test runs the shim against a fake Medplum on loopback: it
// answers the super admin's login, introspects the tokens a test names as
// active, and stands in for the FHIRcast hub, accepting a subscribe only when
// it carries the super admin's token, and recording every request that
// reaches it.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
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
  "hub.events": "Patient-open,Patient-close",
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
    "hub.event": "Patient-open",
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
      if (server.unanswered.has(token)) return reply(503, {});
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
  /** The tokens introspection answers with an error, as when Medplum cannot. */
  server.unanswered = new Set();
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
          scope: "fhircast/Patient-open.read",
          exp: now / 1000 + 3600,
        },
      ],
      [ADMIN_TOKEN, { active: true, exp: now / 1000 + 3600 }],
    ]);
    medplum.introspected = [];
    medplum.unanswered = new Set();
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

    it("accepts the super admin's own Medplum token at the hub, as E2E subscribes and publishes with it", async () => {
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

  describe("the SMART access token at the hub", () => {
    it("accepts a live SMART access token on subscribe, subscribing as the super admin", async () => {
      const answer = await subscribe(SMART_TOKEN);

      assert.equal(answer.status, 202);
    });

    it("refuses a token Medplum's introspection does not call active, without forwarding it", async () => {
      const answer = await subscribe("a-token-medplum-never-issued");

      assert.deepEqual(
        { status: answer.status, reachedHub: medplum.hubRequests.length },
        { status: 401, reachedHub: 0 },
      );
    });

    it("refuses a SMART access token once its exp has passed, without forwarding it", async () => {
      now += 3600_000;

      const answer = await subscribe(SMART_TOKEN);

      assert.deepEqual(
        { status: answer.status, reachedHub: medplum.hubRequests.length },
        { status: 401, reachedHub: 0 },
      );
    });

    it("accepts a SMART access token until its exp", async () => {
      now += 3599_000;

      const answer = await subscribe(SMART_TOKEN);

      assert.equal(answer.status, 202);
    });

    it("accepts a live SMART access token on unsubscribe", async () => {
      const answer = await unsubscribe(SMART_TOKEN, "endpoint-confirms");

      assert.equal(answer.status, 202);
    });

    it("refuses a token that is not live on unsubscribe, without forwarding it", async () => {
      const answer = await unsubscribe(
        "a-token-medplum-never-issued",
        "endpoint-confirms",
      );

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
    "hub.events": "Patient-open,Patient-close",
    "hub.reason": "access token expired",
  };

  /** The FHIRcast denial the shim sends a subscription `subscribe()` made, once its token has been revoked. */
  const REVOKED = { ...EXPIRED, "hub.reason": "access token revoked" };

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

  /** Until Medplum's introspection has been asked about `token` `times` times in this test. */
  async function introspected(token, times) {
    while (introspectionsOf(token) < times) await sleep(5);
  }

  describe("a SMART access token's expiry", () => {
    it(
      "denies the subscription made with it on its open socket, then closes it as Medplum's hub does",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);
        const { closed, first, socket } = channelSocket("endpoint-confirms");
        const closing = new Promise((resolve) =>
          socket.addEventListener("close", ({ code }) => resolve(code)),
        );
        await first;

        now += 3600_000;

        assert.deepEqual(
          { messages: await closed, closeCode: await closing },
          // 1005: a close frame naming no status, as `ws`'s close() sends one.
          { messages: [CONFIRMED, EXPIRED], closeCode: 1005 },
        );
      },
    );

    it(
      "sends its denial whole, holding back a frame Medplum has only begun",
      { timeout: 5000 },
      async () => {
        medplum.subscriptionEndpoints.push("endpoint-cut-short");
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);
        const { closed, first } = channelSocket("endpoint-cut-short");
        await first;

        now += 3600_000;

        assert.deepEqual(await closed, [CONFIRMED, EXPIRED]);
      },
    );

    it(
      "denies none of the subscriptions made with another token",
      { timeout: 5000 },
      async () => {
        medplum.introspections.set(ANOTHER_SMART_TOKEN, {
          active: true,
          exp: now / 1000 + 7200,
        });
        medplum.subscriptionEndpoints.push(
          "endpoint-confirms",
          "endpoint-long",
        );
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);
        assert.equal((await subscribe(ANOTHER_SMART_TOKEN)).status, 202);
        const expiring = channelSocket("endpoint-confirms");
        const other = channelSocket("endpoint-long");
        await expiring.first;
        await other.nth(2);

        now += 3600_000;
        await expiring.closed;
        // Ends the other subscription's socket, whatever it was sent before.
        await drop("endpoint-long");

        assert.deepEqual(await other.closed, [CONFIRMED, LONG_EVENT]);
      },
    );

    it(
      "has the hub deny the endpoint of a subscription made with it from then on",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);

        now += 3600_000;
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
          (await subscribe(SMART_TOKEN, { "Accept-Encoding": "gzip" })).status,
          202,
        );

        now += 3600_000;
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
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);
        assert.equal(
          (await unsubscribe(SMART_TOKEN, "endpoint-confirms")).status,
          202,
        );

        now += 3600_000;
        const { first, socket } = channelSocket("endpoint-confirms");
        try {
          assert.deepEqual(await first, CONFIRMED);
        } finally {
          socket.close();
        }
      },
    );
  });

  describe("a SMART access token's revocation", () => {
    it(
      "denies the subscription made with it on its open socket once Medplum's introspection no longer calls it active",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);
        const { closed, first } = channelSocket("endpoint-confirms");
        await first;

        // As Medplum's introspection answers once the token's Login is revoked.
        medplum.introspections.delete(SMART_TOKEN);

        assert.deepEqual(await closed, [CONFIRMED, REVOKED]);
      },
    );

    it(
      "has the hub deny the endpoint of a subscription made with it from then on",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);
        const revoked = channelSocket("endpoint-confirms");
        await revoked.first;
        medplum.introspections.delete(SMART_TOKEN);
        await revoked.closed;

        const { first, socket } = channelSocket("endpoint-confirms");
        try {
          assert.deepEqual(await first, DENIAL);
        } finally {
          socket.close();
        }
      },
    );

    it(
      "denies no subscription while Medplum's introspection calls its token active",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);
        const { closed, first } = channelSocket("endpoint-confirms");
        await first;

        // The subscribe's own introspection, then at least two checks.
        await introspected(SMART_TOKEN, 3);
        await drop("endpoint-confirms");

        assert.deepEqual(await closed, [CONFIRMED]);
      },
    );

    it(
      "denies no subscription while Medplum's introspection cannot answer",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);
        medplum.unanswered.add(SMART_TOKEN);
        const { closed, first } = channelSocket("endpoint-confirms");
        await first;

        // The subscribe's own introspection, then at least two checks unanswered.
        await introspected(SMART_TOKEN, 4);
        await drop("endpoint-confirms");

        assert.deepEqual(await closed, [CONFIRMED]);
      },
    );

    it(
      "asks Medplum's introspection nothing once the shim has closed",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);
        await introspected(SMART_TOKEN, 2);
        await close(shim);
        // Lets a check already under way end.
        await sleep(TOKEN_CHECK_SECONDS * 2000);
        const asked = introspectionsOf(SMART_TOKEN);

        await sleep(TOKEN_CHECK_SECONDS * 10_000);

        assert.equal(introspectionsOf(SMART_TOKEN), asked);
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
      "denies a SMART access token's subscriptions on their open sockets at once",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);
        const { closed, first } = channelSocket("endpoint-confirms");
        await first;

        await expireAtOnce(SMART_TOKEN);

        assert.deepEqual(await closed, [CONFIRMED, EXPIRED]);
      },
    );

    it("has the hub refuse the token from then on, without forwarding it", async () => {
      await expireAtOnce(SMART_TOKEN);

      const answer = await subscribe(SMART_TOKEN);

      assert.deepEqual(
        { status: answer.status, reachedHub: medplum.hubRequests.length },
        { status: 401, reachedHub: 0 },
      );
    });

    it(
      "has the hub deny the endpoint of a subscription made with it from then on",
      { timeout: 5000 },
      async () => {
        assert.equal((await subscribe(SMART_TOKEN)).status, 202);
        await expireAtOnce(SMART_TOKEN);

        const { first, socket } = channelSocket("endpoint-confirms");
        try {
          assert.deepEqual(await first, DENIAL);
        } finally {
          socket.close();
        }
      },
    );

    it("is not there unless test state is on", async () => {
      await startShim({ testState: false });

      const answer = await expireAtOnce(SMART_TOKEN);

      assert.equal(answer.status, 404);
    });
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

    it("is not there unless test state is on", async () => {
      await startShim({ testState: false });

      const answer = await fetch(
        `${shimUrl}/shim/test/drop?endpoint=endpoint-confirms`,
        { method: "POST" },
      );

      assert.equal(answer.status, 404);
    });
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

    it("is not there unless test state is on", async () => {
      await startShim({ testState: false });

      const answer = await endLeaseAtOnce("endpoint-confirms");

      assert.equal(answer.status, 404);
    });
  });

  /** A FHIRcast STU3 websocket subscribe, at the hub URL the shim fronts, with `bearer` and any other `headers`. */
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
        "hub.events": "Patient-open,Patient-close",
      }),
    });
  }

  /** A FHIRcast STU3 unsubscribe of the subscription at the channel endpoint `id`. */
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

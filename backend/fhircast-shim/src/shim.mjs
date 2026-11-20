import { createPublicKey, randomBytes, randomUUID, verify } from "node:crypto";
import { createServer, request } from "node:http";
import { connect } from "node:net";
import { Transform } from "node:stream";

import { medplumAdmin } from "./medplum.mjs";

const JSON_TYPE = {
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
};

/**
 * Medplum's FHIRcast hub, behind the check Epic's hub makes and Medplum's does
 * not: the hub accepts any Medplum access token — the SMART access token of an
 * EHR launch, as Epic's does — while Medplum's introspection calls it active
 * and its `exp` is ahead. Once it is not — its `exp` has passed, or it has
 * been revoked — every subscription made with it is denied on its open socket,
 * FHIRcast's `hub.mode` `denied`, and its endpoint from then on. It also holds
 * each subscription to a lease of its own, which Medplum grants but never
 * enforces on an open socket: the hub's confirmation states what is left of
 * it, the socket is closed when it runs out, and the endpoint is denied from
 * then on. An endpoint is denied as Medplum denies one it does not know.
 *
 * Beneath `hub.url` it answers what Epic's hub answers and Medplum's does not:
 * the hub's configuration, the registration of a dynamic client by the SMART
 * access token, and the token endpoint that issues that client a hub token
 * for an RS384 client assertion. The hub accepts a hub token as `Bearer` as it
 * does a Medplum access token, until the hub token's own lifetime ends, and
 * denies its subscriptions the same way then.
 *
 * @param {object} options
 * @param {string} options.medplumUrl Medplum's own URL, as the shim reaches it
 * @param {{email: string, password: string}} options.admin Medplum's super admin
 * @param {number} options.leaseSeconds how long a subscription's lease lasts, from its first socket
 * @param {number} options.tokenCheckSeconds how often the hub checks the bearer
 *   of every standing subscription for its expiry or revocation
 * @param {string} options.hubAudience the hub's audience, a URN the stand-in's
 *   environment names: what its configuration states, and a client
 *   assertion's `aud` must be
 * @param {number} options.hubTokenSeconds how long a hub token lasts
 * @param {boolean} options.testState whether `GET /shim/test/state`,
 *   `POST /shim/test/drop`, `POST /shim/test/end-lease`,
 *   `POST /shim/test/expire`, `POST /shim/test/read-only` and
 *   `POST /shim/test/forget-client` answer; the state lists the channel
 *   endpoints whose lease has ended, and counts the registrations made
 * @param {() => number} [options.now] the clock, in milliseconds
 */
export function createShim(options) {
  const medplum = medplumAdmin(options.medplumUrl, options.admin);
  const nowMs = options.now ?? Date.now;

  function handleTestState(res) {
    sendJson(res, 200, {
      endedLeases: [...leaseEnds]
        .filter(([, end]) => end <= nowMs())
        .map(([id]) => id),
      registrations,
    });
  }

  /**
   * Bearer -> what the hub holds of it, once it has seen it. A bearer is one
   * of two kinds. A Medplum access token is any bearer on a hub path the shim
   * did not issue: the SMART access token of an EHR launch through the
   * stand-in, or the super admin's own, which E2E subscribes with; it is
   * active while Medplum's introspection calls it so, and `clientId` is the
   * client Medplum issued it to, as its introspection names it. A hub token,
   * which the hub's own token endpoint issues, the shim alone holds, so it
   * never reaches Medplum, and it is active throughout its lifetime.
   *
   * Either kind holds `endsAt`, when the hub stops accepting it, in ms: a
   * Medplum access token's `exp`, as Medplum's introspection first stated it,
   * or a hub token's lifetime's end; or sooner, once it has been expired at
   * once or found revoked. `ended` is whether its subscriptions have been
   * denied for that, `isActive()` whether its issuer still calls it active,
   * and `fromMedplum` whether it is a Medplum access token.
   */
  const bearers = new Map();

  /** What the hub holds of the Medplum access token `token`, whose introspection stated `claims`. */
  function heldMedplumToken(token, claims) {
    return {
      endsAt: claims.exp * 1000,
      ended: false,
      fromMedplum: true,
      clientId: claims.client_id,
      isActive: async () => (await medplum.activeClaims(token)) !== undefined,
    };
  }

  /** What the hub holds of a hub token, or of a bearer expired before it was seen, until `endsAt`. */
  function heldHubToken(endsAt) {
    return {
      endsAt,
      ended: false,
      fromMedplum: false,
      isActive: async () => true,
    };
  }

  /**
   * What the hub holds of the bearer `token` while the hub accepts it — its
   * issuer calls it active, and its end is still ahead on the shim's clock —
   * and `undefined` while it does not. A bearer the hub has not seen is a
   * Medplum access token, held from its first introspection that calls it
   * active.
   */
  async function liveBearer(token) {
    const held = bearers.get(token);
    if (held === undefined) {
      const claims = await medplum.activeClaims(token);
      if (claims === undefined) return undefined;
      bearers.set(token, heldMedplumToken(token, claims));
    } else if (!(await held.isActive())) {
      return undefined;
    }
    const live = bearers.get(token);
    return live.endsAt > nowMs() ? live : undefined;
  }

  /** channel endpoint id -> the subscription made there with a bearer: that bearer, and its topic and events. */
  const bearerSubscriptions = new Map();

  /**
   * What a bearer's request to the hub did, from Medplum's answer, which
   * names the channel endpoint either way: a subscribe accepted stands there
   * until its bearer ends; an unsubscribe accepted ends that.
   */
  function recordHubAnswer({ token, body }, status, answer) {
    const form = new URLSearchParams(body.toString("utf8"));
    const id = channelEndpointIdOfUrl(
      parseJson(answer.toString("utf8"))?.["hub.channel.endpoint"],
    );
    if (status !== 202 || id === undefined) return;
    if (form.get("hub.mode") === "subscribe") {
      bearerSubscriptions.set(id, {
        token,
        topic: form.get("hub.topic"),
        events: form.get("hub.events"),
      });
    } else if (form.get("hub.mode") === "unsubscribe") {
      bearerSubscriptions.delete(id);
    }
  }

  /**
   * The bearer `token` ends now, if it has not already: the hub accepts it no
   * more, and every subscription made with it is denied on its open sockets,
   * for `reason`.
   */
  function endBearer(token, reason) {
    const held = bearers.get(token) ?? heldHubToken(nowMs());
    bearers.set(token, held);
    if (held.ended) return;
    held.ended = true;
    held.endsAt = Math.min(held.endsAt, nowMs());
    for (const [id, subscription] of bearerSubscriptions) {
      if (subscription.token === token) denyTunnelsAt(id, subscription, reason);
    }
  }

  /**
   * Checks the bearer of every standing subscription, and ends each one that
   * is no longer live: its end has passed, so it has expired, or its issuer no
   * longer calls it active, so it has been revoked, as when the Login of a
   * Medplum access token is signed out.
   */
  async function checkBearers() {
    const standing = new Set(
      [...bearerSubscriptions.values()].map(({ token }) => token),
    );
    for (const token of standing) {
      const held = bearers.get(token);
      if (held.endsAt <= nowMs()) {
        endBearer(token, TOKEN_EXPIRED);
      } else if (!(await isStillActive(held))) {
        endBearer(token, TOKEN_REVOKED);
      }
    }
  }

  /**
   * Whether the issuer of the bearer the hub holds as `held` still calls it
   * active; it is taken to while the issuer cannot answer, which revokes
   * nothing.
   */
  async function isStillActive(held) {
    try {
      return await held.isActive();
    } catch {
      return true;
    }
  }

  /** The next check of the bearers; none once the server has closed. */
  let nextCheck;

  function scheduleCheck() {
    nextCheck = setTimeout(
      () =>
        checkBearers().finally(() => {
          if (nextCheck !== undefined) scheduleCheck();
        }),
      options.tokenCheckSeconds * 1000,
    );
    nextCheck.unref();
  }

  /** Whether the channel endpoint `id` holds a subscription whose bearer has ended. */
  function isTokenEndedAt(id) {
    const subscription = bearerSubscriptions.get(id);
    return (
      subscription !== undefined &&
      bearers.get(subscription.token).endsAt <= nowMs()
    );
  }

  /**
   * Everything that is not the shim's own goes to Medplum unchanged, except
   * a bearer on a hub path, which is exchanged for the super admin's Medplum
   * token while the hub accepts it, so the subscription lands on the topic
   * the super admin publishes to. One it no longer accepts is refused without
   * reaching Medplum.
   */
  async function forward(req, res) {
    const headers = withoutHopByHopHeaders(req.headers);
    const bearer = bearerOf(req);
    // Medplum's origin with the request's path alone, dot segments resolved:
    // an absolute request target cannot name another host, and the hub check
    // below sees the path Medplum will, which the raw request line could
    // otherwise name and then climb out of.
    const { pathname, search } = new URL(req.url, "http://shim");
    const target = new URL(`${pathname}${search}`, options.medplumUrl);
    const onHub = target.pathname.startsWith("/fhircast/");
    /** A bearer's request to the hub: read whole, so what it subscribes is recorded. */
    let bearerRequest;
    if (bearer !== undefined && onHub) {
      if ((await liveBearer(bearer)) === undefined) {
        return sendJson(res, 401, { error: "invalid_token" });
      }
      bearerRequest = { token: bearer, body: await readBytes(req) };
      // Medplum's answer is read too, so it must come uncompressed.
      delete headers["accept-encoding"];
      headers.authorization = `Bearer ${await medplum.token()}`;
    }
    headers.host = target.host;
    const upstream = request(
      target,
      { method: req.method, headers },
      (answer) => {
        if (bearerRequest === undefined) {
          res.writeHead(answer.statusCode ?? 502, answer.headers);
          return answer.pipe(res);
        }
        readBytes(answer).then(
          (body) => {
            recordHubAnswer(bearerRequest, answer.statusCode, body);
            res.writeHead(answer.statusCode ?? 502, answer.headers);
            res.end(body);
          },
          () => res.destroy(),
        );
      },
    );
    upstream.on("error", () => {
      if (!res.headersSent)
        sendJson(res, 502, { error: "temporarily_unavailable" });
      else res.destroy();
    });
    if (bearerRequest === undefined) req.pipe(upstream);
    else upstream.end(bearerRequest.body);
  }

  /** Upgraded sockets, which the HTTP server no longer tracks: closed with it. */
  const tunnels = new Set();

  /** channel endpoint id -> when its lease ends, in ms. */
  const leaseEnds = new Map();

  /**
   * channel endpoint id -> the tunnels open at it: each one's two sockets, the
   * browser's and Medplum's, and the relay between them.
   */
  const tunnelsAt = new Map();

  /** Closes every tunnel open at the channel endpoint `id`, both its ends. */
  function dropTunnelsAt(id) {
    for (const { socket, upstream } of tunnelsAt.get(id) ?? []) {
      socket.destroy();
      upstream.destroy();
    }
  }

  /**
   * FHIRcast's denial of `subscription` on every tunnel open at the channel
   * endpoint `id`, as Medplum's hub denies one: the denial, naming its topic,
   * its events and `reason`, then a close frame. Medplum's side is stopped and
   * closed first, so nothing of its follows.
   */
  function denyTunnelsAt(id, { topic, events }, reason) {
    const denial = textFrame(
      JSON.stringify({
        "hub.mode": "denied",
        "hub.topic": topic,
        "hub.events": events,
        "hub.reason": reason,
      }),
    );
    for (const { socket, upstream, relay } of tunnelsAt.get(id) ?? []) {
      upstream.unpipe();
      socket.unpipe();
      upstream.destroy();
      relay.unpipe();
      socket.end(Buffer.concat([denial, CLOSE_FRAME]));
      // Reads the browser's own close frame, and its end of the connection.
      socket.resume();
    }
  }

  /**
   * When the lease of the channel endpoint `id` ends, begun with its first
   * socket — Medplum's answer to the subscription names no lease, so that is
   * the shim's first sight of it — and its tunnels closed then.
   */
  function leaseEndOf(id) {
    if (!leaseEnds.has(id)) {
      const lease = options.leaseSeconds * 1000;
      leaseEnds.set(id, nowMs() + lease);
      setTimeout(() => dropTunnelsAt(id), lease).unref();
    }
    return leaseEnds.get(id);
  }

  /**
   * A websocket upgrade — the hub's `hub.channel.endpoint` — goes to Medplum
   * byte for byte, but for three things: the Host; no extension offer, so the
   * hub's frames stay plain; and the subscription's standing. At an endpoint
   * the hub denies — its lease has run out, or the bearer its subscription
   * was made with has expired or been revoked — the request
   * names an endpoint Medplum does not know, so Medplum denies it itself; at
   * one still open, the hub's confirmation states the whole seconds left of
   * the lease. Then both directions are piped.
   */
  function tunnel(req, socket, head) {
    const target = new URL(options.medplumUrl);
    const id = channelEndpointIdOfTarget(req.url);
    const leaseEnd = id === undefined ? undefined : leaseEndOf(id);
    const open =
      leaseEnd !== undefined && leaseEnd > nowMs() && !isTokenEndedAt(id);
    const requestTarget =
      id === undefined || open
        ? req.url
        : req.url.replace(`/${id}`, `/${id}${DENIED}`);
    const relay = open
      ? hubFrames(() => Math.max(1, Math.ceil((leaseEnd - nowMs()) / 1000)))
      : undefined;
    const upstream = connect(Number(target.port || 80), target.hostname, () => {
      const lines = [`${req.method} ${requestTarget} HTTP/1.1`];
      for (let i = 0; i < req.rawHeaders.length; i += 2) {
        const name = req.rawHeaders[i];
        if (name.toLowerCase() === "sec-websocket-extensions") continue;
        const value =
          name.toLowerCase() === "host" ? target.host : req.rawHeaders[i + 1];
        lines.push(`${name}: ${value}`);
      }
      upstream.write(`${lines.join("\r\n")}\r\n\r\n`);
      if (head.length > 0) upstream.write(head);
      socket.pipe(upstream);
      if (relay === undefined) return upstream.pipe(socket);
      upstream.pipe(relay).pipe(socket);
    });
    for (const end of [socket, upstream]) {
      tunnels.add(end);
      end.on("close", () => tunnels.delete(end));
    }
    if (relay !== undefined) {
      const opened = { socket, upstream, relay };
      if (!tunnelsAt.has(id)) tunnelsAt.set(id, new Set());
      tunnelsAt.get(id).add(opened);
      socket.on("close", () => tunnelsAt.get(id).delete(opened));
    }
    upstream.on("error", () => socket.destroy());
    socket.on("error", () => upstream.destroy());
  }

  /**
   * `POST /shim/test/drop?endpoint={id}`: closes every tunnel at that channel
   * endpoint, as a network fault would, for E2E's dropped-socket case.
   */
  function handleTestDrop(req, res) {
    const id = new URL(req.url, "http://shim").searchParams.get("endpoint");
    if (id !== null) dropTunnelsAt(id);
    sendNoContent(res);
  }

  /**
   * `POST /shim/test/end-lease?endpoint={id}`: that channel endpoint's lease
   * ends at once, as when it runs out — its tunnels closed, the endpoint
   * denied and listed as ended from then on — so E2E need not wait out a
   * real lease.
   */
  function handleTestEndLease(req, res) {
    const id = new URL(req.url, "http://shim").searchParams.get("endpoint");
    if (id !== null) {
      leaseEnds.set(id, nowMs());
      dropTunnelsAt(id);
    }
    sendNoContent(res);
  }

  /**
   * `POST /shim/test/expire`, the form's `token` a SMART access token or a
   * hub token: that token counts as expired at once, as when its lifetime
   * ends, so E2E need not wait out a real token's lifetime.
   */
  async function handleTestExpire(req, res) {
    const token = new URLSearchParams(await readBody(req)).get("token");
    if (token !== null) endBearer(token, TOKEN_EXPIRED);
    sendNoContent(res);
  }

  /** Whether the hub is read-only: it registers no client while it is. */
  let readOnly = false;

  /**
   * `GET {hub.url}/subspace-configuration`: the hub's audience, which a
   * client assertion at its token endpoint names as its `aud`, and whether it
   * is read-only, `"1"` or `"0"`, as Epic's hub states both.
   */
  function handleConfiguration(res) {
    sendJson(res, 200, {
      audience: options.hubAudience,
      isReadOnly: readOnly ? "1" : "0",
    });
  }

  /** dynamic `client_id` -> the RSA public key its registration named. */
  const dynamicClients = new Map();

  /** How many clients the hub has registered, for E2E assertions. */
  let registrations = 0;

  /**
   * `POST {hub.url}/register`, as Epic's hub registers a client: the bearer a
   * live SMART access token, checked as the hub checks it on a subscription,
   * and a JSON body whose `software_id` is the `client_id` Medplum issued that
   * SMART access token to, and whose `jwks` holds one RSA public key. The hub
   * holds no static client ID of its own — Medplum assigns it when the seed
   * runs, after the shim has started — so the bearer's client stands for it:
   * through the stand-in that is the seeded static ClientApplication, and any
   * other Medplum client registers only under its own ID. Answers a new
   * dynamic `client_id`, whose client assertions at the token endpoint that
   * key verifies; refused while the hub is read-only.
   */
  async function handleRegister(req, res) {
    const bearer = bearerOf(req);
    const held = bearer === undefined ? undefined : await liveBearer(bearer);
    if (held?.fromMedplum !== true) {
      return sendError(
        res,
        401,
        "invalid_token",
        "the bearer is not a live SMART access token",
      );
    }
    if (readOnly) {
      return sendError(res, 403, "access_denied", "the hub is read-only");
    }
    const body = parseJson(await readBody(req));
    if (held.clientId === undefined || body?.software_id !== held.clientId) {
      return sendError(
        res,
        400,
        "invalid_client_metadata",
        "software_id is not the static client ID",
      );
    }
    const publicKey = rsaPublicKeyOf(body.jwks);
    if (publicKey === undefined) {
      return sendError(
        res,
        400,
        "invalid_client_metadata",
        "jwks must hold one RSA public key",
      );
    }
    const clientId = randomUUID();
    dynamicClients.set(clientId, { publicKey, usedJtis: new Set() });
    registrations += 1;
    sendJson(res, 200, { client_id: clientId });
  }

  /**
   * `POST {hub.url}/token`, as Epic's hub issues a hub token: the form's
   * `grant_type` `client_credentials`, its `client_assertion_type` JWT
   * bearer, and its `client_assertion` a JWT a registered dynamic client's
   * key signed RS384, `iss` and `sub` that client's `client_id`, `aud` the
   * hub's audience, `exp` ahead but less than five minutes ahead, and a `jti`
   * that client has not used before. Answers a hub token, which the hub
   * accepts as `Bearer` until `hubTokenSeconds` have passed; `INVALID-CLIENT`
   * for a client it has not registered.
   */
  async function handleToken(req, res) {
    const form = new URLSearchParams(await readBody(req));
    if (form.get("grant_type") !== "client_credentials") {
      return sendError(
        res,
        400,
        "unsupported_grant_type",
        "grant_type must be client_credentials",
      );
    }
    if (form.get("client_assertion_type") !== JWT_BEARER) {
      return sendError(
        res,
        400,
        "invalid_request",
        `client_assertion_type must be ${JWT_BEARER}`,
      );
    }
    const jwt = parseJwt(form.get("client_assertion") ?? "");
    if (jwt === undefined) {
      return sendError(
        res,
        400,
        "invalid_request",
        "client_assertion is no JWT",
      );
    }
    const { claims } = jwt;
    const client =
      typeof claims.iss === "string"
        ? dynamicClients.get(claims.iss)
        : undefined;
    if (client === undefined) {
      return sendError(
        res,
        401,
        "INVALID-CLIENT",
        "the client is not registered",
      );
    }
    const refusal = assertionRefusal(jwt, client);
    if (refusal !== undefined) {
      return sendError(res, 400, "invalid_grant", refusal);
    }
    client.usedJtis.add(claims.jti);
    const accessToken = randomBytes(32).toString("base64url");
    bearers.set(
      accessToken,
      heldHubToken(nowMs() + options.hubTokenSeconds * 1000),
    );
    sendJson(res, 200, {
      access_token: accessToken,
      token_type: "bearer",
      expires_in: options.hubTokenSeconds,
      scope: HUB_TOKEN_SCOPE,
    });
  }

  /**
   * Why the hub refuses the client assertion `jwt` of the registered client
   * `client`, or `undefined` when it does not.
   */
  function assertionRefusal(
    { header, claims, signingInput, signature },
    client,
  ) {
    if (header.alg !== "RS384") return "the assertion must be signed RS384";
    if (!verify("sha384", signingInput, client.publicKey, signature)) {
      return "the assertion's signature does not verify";
    }
    if (claims.sub !== claims.iss) return "sub must be the client_id";
    if (claims.aud !== options.hubAudience) {
      return "aud must be the hub's audience";
    }
    if (typeof claims.exp !== "number" || claims.exp * 1000 <= nowMs()) {
      return "the assertion has expired";
    }
    if (claims.exp * 1000 >= nowMs() + MAX_ASSERTION_MS) {
      return "exp must be less than five minutes ahead";
    }
    if (typeof claims.jti !== "string" || claims.jti === "") {
      return "the assertion must carry a jti";
    }
    if (client.usedJtis.has(claims.jti)) return "the jti has been used";
    return undefined;
  }

  /**
   * `POST /shim/test/forget-client`, the form's `client_id` a dynamic
   * client's: the hub forgets that client, so its token endpoint answers
   * `INVALID-CLIENT` for it, as when Epic's hub has dropped a registration.
   */
  async function handleTestForgetClient(req, res) {
    const clientId = new URLSearchParams(await readBody(req)).get("client_id");
    if (clientId !== null) dynamicClients.delete(clientId);
    sendNoContent(res);
  }

  /**
   * `POST /shim/test/read-only`, the form's `isReadOnly` `"1"` or `"0"`:
   * switches the hub's read-only mode on or off, so E2E can have a
   * registration refused.
   */
  async function handleTestReadOnly(req, res) {
    const isReadOnly = new URLSearchParams(await readBody(req)).get(
      "isReadOnly",
    );
    if (isReadOnly === "1" || isReadOnly === "0") readOnly = isReadOnly === "1";
    sendNoContent(res);
  }

  /**
   * The shim's own paths: the hub's configuration, registration and token
   * endpoints beneath `hub.url`, and the test-only controls; every other goes
   * to Medplum.
   */
  function route(req, res) {
    const { pathname } = new URL(req.url, "http://shim");
    const methodAndPath = `${req.method} ${pathname}`;
    let handled;
    if (methodAndPath === `GET ${HUB_PATH}/subspace-configuration`)
      handled = handleConfiguration(res);
    else if (methodAndPath === `POST ${HUB_PATH}/register`)
      handled = handleRegister(req, res);
    else if (methodAndPath === `POST ${HUB_PATH}/token`)
      handled = handleToken(req, res);
    else if (
      methodAndPath === "POST /shim/test/forget-client" &&
      options.testState
    )
      handled = handleTestForgetClient(req, res);
    else if (methodAndPath === "GET /shim/test/state" && options.testState)
      handled = handleTestState(res);
    else if (methodAndPath === "POST /shim/test/drop" && options.testState)
      handled = handleTestDrop(req, res);
    else if (methodAndPath === "POST /shim/test/end-lease" && options.testState)
      handled = handleTestEndLease(req, res);
    else if (methodAndPath === "POST /shim/test/expire" && options.testState)
      handled = handleTestExpire(req, res);
    else if (methodAndPath === "POST /shim/test/read-only" && options.testState)
      handled = handleTestReadOnly(req, res);
    else if (pathname.startsWith("/shim/"))
      handled = sendJson(res, 404, { error: "not_found" });
    else handled = forward(req, res);
    Promise.resolve(handled).catch(() => {
      if (!res.headersSent)
        sendJson(res, 502, { error: "temporarily_unavailable" });
      else res.destroy();
    });
  }

  const server = createServer(
    // A websocket alone is tunnelled; any other offer to upgrade — Java's
    // HTTP client offers h2c on every request — is answered as plain HTTP.
    { shouldUpgradeCallback: isWebSocketUpgrade },
    route,
  );
  server.on("upgrade", tunnel);
  // A server closes only once every TCP connection has ended, upgraded ones
  // included, which Node's own closeAllConnections() does not reach.
  const closeHttpConnections = server.closeAllConnections.bind(server);
  server.closeAllConnections = () => {
    closeHttpConnections();
    for (const end of tunnels) end.destroy();
  };
  scheduleCheck();
  server.on("close", () => {
    clearTimeout(nextCheck);
    nextCheck = undefined;
  });
  return server;
}

/** Medplum's `hub.url`, beneath its base URL: the hub's own endpoints sit beneath it. */
const HUB_PATH = "/fhircast/STU3";

/** RFC 7523: the `client_assertion_type` of a JWT client assertion. */
const JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

/** How far ahead a client assertion's `exp` may be: less than five minutes. */
const MAX_ASSERTION_MS = 5 * 60 * 1000;

/** What a hub token is good for, in FHIRcast STU2's scope grammar (ADR 0016). */
const HUB_TOKEN_SCOPE =
  "fhircast/patient-open.read fhircast/patient-close.read";

/**
 * The compact JWT `text`: its header and claims, parsed, the input its
 * signature covers and the signature's bytes; `undefined` when it is not
 * three base64url parts whose first two are JSON objects.
 */
function parseJwt(text) {
  const parts = text.split(".");
  if (parts.length !== 3) return undefined;
  const [header, claims] = parts
    .slice(0, 2)
    .map((part) => parseJson(Buffer.from(part, "base64url").toString("utf8")));
  if (!isObject(header) || !isObject(claims)) return undefined;
  return {
    header,
    claims,
    signingInput: Buffer.from(`${parts[0]}.${parts[1]}`),
    signature: Buffer.from(parts[2], "base64url"),
  };
}

function isObject(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Where Medplum's hub answers a subscription's socket: `/ws/fhircast/{id}`. */
const CHANNEL_ENDPOINT = /^\/ws\/fhircast\/([^/?#]+)/;

/** The channel endpoint id an upgrade's request target names, if it names one. */
function channelEndpointIdOfTarget(requestTarget) {
  return CHANNEL_ENDPOINT.exec(requestTarget)?.[1];
}

/** The channel endpoint id the websocket URL `url` names, if it names one: a `hub.channel.endpoint`. */
function channelEndpointIdOfUrl(url) {
  const pathname = typeof url === "string" ? URL.parse(url)?.pathname : "";
  return channelEndpointIdOfTarget(pathname ?? "");
}

/** The `hub.reason` of the shim's denial of a subscription whose bearer has expired. */
const TOKEN_EXPIRED = "access token expired";

/** The `hub.reason` of the shim's denial of a subscription whose bearer has been revoked. */
const TOKEN_REVOKED = "access token revoked";

/** A close frame with no status, as Medplum's hub closes a socket after denying it. */
const CLOSE_FRAME = Buffer.from([0x88, 0x00]);

/** Appended to an endpoint the hub denies: an endpoint Medplum knows nothing of. */
const DENIED = "-denied";

/**
 * Medplum's answer to an upgrade, passed on a whole websocket frame at a
 * time, so the shim can put a frame of its own between any two: the handshake
 * as it came, then the first frame rewritten when it is the subscription's
 * confirmation, `hub.mode` `subscribe`, to state `secondsLeft()` as its
 * `hub.lease_seconds`, and every other as it came. A frame is held until the
 * whole of it has come; Medplum's hub sends each message as one frame.
 */
function hubFrames(secondsLeft) {
  let pending = Buffer.alloc(0);
  let handshakeSeen = false;
  let firstFrameSeen = false;
  return new Transform({
    transform(chunk, _encoding, done) {
      pending = Buffer.concat([pending, chunk]);
      if (!handshakeSeen) {
        const end = pending.indexOf("\r\n\r\n");
        if (end < 0) return done();
        handshakeSeen = true;
        this.push(pending.subarray(0, end + 4));
        pending = pending.subarray(end + 4);
      }
      const whole = wholeFramesLength(pending);
      if (whole === 0) return done();
      let frames = pending.subarray(0, whole);
      pending = pending.subarray(whole);
      if (!firstFrameSeen) {
        firstFrameSeen = true;
        frames = withLeaseIn(frames, secondsLeft());
      }
      done(null, frames);
    },
    flush(done) {
      done(null, pending);
    },
  });
}

/**
 * `frames`, whole websocket frames from the hub, with the first one's
 * `hub.lease_seconds` stated as `seconds` when it is the subscription's
 * confirmation; as they are when it is anything else.
 */
function withLeaseIn(frames, seconds) {
  const frame = frameAt(frames);
  const message =
    frames[0] === FINAL_TEXT_FRAME
      ? parseJson(frames.subarray(frame.start, frame.end).toString("utf8"))
      : undefined;
  if (message?.["hub.mode"] !== "subscribe") return frames;
  return Buffer.concat([
    textFrame(JSON.stringify({ ...message, "hub.lease_seconds": seconds })),
    frames.subarray(frame.end),
  ]);
}

/**
 * RFC 6455 §5.2: where the payload of the unmasked frame `bytes` start with
 * begins, and where the frame ends; `undefined` until the whole of it has come.
 */
function frameAt(bytes) {
  const payload = payloadOf(bytes);
  if (payload === undefined) return undefined;
  const end = payload.start + payload.length;
  return bytes.length < end ? undefined : { start: payload.start, end };
}

/**
 * RFC 6455 §5.2: where the payload of the unmasked frame `bytes` start with
 * begins, and how long it is, from the frame's length, carried in 7, 16 or 64
 * bits; `undefined` until the whole of that length has come.
 */
function payloadOf(bytes) {
  if (bytes.length < 2) return undefined;
  switch (bytes[1]) {
    case 126:
      return bytes.length < 4
        ? undefined
        : { start: 4, length: bytes.readUInt16BE(2) };
    case 127:
      return bytes.length < 10
        ? undefined
        : { start: 10, length: Number(bytes.readBigUInt64BE(2)) };
    default:
      return { start: 2, length: bytes[1] };
  }
}

/** How many of the bytes `bytes` start with are whole frames. */
function wholeFramesLength(bytes) {
  let length = 0;
  for (;;) {
    const frame = frameAt(bytes.subarray(length));
    if (frame === undefined) return length;
    length += frame.end;
  }
}

/** The first byte of a whole text frame: FIN set, opcode 1. */
const FINAL_TEXT_FRAME = 0x81;

/** An unmasked text frame, as a server sends one, holding `text`. */
function textFrame(text) {
  const payload = Buffer.from(text);
  const header =
    payload.length < 126
      ? Buffer.from([FINAL_TEXT_FRAME, payload.length])
      : Buffer.from([
          FINAL_TEXT_FRAME,
          126,
          payload.length >> 8,
          payload.length & 0xff,
        ]);
  return Buffer.concat([header, payload]);
}

function isWebSocketUpgrade(req) {
  return req.headers.upgrade?.toLowerCase() === "websocket";
}

/**
 * RFC 9110 §7.6.1: the headers that belong to this connection alone, and any
 * its Connection header names, go no further than the shim.
 */
function withoutHopByHopHeaders(incoming) {
  const named = (incoming.connection ?? "")
    .split(",")
    .map((name) => name.trim().toLowerCase());
  const hopByHop = new Set([
    "connection",
    "keep-alive",
    "proxy-connection",
    "te",
    "trailer",
    "upgrade",
    "http2-settings",
    ...named,
  ]);
  return Object.fromEntries(
    Object.entries(incoming).filter(([name]) => !hopByHop.has(name)),
  );
}

function bearerOf(req) {
  const match = /^Bearer (\S+)$/.exec(req.headers.authorization ?? "");
  return match?.[1];
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

async function readBody(req) {
  return (await readBytes(req)).toString("utf8");
}

function readBytes(stream) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    stream.on("data", (chunk) => chunks.push(chunk));
    stream.on("end", () => resolve(Buffer.concat(chunks)));
    stream.on("error", reject);
  });
}

/** An OAuth error answer: `error`, and `description` as its `error_description`. */
function sendError(res, status, error, description) {
  sendJson(res, status, { error, error_description: description });
}

/**
 * The one RSA public key the JWK Set `jwks` holds, as a key object; `undefined`
 * when it holds none, more than one, a key of another type, or a private one.
 */
function rsaPublicKeyOf(jwks) {
  const keys = jwks?.keys;
  if (!Array.isArray(keys) || keys.length !== 1) return undefined;
  const [jwk] = keys;
  if (jwk?.kty !== "RSA" || "d" in jwk) return undefined;
  try {
    return createPublicKey({ key: jwk, format: "jwk" });
  } catch {
    return undefined;
  }
}

function sendJson(res, status, body) {
  res.writeHead(status, JSON_TYPE);
  res.end(JSON.stringify(body));
}

/** A test-only control's answer: done, nothing to say. */
function sendNoContent(res) {
  res.writeHead(204, { "Cache-Control": "no-store" });
  res.end();
}

import { randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { createServer, request } from "node:http";
import { connect } from "node:net";
import { Transform } from "node:stream";

import {
  decodeJson,
  isUsablePublicKey,
  SIGNING_ALGORITHMS,
  verifiedClaims,
} from "./jws.mjs";
import { medplumAdmin } from "./medplum.mjs";

const JSON_TYPE = {
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
};

/**
 * The local hub's authorization role, which Medplum lacks: RFC 7591 dynamic
 * registration, the RFC 7523 `jwt-bearer` grant, RFC 7009 revocation and
 * RFC 7592 deletion. Its hub accepts, besides the hub tokens it issues, any
 * Medplum access token — the SMART access token of an EHR launch, as Epic's
 * does — while Medplum's introspection calls it active and its `exp` is ahead.
 * Once it is not — its `exp` has passed, or it has been revoked — every
 * subscription made with it is denied on its open socket, FHIRcast's
 * `hub.mode` `denied`, and its endpoint from then on. It also holds each
 * subscription to a lease of its own, which Medplum grants but never enforces
 * on an open socket: the hub's confirmation states what is left of it, the
 * socket is closed when it runs out, and the endpoint is denied from then on.
 * An endpoint is denied as Medplum denies one it does not know.
 *
 * @param {object} options
 * @param {string} options.publicBaseUrl the URL the browser and the backend reach the shim at, with a trailing slash
 * @param {string} options.medplumUrl Medplum's own URL, as the shim reaches it
 * @param {{email: string, password: string}} options.admin Medplum's super admin
 * @param {number} options.tokenLifetimeSeconds how long a hub token lasts
 * @param {number} options.leaseSeconds how long a subscription's lease lasts, from its first socket
 * @param {number} options.tokenCheckSeconds how often the hub checks the Medplum
 *   access token of every standing subscription for its expiry or revocation
 * @param {boolean} options.testState whether `GET /dcr/test/state`, `POST /dcr/test/drop` and
 *   `POST /dcr/test/expire` answer; the state lists the registrations, the hub
 *   tokens revoked and expired, and the channel endpoints whose lease has ended
 * @param {() => number} [options.now] the clock, in milliseconds
 */
export function createShim(options) {
  const medplum = medplumAdmin(options.medplumUrl, options.admin);
  const nowMs = options.now ?? Date.now;
  /** client_id -> registration */
  const registrations = new Map();

  async function handleRegister(req, res) {
    const bearer = bearerOf(req);
    if (
      bearer === undefined ||
      (await medplum.activeClaims(bearer)) === undefined
    ) {
      return sendJson(res, 401, { error: "invalid_token" });
    }
    const metadata = parseJson(await readBody(req));
    const keys = metadata?.jwks?.keys;
    if (
      !Array.isArray(keys) ||
      keys.length !== 1 ||
      !isUsablePublicKey(keys[0])
    ) {
      return sendJson(res, 400, {
        error: "invalid_client_metadata",
        error_description: `jwks must hold one public key for ${SIGNING_ALGORITHMS.join(" or ")}`,
      });
    }
    const registration = {
      client_id: randomUUID(),
      client_id_issued_at: Math.floor(nowMs() / 1000),
      registration_access_token: randomBytes(32).toString("base64url"),
      jwk: keys[0],
    };
    registrations.set(registration.client_id, registration);
    sendJson(res, 201, {
      client_id: registration.client_id,
      client_id_issued_at: registration.client_id_issued_at,
      registration_access_token: registration.registration_access_token,
      registration_client_uri: `${options.publicBaseUrl}dcr/register/${registration.client_id}`,
      grant_types: [JWT_BEARER],
      token_endpoint_auth_method: "private_key_jwt",
      jwks: { keys: [registration.jwk] },
    });
  }

  /** hub token -> what it was issued for */
  const hubTokens = new Map();

  const tokenEndpoint = `${options.publicBaseUrl}dcr/token`;

  /** RFC 7523 §3: `aud` names this token endpoint, as a string or among an array's values. */
  function isForThisTokenEndpoint({ aud }) {
    return Array.isArray(aud)
      ? aud.includes(tokenEndpoint)
      : aud === tokenEndpoint;
  }

  /** RFC 7523 §3: an `exp` is required, and the assertion is refused once it has passed. */
  function isUnexpired({ exp }) {
    return Number.isFinite(exp) && exp * 1000 > nowMs();
  }

  /** `${client_id} ${jti}` -> the assertion's exp, in ms: kept until it would be refused anyway. */
  const usedJtis = new Map();

  /**
   * RFC 7523 §3: a `jti` is required, and each is accepted once. Checked last,
   * so only an assertion that passed every other check uses its `jti` up.
   */
  function isFirstUseOfJti(clientId, { jti, exp }) {
    if (typeof jti !== "string" || jti === "") return false;
    for (const [used, until] of usedJtis) {
      if (until <= nowMs()) usedJtis.delete(used);
    }
    const key = `${clientId} ${jti}`;
    if (usedJtis.has(key)) return false;
    usedJtis.set(key, exp * 1000);
    return true;
  }

  async function handleToken(req, res) {
    const form = new URLSearchParams(await readBody(req));
    if (form.get("grant_type") !== JWT_BEARER) {
      return sendJson(res, 400, { error: "unsupported_grant_type" });
    }
    const assertion = form.get("assertion") ?? "";
    const [, payload] = assertion.split(".");
    const registration = registrations.get(decodeJson(payload)?.iss);
    const claims = registration && verifiedClaims(assertion, registration.jwk);
    if (
      claims === undefined ||
      claims.sub !== registration.client_id ||
      !isForThisTokenEndpoint(claims) ||
      !isUnexpired(claims) ||
      !isFirstUseOfJti(registration.client_id, claims)
    ) {
      return sendJson(res, 400, { error: "invalid_grant" });
    }
    const accessToken = `${HUB_TOKEN_PREFIX}${randomBytes(32).toString("base64url")}`;
    hubTokens.set(accessToken, {
      clientId: registration.client_id,
      expiresAt: nowMs() + options.tokenLifetimeSeconds * 1000,
    });
    sendJson(res, 200, {
      access_token: accessToken,
      token_type: "Bearer",
      expires_in: options.tokenLifetimeSeconds,
    });
  }

  /**
   * RFC 7592 §2.3: the registration is deleted when the caller presents its
   * registration access token; its client_id then gets no further token.
   */
  function handleDeleteRegistration(req, res, clientId) {
    const registration = registrations.get(clientId);
    const presented = bearerOf(req);
    if (
      registration === undefined ||
      presented === undefined ||
      !sameSecret(presented, registration.registration_access_token)
    ) {
      return sendJson(res, 401, { error: "invalid_token" });
    }
    registrations.delete(clientId);
    res.writeHead(204, { "Cache-Control": "no-store" });
    res.end();
  }

  /** The shim's endpoints, as the hub's metadata names them. */
  const endpoints = {
    registration_endpoint: `${options.publicBaseUrl}dcr/register`,
    token_endpoint: tokenEndpoint,
    revocation_endpoint: `${options.publicBaseUrl}dcr/revoke`,
  };

  /** Medplum's own hub metadata, with the shim's endpoints added. */
  async function handleFhircastConfiguration(res) {
    const answer = await fetch(
      new URL(FHIRCAST_CONFIGURATION, options.medplumUrl),
    );
    if (!answer.ok)
      return sendJson(res, 502, { error: "temporarily_unavailable" });
    sendJson(res, 200, { ...(await answer.json()), ...endpoints });
  }

  function handleTestState(res) {
    sendJson(res, 200, {
      registrations: [...registrations.values()].map(
        ({ client_id, client_id_issued_at, jwk }) => ({
          client_id,
          client_id_issued_at,
          jwk,
        }),
      ),
      revokedTokens: [...hubTokens]
        .filter(([, issued]) => issued.revoked)
        .map(([token]) => token),
      expiredTokens: [...hubTokens]
        .filter(([, issued]) => issued.expiresAt <= nowMs())
        .map(([token]) => token),
      endedLeases: [...leaseEnds]
        .filter(([, end]) => end <= nowMs())
        .map(([id]) => id),
    });
  }

  /** Whether `token` is one of ours that may still be used. */
  function isLiveHubToken(token) {
    const issued = hubTokens.get(token);
    return (
      issued !== undefined &&
      !issued.revoked &&
      issued.expiresAt > nowMs() &&
      registrations.has(issued.clientId)
    );
  }

  /**
   * Medplum access token -> what the hub holds of it. A Medplum access token
   * is any bearer on a hub path but a hub token: the SMART access token of an
   * EHR launch through the stand-in, or the super admin's own, which E2E
   * subscribes with. `endsAt` is when the hub stops accepting it, in ms: its
   * `exp`, as Medplum's introspection first stated it, or sooner, once it has
   * been expired at once or found revoked. `ended` is whether its
   * subscriptions have been denied for that.
   */
  const medplumTokens = new Map();

  /**
   * Whether the hub may accept the Medplum access token `token`: Medplum's
   * introspection calls it active, and its end is still ahead on the shim's
   * clock.
   */
  async function isLiveMedplumToken(token) {
    const claims = await medplum.activeClaims(token);
    if (claims === undefined) return false;
    if (!medplumTokens.has(token)) {
      medplumTokens.set(token, { endsAt: claims.exp * 1000, ended: false });
    }
    return medplumTokens.get(token).endsAt > nowMs();
  }

  /** channel endpoint id -> the subscription made there with a Medplum access token: that token, and its topic and events. */
  const medplumTokenSubscriptions = new Map();

  /**
   * What a Medplum access token's request to the hub did, from Medplum's
   * answer, which names the channel endpoint either way: a subscribe accepted
   * stands there until its token ends; an unsubscribe accepted ends that.
   */
  function recordHubAnswer({ token, body }, status, answer) {
    const form = new URLSearchParams(body.toString("utf8"));
    const id = channelEndpointIdOfUrl(
      parseJson(answer.toString("utf8"))?.["hub.channel.endpoint"],
    );
    if (status !== 202 || id === undefined) return;
    if (form.get("hub.mode") === "subscribe") {
      medplumTokenSubscriptions.set(id, {
        token,
        topic: form.get("hub.topic"),
        events: form.get("hub.events"),
      });
    } else if (form.get("hub.mode") === "unsubscribe") {
      medplumTokenSubscriptions.delete(id);
    }
  }

  /**
   * The Medplum access token `token` ends now, if it has not already: the hub
   * accepts it no more, and every subscription made with it is denied on its
   * open sockets, for `reason`.
   */
  function endMedplumToken(token, reason) {
    const held = medplumTokens.get(token) ?? { endsAt: nowMs(), ended: false };
    medplumTokens.set(token, held);
    if (held.ended) return;
    held.ended = true;
    held.endsAt = Math.min(held.endsAt, nowMs());
    for (const [id, subscription] of medplumTokenSubscriptions) {
      if (subscription.token === token) denyTunnelsAt(id, subscription, reason);
    }
  }

  /**
   * Checks the Medplum access token of every standing subscription, and ends
   * each one that is no longer live: its end has passed, so it has expired, or
   * Medplum's introspection no longer calls it active, so it has been revoked,
   * as when its Login is signed out.
   */
  async function checkMedplumTokens() {
    const standing = new Set(
      [...medplumTokenSubscriptions.values()].map(({ token }) => token),
    );
    for (const token of standing) {
      if (medplumTokens.get(token).endsAt <= nowMs()) {
        endMedplumToken(token, TOKEN_EXPIRED);
      } else if (!(await isStillActive(token))) {
        endMedplumToken(token, TOKEN_REVOKED);
      }
    }
  }

  /**
   * Whether Medplum's introspection still calls `token` active; it is taken
   * to while Medplum cannot answer, which revokes nothing.
   */
  async function isStillActive(token) {
    try {
      return (await medplum.activeClaims(token)) !== undefined;
    } catch {
      return true;
    }
  }

  /** The next check of the Medplum access tokens; none once the server has closed. */
  let nextCheck;

  function scheduleCheck() {
    nextCheck = setTimeout(
      () =>
        checkMedplumTokens().finally(() => {
          if (nextCheck !== undefined) scheduleCheck();
        }),
      options.tokenCheckSeconds * 1000,
    );
    nextCheck.unref();
  }

  /** Whether the channel endpoint `id` holds a subscription whose Medplum access token has ended. */
  function isTokenEndedAt(id) {
    const subscription = medplumTokenSubscriptions.get(id);
    return (
      subscription !== undefined &&
      medplumTokens.get(subscription.token).endsAt <= nowMs()
    );
  }

  /**
   * RFC 7009: the token is revoked, and the answer is 200 whether or not it
   * was one of ours, so a caller learns nothing about tokens it does not hold.
   */
  async function handleRevoke(req, res) {
    const token = new URLSearchParams(await readBody(req)).get("token");
    const issued = hubTokens.get(token);
    if (issued !== undefined) issued.revoked = true;
    res.writeHead(200, { "Cache-Control": "no-store" });
    res.end();
  }

  /**
   * Everything that is not the shim's own goes to Medplum unchanged, except
   * a bearer on a hub path, which is exchanged for the super admin's Medplum
   * token while it is live — one of our hub tokens, or else a Medplum access
   * token — so the subscription lands on the topic the super admin publishes
   * to. One no longer live is refused without reaching Medplum, and a hub
   * token opens nothing else.
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
    /** A Medplum access token's request to the hub: read whole, so what it subscribes is recorded. */
    let medplumTokenRequest;
    if (bearer?.startsWith(HUB_TOKEN_PREFIX)) {
      if (!onHub || !isLiveHubToken(bearer)) {
        return sendJson(res, 401, { error: "invalid_token" });
      }
    } else if (bearer !== undefined && onHub) {
      if (!(await isLiveMedplumToken(bearer))) {
        return sendJson(res, 401, { error: "invalid_token" });
      }
      medplumTokenRequest = { token: bearer, body: await readBytes(req) };
      // Medplum's answer is read too, so it must come uncompressed.
      delete headers["accept-encoding"];
    }
    if (bearer !== undefined && onHub) {
      headers.authorization = `Bearer ${await medplum.token()}`;
    }
    headers.host = target.host;
    const upstream = request(
      target,
      { method: req.method, headers },
      (answer) => {
        if (medplumTokenRequest === undefined) {
          res.writeHead(answer.statusCode ?? 502, answer.headers);
          return answer.pipe(res);
        }
        readBytes(answer).then(
          (body) => {
            recordHubAnswer(medplumTokenRequest, answer.statusCode, body);
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
    if (medplumTokenRequest === undefined) req.pipe(upstream);
    else upstream.end(medplumTokenRequest.body);
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
   * the hub denies — its lease has run out, or the Medplum access token its
   * subscription was made with has expired or been revoked — the request
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
   * `POST /dcr/test/drop?endpoint={id}`: closes every tunnel at that channel
   * endpoint, as a network fault would, for E2E's dropped-socket case.
   */
  function handleTestDrop(req, res) {
    const id = new URL(req.url, "http://shim").searchParams.get("endpoint");
    if (id !== null) dropTunnelsAt(id);
    res.writeHead(204, { "Cache-Control": "no-store" });
    res.end();
  }

  /**
   * `POST /dcr/test/expire`, the form's `token` a SMART access token: that
   * token counts as expired at once, as when its `exp` passes, so E2E need not
   * wait out a real token's lifetime.
   */
  async function handleTestExpire(req, res) {
    const token = new URLSearchParams(await readBody(req)).get("token");
    if (token !== null) endMedplumToken(token, TOKEN_EXPIRED);
    res.writeHead(204, { "Cache-Control": "no-store" });
    res.end();
  }

  function route(req, res) {
    const { pathname } = new URL(req.url, "http://shim");
    const methodAndPath = `${req.method} ${pathname}`;
    // The SPA calls the shim's own endpoints from the app's origin, with the
    // SMART token as Bearer, so they answer CORS; Medplum answers its own.
    if (isShimPath(pathname)) {
      allowAnyOrigin(res);
      if (req.method === "OPTIONS") {
        res.writeHead(204);
        return res.end();
      }
    }
    let handled;
    if (methodAndPath === `GET ${FHIRCAST_CONFIGURATION}`)
      handled = handleFhircastConfiguration(res);
    else if (methodAndPath === "POST /dcr/register")
      handled = handleRegister(req, res);
    else if (methodAndPath === "POST /dcr/token")
      handled = handleToken(req, res);
    else if (methodAndPath === "POST /dcr/revoke")
      handled = handleRevoke(req, res);
    else if (req.method === "DELETE" && pathname.startsWith(REGISTRATION_PATH))
      handled = handleDeleteRegistration(
        req,
        res,
        pathname.slice(REGISTRATION_PATH.length),
      );
    else if (methodAndPath === "GET /dcr/test/state" && options.testState)
      handled = handleTestState(res);
    else if (methodAndPath === "POST /dcr/test/drop" && options.testState)
      handled = handleTestDrop(req, res);
    else if (methodAndPath === "POST /dcr/test/expire" && options.testState)
      handled = handleTestExpire(req, res);
    else if (pathname.startsWith("/dcr/"))
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

export const JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";

/**
 * What sets the shim's hub tokens apart from Medplum's own access tokens,
 * which the hub accepts while Medplum calls them live, and which open the rest
 * of Medplum as Medplum decides.
 */
const HUB_TOKEN_PREFIX = "hub_";

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

/** The `hub.reason` of the shim's denial of a subscription whose Medplum access token has expired. */
const TOKEN_EXPIRED = "access token expired";

/** The `hub.reason` of the shim's denial of a subscription whose Medplum access token has been revoked. */
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

/** The hub's metadata, under the hub URL Medplum's token response names. */
const FHIRCAST_CONFIGURATION =
  "/fhircast/STU3/.well-known/fhircast-configuration";

/** The paths the shim answers itself; every other goes to Medplum. */
function isShimPath(pathname) {
  return pathname.startsWith("/dcr/") || pathname === FHIRCAST_CONFIGURATION;
}

/**
 * Any origin, as Medplum's own `allowedOrigins` here: whichever port the
 * backend serves the SPA on. `*` grants no credentials, and none is wanted:
 * the SPA sends its tokens as Bearer, never as cookies.
 */
function allowAnyOrigin(res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE");
  res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
  res.setHeader("Access-Control-Max-Age", "600");
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

/** Where a registration lives, RFC 7592's client configuration endpoint, by client_id. */
const REGISTRATION_PATH = "/dcr/register/";

/** Whether two secrets are equal, compared in constant time. */
function sameSecret(presented, expected) {
  const a = Buffer.from(presented);
  const b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
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

export async function readBody(req) {
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

function sendJson(res, status, body) {
  res.writeHead(status, JSON_TYPE);
  res.end(JSON.stringify(body));
}

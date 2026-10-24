import { randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { createServer, request } from "node:http";
import { connect } from "node:net";

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
 * RFC 7592 deletion.
 *
 * @param {object} options
 * @param {string} options.publicBaseUrl the URL the browser and the backend reach the shim at, with a trailing slash
 * @param {string} options.medplumUrl Medplum's own URL, as the shim reaches it
 * @param {{email: string, password: string}} options.admin Medplum's super admin
 * @param {number} options.tokenLifetimeSeconds how long a hub token lasts
 * @param {boolean} options.testState whether `GET /dcr/test/state` answers
 * @param {() => number} [options.now] the clock, in milliseconds
 */
export function createShim(options) {
  const medplum = medplumAdmin(options.medplumUrl, options.admin);
  const nowMs = options.now ?? Date.now;
  /** client_id -> registration */
  const registrations = new Map();

  async function handleRegister(req, res) {
    const bearer = bearerOf(req);
    if (bearer === undefined || !(await medplum.isActive(bearer))) {
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
   * that a hub token on a hub path is exchanged for the super admin's Medplum
   * token: the hub accepts our tokens only through here. A hub token that is
   * no longer live is refused without reaching Medplum.
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
    if (bearer?.startsWith(HUB_TOKEN_PREFIX)) {
      if (
        !target.pathname.startsWith("/fhircast/") ||
        !isLiveHubToken(bearer)
      ) {
        return sendJson(res, 401, { error: "invalid_token" });
      }
      headers.authorization = `Bearer ${await medplum.token()}`;
    }
    headers.host = target.host;
    const upstream = request(
      target,
      { method: req.method, headers },
      (answer) => {
        res.writeHead(answer.statusCode ?? 502, answer.headers);
        answer.pipe(res);
      },
    );
    upstream.on("error", () => {
      if (!res.headersSent)
        sendJson(res, 502, { error: "temporarily_unavailable" });
      else res.destroy();
    });
    req.pipe(upstream);
  }

  /** Upgraded sockets, which the HTTP server no longer tracks: closed with it. */
  const tunnels = new Set();

  /**
   * A websocket upgrade — the hub's `hub.channel.endpoint` — goes to Medplum
   * byte for byte: the request line and headers as received, Host aside, and
   * then both directions piped.
   */
  function tunnel(req, socket, head) {
    const target = new URL(options.medplumUrl);
    const upstream = connect(Number(target.port || 80), target.hostname, () => {
      const lines = [`${req.method} ${req.url} HTTP/1.1`];
      for (let i = 0; i < req.rawHeaders.length; i += 2) {
        const name = req.rawHeaders[i];
        const value =
          name.toLowerCase() === "host" ? target.host : req.rawHeaders[i + 1];
        lines.push(`${name}: ${value}`);
      }
      upstream.write(`${lines.join("\r\n")}\r\n\r\n`);
      if (head.length > 0) upstream.write(head);
      socket.pipe(upstream).pipe(socket);
    });
    for (const end of [socket, upstream]) {
      tunnels.add(end);
      end.on("close", () => tunnels.delete(end));
    }
    upstream.on("error", () => socket.destroy());
    socket.on("error", () => upstream.destroy());
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
  return server;
}

export const JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";

/** What sets the shim's hub tokens apart from Medplum's own, which pass through untouched. */
const HUB_TOKEN_PREFIX = "hub_";

/** The hub's metadata, under the hub URL Medplum's token response names. */
const FHIRCAST_CONFIGURATION =
  "/fhircast/STU3/.well-known/fhircast-configuration";

/** The paths the shim answers itself; every other goes to Medplum. */
function isShimPath(pathname) {
  return (
    pathname.startsWith("/dcr/") || pathname === FHIRCAST_CONFIGURATION
  );
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

export function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function sendJson(res, status, body) {
  res.writeHead(status, JSON_TYPE);
  res.end(JSON.stringify(body));
}

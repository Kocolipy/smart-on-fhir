/**
 * What the tab keeps of its FHIRcast connection: one `sessionStorage` entry,
 * {@link FHIRCAST_STORAGE_KEYS}'s `connection` — an accepted deviation (ADR
 * 0011, ADR 0016; `docs/BACKEND_CONTRACT.md` lists the keys). It holds the
 * hub URL and topic, the private JWK and its `kid`, the `client_id`, the
 * token and revocation endpoints, the `registration_client_uri` and
 * `registration_access_token` when the hub answered them, and the one live
 * subscription — its channel endpoint, its Hub Token and when each runs out.
 * Never the SMART access token. Read back decoded, never trusted: anything
 * malformed or partial is taken for nothing kept, and every URL is held to
 * `127.0.0.1` again.
 */

import { readObject } from "../decode";
import { ES384, HTTP_SCHEMES, memberOf, onLoopback, type Stored, WS_SCHEMES } from "./model";

/**
 * Where the tab keeps what connecting obtained: one JSON entry, `connection`,
 * describing the Hub Registration and its one live Hub Subscription (see
 * {@link persist}). Read back on load, decoded rather than trusted.
 */
export const FHIRCAST_STORAGE_KEYS = {
  connection: "fhircast-connection",
} as const;

/**
 * What an earlier version of this module kept, each under a key of its own —
 * the SMART access token among them. Never read; forgotten with the rest, so a
 * tab that loaded that version keeps none of it past its session.
 */
const RETIRED_STORAGE_KEYS = [
  "fhircast-client-id",
  "fhircast-hub-token",
  "fhircast-private-jwk",
  "fhircast-smart-access-token",
];

/** Forgets everything connecting kept: the session it belonged to has ended. */
export function clearFhircastStorage(): void {
  for (const key of [...Object.values(FHIRCAST_STORAGE_KEYS), ...RETIRED_STORAGE_KEYS]) {
    window.sessionStorage.removeItem(key);
  }
}

/**
 * Keeps `stored` as the tab's one entry, {@link FHIRCAST_STORAGE_KEYS}'s
 * `connection`: everything resuming it after a reload takes, and everything
 * tearing it down does. Never the SMART access token: only registering takes
 * that, and registering anew fetches a new release.
 */
export function persist({ client, subscription }: Stored) {
  window.sessionStorage.setItem(
    FHIRCAST_STORAGE_KEYS.connection,
    JSON.stringify({
      clientId: client.clientId,
      hubTopic: client.hub.hubTopic,
      hubUrl: client.hub.hubUrl,
      keyId: client.signing.keyId,
      registration: client.management,
      revocationEndpoint: client.revocation,
      signingJwk: client.signing.jwk,
      subscription,
      tokenEndpoint: client.tokenEndpoint,
    }),
  );
}

/**
 * What the tab kept does not decode. It carries no message: {@link readStored}
 * takes anything malformed for nothing kept, and reads no reason why.
 */
class Unreadable extends Error {}

/** `url`, read from what the tab kept, held to `schemes` on `127.0.0.1` again. */
function storedUrl(url: string, schemes: readonly string[]): string {
  if (!onLoopback(url, schemes)) throw new Unreadable();
  return url;
}

/** The instant `value` holds under `key`: a number, or `null` for none; anything else, or nothing, is refused. */
function storedInstant(value: unknown, key: string): number | null {
  const instant = memberOf(value, key);
  if (instant === null || typeof instant === "number") return instant;
  throw new Unreadable();
}

/**
 * What the tab kept, decoded rather than trusted: `null` for nothing kept, and
 * for anything malformed or partial — each member present, each URL on
 * `127.0.0.1` again, and the key one WebCrypto takes for ES384.
 */
export async function readStored(): Promise<Stored | null> {
  const kept = window.sessionStorage.getItem(FHIRCAST_STORAGE_KEYS.connection);
  try {
    // Nothing kept reads as JSON `null`, which no stored state decodes from.
    const body: unknown = JSON.parse(kept ?? "null");
    const stored = readObject(body, "stored");
    const key = stored.object("signingJwk");
    const jwk: JsonWebKey = {
      crv: key.string("crv"),
      d: key.string("d"),
      kty: key.string("kty"),
      x: key.string("x"),
      y: key.string("y"),
    };
    const revocation = stored.nullableString("revocationEndpoint");
    const registration = memberOf(body, "registration");
    const subscription = memberOf(body, "subscription");
    const management = registration === null ? null : stored.object("registration");
    const held = subscription === null ? null : stored.object("subscription");
    return {
      client: {
        clientId: stored.string("clientId"),
        hub: {
          hubTopic: stored.string("hubTopic"),
          hubUrl: storedUrl(stored.string("hubUrl"), HTTP_SCHEMES),
        },
        management:
          management === null
            ? null
            : {
                token: management.string("token"),
                uri: storedUrl(management.string("uri"), HTTP_SCHEMES),
              },
        revocation: revocation === null ? null : storedUrl(revocation, HTTP_SCHEMES),
        signing: {
          jwk,
          keyId: stored.string("keyId"),
          privateKey: await crypto.subtle.importKey("jwk", jwk, ES384, false, ["sign"]),
        },
        tokenEndpoint: storedUrl(stored.string("tokenEndpoint"), HTTP_SCHEMES),
      },
      subscription:
        held === null
          ? null
          : {
              endpoint: storedUrl(held.string("endpoint"), WS_SCHEMES),
              hubToken: held.string("hubToken"),
              hubTokenExpiresAt: storedInstant(subscription, "hubTokenExpiresAt"),
              leaseExpiresAt: storedInstant(subscription, "leaseExpiresAt"),
            },
    };
  } catch {
    return null;
  }
}

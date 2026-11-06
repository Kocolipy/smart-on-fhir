/**
 * What the tab keeps of its FHIRcast connection: two `sessionStorage`
 * entries, {@link FHIRCAST_STORAGE_KEYS} — an accepted deviation (ADR 0011,
 * ADR 0016; `docs/BACKEND_CONTRACT.md` lists the keys). `connection` holds the
 * hub URL and topic, and the one live Hub Subscription — its channel endpoint
 * and when its lease runs out; `tokenExpiry` when the SMART access token
 * expires. Never the token itself, which stays in the tab's memory. Read back
 * decoded, never trusted: anything malformed or partial is taken for nothing
 * kept, and every URL is held to `127.0.0.1` again.
 */

import { readObject } from "../decode";
import { HTTP_SCHEMES, memberOf, onLoopback, type Stored, WS_SCHEMES } from "./model";

/**
 * Where the tab keeps what connecting obtained: one JSON entry, `connection`,
 * describing its hub and its one live Hub Subscription (see {@link persist}).
 * Read back on load, decoded rather than trusted.
 */
export const FHIRCAST_STORAGE_KEYS = {
  connection: "fhircast-connection",
  tokenExpiry: "fhircast-token-expiry",
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
 * `connection`: everything resuming it after a reload takes but the SMART
 * access token, which the reload's release carries.
 */
export function persist({ hub, subscription }: Stored) {
  window.sessionStorage.setItem(
    FHIRCAST_STORAGE_KEYS.connection,
    JSON.stringify({ hubTopic: hub.hubTopic, hubUrl: hub.hubUrl, subscription }),
  );
}

/**
 * Keeps `expiresAt`, when the SMART access token the tab was last released
 * expires, on `Date.now()`'s clock, under {@link FHIRCAST_STORAGE_KEYS}'s
 * `tokenExpiry`: never the token. What a reload or a Retry the backend
 * releases nothing to reads, to tell a token past its expiry from FHIRcast
 * unavailable.
 */
export function keepTokenExpiry(expiresAt: number) {
  window.sessionStorage.setItem(FHIRCAST_STORAGE_KEYS.tokenExpiry, JSON.stringify(expiresAt));
}

/**
 * When the SMART access token the tab was last released expires, as
 * {@link keepTokenExpiry} kept it: `null` for nothing kept, and for anything
 * kept that is no number.
 */
export function keptTokenExpiry(): number | null {
  const kept = window.sessionStorage.getItem(FHIRCAST_STORAGE_KEYS.tokenExpiry);
  try {
    const expiresAt: unknown = JSON.parse(kept ?? "null");
    return typeof expiresAt === "number" ? expiresAt : null;
  } catch {
    return null;
  }
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
 * for anything malformed or partial — each member present, and each URL on
 * `127.0.0.1` again.
 */
export function readStored(): Stored | null {
  const kept = window.sessionStorage.getItem(FHIRCAST_STORAGE_KEYS.connection);
  try {
    // Nothing kept reads as JSON `null`, which no stored state decodes from.
    const body: unknown = JSON.parse(kept ?? "null");
    const stored = readObject(body, "stored");
    const subscription = stored.object("subscription");
    return {
      hub: {
        hubTopic: stored.string("hubTopic"),
        hubUrl: storedUrl(stored.string("hubUrl"), HTTP_SCHEMES),
      },
      subscription: {
        endpoint: storedUrl(subscription.string("endpoint"), WS_SCHEMES),
        leaseExpiresAt: storedInstant(memberOf(body, "subscription"), "leaseExpiresAt"),
      },
    };
  } catch {
    return null;
  }
}

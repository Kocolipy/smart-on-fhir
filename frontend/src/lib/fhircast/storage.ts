/**
 * What the tab keeps of its FHIRcast connection: one `sessionStorage` entry,
 * {@link FHIRCAST_STORAGE_KEYS} — an accepted deviation (ADR 0011, ADR 0016;
 * `docs/BACKEND_CONTRACT.md` lists the key). `connection` holds the hub URL
 * and topic, and the one live Hub Subscription — its channel endpoint, when
 * its lease runs out, and when the hub token it was made with expires. Never
 * a token itself: the hub token and the SMART access token stay in the tab's
 * memory. Read back decoded, never trusted: anything malformed or partial is
 * taken for nothing kept, and every URL is held to `127.0.0.1` again.
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
} as const;

/** Forgets everything connecting kept: the session it belonged to has ended. */
export function clearFhircastStorage(): void {
  for (const key of Object.values(FHIRCAST_STORAGE_KEYS)) {
    window.sessionStorage.removeItem(key);
  }
}

/**
 * Keeps `stored` as the tab's one entry, {@link FHIRCAST_STORAGE_KEYS}'s
 * `connection`: everything resuming it after a reload takes but a hub token,
 * which the reload obtains for the profile's client.
 */
export function persist({ hub, subscription }: Stored) {
  window.sessionStorage.setItem(
    FHIRCAST_STORAGE_KEYS.connection,
    JSON.stringify({ hubTopic: hub.hubTopic, hubUrl: hub.hubUrl, subscription }),
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
    const rawSubscription = memberOf(body, "subscription");
    return {
      hub: {
        hubTopic: stored.string("hubTopic"),
        hubUrl: storedUrl(stored.string("hubUrl"), HTTP_SCHEMES),
      },
      subscription: {
        endpoint: storedUrl(subscription.string("endpoint"), WS_SCHEMES),
        hubTokenExpiresAt: storedInstant(rawSubscription, "hubTokenExpiresAt"),
        leaseExpiresAt: storedInstant(rawSubscription, "leaseExpiresAt"),
      },
    };
  } catch {
    return null;
  }
}

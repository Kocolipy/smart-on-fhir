/**
 * What the FHIRcast module's suites share: a release, the real-clock waits
 * that leave a fake clock where a test put it, what the tab keeps, and the
 * readings of what the page sent the fake hub. The hub itself is
 * `fake-hub.testHelpers.ts`.
 */
import {
  type FakeHub,
  HUB_URL,
  type HubAnswer,
  type HubRequest,
  REGISTRATION_CLIENT_URI,
  REGISTRATION_ENDPOINT,
  REVOCATION_ENDPOINT,
} from "./fake-hub.testHelpers";
import { FHIRCAST_STORAGE_KEYS, type FhircastRelease } from "./fhircast";

export const SMART_ACCESS_TOKEN = `smart-${crypto.randomUUID()}`;

/** The real `setTimeout`, taken before any test fakes the clock. */
export const realSetTimeout = globalThis.setTimeout;

/** How long {@link eventually} retries, on the real clock: WebCrypto signs off the main thread. */
const EVENTUALLY_MS = 5_000;

/**
 * Retries `check` on the real clock until it passes. Unlike `vi.waitFor`,
 * which moves a fake clock on between tries, it leaves the fake clock where
 * it is, so a test times the hub's steps from exactly where it put the clock.
 */
export async function eventually(check: () => void): Promise<void> {
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
export async function throughout(check: () => void): Promise<void> {
  const stopAt = performance.now() + THROUGHOUT_MS;
  while (performance.now() < stopAt) {
    check();
    await new Promise((resolve) => realSetTimeout(resolve, 5));
  }
  check();
}

export const RELEASE: FhircastRelease = {
  accessToken: SMART_ACCESS_TOKEN,
  expiresAt: "2026-10-24T10:00:00Z",
  hubTopic: "topic-1",
  hubUrl: HUB_URL,
  scope: ["fhircast/Patient-open.read", "openid"],
};

/** What the FHIRcast module keeps in the tab, parsed: `null` for nothing. */
export const keptState = (): Record<string, unknown> | null =>
  JSON.parse(window.sessionStorage.getItem(FHIRCAST_STORAGE_KEYS.connection) ?? "null") as Record<
    string,
    unknown
  > | null;

/** The binary string a base64url segment encodes. */
const fromBase64Url = (text: string): string => atob(text.replace(/-/g, "+").replace(/_/g, "/"));

const decodeSegment = (segment: string): Record<string, unknown> =>
  JSON.parse(fromBase64Url(segment)) as Record<string, unknown>;

/** The assertion the token request carried, split into its header, claims and signature. */
export function assertionOf(body: string) {
  const assertion = new URLSearchParams(body).get("assertion") ?? "";
  const [header, claims, signature] = assertion.split(".");
  return { claims: decodeSegment(claims), header: decodeSegment(header), signature, assertion };
}

/**
 * Whether the assertion `body` carries, under `parameter`, is signed by the
 * key the page registered at `hub`.
 */
export async function signedByRegisteredKey(
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
  const bytes = Uint8Array.from(fromBase64Url(signature), (char) => char.charCodeAt(0));
  return crypto.subtle.verify(
    { hash: "SHA-384", name: "ECDSA" },
    key,
    bytes,
    new TextEncoder().encode(signed),
  );
}

/** The release, as the session's FHIRcast is handed it. */
export const releasing = () => Promise.resolve(RELEASE);

/** A Patient-open on the release's topic, as the hub sends it. */
export const PATIENT_OPEN = {
  event: {
    context: [{ key: "patient", resource: { id: "patient-1", resourceType: "Patient" } }],
    "hub.event": "Patient-open",
    "hub.topic": "topic-1",
  },
  id: "event-1",
  timestamp: "2026-10-24T02:00:05Z",
};

/** A hub answer held back until the test gives it: what a step under way waits on. */
export function held() {
  let give: (answer: HubAnswer) => void = () => undefined;
  const answer = new Promise<HubAnswer>((resolve) => (give = resolve));
  return { answer, give: (given: HubAnswer) => give(given) };
}

/** Each request, as its method and URL. */
export const routes = (requests: readonly HubRequest[]) =>
  requests.map(({ method, url }) => `${method} ${url}`);

/** A revocation of the Hub Token, as {@link routes} names it. */
export const REVOKE = `POST ${REVOCATION_ENDPOINT}`;
/** A deletion of the registration, as {@link routes} names it. */
export const DELETE = `DELETE ${REGISTRATION_CLIENT_URI}`;

/** A form body's parameters. */
export const form = (body: string) => Object.fromEntries(new URLSearchParams(body));

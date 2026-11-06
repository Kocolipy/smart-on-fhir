/**
 * What the FHIRcast module's suites share: a release, the real-clock waits
 * that leave a fake clock where a test put it, what the tab keeps, and the
 * readings of what the page sent the fake hub. The hub itself is
 * `fake-hub.testHelpers.ts`.
 */
import { HUB_URL, type HubAnswer, type HubRequest } from "../fake-hub.testHelpers";
import { FHIRCAST_STORAGE_KEYS, type FhircastRelease } from ".";

export const SMART_ACCESS_TOKEN = `smart-${crypto.randomUUID()}`;

/** The real `setTimeout`, taken before any test fakes the clock. */
export const realSetTimeout = globalThis.setTimeout;

/** How long {@link eventually} retries, on the real clock. */
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
 * How many real ticks {@link throughout} holds its check across: the fake hub
 * answers and the fake socket speaks on promises alone, so a few ticks see
 * every step the page has begun through.
 */
const SETTLED_TICKS = 5;

/**
 * Holds `check` to passing on the real clock across {@link SETTLED_TICKS}
 * ticks, leaving the fake clock where it is: what a test asserts never
 * happens is given every step the page has begun, rather than checked before
 * it could reach the hub.
 */
export async function throughout(check: () => void): Promise<void> {
  for (let tick = 0; tick < SETTLED_TICKS; tick += 1) {
    check();
    await new Promise((resolve) => realSetTimeout(resolve, 0));
  }
  check();
}

/**
 * A release whose SMART access token expires far beyond any clock a suite
 * runs on, real or fake: a suite of the token's expiry releases its own.
 */
export const RELEASE: FhircastRelease = {
  accessToken: SMART_ACCESS_TOKEN,
  expiresAt: "2100-01-01T00:00:00Z",
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

/** A form body's parameters. */
export const form = (body: string) => Object.fromEntries(new URLSearchParams(body));

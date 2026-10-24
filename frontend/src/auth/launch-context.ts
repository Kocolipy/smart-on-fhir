/**
 * The **Launch context**: the patient and encounter an EHR launch put in
 * context, kept for this tab alone.
 *
 * The backend keeps neither. A successful Epic Login lands at
 * `/#patient=<id>&encounter=<id>`, carrying whichever of the two it had, or at
 * the bare marker `/#launch` with neither (ADR 0012, D8). On load the SPA
 * reads that fragment, holds each value to FHIR's id grammar, strips the
 * fragment from the URL, and keeps what passed in `sessionStorage`, which is
 * per tab. Its lifecycle (`docs/BACKEND_CONTRACT.md`):
 *
 * - every Epic landing, the bare marker included, **replaces** it;
 * - a reload carries no fragment, so it **keeps** it;
 * - every end of the session — sign-out, expiry, an Idle sign-out, a password
 *   change — and a password Login **clear** it (`session-transitions.ts`,
 *   `auth-context.tsx`).
 *
 * The encounter is held but nothing reads it yet.
 */

/** The patient and encounter in context, each a FHIR id; either may be absent. */
export interface LaunchContext {
  encounter?: string;
  patient?: string;
}

/** FHIR's id grammar, as the backend's `PatientId` and `LaunchContext` hold it. */
const FHIR_ID = /^[A-Za-z0-9\-.]{1,64}$/;

/** The fragment of a landing with nothing to put in context. */
const BARE_MARKER = "#launch";

const STORAGE_KEY = "launch-context";

const isFhirId = (value: unknown): value is string =>
  typeof value === "string" && FHIR_ID.test(value);

/** The launch context of whichever of the two values is a FHIR id; anything else is left out. */
function withinGrammar(patient: unknown, encounter: unknown): LaunchContext {
  return {
    ...(isFhirId(encounter) ? { encounter } : {}),
    ...(isFhirId(patient) ? { patient } : {}),
  };
}

/**
 * The launch context a URL fragment carries, or `null` for a fragment that is
 * no Epic landing — which names neither member and is not the bare marker.
 */
function landingIn(hash: string): LaunchContext | null {
  if (hash === BARE_MARKER) return {};
  const members = new URLSearchParams(hash.slice(1));
  if (!members.has("patient") && !members.has("encounter")) return null;
  return withinGrammar(members.get("patient"), members.get("encounter"));
}

/**
 * On load: an Epic landing's fragment replaces the tab's Launch context and is
 * stripped from the URL, so neither a bookmark, a shared link nor the history
 * entry keeps it. Any other URL changes nothing.
 */
export function adoptLaunchLanding(): void {
  const landed = landingIn(window.location.hash);
  if (landed === null) return;
  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(landed));
  const { pathname, search } = window.location;
  window.history.replaceState(window.history.state, "", `${pathname}${search}`);
}

/**
 * The tab's Launch context, or none. The storage is the page's to write, so
 * what is read back is held to the grammar again rather than trusted.
 */
export function launchContext(): LaunchContext {
  const stored = window.sessionStorage.getItem(STORAGE_KEY);
  if (stored === null) return {};
  let value: unknown;
  try {
    value = JSON.parse(stored);
  } catch {
    return {};
  }
  if (typeof value !== "object" || value === null) return {};
  return withinGrammar(Reflect.get(value, "patient"), Reflect.get(value, "encounter"));
}

/**
 * Whether this tab's session began with an EHR launch: an Epic landing put a
 * Launch context here, the bare marker's included, and no password Login or
 * end of the session has cleared it since. Home shows the FHIRcast card on it.
 */
export function landedFromEpic(): boolean {
  return window.sessionStorage.getItem(STORAGE_KEY) !== null;
}

/** Forgets the tab's Launch context. */
export function clearLaunchContext(): void {
  window.sessionStorage.removeItem(STORAGE_KEY);
}

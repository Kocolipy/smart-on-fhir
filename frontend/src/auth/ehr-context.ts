/**
 * The **EHR context**: what the EHR currently has open for this tab — today a
 * patient and an encounter, each a FHIR id, either possibly absent — kept for
 * this tab alone, under one `sessionStorage` key.
 *
 * The backend keeps none of it. A successful Epic Login lands at
 * `/#patient=<id>&encounter=<id>`, carrying whichever of the two it had, or at
 * the bare marker `/#launch` with neither (ADR 0012, D8): that fragment is the
 * **Launch context**, and it seeds the EHR context. On load the SPA reads it,
 * holds each value to FHIR's id grammar, strips the fragment from the URL, and
 * keeps what passed. Its lifecycle (`docs/BACKEND_CONTRACT.md`):
 *
 * - every Epic landing, the bare marker included, **replaces** it;
 * - a reload carries no fragment, so it **keeps** it;
 * - every end of the session — sign-out, expiry, an Idle sign-out, a password
 *   change — and a password Login **clear** it (`session-transitions.ts`,
 *   `auth-context.tsx`).
 *
 * Whether the key exists at all says whether this tab's session began with an
 * EHR launch: present, even naming nothing, after any landing; absent after a
 * password Login or once cleared.
 *
 * It is a subscribable store, shaped for `useSyncExternalStore`
 * ({@link useEhrContext}): Home, the Patient card and the FHIRcast card's gate
 * re-render when it changes, not only on load. Nothing yet changes it after it
 * is seeded but the end of the session. The encounter is held but nothing
 * reads it yet.
 */

import { useSyncExternalStore } from "react";

import { watchers } from "@/lib/watchers";

/** The patient and encounter in context, each a FHIR id; either may be absent. */
export interface EhrContext {
  encounter?: string;
  patient?: string;
}

/** FHIR's id grammar, as the backend's `PatientId` and `LaunchContext` hold it. */
const FHIR_ID = /^[A-Za-z0-9\-.]{1,64}$/;

/** The fragment of a landing with nothing to put in context. */
const BARE_MARKER = "#launch";

const STORAGE_KEY = "ehr-context";

const isFhirId = (value: unknown): value is string =>
  typeof value === "string" && FHIR_ID.test(value);

/** The context of whichever of the two values is a FHIR id; anything else is left out. */
function withinGrammar(patient: unknown, encounter: unknown): EhrContext {
  return {
    ...(isFhirId(encounter) ? { encounter } : {}),
    ...(isFhirId(patient) ? { patient } : {}),
  };
}

/**
 * The Launch context a URL fragment carries, or `null` for a fragment that is
 * no Epic landing — which names neither member and is not the bare marker.
 */
function landingIn(hash: string): EhrContext | null {
  if (hash === BARE_MARKER) return {};
  const members = new URLSearchParams(hash.slice(1));
  if (!members.has("patient") && !members.has("encounter")) return null;
  return withinGrammar(members.get("patient"), members.get("encounter"));
}

/** Whoever is watching the EHR context, told when it changes. */
const watching = watchers();

/**
 * On load: an Epic landing's fragment, its Launch context, replaces the tab's
 * EHR context and is stripped from the URL, so neither a bookmark, a shared
 * link nor the history entry keeps it. Any other URL changes nothing.
 */
export function adoptLaunchLanding(): void {
  const landed = landingIn(window.location.hash);
  if (landed === null) return;
  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(landed));
  const { pathname, search } = window.location;
  window.history.replaceState(window.history.state, "", `${pathname}${search}`);
  watching.changed();
}

/** What the stored text decodes to: held to the grammar again rather than trusted. */
function decoded(stored: string): EhrContext {
  let value: unknown;
  try {
    value = JSON.parse(stored);
  } catch {
    // Not JSON: the value stays undefined, which decodes to nothing below.
  }
  if (typeof value !== "object" || value === null) return {};
  return withinGrammar(Reflect.get(value, "patient"), Reflect.get(value, "encounter"));
}

/** The last read, so an unchanged storage reads back as the same value. */
let lastRead: { stored: string | null; context: EhrContext | null } = {
  context: null,
  stored: null,
};

/**
 * The tab's EHR context, or `null` when the tab holds none. The storage is the
 * page's to write, so what is read back is decoded rather than trusted; the
 * same value comes back until the storage changes.
 */
export function ehrContext(): EhrContext | null {
  const stored = window.sessionStorage.getItem(STORAGE_KEY);
  if (stored !== lastRead.stored) {
    lastRead = { context: stored === null ? null : decoded(stored), stored };
  }
  return lastRead.context;
}

/** Calls `listener` whenever the EHR context changes; answers how to stop. */
export const subscribeEhrContext: (listener: () => void) => () => void = watching.watch;

/** The tab's EHR context, or `null`, re-rendering the caller whenever it changes. */
export function useEhrContext(): EhrContext | null {
  return useSyncExternalStore(subscribeEhrContext, ehrContext);
}

/** Forgets the tab's EHR context. */
export function clearEhrContext(): void {
  window.sessionStorage.removeItem(STORAGE_KEY);
  watching.changed();
}

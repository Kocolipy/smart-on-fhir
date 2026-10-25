/**
 * The session's FHIRcast connection, started from the auth side (ADR 0016):
 * the FHIRcast module (`@/lib/fhircast`) holds the connection as a
 * module-level singleton and imports nothing of the auth layer, so the glue
 * that hands it a release through the session seam sits here.
 *
 * The release is `GET /api/auth/epic/fhircast`, the signed-in Epic session's
 * SMART access token and the hub its Epic Login named. Not a gated read: the
 * operation is self-service (`security: sessionCookie: []`), so it carries no
 * Permission for a declaration to name, and its answer is no listing — only
 * whether a release came back matters. It still goes through
 * `useSessionRequest`, so a `401` ends the session in the one place it is
 * ended.
 */

import { useCallback, useEffect } from "react";

import { decodeFhircastRelease, startFhircast, type FhircastReleaseSource } from "@/lib/fhircast";

import { useAuthState } from "./auth-context-value";
import { useEhrContext } from "./ehr-context";
import { useSessionRequest } from "./use-session-request";

const FHIRCAST_RELEASE_PATH = "/api/auth/epic/fhircast";

/**
 * Starts the session's FHIRcast connection while the session is signed in and
 * the tab holds an EHR context. Called by the signed-in layout, so the
 * connection begins on whichever signed-in page the tab is on and lasts
 * across navigation and Home remounts: the start is idempotent, and only the
 * end of the session (`endFhircast`, from `session-transitions.ts`) stops it.
 * A Retry (`retryFhircast`) asks for the release through the same seam.
 */
export function useSessionFhircast(): void {
  const request = useSessionRequest();
  const signedIn = useAuthState().user !== null;
  const inEhrContext = useEhrContext() !== null;
  const release = useCallback<FhircastReleaseSource>(async () => {
    const result = await request(FHIRCAST_RELEASE_PATH, {}, decodeFhircastRelease);
    return result.kind === "ok" ? result.data : null;
  }, [request]);

  useEffect(() => {
    if (signedIn && inEhrContext) startFhircast(release);
  }, [inEhrContext, release, signedIn]);
}

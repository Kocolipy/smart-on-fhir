/**
 * The FHIRcast release through the session seam: `GET /api/auth/epic/fhircast`
 * (ADR 0016), the signed-in Epic session's SMART access token and the hub its
 * Epic Login named, for the FHIRcast module (`@/lib/fhircast`) to connect with.
 *
 * Not a gated read: the operation is self-service (`security: sessionCookie:
 * []`), so it carries no Permission for a declaration to name, and its answer
 * is no listing — the card only needs to know whether a release came back. It
 * still goes through `useSessionRequest`, so a `401` ends the session in the
 * one place it is ended.
 */

import { useCallback } from "react";

import { decodeFhircastRelease, type FhircastRelease } from "@/lib/fhircast";

import { useSessionRequest } from "./use-session-request";

const FHIRCAST_RELEASE_PATH = "/api/auth/epic/fhircast";

/** Asks for the release; `null` for every answer that is not one (`404` included). */
export function useFhircastRelease(): () => Promise<FhircastRelease | null> {
  const request = useSessionRequest();
  return useCallback(async () => {
    const result = await request(FHIRCAST_RELEASE_PATH, {}, decodeFhircastRelease);
    return result.kind === "ok" ? result.data : null;
  }, [request]);
}

/**
 * The FHIRcast release: what the backend hands the session's FHIRcast to
 * connect with (`GET /api/auth/epic/fhircast`, ADR 0016), and its decoder.
 */

import { DecodeError, jsonDecoder, readObject } from "../decode";
import { isInstant } from "../time";

/** What the backend releases for FHIRcast: `GET /api/auth/epic/fhircast`. */
export interface FhircastRelease {
  /** The SMART access token, the `Bearer` of every request made of the hub. */
  accessToken: string;
  /** When the SMART access token expires, an instant: the connection ends with it. */
  expiresAt: string;
  hubTopic: string;
  hubUrl: string;
  scope: string[];
}

/** The release as the backend answers it, every member required, its expiry an instant. */
export const decodeFhircastRelease = jsonDecoder((body: unknown): FhircastRelease => {
  const release = readObject(body, "FhircastRelease");
  const expiresAt = release.string("expiresAt");
  if (!isInstant(expiresAt)) throw new DecodeError("FhircastRelease.expiresAt is no instant");
  return {
    accessToken: release.string("accessToken"),
    expiresAt,
    hubTopic: release.string("hubTopic"),
    hubUrl: release.string("hubUrl"),
    scope: release.array("scope", (scope) => {
      if (typeof scope !== "string") throw new DecodeError("FhircastRelease.scope is not strings");
      return scope;
    }),
  };
});

/**
 * The FHIRcast release: what the backend hands the session's FHIRcast to
 * connect with (`GET /api/auth/epic/fhircast`, ADR 0016), and its decoder.
 */

import { DecodeError, jsonDecoder, readObject } from "../decode";
import { isInstant } from "../time";

import type { Hub } from "./model";

/** The FHIRcast Hub and the application's Epic client id: released for the whole session. */
interface ReleasedHub extends Hub {
  /** The application's Epic client id, the hub's `software_id`. */
  softwareId: string;
}

/** The SMART access token and what the release says of it: released only while it is unexpired. */
export interface ReleasedToken {
  /** The SMART access token, the `Bearer` of every request made of the hub. */
  accessToken: string;
  /** When the SMART access token expires, an instant: the connection ends with it. */
  expiresAt: string;
  scope: string[];
}

/**
 * What the backend releases for FHIRcast: `GET /api/auth/epic/fhircast`. The
 * hub always; the SMART access token, its expiry and its scope together, or
 * none of them once the token has expired.
 */
export type FhircastRelease = ReleasedHub | (ReleasedHub & ReleasedToken);

/** Whether `release` carries the SMART access token. */
export const carriesToken = (release: FhircastRelease): release is ReleasedHub & ReleasedToken =>
  "accessToken" in release;

/** The members of the release that are present together or not at all. */
const TOKEN_MEMBERS = ["accessToken", "expiresAt", "scope"] as const;

/**
 * The release as the backend answers it: the hub and the software id always,
 * and, when any of the token's members is present, every one of them, its
 * expiry an instant.
 */
export const decodeFhircastRelease = jsonDecoder((body: unknown): FhircastRelease => {
  const release = readObject(body, "FhircastRelease");
  const hub: ReleasedHub = {
    hubTopic: release.string("hubTopic"),
    hubUrl: release.string("hubUrl"),
    softwareId: release.string("softwareId"),
  };
  const withToken = TOKEN_MEMBERS.some((member) => release.has(member));
  if (!withToken) return hub;
  const accessToken = release.string("accessToken");
  const expiresAt = release.string("expiresAt");
  if (!isInstant(expiresAt)) throw new DecodeError("FhircastRelease.expiresAt is no instant");
  return {
    ...hub,
    accessToken,
    expiresAt,
    scope: release.array("scope", (scope) => {
      if (typeof scope !== "string") throw new DecodeError("FhircastRelease.scope is not strings");
      return scope;
    }),
  };
});

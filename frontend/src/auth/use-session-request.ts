/**
 * The seam feature modules make requests through.
 *
 * `apiFetch` classifies a `401` as `unauthenticated`; responding to that is a
 * session concern, not a feature one, so it is handled here once. Callers
 * receive a `SessionResult`, which has no `unauthenticated` member — there is
 * no case for a future page to forget.
 */

import { useCallback } from "react";

import {
  apiFetch,
  CSRF_EXPIRED_MESSAGE,
  FORBIDDEN_MESSAGE,
  type ApiDecoder,
  type ApiRequestInit,
  type ApiResult,
} from "@/lib/http";

import { useAuthState } from "./auth-context-value";

/**
 * Re-exported so a feature reads the whole result contract — the cases and the
 * copy for the two cases it does not own — from this seam alone. A `forbidden`
 * result passes through untouched: an authorization refusal is not the end of
 * the session, so the seam never expires it.
 */
export { CSRF_EXPIRED_MESSAGE, FORBIDDEN_MESSAGE } from "@/lib/http";

/** An `ApiResult` whose session outcome the seam has already handled. */
export type SessionResult<T> = Exclude<ApiResult<T>, { kind: "unauthenticated" }>;

/**
 * The copy for an unsuccessful result: the seam's own for the two transport
 * outcomes, and the feature's `failedMessage` for a plain `failed`, which only
 * the feature can describe.
 */
export function refusalMessage(
  result: Exclude<SessionResult<unknown>, { kind: "ok" }>,
  failedMessage: string,
): string {
  switch (result.kind) {
    case "forbidden":
      return FORBIDDEN_MESSAGE;
    case "csrf-expired":
      return CSRF_EXPIRED_MESSAGE;
    case "failed":
      return failedMessage;
  }
}

export interface SessionRequest {
  (path: string, init?: ApiRequestInit): Promise<SessionResult<void>>;
  <T>(path: string, init: ApiRequestInit, decode: ApiDecoder<T>): Promise<SessionResult<T>>;
  /**
   * For a caller forwarding a decoder it may not have — the gated write's
   * operation, say — so the no-content choice is made here once, not twice.
   */
  <T>(
    path: string,
    init: ApiRequestInit,
    decode: ApiDecoder<T> | undefined,
  ): Promise<SessionResult<T | void>>;
}

export function useSessionRequest(): SessionRequest {
  const { expireSession } = useAuthState();

  return useCallback(
    async <T>(
      path: string,
      init: ApiRequestInit = {},
      decode?: ApiDecoder<T>,
    ): Promise<SessionResult<T | void>> => {
      const result =
        decode === undefined ? await apiFetch(path, init) : await apiFetch(path, init, decode);

      if (result.kind === "unauthenticated") {
        // The session is ended here; the route guard redirects to the login
        // route on the same update, so whatever the caller does with this
        // result is superseded before it can paint.
        expireSession();
        return { kind: "failed", status: 401 };
      }
      return result;
    },
    [expireSession],
  ) as SessionRequest;
}

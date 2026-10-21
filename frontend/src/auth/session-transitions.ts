/**
 * The **session transitions**: the one place the auth state changes, and the
 * one place in `src/auth` that forgets the CSRF token.
 *
 * The token is worth exactly as long as the session it was issued to (ADR
 * 0009), so every change of session is also the end of the token held. Each
 * transition here makes both changes in the same step, so the state and the
 * token cannot drift apart — a session the SPA still shows as signed in never
 * outlives its token being forgotten, nor the other way round. Callers say
 * *which* transition happened; none of them reaches the token itself.
 *
 * Ending a session also forgets the tab's Launch context (`launch-context.ts`):
 * the patient an EHR launch put in context belongs to the session it signed
 * in, and is not shown to whoever signs in next.
 *
 * Two transitions change the auth state without changing the session, and so
 * keep the token: a start-up check that did not answer (`checkFailed`), which
 * leaves the session unknown rather than ended, and the retry of that check
 * (`recheck`).
 */

import { useCallback, useState } from "react";

import { discardCsrfToken } from "@/lib/http";

import type { AuthUser, SessionCheckFailure } from "./api";
import type { Session } from "./auth-context-value";
import { clearLaunchContext } from "./launch-context";
import type { SessionEndReason } from "./sign-in-reason";

const CHECKING: Session = {
  checkFailure: null,
  signInReason: null,
  status: "checking",
  user: null,
};

export interface SessionTransitions {
  /**
   * The start-up check did not answer, so the session is unknown — neither
   * signed in nor ended. Not a change of session, so the token is kept: the
   * backend may still hold the session it belongs to.
   */
  checkFailed: (failure: SessionCheckFailure) => void;
  /**
   * The session is to be checked again, after one that did not answer. Not a
   * change of session either, so the token is kept.
   */
  recheck: () => void;
  /**
   * The session has ended, recording why for the login page, or `null` for one
   * the User ended or that had none to give. Also the transition for a session
   * that was never signed in: a refused login ends the Guest's own.
   */
  end: (reason: SessionEndReason | null) => void;
  session: Session;
  /**
   * The session is signed in as `user` — by a login, which rotated the session
   * id, or by the start-up check finding one already signed in.
   */
  signIn: (user: AuthUser) => void;
}

export function useSessionTransitions(): SessionTransitions {
  const [session, setSession] = useState<Session>(CHECKING);

  const signIn = useCallback((user: AuthUser) => {
    discardCsrfToken();
    setSession({ checkFailure: null, signInReason: null, status: "authenticated", user });
  }, []);

  const end = useCallback((reason: SessionEndReason | null) => {
    discardCsrfToken();
    clearLaunchContext();
    setSession({ checkFailure: null, signInReason: reason, status: "guest", user: null });
  }, []);

  const checkFailed = useCallback((failure: SessionCheckFailure) => {
    setSession({ checkFailure: failure, signInReason: null, status: "unchecked", user: null });
  }, []);

  const recheck = useCallback(() => setSession(CHECKING), []);

  return { checkFailed, end, recheck, session, signIn };
}

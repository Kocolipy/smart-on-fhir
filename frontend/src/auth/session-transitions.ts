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
 */

import { useCallback, useState } from "react";

import { discardCsrfToken } from "@/lib/http";

import type { AuthUser } from "./api";
import type { AuthStatus } from "./auth-context-value";
import type { SessionEndReason } from "./sign-in-reason";

/** What the SPA knows about the visitor's session, changed only as a whole. */
export interface Session {
  /** Why the current `guest` status began: see `AuthContextState.signInReason`. */
  signInReason: SessionEndReason | null;
  status: AuthStatus;
  user: AuthUser | null;
}

const CHECKING: Session = { signInReason: null, status: "checking", user: null };

export interface SessionTransitions {
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

  // Stryker disable ArrayDeclaration: equivalent mutants. The only mutation of
  // these empty dependency lists is a one-element constant list, which never
  // changes between renders either, so each transition keeps its identity just
  // as it does now and no observable behavior can tell the two apart.
  const signIn = useCallback((user: AuthUser) => {
    discardCsrfToken();
    setSession({ signInReason: null, status: "authenticated", user });
  }, []);

  const end = useCallback((reason: SessionEndReason | null) => {
    discardCsrfToken();
    setSession({ signInReason: reason, status: "guest", user: null });
  }, []);
  // Stryker restore ArrayDeclaration

  return { end, session, signIn };
}

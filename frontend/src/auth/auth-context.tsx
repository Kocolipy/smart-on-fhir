import { useCallback, useEffect, useMemo, type ReactNode } from "react";

import * as authApi from "./api";
import { AuthContext, type AuthContextState } from "./auth-context-value";
import { IdleSignOut } from "./idle-sign-out";
import { useSessionTransitions } from "./session-transitions";
import { sessionEndReasonFor } from "./sign-in-reason";

export function AuthProvider({ children }: { children: ReactNode }) {
  // Every change of session goes through one of these, which forget the CSRF
  // token in the same step; nothing here reaches the token itself.
  const { end, session, signIn } = useSessionTransitions();
  const { signInReason, status, user } = session;

  useEffect(() => {
    let active = true;
    void authApi
      .getCurrentUser()
      .then((currentUser) => {
        if (!active) return;
        if (currentUser) signIn(currentUser);
        else end(null);
      })
      .catch(() => {
        if (active) end(null);
      });
    return () => {
      active = false;
    };
  }, [end, signIn]);

  /**
   * Ends the session the backend has already refused.
   *
   * Driven by `useSessionRequest`, never by a page: the distinction between an
   * expired session and a cold visit is set here and read by the route guards.
   */
  const expireSession = useCallback(() => end("expired"), [end]);

  /**
   * Ends a session the user left idle. Driven by `IdleSignOut` only.
   *
   * The logout is attempted, but its outcome cannot keep the session on screen:
   * the point is that an unattended page stops showing, so a logout that fails
   * still ends the session here, its token included. The backend's own idle
   * bound ends the session it may still hold.
   */
  const signOutForInactivity = useCallback(async () => {
    await authApi.logout();
    end("inactive");
  }, [end]);

  const value = useMemo<AuthContextState>(
    () => ({
      changePassword: async (currentPassword, newPassword) => {
        const outcome = await authApi.changePassword(currentPassword, newPassword);
        // A change or a lockout ends every session the User held on the
        // backend, this one included. Mirror that, recording which for the
        // login page; a refusal leaves the session standing.
        const reason = sessionEndReasonFor(outcome);
        if (reason) end(reason);
        return outcome;
      },
      expireSession,
      login: async (username, password) => {
        const outcome = await authApi.login(username, password);
        // A refused login ends whatever session the browser held, so the
        // Guest's token goes with it and a retry fetches the next session's.
        // Any other outcome reached no verdict and changes nothing.
        if (outcome.kind === "signed-in") signIn(outcome.user);
        if (outcome.kind === "refused") end(null);
        return outcome;
      },
      logout: async () => {
        const outcome = await authApi.logout();
        // A logout that may have left the backend's session standing keeps it
        // here too, its CSRF token with it (ADR-0009).
        if (outcome.kind === "signed-out") end(null);
        return outcome;
      },
      signInReason,
      signOutForInactivity,
      status,
      user,
    }),
    [end, expireSession, signIn, signInReason, signOutForInactivity, status, user],
  );

  return (
    <AuthContext.Provider value={value}>
      {children}
      {/* Mounted only while signed in, so the idle clock starts at authentication. */}
      {user ? <IdleSignOut idleTimeoutSeconds={user.idleTimeoutSeconds} /> : null}
    </AuthContext.Provider>
  );
}

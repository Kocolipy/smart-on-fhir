import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";

import { discardCsrfToken } from "@/lib/http";

import * as authApi from "./api";
import type { AuthUser } from "./api";
import { AuthContext, type AuthContextState, type AuthStatus } from "./auth-context-value";
import { IdleSignOut } from "./idle-sign-out";
import type { SessionEndReason } from "./sign-in-reason";

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>("checking");
  const [user, setUser] = useState<AuthUser | null>(null);
  // Why the Guest is one now, for the login page to say. Every transition
  // replaces it, so exactly one reason or none is ever recorded.
  const [signInReason, setSignInReason] = useState<SessionEndReason | null>(null);

  useEffect(() => {
    let active = true;
    void authApi
      .getCurrentUser()
      .then((currentUser) => {
        if (!active) return;
        setUser(currentUser);
        setStatus(currentUser ? "authenticated" : "guest");
      })
      .catch(() => {
        if (active) setStatus("guest");
      });
    return () => {
      active = false;
    };
  }, []);

  /** Moves to `guest`, recording why, or `null` for a session the User ended. */
  const endSession = useCallback((why: SessionEndReason | null) => {
    setUser(null);
    setSignInReason(why);
    setStatus("guest");
  }, []);

  /**
   * Ends the session the backend has already refused.
   *
   * Driven by `useSessionRequest`, never by a page: the distinction between an
   * expired session and a cold visit is set here and read by the route guards.
   */
  const expireSession = useCallback(() => {
    // The session's CSRF token ended with it; the next login fetches its own.
    discardCsrfToken();
    endSession("expired");
  }, [endSession]);

  /**
   * Ends a session the user left idle. Driven by `IdleSignOut` only.
   *
   * The logout is attempted, but its outcome cannot keep the session on screen:
   * the point is that an unattended page stops showing, so a logout that fails
   * still clears the state, and the token is forgotten either way.
   */
  const signOutForInactivity = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // Signed out locally regardless; the backend's own idle bound ends the session.
    }
    discardCsrfToken();
    endSession("inactive");
  }, [endSession]);

  const value = useMemo<AuthContextState>(
    () => ({
      changePassword: async (currentPassword, newPassword) => {
        const outcome = await authApi.changePassword(currentPassword, newPassword);
        if (outcome.kind === "changed") {
          // The backend has already ended this session along with every other
          // one the User held; mirror that, recording why for the login page.
          endSession("password-changed");
        }
        return outcome;
      },
      expireSession,
      login: async (username, password) => {
        const currentUser = await authApi.login(username, password);
        setUser(currentUser);
        setSignInReason(null);
        setStatus("authenticated");
      },
      logout: async () => {
        await authApi.logout();
        endSession(null);
      },
      signInReason,
      signOutForInactivity,
      status,
      user,
    }),
    [endSession, expireSession, signInReason, signOutForInactivity, status, user],
  );

  return (
    <AuthContext.Provider value={value}>
      {children}
      {/* Mounted only while signed in, so the idle clock starts at authentication. */}
      {user ? <IdleSignOut idleTimeoutSeconds={user.idleTimeoutSeconds} /> : null}
    </AuthContext.Provider>
  );
}

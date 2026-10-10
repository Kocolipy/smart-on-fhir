import { createContext, useContext } from "react";

import type { AuthUser, PasswordChangeOutcome } from "./api";
import type { SessionEndReason } from "./sign-in-reason";

/** The session status: what the SPA currently knows about the visitor's session. */
export type AuthStatus = "checking" | "authenticated" | "guest";

/**
 * What a page may read about the session. Why a Guest is one is not here: the
 * login page reads its notice from `sign-in-reason.ts`.
 *
 * Deliberately has no member for *ending* a session: a feature request that
 * comes back unauthenticated is handled by `useSessionRequest`, so no page has
 * to remember to relay it. `changePassword` ends one only on a success or a
 * lockout, which is the backend's doing — either revokes every session of the
 * User, this one included.
 */
export interface AuthContextValue {
  /**
   * Submits the session's own password change. On `changed` or `locked` the
   * auth state is already cleared, so the route guard returns the visitor to
   * login, which says which.
   */
  changePassword: (currentPassword: string, newPassword: string) => Promise<PasswordChangeOutcome>;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  status: AuthStatus;
  user: AuthUser | null;
}

/** The context value including the session controls only `src/auth` may drive. */
export interface AuthContextState extends AuthContextValue {
  expireSession: () => void;
  /**
   * Why the current `guest` status began — an Expired session, an Idle
   * sign-out, a password change or the lockout a change ran into — or `null`
   * for a cold visit, a logout or a refused login.
   * Read by the route guards, which carry it to the login page.
   */
  signInReason: SessionEndReason | null;
  /**
   * Ends an idle session: logs out on the backend, forgets the CSRF token and
   * clears the auth state, recording why for the login page. Resolves once the
   * state is cleared, whatever the logout request came to.
   */
  signOutForInactivity: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextState | null>(null);

/** Internal to `src/auth`: the full state, session controls included. */
export function useAuthState(): AuthContextState {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside AuthProvider.");
  return context;
}

export function useAuth(): AuthContextValue {
  return useAuthState();
}

import { createContext, useContext } from "react";

import type {
  AuthUser,
  LoginOutcome,
  LogoutOutcome,
  PasswordChangeOutcome,
  SessionCheckFailure,
} from "./api";
import type { SessionEndReason } from "./sign-in-reason";

/**
 * What the SPA knows about the visitor's session, changed only as a whole by
 * the session transitions. Each status carries exactly what it has, so nothing
 * can read a field its status does not give:
 *
 * - `checkFailure` is what the start-up check came to, on `unchecked` only — a
 *   check that did not answer, so the session is unknown, not ended, and the
 *   visitor is no Guest. The route guards say so rather than send them to login.
 * - `signInReason` is why the current `guest` status began — an Expired
 *   session, an Idle sign-out, a password change or the lockout a change ran
 *   into — or `null` for a cold visit, a logout or a refused login. The route
 *   guards carry it to the login page.
 * - `user` is the signed-in User, on `authenticated` only.
 */
export type Session =
  | { checkFailure: null; signInReason: null; status: "checking"; user: null }
  | { checkFailure: SessionCheckFailure; signInReason: null; status: "unchecked"; user: null }
  | { checkFailure: null; signInReason: null; status: "authenticated"; user: AuthUser }
  | { checkFailure: null; signInReason: SessionEndReason | null; status: "guest"; user: null };

/** The session status: what the SPA currently knows about the visitor's session. */
export type AuthStatus = Session["status"];

/**
 * Which session is current, as the session transitions number them: every
 * sign-in and every end starts the next. A request is sent under the one
 * current then, and its `401` ends that one only — never a later session.
 */
export type SessionGeneration = number;

/**
 * What a page may read about the session. Why a Guest is one is not here: the
 * login page reads its notice from `sign-in-reason.ts`.
 *
 * Deliberately has no member for *ending* a session: a feature request that
 * comes back unauthenticated is handled by `useSessionRequest`, so no page has
 * to remember to relay it. `changePassword` ends one only on a success or a
 * lockout, which is the backend's doing — either revokes every session of the
 * User, this one included.
 *
 * The three **session actions** — `login`, `logout`, `changePassword` — never
 * reject for a request outcome: each resolves to a closed outcome with the
 * session already updated for it. A page runs one through
 * `useSessionAction` (`use-session-action.ts`) and renders what it hands back.
 */
export interface AuthContextValue {
  /**
   * Submits the session's own password change. On `changed` or `locked` the
   * auth state is already cleared, so the route guard returns the visitor to
   * login, which says which; every other outcome leaves the session standing.
   */
  changePassword: (currentPassword: string, newPassword: string) => Promise<PasswordChangeOutcome>;
  /**
   * Signs in. On `signed-in` the session is the new User's; on `refused` the
   * browser's session is ended, its CSRF token with it; every other outcome
   * leaves the session as it was.
   */
  login: (username: string, password: string) => Promise<LoginOutcome>;
  /**
   * Signs out. On `signed-out` the session is ended with no sign-in reason;
   * `csrf-expired` and `failed` leave it standing, its CSRF token with it.
   */
  logout: () => Promise<LogoutOutcome>;
  status: AuthStatus;
  user: AuthUser | null;
}

/** The session controls only `src/auth` may drive. */
export interface AuthControls {
  /**
   * Ends, as expired, the session a request answering `401` was sent under —
   * if it is still current. A later session is left as it stands.
   */
  expireSession: (sentUnder: SessionGeneration) => void;
  /** The session current now, read by `useSessionRequest` as it sends a request. */
  sessionGeneration: () => SessionGeneration;
  /**
   * Checks an unknown session again, under the start-up check's rules: `ok`
   * signs in, `401` makes a Guest, anything else leaves it unknown.
   */
  retrySessionCheck: () => void;
  /**
   * Ends an idle session: logs out on the backend, forgets the CSRF token and
   * clears the auth state, recording why for the login page. Resolves once the
   * state is cleared, whatever the logout request came to.
   */
  signOutForInactivity: () => Promise<void>;
}

/**
 * The context value including the session controls only `src/auth` may drive,
 * and the whole `Session` — so a reader that narrows on `status` gets what that
 * status carries.
 */
export type AuthContextState = Omit<AuthContextValue, "status" | "user"> & AuthControls & Session;

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

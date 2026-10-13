import { vi } from "vitest";

import type { AuthUser, SessionCheckFailure } from "./api";
import type { AuthContextState, Session } from "./auth-context-value";
import type { SessionEndReason } from "./sign-in-reason";

/** Every member of the auth state that is not the session: the actions and controls. */
type AuthActions = Omit<AuthContextState, keyof Session>;

/** A session still waiting on its start-up check. */
export const CHECKING: Session = {
  checkFailure: null,
  signInReason: null,
  status: "checking",
  user: null,
};

/**
 * A session signed in as `ada`: unflagged, with a 15-minute idle limit and no
 * Permission, unless `user` says otherwise.
 */
export function signedIn(user: Partial<AuthUser> = {}): Session {
  return {
    checkFailure: null,
    signInReason: null,
    status: "authenticated",
    user: {
      idleTimeoutSeconds: 900,
      passwordChangeRequired: false,
      permissions: [],
      username: "ada",
      ...user,
    },
  };
}

/** A Guest, whose status began for `signInReason` — `null` for a cold visit. */
export function guest(signInReason: SessionEndReason | null = null): Session {
  return { checkFailure: null, signInReason, status: "guest", user: null };
}

/** A session whose start-up check came to `failure`: unknown, not ended. */
export function unchecked(failure: SessionCheckFailure = { kind: "failed" }): Session {
  return { checkFailure: failure, signInReason: null, status: "unchecked", user: null };
}

/**
 * The auth state a page or hook reads from `AuthContext`, for `session`: every
 * action and control a fresh `vi.fn()` unless `actions` hands one in.
 */
export function authState(session: Session, actions: Partial<AuthActions> = {}): AuthContextState {
  return {
    changePassword: vi.fn(),
    expireSession: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
    retrySessionCheck: vi.fn(),
    signOutForInactivity: vi.fn(),
    ...actions,
    ...session,
  };
}

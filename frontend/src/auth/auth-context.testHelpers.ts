import { vi } from "vitest";

import {
  ME_PATH,
  PERMISSIONS,
  type AuthUser,
  type Permission,
  type SessionCheckFailure,
} from "./api";
import type { AuthContextState, Session } from "./auth-context-value";

// ---- the User a test signs in as ------------------------------------------------

/** Every Permission the backend grants, as a Superuser's session holds them. */
export const EVERY_PERMISSION: readonly Permission[] = PERMISSIONS;

/**
 * The User `ada`, as the backend's `UserResponse` names her: unflagged, with a
 * 15-minute idle limit and no Permission, unless `overrides` says otherwise.
 */
export function testUser(overrides: Partial<AuthUser> = {}): AuthUser {
  return {
    idleTimeoutSeconds: 900,
    passwordChangeRequired: false,
    permissions: [],
    username: "ada",
    ...overrides,
  };
}

// ---- the auth endpoints, as the backend layer keys them -------------------------

/**
 * The session's own requests, each as the `"METHOD /path"` key a suite answers
 * it by in the backend layer (`@/lib/fake-backend.testHelpers`).
 */
export const AUTH_REQUESTS = {
  /** The password change. */
  change: "POST /api/auth/change-password",
  login: "POST /api/auth/login",
  logout: "DELETE /api/auth/logout",
  me: `GET ${ME_PATH}`,
  token: "GET /api/auth/csrf",
} as const;

// ---- auth state builders, for the hooks under src/auth ---------------------------
//
// A hook that reads the session rather than changes it — the gated read and
// write, the session request — is tested at its own seam, against a hand-built
// `AuthContext` value, its requests going to the backend layer
// (`@/lib/fake-backend.testHelpers`). A page suite renders through the real
// provider with `renderAsSession` (`./session-adapter.testHelpers`) instead.

/** Every member of the auth state that is not the session: the actions and controls. */
type AuthActions = Omit<AuthContextState, keyof Session>;

/** A session still waiting on its start-up check. */
export const CHECKING: Session = {
  checkFailure: null,
  signInReason: null,
  status: "checking",
  user: null,
};

/** A session signed in as {@link testUser}, with `user` overriding it. */
export function signedIn(user: Partial<AuthUser> = {}): Session {
  return { checkFailure: null, signInReason: null, status: "authenticated", user: testUser(user) };
}

/** A session whose start-up check came to `failure`: unknown, not ended. */
export function unchecked(failure: SessionCheckFailure = { kind: "failed" }): Session {
  return { checkFailure: failure, signInReason: null, status: "unchecked", user: null };
}

/**
 * The auth state a hook reads from `AuthContext`, for `session`: every action
 * and control a fresh `vi.fn()` unless `actions` hands one in.
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

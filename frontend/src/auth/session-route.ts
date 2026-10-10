/**
 * The session's routing contract, as one pure transition table.
 *
 * Both route guards are adapters over `resolveSessionRoute`, so the whole
 * contract — who waits, who renders, who is redirected where, and what the
 * redirect carries — is decided in one function that needs no router to test.
 */

import type { Permission } from "./api";
import type { AuthStatus } from "./auth-context-value";
import { holdsAny } from "./permissions";
import type { SessionEndReason, SignInReasonState } from "./sign-in-reason";

/** The login route, which is also where an unauthenticated visitor is sent. */
export const LOGIN_PATH = "/";

/** Where an authenticated visitor lands with no return destination recorded. */
export const DEFAULT_DESTINATION = "/showcase";

/**
 * The self-service password change: open to every authenticated visitor, and
 * the only route a session with the change-required flag is offered.
 */
export const CREDENTIAL_CHANGE_PATH = "/change-password";

/**
 * What a route requires of the current visitor: a guest, any authenticated
 * session, or an authenticated session holding at least one of the named
 * Permissions.
 */
export type SessionRequirement = "authenticated" | "guest" | { anyOf: readonly Permission[] };

/**
 * State a redirect carries forward.
 *
 * `from` is the **return destination**: the protected path the Guest asked
 * for, replayed once they sign in. `reason` is the sign-in reason the session
 * ended with, absent on a cold visit; `sign-in-reason.ts` owns what it says.
 */
export interface SessionRouteState extends SignInReasonState {
  from?: string;
}

export type SessionRoute =
  | { kind: "pending" }
  | { kind: "render" }
  | { kind: "redirect"; to: string; state?: SessionRouteState };

export interface SessionRouteInput {
  /** The session carries the change-required flag and is confined to the change. */
  passwordChangeRequired: boolean;
  /** The path being visited, recorded as the return destination on a redirect. */
  pathname: string;
  requires: SessionRequirement;
  /** A return destination carried by an earlier redirect, if there was one. */
  returnTo?: string;
  /** The authenticated session's Permissions, when they are known. */
  permissions?: readonly Permission[];
  /** Why the current `guest` status began, or `null` for a cold visit. */
  signInReason: SessionEndReason | null;
  status: AuthStatus;
}

export function resolveSessionRoute({
  passwordChangeRequired,
  pathname,
  permissions,
  requires,
  returnTo,
  signInReason,
  status,
}: SessionRouteInput): SessionRoute {
  if (status === "checking") return { kind: "pending" };

  if (status === "guest") {
    if (requires === "guest") return { kind: "render" };
    // A change, or the lockout a change ran into, ends the session for good,
    // so the page it was made from is no destination to replay: the next
    // sign-in is no continuation of this one and lands on the default instead.
    if (signInReason === "password-changed" || signInReason === "locked") {
      return { kind: "redirect", state: { reason: signInReason }, to: LOGIN_PATH };
    }
    // An Idle sign-out replays the page it left, as an expiry does: the User
    // did not choose to leave it. A cold visit records the destination alone.
    return {
      kind: "redirect",
      state: signInReason ? { from: pathname, reason: signInReason } : { from: pathname },
      to: LOGIN_PATH,
    };
  }

  // Confinement outranks every other rule, a recorded return destination and a
  // Superuser's Permissions included: the session may do nothing else until it
  // changes.
  if (passwordChangeRequired) {
    return pathname === CREDENTIAL_CHANGE_PATH
      ? { kind: "render" }
      : { kind: "redirect", to: CREDENTIAL_CHANGE_PATH };
  }

  if (requires === "guest") return { kind: "redirect", to: returnTo ?? DEFAULT_DESTINATION };
  return authenticatedRoute(requires, permissions);
}

/**
 * An unflagged, authenticated session on a protected route: any session renders
 * an authenticated one, and a Permission-guarded one renders for a session
 * holding any one of its Permissions. A page the session holds none of them for
 * is routed away to the default destination, never rendered empty.
 */
function authenticatedRoute(
  requires: Exclude<SessionRequirement, "guest">,
  permissions: readonly Permission[] = [],
): SessionRoute {
  if (requires === "authenticated" || holdsAny({ permissions }, requires.anyOf)) {
    return { kind: "render" };
  }
  return { kind: "redirect", to: DEFAULT_DESTINATION };
}

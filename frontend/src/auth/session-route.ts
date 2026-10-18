/**
 * The session's routing contract, as one pure transition table.
 *
 * Both route guards are adapters over `resolveSessionRoute`, so the whole
 * contract — who waits, who renders, who is redirected where, and what the
 * redirect carries — is decided in one function that needs no router to test.
 */

import type { Permission, SessionCheckFailure } from "./api";
import { mayOpen, type PageRequirement } from "./permissions";
import type { SessionEndReason, SignInReasonState } from "./sign-in-reason";

/** The login route, which is also where an unauthenticated visitor is sent. */
export const LOGIN_PATH = "/login";

/** Home: where an authenticated visitor lands with no return destination recorded. */
export const DEFAULT_DESTINATION = "/";

/**
 * The self-service password change: open to every authenticated visitor, and
 * the only route a session with the change-required flag is offered.
 */
export const CREDENTIAL_CHANGE_PATH = "/change-password";

/**
 * What a route requires of the current visitor: a guest, or what a signed-in
 * page requires — any authenticated session, or one holding at least one of
 * the named Permissions.
 */
export type SessionRequirement = "guest" | PageRequirement;

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

/**
 * `unchecked` is the start-up check that did not answer: the session is
 * unknown, so the route is neither rendered nor left — a redirect to login
 * would treat a live session as a Guest's. It carries what the check came to,
 * for the line that says so.
 */
export type SessionRoute =
  | { kind: "pending" }
  | { kind: "unchecked"; failure: SessionCheckFailure }
  | { kind: "render" }
  | { kind: "redirect"; to: string; state?: SessionRouteState };

/**
 * The session as routing reads it: its status, with what that status carries —
 * an unanswered check its failure, a Guest the sign-in reason it began with
 * (`null` for a cold visit). The auth state's `Session` is one.
 */
export type RoutedSession =
  | { status: "checking" | "authenticated" }
  | { checkFailure: SessionCheckFailure; status: "unchecked" }
  | { signInReason: SessionEndReason | null; status: "guest" };

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
  session: RoutedSession;
}

export function resolveSessionRoute({
  passwordChangeRequired,
  pathname,
  permissions,
  requires,
  returnTo,
  session,
}: SessionRouteInput): SessionRoute {
  if (session.status === "checking") return { kind: "pending" };
  if (session.status === "unchecked") return { failure: session.checkFailure, kind: "unchecked" };

  if (session.status === "guest") {
    const { signInReason } = session;
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
  requires: PageRequirement,
  permissions: readonly Permission[] | undefined,
): SessionRoute {
  if (mayOpen({ permissions }, requires)) return { kind: "render" };
  return { kind: "redirect", to: DEFAULT_DESTINATION };
}

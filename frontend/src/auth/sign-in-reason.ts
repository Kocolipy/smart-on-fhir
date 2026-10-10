/**
 * The **sign-in reason**: why a Guest is at the login page, and what it says.
 *
 * The one owner of the closed set of reasons, how each one reaches the login
 * page, which wins when two arrive at once, and the copy for each. Nothing else
 * in the SPA decides any of those: the provider records a reason, the routing
 * contract carries it, and the login page renders the notice this module gives.
 */

import type { PasswordChangeOutcome } from "./api";

/**
 * The reasons the SPA records when it ends a session: an Expired session, an
 * Idle sign-out, the User's own successful password change, and a password
 * change that locked the account instead. Exactly one or none at a time, so no
 * impossible combination can be represented. They reach the login page in
 * router state, as `SignInReasonState`.
 */
export type SessionEndReason = "expired" | "inactive" | "locked" | "password-changed";

/**
 * The reason a password change records, or `null` for an outcome that leaves
 * the session standing. A change replaces the password and a lockout revokes
 * every session of the User, so both end this one; every refusal keeps it.
 */
export function sessionEndReasonFor(outcome: PasswordChangeOutcome): SessionEndReason | null {
  switch (outcome.kind) {
    case "changed":
      return "password-changed";
    case "locked":
      return "locked";
    default:
      return null;
  }
}

/** Every `SessionEndReason`, so router state can be checked against the set. */
const SESSION_END_REASONS: ReadonlySet<unknown> = new Set<SessionEndReason>([
  "expired",
  "inactive",
  "locked",
  "password-changed",
]);

/**
 * The reasons an Epic launch lands with. Epic Login reaches the SPA by
 * full-page navigation, so these arrive as the backend's `?signin=` query
 * marker rather than router state.
 */
type EpicLandingReason = "epic-refused" | "epic-unavailable";

type SignInReason = SessionEndReason | EpicLandingReason;

/** The router-state field a session-ending redirect carries its reason in. */
export interface SignInReasonState {
  reason?: SessionEndReason;
}

/**
 * The backend-owned `?signin=` wire values (`EpicLanding.java`) and the reason
 * each names. Any other value names none.
 */
const EPIC_MARKERS: ReadonlyMap<string | null, EpicLandingReason> = new Map([
  ["refused", "epic-refused"],
  ["unavailable", "epic-unavailable"],
]);

/** The copy for each reason; exhaustive, so a new reason cannot ship without its own. */
function noticeFor(reason: SignInReason): string {
  switch (reason) {
    case "expired":
      return "Your session ended. Please sign in again.";
    case "inactive":
      return "You were signed out because you were inactive. Please sign in again.";
    case "locked":
      // Said only here, after the User's own session ended: a refused login
      // never says "locked", which would tell a guesser the name is real.
      return "Too many incorrect passwords: the account is now locked and your session has ended. An Admin must Unlock the account before you can sign in again.";
    case "password-changed":
      return "Your password was changed. Sign in with your new password.";
    case "epic-refused":
      // Neutral on purpose: the backend gives the browser no reason, and the
      // SPA invents none.
      return "Sign-in from Epic was refused";
    case "epic-unavailable":
      // Distinct from a refusal, because relaunching shortly may well succeed.
      return "Sign-in from Epic is temporarily unavailable. Try again shortly.";
  }
}

/** Where the login page is, as far as the notice is concerned. */
interface LoginLocation {
  search: string;
  state: unknown;
}

/**
 * The reason router state carries, if it carries one of the closed set. The
 * history entry can hold anything, a stale or foreign shape included, so it is
 * decoded rather than cast.
 */
function carriedReason(state: unknown): SessionEndReason | undefined {
  const reason = (state as { reason?: unknown } | null)?.reason;
  return SESSION_END_REASONS.has(reason) ? (reason as SessionEndReason) : undefined;
}

/**
 * The reason the login page was reached with. An Epic marker wins over any
 * router state: the landing is a fresh navigation, so state the history entry
 * happens to carry is stale.
 */
function signInReasonAt({ search, state }: LoginLocation): SignInReason | undefined {
  return EPIC_MARKERS.get(new URLSearchParams(search).get("signin")) ?? carriedReason(state);
}

/** The notice the login page shows for how it was reached, or `null` for none. */
export function signInNoticeFor(location: LoginLocation): string | null {
  const reason = signInReasonAt(location);
  return reason ? noticeFor(reason) : null;
}

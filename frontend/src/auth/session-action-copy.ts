/**
 * The copy for every **session action** outcome — login, logout and the
 * password change — and the only place it lives.
 *
 * Each action resolves to a closed outcome (`auth/api.ts`), never a throw; the
 * page that ran it renders the line this module gives for it. Each switch is
 * exhaustive over the outcomes that carry copy, so a new outcome cannot ship
 * without its own. The two transport outcomes read the request seam's
 * {@link refusalMessage}, as the gated read and write do, so `forbidden` and
 * `csrf-expired` say the same thing everywhere.
 *
 * The outcomes that carry no copy here are the ones that end or start a
 * session: the guards move the visitor on, and the login page says why
 * (`sign-in-reason.ts`).
 */

import type { LoginOutcome, LogoutOutcome, PasswordChangeOutcome } from "./api";
import { refusalMessage } from "./use-session-request";

/** What a refused login says: the same for an unknown name, a wrong password and a lockout. */
const LOGIN_REFUSED_MESSAGE = "The username or password is incorrect.";

/** What a login that did not reach a verdict says. */
export function loginMessage(outcome: Exclude<LoginOutcome, { kind: "signed-in" }>): string {
  switch (outcome.kind) {
    case "refused":
      return LOGIN_REFUSED_MESSAGE;
    case "forbidden":
    case "csrf-expired":
    case "failed":
      return refusalMessage(outcome, "Unable to sign in. Please try again.");
  }
}

/**
 * What a Sign out that left the session standing says — the whole line, so it
 * also tells the User the session still stands.
 */
export function logoutMessage(outcome: Exclude<LogoutOutcome, { kind: "signed-out" }>): string {
  return `${refusalMessage(outcome, "Unable to sign out. Please try again.")} You are still signed in.`;
}

/**
 * What a refused password change says, while the session stands. None of these
 * contain either submitted value: a policy refusal shows the backend's
 * statement of the rule, which names the rule and never the password.
 */
export function passwordChangeMessage(
  outcome: Exclude<PasswordChangeOutcome, { kind: "changed" | "locked" }>,
): string {
  switch (outcome.kind) {
    case "policy-violation":
      return outcome.message;
    case "current-password-rejected":
      return "The current password is incorrect.";
    case "forbidden":
    case "csrf-expired":
    case "failed":
      return refusalMessage(outcome, "Unable to change the password. Please try again.");
  }
}

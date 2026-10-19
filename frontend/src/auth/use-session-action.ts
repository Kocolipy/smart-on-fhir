/**
 * The one way a page runs a **session action** — login, logout or the
 * password change.
 *
 * A page names the action and runs it with the action's own arguments; the
 * hook owns the rest, in one order for all three: it withdraws the line an
 * earlier run left, holds the pending flag while the action is out, and reads
 * the closed outcome it resolves to (`auth/api.ts`; an action never throws).
 * An outcome that ends or starts a session — `signed-in`, `signed-out`,
 * `changed`, `locked`, `expired` — has already moved the session, so the guards move the
 * visitor on and the login page says why (`sign-in-reason.ts`); there is
 * nothing to show, and the hook shows nothing. Every other outcome leaves the
 * visitor on the page, and the hook hands it the one line
 * `session-action-copy.ts` gives for it — on the signed-in layout's one error
 * line when the caller hands it a reporter there, or as `error` for the login
 * page, which is outside that layout. The page renders `pending` (and, on the
 * login page, `error`) as they come, as it does a gated write's, and never
 * reads the outcome itself.
 *
 * Pending clears once the action settles, whatever its outcome. On one that
 * ended or started a session the guard replaces the page on that same update,
 * so the settled control is never seen; a page still mounted gets its control
 * back, as one left on the page needs.
 *
 * Which outcome of login or logout starts or ends a session is decided here, by
 * {@link LINE_FOR}. For the password change it is `sign-in-reason.ts`'s
 * `sessionEndReasonFor`, the rule the provider ends the session by, and
 * {@link LINE_FOR} reads it rather than repeating it. Each copy function only
 * takes the outcomes that remain.
 */

import { useState } from "react";

import type { PasswordChangeOutcome } from "./api";
import { useAuth, type AuthContextValue } from "./auth-context-value";
import type { StatusReporter } from "./page-status";
import { loginMessage, logoutMessage, passwordChangeMessage } from "./session-action-copy";
import { sessionEndReasonFor } from "./sign-in-reason";

type SessionActions = Pick<AuthContextValue, "changePassword" | "login" | "logout">;

/** The auth context member a session action is: `login`, `logout` or `changePassword`. */
export type SessionActionName = keyof SessionActions;

type ArgsOf<N extends SessionActionName> = Parameters<SessionActions[N]>;
type OutcomeOf<N extends SessionActionName> = Awaited<ReturnType<SessionActions[N]>>;

/**
 * Whether a password change ended the session, by the provider's own rule; the
 * type only lets the copy below see the refusals that remain.
 */
function endedSession(
  outcome: PasswordChangeOutcome,
): outcome is Extract<PasswordChangeOutcome, { kind: "changed" | "expired" | "locked" }> {
  return sessionEndReasonFor(outcome) !== null;
}

/**
 * The line each action's outcome shows, or `null` for one that ended or
 * started a session, which the guards answer by moving the visitor on.
 */
const LINE_FOR: { [N in SessionActionName]: (outcome: OutcomeOf<N>) => string | null } = {
  changePassword: (outcome) => (endedSession(outcome) ? null : passwordChangeMessage(outcome)),
  login: (outcome) => (outcome.kind === "signed-in" ? null : loginMessage(outcome)),
  logout: (outcome) => (outcome.kind === "signed-out" ? null : logoutMessage(outcome)),
};

/**
 * How a run settled: `stayed` when the outcome left the visitor on the page with
 * its line, `moved-on` when it ended or started a session and the guards take
 * the visitor elsewhere.
 */
export type SessionActionResult = "moved-on" | "stayed";

/** A session action whose line goes to a page status: the control's half. */
export interface ReportingSessionAction<A extends unknown[]> {
  /** Whether a run is out; the control that runs it disables on this. */
  pending: boolean;
  /**
   * Runs the action once. Never rejects, and resolves how it settled — for a
   * page that tidies up after a refusal.
   */
  run: (...args: A) => Promise<SessionActionResult>;
}

/** A session action that holds its own line, for a page outside the signed-in layout. */
export interface SessionAction<A extends unknown[]> extends ReportingSessionAction<A> {
  /** The line the latest run's outcome shows, or `null` while there is none. */
  error: string | null;
}

/**
 * Runs `name`. Inside the signed-in layout the caller hands it a reporter on
 * the layout's page status (`useStatusReporter`), and the line goes there: the
 * run withdraws its own earlier line as it starts and reports the new one as it
 * settles, under the status's latest-event rule. Outside it — the login page —
 * the hook holds the line itself and hands back `error`.
 */
export function useSessionAction<N extends SessionActionName>(name: N): SessionAction<ArgsOf<N>>;
export function useSessionAction<N extends SessionActionName>(
  name: N,
  reporter: StatusReporter,
): ReportingSessionAction<ArgsOf<N>>;
export function useSessionAction<N extends SessionActionName>(
  name: N,
  reporter?: StatusReporter,
): SessionAction<ArgsOf<N>> {
  const auth = useAuth();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Where the outcome's line goes: the layout's status, or the hook's own `error`.
  const report = reporter?.report ?? setError;

  const run = async (...args: ArgsOf<N>): Promise<SessionActionResult> => {
    // `auth[name]` and `LINE_FOR[name]` agree on `N`, which TypeScript cannot follow through the index.
    const act = auth[name] as (...args: ArgsOf<N>) => Promise<OutcomeOf<N>>;
    report(null);
    setPending(true);
    try {
      const line = LINE_FOR[name](await act(...args));
      report(line);
      return line === null ? "moved-on" : "stayed";
    } finally {
      setPending(false);
    }
  };

  return { error, pending, run };
}

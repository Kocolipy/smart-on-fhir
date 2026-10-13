import { describe, expect, it } from "vitest";

import type {
  LoginOutcome,
  LogoutOutcome,
  PasswordChangeOutcome,
  SessionCheckFailure,
} from "./api";
import {
  loginMessage,
  logoutMessage,
  passwordChangeMessage,
  sessionCheckMessage,
} from "./session-action-copy";

// Literal copy throughout, not the constants it is built from: a change to
// what the User reads must fail here.
const FORBIDDEN = "You don't have permission to do this.";
const CSRF_EXPIRED = "Your security token expired. Please try again.";

describe("the session-action copy", () => {
  it.each<[Exclude<LoginOutcome, { kind: "signed-in" }>, string]>([
    // The same for an unknown name, a wrong password and a lockout.
    [{ kind: "refused" }, "The username or password is incorrect."],
    [{ kind: "forbidden" }, FORBIDDEN],
    [{ kind: "csrf-expired" }, CSRF_EXPIRED],
    [{ kind: "failed" }, "Unable to sign in. Please try again."],
  ])("a login that came to %j says %j", (outcome, copy) => {
    expect(loginMessage(outcome)).toBe(copy);
  });

  it.each<[Exclude<LogoutOutcome, { kind: "signed-out" }>, string]>([
    // The whole line: the session stood, and the copy says so.
    [{ kind: "csrf-expired" }, `${CSRF_EXPIRED} You are still signed in.`],
    [{ kind: "failed" }, "Unable to sign out. Please try again. You are still signed in."],
  ])("a logout that came to %j says %j", (outcome, copy) => {
    expect(logoutMessage(outcome)).toBe(copy);
  });

  it.each<[Exclude<PasswordChangeOutcome, { kind: "changed" | "locked" }>, string]>([
    // The backend's statement of the rule, passed through as it names it.
    [
      { kind: "policy-violation", message: "The new password must not contain the user name" },
      "The new password must not contain the user name",
    ],
    [{ kind: "current-password-rejected" }, "The current password is incorrect."],
    [{ kind: "forbidden" }, FORBIDDEN],
    [{ kind: "csrf-expired" }, CSRF_EXPIRED],
    [{ kind: "failed" }, "Unable to change the password. Please try again."],
  ])("a password change that came to %j says %j", (outcome, copy) => {
    expect(passwordChangeMessage(outcome)).toBe(copy);
  });

  it.each<[SessionCheckFailure, string]>([
    // The whole line: the session could not be checked, and why when the seam knows.
    [{ kind: "forbidden" }, `Your session could not be checked. ${FORBIDDEN}`],
    [{ kind: "csrf-expired" }, `Your session could not be checked. ${CSRF_EXPIRED}`],
    [{ kind: "failed" }, "Your session could not be checked. Please try again."],
  ])("a session check that came to %j says %j", (outcome, copy) => {
    expect(sessionCheckMessage(outcome)).toBe(copy);
  });
});

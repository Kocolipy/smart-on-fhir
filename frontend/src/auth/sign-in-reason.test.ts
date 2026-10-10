import { describe, expect, it } from "vitest";

import type { PasswordChangeOutcome } from "./api";
import { sessionEndReasonFor, signInNoticeFor, type SignInReasonState } from "./sign-in-reason";

const EXPIRED = "Your session ended. Please sign in again.";
const INACTIVE = "You were signed out because you were inactive. Please sign in again.";
const CHANGED = "Your password was changed. Sign in with your new password.";
const LOCKED =
  "Too many incorrect passwords: the account is now locked and your session has ended. An Admin must Unlock the account before you can sign in again.";
const EPIC_REFUSED = "Sign-in from Epic was refused";
const EPIC_UNAVAILABLE = "Sign-in from Epic is temporarily unavailable. Try again shortly.";

const carrying = (state: SignInReasonState & { from?: string }, search = "") => ({ search, state });
const landing = (search: string) => ({ search, state: null });

describe("signInNoticeFor", () => {
  it.each([
    // Each reason, through the carriage it arrives by.
    [
      "an Expired session, carried in router state",
      carrying({ from: "/a", reason: "expired" }),
      EXPIRED,
    ],
    [
      "an Idle sign-out, carried in router state",
      carrying({ from: "/a", reason: "inactive" }),
      INACTIVE,
    ],
    [
      "a password change, carried in router state",
      carrying({ reason: "password-changed" }),
      CHANGED,
    ],
    [
      "a lockout during a password change, carried in router state",
      carrying({ reason: "locked" }),
      LOCKED,
    ],
    ["an Epic refusal, landed as ?signin=refused", landing("?signin=refused"), EPIC_REFUSED],
    [
      "Epic being unavailable, landed as ?signin=unavailable",
      landing("?signin=unavailable"),
      EPIC_UNAVAILABLE,
    ],
    // An Epic landing is a fresh navigation, so its marker wins over any router state.
    [
      "an Epic refusal over a carried expiry",
      carrying({ reason: "expired" }, "?signin=refused"),
      EPIC_REFUSED,
    ],
    [
      "an Epic refusal over a carried Idle sign-out",
      carrying({ reason: "inactive" }, "?signin=refused"),
      EPIC_REFUSED,
    ],
    [
      "Epic unavailable over a carried password change",
      carrying({ reason: "password-changed" }, "?signin=unavailable"),
      EPIC_UNAVAILABLE,
    ],
    // Nothing to say.
    ["a cold visit", landing(""), null],
    ["a return destination with no reason", carrying({ from: "/accounts" }), null],
    ["a ?signin= marker it does not know", landing("?signin=elsewhere"), null],
    ["a ?signin= marker naming an object property", landing("?signin=constructor"), null],
    [
      "an unknown marker beside a carried reason",
      carrying({ reason: "expired" }, "?signin=elsewhere"),
      EXPIRED,
    ],
    // Router state is whatever the history entry holds, so it is decoded, not trusted.
    ["a carried reason it does not know", { search: "", state: { reason: "stale" } }, null],
    [
      "a carried reason naming an object property",
      { search: "", state: { reason: "constructor" } },
      null,
    ],
    ["router state that is not an object", { search: "", state: "expired" }, null],
  ])("says the right thing for %s", (_name, location, notice) => {
    expect(signInNoticeFor(location)).toBe(notice);
  });
});

describe("sessionEndReasonFor", () => {
  it.each<[PasswordChangeOutcome, string | null]>([
    // The two outcomes after which the backend holds no session for the User.
    [{ kind: "changed" }, "password-changed"],
    [{ kind: "locked" }, "locked"],
    // Every refusal leaves the session standing, so it records no reason.
    [{ kind: "policy-violation", message: "Too short." }, null],
    [{ kind: "current-password-rejected" }, null],
    [{ kind: "forbidden" }, null],
    [{ kind: "csrf-expired" }, null],
    [{ kind: "failed" }, null],
  ])("a password change that came to %o ends the session for %s", (outcome, reason) => {
    expect(sessionEndReasonFor(outcome)).toBe(reason);
  });
});

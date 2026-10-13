import { act, fireEvent, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { testUser } from "@/auth/auth-context.testHelpers";
import {
  bare,
  deferred,
  GUEST,
  json,
  renderAsSession,
  type Answers,
} from "@/auth/session-adapter.testHelpers";
import type { SessionRouteState } from "@/auth/session-route";

import { Login } from "./login";

const INACTIVE = "You were signed out because you were inactive. Please sign in again.";
const EXPIRED = "Your session ended. Please sign in again.";
const EPIC_UNAVAILABLE = "Sign-in from Epic is temporarily unavailable. Try again shortly.";
const CLINICIANS = "Clinicians: open this application from Epic.";
const LOGIN = "POST /api/auth/login";

/** What the form is filled in with, as the login request names it. */
const TEST_LOGIN = ["ada", "secret-value"] as const;
const [username, password] = TEST_LOGIN;

/** The login's `UserResponse`: the backend signed the User in. */
const SIGNED_IN = json(testUser());

/** The login page for a Guest, at `/` with `search` and router `state`, the login answered as `answers` says. */
const renderLogin = (answers: Answers = {}, state?: SessionRouteState, search = "") =>
  renderAsSession(<Login />, GUEST, { answers, at: { pathname: "/", search, state } });

const submit = () =>
  act(async () => {
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: username } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: password } });
    fireEvent.submit(screen.getByRole("button", { name: /Sign/ }));
  });

describe("Login", () => {
  // Running the login — pending, the outcome, which one signs in, its copy — is
  // `use-session-action.test.tsx`'s; these prove the page renders what it hands
  // back. Which sign-in reason says what, and which one wins, is the table in
  // `sign-in-reason.test.ts`; these prove the page renders the notice it gives
  // for each carriage.
  it("says why for a reason carried in router state", async () => {
    await renderLogin({}, { from: "/accounts", reason: "inactive" });

    // The whole status, not a substring.
    expect(screen.getByRole("status").textContent).toBe(INACTIVE);
  });

  it("says why for a reason an Epic launch landed with", async () => {
    await renderLogin({}, undefined, "?signin=unavailable");

    expect(screen.getByRole("status").textContent).toBe(EPIC_UNAVAILABLE);
  });

  it("says nothing on a cold visit", async () => {
    await renderLogin();

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("tells clinicians where they sign in", async () => {
    await renderLogin();

    expect(screen.getByText(CLINICIANS).textContent).toBe(CLINICIANS);
  });

  it.each(["?signin=refused", "?signin=unavailable"])(
    "tells clinicians where they sign in beside the %s notice",
    async (search) => {
      await renderLogin({}, undefined, search);

      expect(screen.getByText(CLINICIANS).textContent).toBe(CLINICIANS);
    },
  );

  it.each(["?signin=refused", "?signin=unavailable"])(
    "keeps the password form working beside the %s notice",
    async (search) => {
      const { requests } = await renderLogin({ [LOGIN]: SIGNED_IN }, undefined, search);

      await submit();

      expect(requests()).toEqual([
        {
          body: { password, username },
          method: "POST",
          path: "/api/auth/login",
        },
      ]);
    },
  );

  it("shows the submission in progress, then lets the form be used again", async () => {
    const signingIn = deferred();
    await renderLogin({ [LOGIN]: signingIn.answer });

    await submit();
    expect(screen.getByRole("button", { name: "Signing in…" })).toBeDisabled();

    await signingIn.settle(SIGNED_IN);
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
  });

  it("says nothing more once signed in", async () => {
    await renderLogin({ [LOGIN]: SIGNED_IN });

    await submit();

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows the refusal's own message, and clears it on the next attempt", async () => {
    await renderLogin({ [LOGIN]: [bare(401), deferred().answer] }, { reason: "expired" });

    await submit();
    expect(screen.getByRole("alert")).toHaveTextContent(
      /^The username or password is incorrect\.$/,
    );
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();

    await submit();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(new RegExp(`^${EXPIRED}$`));
  });
});

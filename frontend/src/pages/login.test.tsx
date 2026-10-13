import { act, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import type { LoginOutcome } from "@/auth/api";
import { AuthContext, type AuthContextState } from "@/auth/auth-context-value";
import { authState, guest } from "@/auth/auth-context.testHelpers";
import type { SessionRouteState } from "@/auth/session-route";

import { Login } from "./login";

const INACTIVE = "You were signed out because you were inactive. Please sign in again.";
const EXPIRED = "Your session ended. Please sign in again.";
const EPIC_UNAVAILABLE = "Sign-in from Epic is temporarily unavailable. Try again shortly.";
const CLINICIANS = "Clinicians: open this application from Epic.";
const SIGNED_IN: LoginOutcome = {
  kind: "signed-in",
  user: {
    idleTimeoutSeconds: 900,
    passwordChangeRequired: false,
    permissions: [],
    username: "ada",
  },
};

function renderLogin(login: AuthContextState["login"], state?: SessionRouteState, search = "") {
  const value = authState(guest(), { login });
  render(
    <MemoryRouter initialEntries={[{ pathname: "/", search, state }]}>
      <AuthContext.Provider value={value}>
        <Login />
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

const submit = () =>
  act(async () => {
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "ada" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "secret-value" } });
    fireEvent.submit(screen.getByRole("button", { name: /Sign/ }));
  });

describe("Login", () => {
  // Running the login — pending, the outcome, which one signs in, its copy — is
  // `use-session-action.test.tsx`'s; these prove the page renders what it hands
  // back. Which sign-in reason says what, and which one wins, is the table in
  // `sign-in-reason.test.ts`; these prove the page renders the notice it gives
  // for each carriage.
  it("says why for a reason carried in router state", () => {
    renderLogin(vi.fn(), { from: "/accounts", reason: "inactive" });

    // The whole status, not a substring.
    expect(screen.getByRole("status").textContent).toBe(INACTIVE);
  });

  it("says why for a reason an Epic launch landed with", () => {
    renderLogin(vi.fn(), undefined, "?signin=unavailable");

    expect(screen.getByRole("status").textContent).toBe(EPIC_UNAVAILABLE);
  });

  it("says nothing on a cold visit", () => {
    renderLogin(vi.fn());

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("tells clinicians where they sign in", () => {
    renderLogin(vi.fn());

    expect(screen.getByText(CLINICIANS).textContent).toBe(CLINICIANS);
  });

  it.each(["?signin=refused", "?signin=unavailable"])(
    "tells clinicians where they sign in beside the %s notice",
    (search) => {
      renderLogin(vi.fn(), undefined, search);

      expect(screen.getByText(CLINICIANS).textContent).toBe(CLINICIANS);
    },
  );

  it.each(["?signin=refused", "?signin=unavailable"])(
    "keeps the password form working beside the %s notice",
    async (search) => {
      const login = vi.fn<AuthContextState["login"]>().mockResolvedValue(SIGNED_IN);
      renderLogin(login, undefined, search);

      await submit();

      expect(login).toHaveBeenCalledWith("ada", "secret-value");
    },
  );

  it("shows the submission in progress, then lets the form be used again", async () => {
    let finish: () => void = () => undefined;
    renderLogin(
      vi.fn(
        () =>
          new Promise<LoginOutcome>((resolve) => {
            finish = () => resolve(SIGNED_IN);
          }),
      ),
    );

    await submit();
    expect(screen.getByRole("button", { name: "Signing in…" })).toBeDisabled();

    await act(async () => finish());
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
  });

  it("says nothing more once signed in", async () => {
    renderLogin(vi.fn<AuthContextState["login"]>().mockResolvedValue(SIGNED_IN));

    await submit();

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows the refusal's own message, and clears it on the next attempt", async () => {
    const login = vi
      .fn<AuthContextState["login"]>()
      .mockResolvedValueOnce({ kind: "refused" })
      .mockReturnValueOnce(new Promise<LoginOutcome>(() => undefined));
    renderLogin(login, { reason: "expired" });

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

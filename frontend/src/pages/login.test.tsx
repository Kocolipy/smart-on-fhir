import { act, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import { AuthContext, type AuthContextState } from "@/auth/auth-context-value";
import type { SessionRouteState } from "@/auth/session-route";

import { Login } from "./login";

const INACTIVE = "You were signed out because you were inactive. Please sign in again.";
const EXPIRED = "Your session ended. Please sign in again.";
const EPIC_UNAVAILABLE = "Sign-in from Epic is temporarily unavailable. Try again shortly.";
const CLINICIANS = "Clinicians: open this application from Epic.";

function renderLogin(login: AuthContextState["login"], state?: SessionRouteState, search = "") {
  const value: AuthContextState = {
    changePassword: vi.fn(),
    expireSession: vi.fn(),
    login,
    logout: vi.fn(),
    signInReason: null,
    signOutForInactivity: vi.fn(),
    status: "guest",
    user: null,
  };
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
  // Which sign-in reason says what, and which one wins, is the table in
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
      const login = vi.fn<AuthContextState["login"]>().mockResolvedValue(undefined);
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
          new Promise<void>((resolve) => {
            finish = resolve;
          }),
      ),
    );

    await submit();
    expect(screen.getByRole("button", { name: "Signing in…" })).toBeDisabled();

    await act(async () => finish());
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
  });

  it("shows the refusal's own message, and clears it on the next attempt", async () => {
    const login = vi
      .fn<AuthContextState["login"]>()
      .mockRejectedValueOnce(new Error("The username or password is incorrect."))
      .mockReturnValueOnce(new Promise<void>(() => undefined));
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

  it("falls back to a generic message for a refusal that is not an Error", async () => {
    renderLogin(vi.fn().mockRejectedValue("offline"));

    await submit();

    expect(screen.getByRole("alert")).toHaveTextContent(/^Unable to sign in\. Please try again\.$/);
  });
});

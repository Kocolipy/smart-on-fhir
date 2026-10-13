import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import type { LogoutOutcome } from "@/auth/api";
import { AuthContext, type AuthContextState } from "@/auth/auth-context-value";
import { authState, CHECKING, signedIn } from "@/auth/auth-context.testHelpers";

import { SignedInShell } from "./signed-in-shell";

const authWith = (
  logout: AuthContextState["logout"],
  passwordChangeRequired = false,
): AuthContextState => authState(signedIn({ passwordChangeRequired }), { logout });

function renderShell(auth: AuthContextState, path = "/showcase") {
  return render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<SignedInShell />}>
            <Route element={<p>page body</p>} path="/showcase" />
            <Route element={<p>page body</p>} path="/accounts" />
            <Route element={<p>page body</p>} path="/audit" />
            <Route element={<p>page body</p>} path="/change-password" />
            <Route element={<p>page body</p>} path="*" />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const STILL_SIGNED_IN = /^Unable to sign out\. Please try again\. You are still signed in\.$/;
const FAILED: LogoutOutcome = { kind: "failed" };

const TOP_ALIGNED_WIDE = "mx-auto flex min-h-svh flex-col gap-6 p-8 max-w-6xl";

const signOut = () => screen.getByRole("button", { name: "Sign out" });

describe("SignedInShell", () => {
  it("shows the signed-in identity, the page title and the page", () => {
    renderShell(authWith(vi.fn()), "/accounts");
    expect(screen.getByText("Signed in as ada")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Accounts" })).toBeInTheDocument();
    expect(screen.getByText("page body")).toBeInTheDocument();
  });

  it("links back to the showcase from the Audit page", () => {
    renderShell(authWith(vi.fn()), "/audit");
    expect(screen.getByRole("link", { name: "Back to showcase" })).toHaveAttribute(
      "href",
      "/showcase",
    );
  });

  it("offers no back link on the Showcase", () => {
    renderShell(authWith(vi.fn()), "/showcase");
    expect(screen.queryByRole("link", { name: /^Back/ })).not.toBeInTheDocument();
  });

  it("offers no back link to a session confined to the password change", () => {
    renderShell(authWith(vi.fn(), true), "/change-password");
    expect(screen.queryByRole("link", { name: /^Back/ })).not.toBeInTheDocument();
  });

  it("offers a back link on the password change once unconfined", () => {
    renderShell(authWith(vi.fn()), "/change-password");
    expect(screen.getByRole("link", { name: "Back" })).toHaveAttribute("href", "/showcase");
  });

  it("signs out through the auth context", async () => {
    const logout = vi.fn<AuthContextState["logout"]>().mockResolvedValue({ kind: "signed-out" });
    renderShell(authWith(logout));
    await userEvent.setup().click(signOut());
    expect(logout).toHaveBeenCalledOnce();
  });

  // Running the Sign out — pending, which outcome signed out, its copy, the line
  // withdrawn on the next try — is `use-session-action.test.tsx`'s; these prove
  // the shell renders what it hands back.
  it("disables Sign out while the logout is in flight", async () => {
    renderShell(authWith(vi.fn(() => new Promise<LogoutOutcome>(() => {}))));
    await userEvent.setup().click(signOut());
    expect(screen.getByRole("button", { name: "Signing out…" })).toBeDisabled();
  });

  it("says a failed Sign out left the user signed in", async () => {
    const logout = vi.fn<AuthContextState["logout"]>().mockResolvedValue(FAILED);
    renderShell(authWith(logout));
    await userEvent.setup().click(signOut());
    expect(await screen.findByRole("alert")).toHaveTextContent(STILL_SIGNED_IN);
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });

  it("titles each signed-in page", () => {
    renderShell(authWith(vi.fn()), "/showcase");
    expect(screen.getByRole("heading", { level: 1, name: "Front End" })).toBeInTheDocument();
  });

  it("titles the password change", () => {
    renderShell(authWith(vi.fn()), "/change-password");
    expect(screen.getByRole("heading", { level: 1, name: "Change password" })).toBeInTheDocument();
  });

  it("links back to the counter from the Accounts page", () => {
    renderShell(authWith(vi.fn()), "/accounts");
    expect(screen.getByRole("link", { name: "Back to counter" })).toHaveAttribute(
      "href",
      "/showcase",
    );
  });

  it("renders safely while the session's user details are unavailable", () => {
    renderShell({ ...authWith(vi.fn()), ...CHECKING });
    expect(screen.getByText(/^Signed in as\s*$/)).toBeInTheDocument();
  });

  it.each([
    ["Accounts", "/accounts"],
    ["Audit", "/audit"],
  ])("lays the %s page out wide and top-aligned", (_, path) => {
    renderShell(authWith(vi.fn()), path);
    expect(screen.getByRole("main").className.trim()).toBe(TOP_ALIGNED_WIDE);
  });

  it("centers the password change in a narrow column", () => {
    renderShell(authWith(vi.fn()), "/change-password");
    expect(screen.getByRole("main")).toHaveClass("max-w-md", "items-center", "justify-center");
  });

  it("centers the Showcase in a medium column", () => {
    renderShell(authWith(vi.fn()), "/showcase");
    expect(screen.getByRole("main")).toHaveClass("max-w-2xl", "items-center", "justify-center");
  });

  it("renders the Showcase layout for a path it has no page for", () => {
    renderShell(authWith(vi.fn()), "/nowhere");
    expect(screen.getByRole("heading", { level: 1, name: "Front End" })).toBeInTheDocument();
  });

  it("renders the Showcase layout for a trailing-slash path", () => {
    renderShell(authWith(vi.fn()), "/showcase/");
    expect(screen.getByRole("heading", { level: 1, name: "Front End" })).toBeInTheDocument();
  });
});

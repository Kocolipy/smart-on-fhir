import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import { SIGN_OUT_FAILED_MESSAGE } from "@/auth/api";
import { AuthContext, type AuthContextState } from "@/auth/auth-context-value";
import { CSRF_EXPIRED_MESSAGE } from "@/auth/use-session-request";

import { SignedInShell } from "./signed-in-shell";

const authWith = (
  logout: AuthContextState["logout"],
  passwordChangeRequired = false,
): AuthContextState => ({
  changePassword: vi.fn(),
  expireSession: vi.fn(),
  login: vi.fn(),
  logout,
  signInReason: null,
  signOutForInactivity: vi.fn(),
  status: "authenticated",
  user: { idleTimeoutSeconds: 900, passwordChangeRequired, permissions: [], username: "ada" },
});

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
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

const stillSignedIn = (message: string) =>
  new RegExp(`^${message.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")} You are still signed in\\.$`);
const STILL_SIGNED_IN = stillSignedIn(SIGN_OUT_FAILED_MESSAGE);

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
    const logout = vi.fn().mockResolvedValue(undefined);
    renderShell(authWith(logout));
    await userEvent.setup().click(signOut());
    expect(logout).toHaveBeenCalledOnce();
  });

  it("disables Sign out while the logout is in flight", async () => {
    renderShell(authWith(vi.fn(() => new Promise<void>(() => {}))));
    await userEvent.setup().click(signOut());
    expect(screen.getByRole("button", { name: "Signing out…" })).toBeDisabled();
  });

  it("says a failed Sign out left the user signed in", async () => {
    const logout = vi.fn().mockRejectedValue(new Error(SIGN_OUT_FAILED_MESSAGE));
    renderShell(authWith(logout));
    await userEvent.setup().click(signOut());
    expect(await screen.findByRole("alert")).toHaveTextContent(STILL_SIGNED_IN);
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });

  it("says a CSRF-expired Sign out left the user signed in", async () => {
    const logout = vi.fn().mockRejectedValue(new Error(CSRF_EXPIRED_MESSAGE));
    renderShell(authWith(logout));
    await userEvent.setup().click(signOut());
    expect(await screen.findByRole("alert")).toHaveTextContent(stillSignedIn(CSRF_EXPIRED_MESSAGE));
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

  it("renders safely while the signed-in user's details are unavailable", () => {
    renderShell({ ...authWith(vi.fn()), user: null });
    expect(screen.getByText(/^Signed in as\s*$/)).toBeInTheDocument();
  });

  it("clears the error line when Sign out is tried again", async () => {
    const logout = vi
      .fn()
      .mockRejectedValueOnce(new Error(SIGN_OUT_FAILED_MESSAGE))
      .mockReturnValueOnce(new Promise<void>(() => {}));
    renderShell(authWith(logout));
    const user = userEvent.setup();
    await user.click(signOut());
    await screen.findByRole("alert");
    await user.click(signOut());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("falls back to a plain message when the logout fails with something other than an Error", async () => {
    renderShell(authWith(vi.fn().mockRejectedValue("boom")));
    await userEvent.setup().click(signOut());
    expect(await screen.findByRole("alert")).toHaveTextContent(STILL_SIGNED_IN);
  });

  it("lays the Accounts page out wide and top-aligned", () => {
    renderShell(authWith(vi.fn()), "/accounts");
    expect(screen.getByRole("main")).toHaveClass("max-w-6xl");
    expect(screen.getByRole("main").className.trim()).toBe(TOP_ALIGNED_WIDE);
  });

  it("lays the Audit page out wide and top-aligned", () => {
    renderShell(authWith(vi.fn()), "/audit");
    expect(screen.getByRole("main")).toHaveClass("max-w-6xl");
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

  it("renders the Showcase layout for a trailing-slash path", () => {
    renderShell(authWith(vi.fn()), "/showcase/");
    expect(screen.getByRole("heading", { level: 1, name: "Front End" })).toBeInTheDocument();
  });
});

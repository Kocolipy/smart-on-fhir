import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";

import {
  bare,
  deferred,
  holding,
  renderAsSession,
  STILL_CHECKING,
  type Answers,
  type StartingSession,
} from "@/auth/session-adapter.testHelpers";

import { SignedInShell } from "./signed-in-shell";

const LOGOUT = "DELETE /api/auth/logout";

const SIGNED_IN = holding([]);

/** The shell at `path` as `session` sees it, a Sign out answered as `answers` says. */
function renderShell(
  path = "/showcase",
  answers: Answers = {},
  session: StartingSession = SIGNED_IN,
) {
  return renderAsSession(
    <Routes>
      <Route element={<SignedInShell />}>
        <Route element={<p>page body</p>} path="/showcase" />
        <Route element={<p>page body</p>} path="/accounts" />
        <Route element={<p>page body</p>} path="/audit" />
        <Route element={<p>page body</p>} path="/change-password" />
        <Route element={<p>page body</p>} path="*" />
      </Route>
    </Routes>,
    session,
    { answers, at: path },
  );
}

const STILL_SIGNED_IN = /^Unable to sign out\. Please try again\. You are still signed in\.$/;

const TOP_ALIGNED_WIDE = "mx-auto flex min-h-svh flex-col gap-6 p-8 max-w-6xl";

const signOut = () => screen.getByRole("button", { name: "Sign out" });

describe("SignedInShell", () => {
  it("shows the signed-in identity, the page title and the page", async () => {
    await renderShell("/accounts");
    expect(screen.getByText("Signed in as ada")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Accounts" })).toBeInTheDocument();
    expect(screen.getByText("page body")).toBeInTheDocument();
  });

  it("links back to the showcase from the Audit page", async () => {
    await renderShell("/audit");
    expect(screen.getByRole("link", { name: "Back to showcase" })).toHaveAttribute(
      "href",
      "/showcase",
    );
  });

  it("offers no back link on the Showcase", async () => {
    await renderShell("/showcase");
    expect(screen.queryByRole("link", { name: /^Back/ })).not.toBeInTheDocument();
  });

  it("offers no back link to a session confined to the password change", async () => {
    await renderShell("/change-password", {}, holding([], { passwordChangeRequired: true }));
    expect(screen.queryByRole("link", { name: /^Back/ })).not.toBeInTheDocument();
  });

  it("offers a back link on the password change once unconfined", async () => {
    await renderShell("/change-password");
    expect(screen.getByRole("link", { name: "Back" })).toHaveAttribute("href", "/showcase");
  });

  it("signs out through the auth context", async () => {
    const { requests, session } = await renderShell("/showcase", {
      [LOGOUT]: bare(204),
    });
    await userEvent.setup().click(signOut());
    expect(requests()).toEqual([{ method: "DELETE", path: "/api/auth/logout" }]);
    expect(session()).toEqual({ signInReason: null, status: "guest" });
  });

  // Running the Sign out — pending, which outcome signed out, its copy, the line
  // withdrawn on the next try — is `use-session-action.test.tsx`'s; these prove
  // the shell renders what it hands back.
  it("disables Sign out while the logout is in flight", async () => {
    await renderShell("/showcase", { [LOGOUT]: deferred().answer });
    await userEvent.setup().click(signOut());
    expect(screen.getByRole("button", { name: "Signing out…" })).toBeDisabled();
  });

  it("says a failed Sign out left the user signed in", async () => {
    await renderShell("/showcase", { [LOGOUT]: bare(500) });
    await userEvent.setup().click(signOut());
    expect(await screen.findByRole("alert")).toHaveTextContent(STILL_SIGNED_IN);
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });

  it("titles each signed-in page", async () => {
    await renderShell("/showcase");
    expect(screen.getByRole("heading", { level: 1, name: "Front End" })).toBeInTheDocument();
  });

  it("titles the password change", async () => {
    await renderShell("/change-password");
    expect(screen.getByRole("heading", { level: 1, name: "Change password" })).toBeInTheDocument();
  });

  it("links back to the counter from the Accounts page", async () => {
    await renderShell("/accounts");
    expect(screen.getByRole("link", { name: "Back to counter" })).toHaveAttribute(
      "href",
      "/showcase",
    );
  });

  it("renders safely while the session's user details are unavailable", async () => {
    await renderShell("/showcase", {}, STILL_CHECKING);
    expect(screen.getByText(/^Signed in as\s*$/)).toBeInTheDocument();
  });

  it.each([
    ["Accounts", "/accounts"],
    ["Audit", "/audit"],
  ])("lays the %s page out wide and top-aligned", async (_, path) => {
    await renderShell(path);
    expect(screen.getByRole("main").className.trim()).toBe(TOP_ALIGNED_WIDE);
  });

  it("centers the password change in a narrow column", async () => {
    await renderShell("/change-password");
    expect(screen.getByRole("main")).toHaveClass("max-w-md", "items-center", "justify-center");
  });

  it("centers the Showcase in a medium column", async () => {
    await renderShell("/showcase");
    expect(screen.getByRole("main")).toHaveClass("max-w-2xl", "items-center", "justify-center");
  });

  it("renders the Showcase layout for a path it has no page for", async () => {
    await renderShell("/nowhere");
    expect(screen.getByRole("heading", { level: 1, name: "Front End" })).toBeInTheDocument();
  });

  it("renders the Showcase layout for a trailing-slash path", async () => {
    await renderShell("/showcase/");
    expect(screen.getByRole("heading", { level: 1, name: "Front End" })).toBeInTheDocument();
  });
});

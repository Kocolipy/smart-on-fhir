import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { EVERY_PERMISSION } from "@/auth/auth-context.testHelpers";
import {
  bare,
  deferred,
  holding,
  json,
  renderAsSession,
  STILL_CHECKING,
  type Answers,
  type StartingSession,
} from "@/auth/session-adapter.testHelpers";

import { SignedInShell } from "./signed-in-shell";
import { renderSignedIn } from "./signed-in-shell.testHelpers";

const LOGOUT = "DELETE /api/auth/logout";

const SIGNED_IN = holding([]);

/** The shell at `path` as `session` sees it, a Sign out answered as `answers` says. */
function renderShell(path = "/", answers: Answers = {}, session: StartingSession = SIGNED_IN) {
  return renderAsSession(
    <Routes>
      <Route element={<SignedInShell />}>
        <Route element={<p>page body</p>} path="/" />
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

  it("links back to Home from the Audit page", async () => {
    await renderShell("/audit");
    expect(screen.getByRole("link", { name: "Back to home" })).toHaveAttribute("href", "/");
  });

  it("offers no back link on Home", async () => {
    await renderShell("/");
    expect(screen.queryByRole("link", { name: /^Back/ })).not.toBeInTheDocument();
  });

  it("offers no back link to a session confined to the password change", async () => {
    await renderShell("/change-password", {}, holding([], { passwordChangeRequired: true }));
    expect(screen.queryByRole("link", { name: /^Back/ })).not.toBeInTheDocument();
  });

  it("offers a back link on the password change once unconfined", async () => {
    await renderShell("/change-password");
    expect(screen.getByRole("link", { name: "Back" })).toHaveAttribute("href", "/");
  });

  it("signs out through the auth context", async () => {
    const { requests, session } = await renderShell("/", {
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
    await renderShell("/", { [LOGOUT]: deferred().answer });
    await userEvent.setup().click(signOut());
    expect(screen.getByRole("button", { name: "Signing out…" })).toBeDisabled();
  });

  it("says a failed Sign out left the user signed in", async () => {
    await renderShell("/", { [LOGOUT]: bare(500) });
    await userEvent.setup().click(signOut());
    expect(await screen.findByRole("alert")).toHaveTextContent(STILL_SIGNED_IN);
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });

  it("titles each signed-in page", async () => {
    await renderShell("/");
    expect(screen.getByRole("heading", { level: 1, name: "Home" })).toBeInTheDocument();
  });

  it("titles the password change", async () => {
    await renderShell("/change-password");
    expect(screen.getByRole("heading", { level: 1, name: "Change password" })).toBeInTheDocument();
  });

  it("links back to Home from the Accounts page", async () => {
    await renderShell("/accounts");
    expect(screen.getByRole("link", { name: "Back to home" })).toHaveAttribute("href", "/");
  });

  it("renders safely while the session's user details are unavailable", async () => {
    await renderShell("/", {}, STILL_CHECKING);
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

  it("centers Home in a medium column", async () => {
    await renderShell("/");
    expect(screen.getByRole("main")).toHaveClass("max-w-2xl", "items-center", "justify-center");
  });

  it("renders the Home layout for a path it has no page for", async () => {
    await renderShell("/nowhere");
    expect(screen.getByRole("heading", { level: 1, name: "Home" })).toBeInTheDocument();
  });

  it("renders a page's own layout for its trailing-slash path", async () => {
    await renderShell("/accounts/");
    expect(screen.getByRole("heading", { level: 1, name: "Accounts" })).toBeInTheDocument();
  });
});

/**
 * The page's one error line belongs to the layout: the page, its panels and
 * Sign out all report to it, so however many of them fail there is one line,
 * showing the latest failure — and it starts empty on every page.
 */
describe("SignedInShell's error line", () => {
  const USERS = "GET /api/admin/accounts";
  const CONNECTORS = "GET /api/admin/connectors";
  const GROUPS = "GET /api/admin/groups";
  const CHANGE = "POST /api/auth/change-password";
  const MISMATCH = "The new password and its confirmation do not match.";
  const SIGN_OUT_FAILED = "Unable to sign out. Please try again. You are still signed in.";

  const lines = () => screen.queryAllByRole("alert").map((line) => line.textContent);

  /** Fills the password change, its confirmation `confirm`, and submits it. */
  async function submitChange(confirm: string) {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Current password"), "Current-Value-4f81");
    await user.type(screen.getByLabelText("New password"), "Next-Value-9c2e-long");
    await user.type(screen.getByLabelText("Confirm new password"), confirm);
    await user.click(screen.getByRole("button", { name: "Change password" }));
  }

  it("shows one line on Accounts, the latest, when the Users, the Connectors and Sign out all fail", async () => {
    await renderSignedIn("/accounts", holding(EVERY_PERMISSION), {
      [CONNECTORS]: bare(503),
      [GROUPS]: json([]),
      [LOGOUT]: bare(500),
      [USERS]: bare(503),
    });
    await screen.findByRole("alert");

    await userEvent.setup().click(signOut());

    expect(lines()).toEqual([SIGN_OUT_FAILED]);
  });

  it("shows a page's failure on the one line in place of an earlier failed Sign out", async () => {
    const users = deferred();
    await renderSignedIn("/accounts", holding(["user:read"]), {
      [LOGOUT]: bare(500),
      [USERS]: users.answer,
    });
    await userEvent.setup().click(signOut());
    await screen.findByRole("alert");

    await users.settle(bare(503));

    expect(lines()).toEqual(["Unable to load the users. Please try again."]);
  });

  it("starts the next page with no line after a failed Sign out", async () => {
    await renderSignedIn("/", SIGNED_IN, { [LOGOUT]: bare(500) });
    const user = userEvent.setup();
    await user.click(signOut());
    await screen.findByRole("alert");

    await user.click(screen.getByRole("link", { name: "Change password" }));

    expect(lines()).toEqual([]);
  });

  it("starts the next page with no line after the page's own failure", async () => {
    await renderSignedIn("/accounts", holding(["user:read"]), { [USERS]: bare(503) });
    await screen.findByRole("alert");

    await userEvent.setup().click(screen.getByRole("link", { name: "Back to home" }));

    expect(lines()).toEqual([]);
  });

  it("keeps a write the user left behind off the next page's line", async () => {
    const unlock = deferred();
    await renderSignedIn("/accounts", holding(["user:read", "user:write"]), {
      "POST /api/admin/accounts/00000000-0000-4000-8000-000000000001/unlock": unlock.answer,
      [USERS]: json([
        {
          active: true,
          admin: false,
          bootstrapAdmin: false,
          createdAt: "2026-01-02T19:04:05Z",
          displayName: null,
          groups: [],
          hasPassword: true,
          id: "00000000-0000-4000-8000-000000000001",
          lastAuthenticatedAt: null,
          lockCause: "FAILURES",
          locked: true,
          passwordChangeRequired: false,
          userName: "grace",
        },
      ]),
    });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Unlock grace" }));
    await user.click(screen.getByRole("link", { name: "Back to home" }));

    await unlock.settle(bare(500));

    expect(lines()).toEqual([]);
  });

  it("shows the password change's mismatch on the one line, in place of a failed Sign out", async () => {
    await renderSignedIn("/change-password", SIGNED_IN, { [LOGOUT]: bare(500) });
    await userEvent.setup().click(signOut());
    await screen.findByRole("alert");

    await submitChange("Something-Else-123");

    expect(lines()).toEqual([MISMATCH]);
  });

  it("shows a refused password change on the one line, in place of a failed Sign out", async () => {
    await renderSignedIn("/change-password", SIGNED_IN, {
      [CHANGE]: json({ message: "The new password must be at least 12 characters long" }, 400),
      [LOGOUT]: bare(500),
    });
    await userEvent.setup().click(signOut());
    await screen.findByRole("alert");

    await submitChange("Next-Value-9c2e-long");

    expect(lines()).toEqual(["The new password must be at least 12 characters long"]);
  });

  it("shows a failed Sign out on the one line, in place of the password change's mismatch", async () => {
    await renderSignedIn("/change-password", SIGNED_IN, { [LOGOUT]: bare(500) });
    await submitChange("Something-Else-123");

    await userEvent.setup().click(signOut());

    expect(lines()).toEqual([SIGN_OUT_FAILED]);
  });
});

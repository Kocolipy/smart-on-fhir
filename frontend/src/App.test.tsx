import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";

import { App } from "./App";
import { AUTH_REQUESTS, EVERY_PERMISSION, testUser } from "./auth/auth-context.testHelpers";
import {
  bare,
  CURRENT_PASSWORD_REFUSED,
  fakeBackend,
  json,
  type Answers,
} from "./lib/fake-backend.testHelpers";

// `App` owns its router and `AuthProvider`, so it renders whole over the
// backend layer alone: each test states its backend as an answer table keyed by
// request, and the real provider, guards, pages and transport run above it.

const { change: CHANGE, login: LOGIN, logout: LOGOUT, me: ME } = AUTH_REQUESTS;

/** No signed-in session: the session check answers `401`. */
const GUEST: Answers = { [ME]: bare(401) };

/** An unflagged signed-in session holding no Permission. */
const SIGNED_IN: Answers = { [ME]: json(testUser()) };

/** Renders the whole app at `path` over a backend answering `answers`. */
function renderApp(answers: Answers, path = "/") {
  window.history.replaceState(null, "", path);
  const backend = fakeBackend(answers);
  render(<App />);
  return backend;
}

describe("App", () => {
  afterEach(() => {
    window.history.replaceState(null, "", "/");
  });

  it("sends a guest's cold visit to Home on to login, claiming no session ended", async () => {
    renderApp(GUEST);

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/login");
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("sends a guest's visit to a path with no route of its own on to login", async () => {
    renderApp(GUEST, "/no-such-page");

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/login");
  });

  it("shows the refused notice on /login?signin=refused", async () => {
    renderApp(GUEST, "/login?signin=refused");

    expect(await screen.findByRole("status")).toHaveTextContent(/^Sign-in from Epic was refused$/);
  });

  it("sends a User's visit to a path with no route of its own on to Home", async () => {
    renderApp(SIGNED_IN, "/no-such-page");

    expect(await screen.findByRole("heading", { name: "Profile" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });

  it("sends a User's visit to login on to Home", async () => {
    renderApp(SIGNED_IN, "/login");

    expect(await screen.findByRole("heading", { name: "Profile" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });

  it("renders Home when the session is authenticated", async () => {
    renderApp(SIGNED_IN);

    expect(await screen.findByRole("heading", { name: "Profile" })).toBeInTheDocument();
    expect(screen.getByText("Signed in as ada")).toBeInTheDocument();
  });

  // A failed Sign out, which leaves the User signed in, is the shell's
  // (`signed-in-shell.test.tsx`); this proves the route change a success makes.
  it("takes the User to login with no Sign-in reason on Sign out from the shell", async () => {
    renderApp({ ...SIGNED_IN, [LOGOUT]: bare(204) });

    await userEvent.setup().click(await screen.findByRole("button", { name: "Sign out" }));

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("returns to login when a page discovers an expired session", async () => {
    renderApp(
      {
        // audit:read, so the audit page reads its listing and meets the 401.
        [ME]: json(testUser({ permissions: ["audit:read"] })),
        "GET /api/admin/audit-events*": bare(401),
      },
      "/audit",
    );

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/login");
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Your session ended. Please sign in again.",
    );
  });

  it("signs in and sends the guest to Home", async () => {
    const [username, password] = ["ada", "correct-password"];
    const { requests } = renderApp({ ...GUEST, [LOGIN]: json(testUser()) });
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Username"), username);
    await user.type(screen.getByLabelText("Password"), password);
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("heading", { name: "Profile" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
    // One token fetch, and the login carrying it: never refused for want of it.
    expect(requests({ tokenFetches: true })).toEqual([
      { method: "GET", path: "/api/auth/me" },
      { method: "GET", path: "/api/auth/csrf" },
      { body: { password, username }, method: "POST", path: "/api/auth/login" },
    ]);
  });

  it("redirects a User holding no Accounts Permission away from account administration", async () => {
    renderApp(SIGNED_IN, "/accounts");

    expect(await screen.findByRole("heading", { name: "Profile" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });

  it("redirects a User holding no audit:read away from the audit trail", async () => {
    renderApp(SIGNED_IN, "/audit");

    expect(await screen.findByRole("heading", { name: "Profile" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });

  it("renders the audit trail for a session holding audit:read", async () => {
    renderApp(
      {
        [ME]: json(testUser({ permissions: ["audit:read"], username: "grace" })),
        "GET /api/admin/audit-events*": json({
          totalResults: 0,
          startIndex: 1,
          events: [],
        }),
      },
      "/audit",
    );

    expect(await screen.findByRole("heading", { name: "Audit" })).toBeInTheDocument();
    expect(
      await screen.findByText("No audit events match the current filters."),
    ).toBeInTheDocument();
  });

  it("renders account administration for a Superuser", async () => {
    renderApp(
      {
        // Every Permission, as the Superuser Group confers them; no role field.
        [ME]: json(testUser({ permissions: EVERY_PERMISSION, username: "grace" })),
        "GET /api/admin/accounts?startIndex=1&count=20": json({
          startIndex: 1,
          totalResults: 1,
          users: [
            {
              active: true,
              admin: true,
              bootstrapAdmin: false,
              createdAt: "2026-01-02T03:04:05Z",
              displayName: null,
              groups: [],
              hasPassword: true,
              id: "00000000-0000-4000-8000-000000000001",
              lastAuthenticatedAt: null,
              locked: false,
              lockCause: null,
              passwordChangeRequired: false,
              userName: "grace",
            },
          ],
        }),
        "GET /api/admin/connectors": json([]),
        "GET /api/admin/groups?startIndex=1&count=20": json({
          groups: [],
          startIndex: 1,
          totalResults: 0,
        }),
      },
      "/accounts",
    );

    expect(await screen.findByRole("heading", { name: "Accounts" })).toBeInTheDocument();
    expect(await screen.findByRole("rowheader", { name: /^grace/ })).toBeInTheDocument();
    expect(await screen.findByText("No groups are provisioned.")).toBeInTheDocument();
    expect(await screen.findByText("No connectors exist.")).toBeInTheDocument();
  });
});

/** A confined session exactly as `/api/auth/me` and login report one. */
const CONFINED: Answers = { [ME]: json(testUser({ passwordChangeRequired: true })) };

describe("App with the change-required flag", () => {
  afterEach(() => {
    window.history.replaceState(null, "", "/");
  });

  const changePage = () => screen.findByRole("heading", { name: "Change your password" });

  it.each(["/", "/login", "/accounts", "/audit", "/no-such-page", "/settings"])(
    "lands a flagged session on Settings from %s",
    async (path) => {
      const { requests } = renderApp(CONFINED, path);

      expect(await changePage()).toBeInTheDocument();
      expect(window.location.pathname).toBe("/settings");
      // Confined: no data call was attempted on the way, only the session check.
      expect(requests({ tokenFetches: true })).toEqual([{ method: "GET", path: "/api/auth/me" }]);
    },
  );

  it("confines a flagged session straight after login, whatever destination was recorded", async () => {
    renderApp({ ...GUEST, [LOGIN]: CONFINED[ME]! }, "/accounts");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Username"), "ada");
    await user.type(screen.getByLabelText("Password"), "provisioned");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await changePage()).toBeInTheDocument();
    expect(window.location.pathname).toBe("/settings");
  });

  it("renders Settings for an unflagged User, and sends a Visitor to login", async () => {
    window.history.replaceState(null, "", "/settings");
    const backend = fakeBackend(SIGNED_IN);
    const { unmount } = render(<App />);
    expect(await changePage()).toBeInTheDocument();
    expect(window.location.pathname).toBe("/settings");
    unmount();

    backend.answer(GUEST);
    render(<App />);
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/login");
  });

  it("returns to login on a successful change, then signs in to the default destination", async () => {
    const [current, next] = ["provisioned-1", "self-chosen-passphrase"];
    const { requests } = renderApp(
      { ...CONFINED, [CHANGE]: bare(204), [LOGIN]: json(testUser()) },
      "/settings",
    );
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Current password"), current);
    await user.type(screen.getByLabelText("New password"), next);
    await user.type(screen.getByLabelText("Confirm new password"), next);
    await user.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/login");
    expect(screen.getByRole("status")).toHaveTextContent(
      /^Your password was changed\. Sign in with your new password\.$/,
    );
    expect(requests({ tokenFetches: true })).toEqual([
      { method: "GET", path: "/api/auth/me" },
      { method: "GET", path: "/api/auth/csrf" },
      {
        body: { currentPassword: current, newPassword: next },
        method: "POST",
        path: "/api/auth/change-password",
      },
    ]);

    await user.type(screen.getByLabelText("Username"), "ada");
    await user.type(screen.getByLabelText("Password"), next);
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    // Not replayed back to Settings: the change recorded no return destination.
    expect(await screen.findByRole("heading", { name: "Profile" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });

  it("keeps the session on a wrong current password and stays on Settings", async () => {
    // The session survives the 401, so the check that follows it still answers.
    renderApp({ ...CONFINED, [CHANGE]: CURRENT_PASSWORD_REFUSED }, "/settings");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Current password"), "wrong-one");
    await user.type(screen.getByLabelText("New password"), "self-chosen-passphrase");
    await user.type(screen.getByLabelText("Confirm new password"), "self-chosen-passphrase");
    await user.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^The current password is incorrect\.$/,
    );
    expect(window.location.pathname).toBe("/settings");
  });

  it("returns to login saying the session ended when the change finds it already gone", async () => {
    const [current, next] = ["provisioned-1", "self-chosen-passphrase"];
    // Refused before the password check, so the 401 carries none of its mark.
    const { requests } = renderApp({ ...CONFINED, [CHANGE]: bare(401) }, "/settings");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Current password"), current);
    await user.type(screen.getByLabelText("New password"), next);
    await user.type(screen.getByLabelText("Confirm new password"), next);
    await user.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/login");
    expect(screen.getByRole("status")).toHaveTextContent(
      /^Your session ended\. Please sign in again\.$/,
    );
    // Not probed: a session check after it would read the gone session as a lockout.
    expect(requests()).toEqual([
      { method: "GET", path: "/api/auth/me" },
      {
        body: { currentPassword: current, newPassword: next },
        method: "POST",
        path: "/api/auth/change-password",
      },
    ]);
  });
});

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { App } from "./App";
import { stubFetchWithCsrf } from "./lib/http.testHelpers";

describe("App", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState(null, "", "/");
  });

  it("renders login at the home route for a guest", async () => {
    stubFetchWithCsrf(vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
  });

  it("redirects a guest away from the showcase", async () => {
    window.history.replaceState(null, "", "/showcase");
    stubFetchWithCsrf(vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });

  it("renders the showcase when the session is authenticated", async () => {
    window.history.replaceState(null, "", "/showcase");
    stubFetchWithCsrf(
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            idleTimeoutSeconds: 900,
            passwordChangeRequired: false,
            permissions: [],
            username: "ada",
          }),
          {
            headers: { "Content-Type": "application/json" },
            status: 200,
          },
        ),
      ),
    );
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Front End" })).toBeInTheDocument();
    expect(screen.getByText("Signed in as ada")).toBeInTheDocument();
  });

  describe("Sign out from the shell", () => {
    const me = () =>
      Response.json({
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        permissions: [],
        username: "ada",
      });

    it("takes the User to login with no Sign-in reason", async () => {
      window.history.replaceState(null, "", "/showcase");
      stubFetchWithCsrf(
        vi
          .fn()
          .mockResolvedValueOnce(me())
          .mockResolvedValueOnce(new Response(null, { status: 204 })),
      );
      render(<App />);

      await userEvent.setup().click(await screen.findByRole("button", { name: "Sign out" }));

      expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
    });

    it("keeps the User signed in, saying so, when the logout fails", async () => {
      window.history.replaceState(null, "", "/showcase");
      stubFetchWithCsrf(
        vi
          .fn()
          .mockResolvedValueOnce(me())
          .mockResolvedValueOnce(new Response(null, { status: 500 })),
      );
      render(<App />);

      await userEvent.setup().click(await screen.findByRole("button", { name: "Sign out" }));

      expect(await screen.findByRole("alert")).toHaveTextContent(
        /^Unable to sign out\. Please try again\. You are still signed in\.$/,
      );
      expect(screen.getByText("Signed in as ada")).toBeInTheDocument();
    });
  });

  it("returns to login when the showcase discovers an expired session", async () => {
    window.history.replaceState(null, "", "/showcase");
    stubFetchWithCsrf(
      vi
        .fn()
        .mockResolvedValueOnce(
          Response.json({
            idleTimeoutSeconds: 900,
            passwordChangeRequired: false,
            // counter:read, so the showcase reads the counter and meets the 401.
            permissions: ["counter:read"],
            username: "ada",
          }),
        )
        .mockResolvedValueOnce(new Response(null, { status: 401 })),
    );
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Your session ended. Please sign in again.",
    );
  });

  it("does not claim a session ended for a visitor who never had one", async () => {
    stubFetchWithCsrf(vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("signs in and sends the guest to the showcase", async () => {
    const [username, password] = ["ada", "correct-password"];
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 401 }))
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            idleTimeoutSeconds: 900,
            passwordChangeRequired: false,
            permissions: [],
            username: "ada",
          }),
          {
            headers: { "Content-Type": "application/json" },
            status: 200,
          },
        ),
      );
    stubFetchWithCsrf(fetchMock);
    const user = userEvent.setup();
    render(<App />);

    await user.type(await screen.findByLabelText("Username"), username);
    await user.type(screen.getByLabelText("Password"), password);
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("heading", { name: "Front End" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/showcase");
    expect(fetchMock).toHaveBeenCalledWith("/api/auth/login", {
      body: JSON.stringify({ username, password }),
      credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": "test-token" },
      method: "POST",
    });
  });

  it("shows invalid credential errors", async () => {
    stubFetchWithCsrf(
      vi
        .fn()
        .mockResolvedValueOnce(new Response(null, { status: 401 }))
        .mockResolvedValueOnce(new Response(null, { status: 401 })),
    );
    const user = userEvent.setup();
    render(<App />);

    await user.type(await screen.findByLabelText("Username"), "ada");
    await user.type(screen.getByLabelText("Password"), "wrong");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The username or password is incorrect.",
    );
  });

  it("redirects a User holding no Accounts Permission away from account administration", async () => {
    window.history.replaceState(null, "", "/accounts");
    stubFetchWithCsrf(
      vi.fn().mockResolvedValue(
        Response.json({
          idleTimeoutSeconds: 900,
          passwordChangeRequired: false,
          permissions: [],
          username: "ada",
        }),
      ),
    );
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Front End" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/showcase");
  });

  it("redirects a User holding no audit:read away from the audit trail", async () => {
    window.history.replaceState(null, "", "/audit");
    stubFetchWithCsrf(
      vi.fn().mockResolvedValue(
        Response.json({
          idleTimeoutSeconds: 900,
          passwordChangeRequired: false,
          permissions: [],
          username: "ada",
        }),
      ),
    );
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Front End" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/showcase");
  });

  it("renders the audit trail for a session holding audit:read", async () => {
    window.history.replaceState(null, "", "/audit");
    stubFetchWithCsrf(
      vi.fn((input: string) =>
        Promise.resolve(
          input.startsWith("/api/admin/audit-events")
            ? Response.json({ events: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
            : Response.json({
                idleTimeoutSeconds: 900,
                passwordChangeRequired: false,
                permissions: ["audit:read"],
                username: "grace",
              }),
        ),
      ),
    );
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Audit" })).toBeInTheDocument();
    expect(
      await screen.findByText("No audit events match the current filters."),
    ).toBeInTheDocument();
  });

  it("renders account administration for a Superuser", async () => {
    window.history.replaceState(null, "", "/accounts");
    stubFetchWithCsrf(
      vi.fn((input: string) =>
        Promise.resolve(
          input === "/api/admin/accounts"
            ? Response.json([
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
              ])
            : input === "/api/admin/groups" || input === "/api/admin/connectors"
              ? Response.json([])
              : // Every Permission, as the Superuser Group confers them; no role field.
                Response.json({
                  idleTimeoutSeconds: 900,
                  passwordChangeRequired: false,
                  permissions: [
                    "audit:read",
                    "connector:read",
                    "connector:token",
                    "connector:write",
                    "counter:read",
                    "counter:write",
                    "group:read",
                    "group:write",
                    "ops:read",
                    "user:read",
                    "user:write",
                  ],
                  username: "grace",
                }),
        ),
      ),
    );
    render(<App />);

    expect(await screen.findByRole("heading", { name: "Accounts" })).toBeInTheDocument();
    expect(await screen.findByRole("rowheader", { name: /^grace/ })).toBeInTheDocument();
    expect(await screen.findByText("No groups are provisioned.")).toBeInTheDocument();
    expect(await screen.findByText("No connectors exist.")).toBeInTheDocument();
  });
});

/** A confined session exactly as `/api/auth/me` and login report one. */
const CONFINED = {
  idleTimeoutSeconds: 900,
  passwordChangeRequired: true,
  permissions: [],
  username: "ada",
};

describe("App with the change-required flag", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    window.history.replaceState(null, "", "/");
  });

  const changePage = () => screen.findByRole("heading", { name: "Change your password" });

  it.each(["/showcase", "/accounts", "/audit", "/", "/no-such-page", "/change-password"])(
    "lands a flagged session on /change-password from %s",
    async (path) => {
      window.history.replaceState(null, "", path);
      const fetchMock = vi.fn().mockResolvedValue(Response.json(CONFINED));
      stubFetchWithCsrf(fetchMock);
      render(<App />);

      expect(await changePage()).toBeInTheDocument();
      expect(window.location.pathname).toBe("/change-password");
      // Confined: no data call was attempted on the way, only the session check.
      expect(fetchMock.mock.calls.map(([url]) => url as string)).toEqual(["/api/auth/me"]);
    },
  );

  it("confines a flagged session straight after login, whatever destination was recorded", async () => {
    window.history.replaceState(null, "", "/accounts");
    stubFetchWithCsrf(
      vi
        .fn()
        .mockResolvedValueOnce(new Response(null, { status: 401 }))
        .mockResolvedValueOnce(Response.json(CONFINED)),
    );
    const user = userEvent.setup();
    render(<App />);

    await user.type(await screen.findByLabelText("Username"), "ada");
    await user.type(screen.getByLabelText("Password"), "provisioned");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await changePage()).toBeInTheDocument();
    expect(window.location.pathname).toBe("/change-password");
  });

  it("renders the change for an unflagged User, and sends a Visitor to login", async () => {
    window.history.replaceState(null, "", "/change-password");
    stubFetchWithCsrf(
      vi.fn().mockResolvedValue(
        Response.json({
          idleTimeoutSeconds: 900,
          passwordChangeRequired: false,
          permissions: [],
          username: "ada",
        }),
      ),
    );
    const { unmount } = render(<App />);
    expect(await changePage()).toBeInTheDocument();
    expect(window.location.pathname).toBe("/change-password");
    unmount();

    stubFetchWithCsrf(vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    render(<App />);
    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
  });

  it("returns to login on a successful change, then signs in to the default destination", async () => {
    const [current, next] = ["provisioned-1", "self-chosen-passphrase"];
    window.history.replaceState(null, "", "/change-password");
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(Response.json(CONFINED))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(
        Response.json({
          idleTimeoutSeconds: 900,
          passwordChangeRequired: false,
          permissions: [],
          username: "ada",
        }),
      )
      .mockResolvedValue(Response.json({ count: 0 }));
    stubFetchWithCsrf(fetchMock);
    const user = userEvent.setup();
    render(<App />);

    await user.type(await screen.findByLabelText("Current password"), current);
    await user.type(screen.getByLabelText("New password"), next);
    await user.type(screen.getByLabelText("Confirm new password"), next);
    await user.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/");
    expect(screen.getByRole("status")).toHaveTextContent(
      /^Your password was changed\. Sign in with your new password\.$/,
    );
    expect(fetchMock).toHaveBeenNthCalledWith(2, "/api/auth/change-password", {
      body: JSON.stringify({ currentPassword: current, newPassword: next }),
      credentials: "include",
      headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": "test-token" },
      method: "POST",
    });

    await user.type(screen.getByLabelText("Username"), "ada");
    await user.type(screen.getByLabelText("Password"), next);
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    // Not replayed back to /change-password: the change recorded no return destination.
    expect(await screen.findByRole("heading", { name: "Front End" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/showcase");
  });

  it("keeps the session on a wrong current password and stays on the change", async () => {
    window.history.replaceState(null, "", "/change-password");
    stubFetchWithCsrf(
      vi
        .fn()
        .mockResolvedValueOnce(Response.json(CONFINED))
        .mockResolvedValueOnce(new Response(null, { status: 401 }))
        .mockResolvedValueOnce(Response.json(CONFINED)),
    );
    const user = userEvent.setup();
    render(<App />);

    await user.type(await screen.findByLabelText("Current password"), "wrong-one");
    await user.type(screen.getByLabelText("New password"), "self-chosen-passphrase");
    await user.type(screen.getByLabelText("Confirm new password"), "self-chosen-passphrase");
    await user.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^The current password is incorrect\.$/,
    );
    expect(window.location.pathname).toBe("/change-password");
  });

  it("shows a policy refusal by the rule the backend names", async () => {
    window.history.replaceState(null, "", "/change-password");
    stubFetchWithCsrf(
      vi
        .fn()
        .mockResolvedValueOnce(Response.json(CONFINED))
        .mockResolvedValueOnce(
          Response.json(
            {
              message: "The new password must not contain the user name",
              rule: "CONTAINS_USER_NAME",
            },
            { status: 400 },
          ),
        ),
    );
    const user = userEvent.setup();
    render(<App />);

    await user.type(await screen.findByLabelText("Current password"), "provisioned-1");
    await user.type(screen.getByLabelText("New password"), "ada-is-my-name-ok");
    await user.type(screen.getByLabelText("Confirm new password"), "ada-is-my-name-ok");
    await user.click(screen.getByRole("button", { name: "Change password" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /^The new password must not contain the user name$/,
    );
  });
});

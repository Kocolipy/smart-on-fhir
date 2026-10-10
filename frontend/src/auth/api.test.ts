import { beforeEach, describe, expect, it, vi } from "vitest";

import { apiFetch, discardCsrfToken } from "@/lib/http";

import { changePassword, getCurrentUser, login, logout } from "./api";

vi.mock("@/lib/http");

const apiFetchMock = vi.mocked(apiFetch);
const TEST_LOGIN = ["ada", "secret"] as const;
/** A truthy non-boolean, as a malformed response might carry the flag. */
const NOT_A_BOOLEAN: unknown = 1;

function resolveWith(result: object) {
  apiFetchMock.mockResolvedValue(result as never);
}

describe("auth API", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
  });

  it("treats an unauthenticated session check as a guest", async () => {
    resolveWith({ kind: "unauthenticated" });

    await expect(getCurrentUser()).resolves.toBeNull();
    expect(apiFetchMock).toHaveBeenCalledWith("/api/auth/me", {}, expect.any(Function));
  });

  it("returns the authenticated user", async () => {
    resolveWith({ kind: "ok", data: { permissions: [], username: "ada" } });

    await expect(getCurrentUser()).resolves.toEqual({ permissions: [], username: "ada" });
  });

  it.each([{ kind: "csrf-expired" }, { kind: "failed", status: 503 }])(
    "rejects a failed session check for $kind",
    async (result) => {
      resolveWith(result);
      await expect(getCurrentUser()).rejects.toThrow("Unable to check the current session.");
    },
  );

  it("requests typed user data when signing in", async () => {
    const [username, password] = TEST_LOGIN;
    resolveWith({ kind: "ok", data: { permissions: [], username } });

    await expect(login(username, password)).resolves.toEqual({ permissions: [], username });
    expect(apiFetchMock).toHaveBeenCalledWith(
      "/api/auth/login",
      {
        body: JSON.stringify({ username, password }),
        headers: { "Content-Type": "application/json" },
        method: "POST",
      },
      expect.any(Function),
    );
  });

  it("reports invalid credentials without exposing backend details", async () => {
    resolveWith({ kind: "unauthenticated" });
    await expect(login("ada", "wrong")).rejects.toThrow("The username or password is incorrect.");
  });

  it("reports a persistent CSRF rejection as a token problem", async () => {
    resolveWith({ kind: "csrf-expired" });
    await expect(login("ada", "secret")).rejects.toThrow(
      "Your security token expired. Please try again.",
    );
  });

  it("reports other login failures", async () => {
    resolveWith({ kind: "failed", status: 500 });
    await expect(login("ada", "secret")).rejects.toThrow("Unable to sign in. Please try again.");
  });

  it("logs out without decoding a response body", async () => {
    resolveWith({ kind: "ok", data: undefined });

    await expect(logout()).resolves.toBeUndefined();
    expect(apiFetchMock).toHaveBeenCalledWith("/api/auth/logout", { method: "DELETE" });
  });

  it("treats an already-expired session as logged out", async () => {
    resolveWith({ kind: "unauthenticated" });
    await expect(logout()).resolves.toBeUndefined();
  });

  it("reports a persistent CSRF rejection on logout", async () => {
    resolveWith({ kind: "csrf-expired" });
    await expect(logout()).rejects.toThrow("Your security token expired. Please try again.");
  });

  it("reports other logout failures", async () => {
    resolveWith({ kind: "failed", status: 500 });
    await expect(logout()).rejects.toThrow("Unable to sign out. Please try again.");
  });

  const PERMISSION_DENIED = /^You don't have permission to do this\.$/;

  it("reports an authorization refusal on the session check and login as permission denied", async () => {
    resolveWith({ kind: "forbidden" });

    await expect(getCurrentUser()).rejects.toThrow(PERMISSION_DENIED);
    await expect(login("ada", "secret")).rejects.toThrow(PERMISSION_DENIED);
  });

  // A 403 that survived the transport's re-fetch-and-retry: the session the
  // logout names is already gone (or the retry's fresh anonymous one is refused),
  // so the User is signed out — which is what they asked for.
  it("treats a logout refused 403 after the retry as logged out", async () => {
    resolveWith({ kind: "forbidden" });
    await expect(logout()).resolves.toBeUndefined();
  });
});

describe("the CSRF token across session changes", () => {
  const discardMock = vi.mocked(discardCsrfToken);

  beforeEach(() => {
    apiFetchMock.mockReset();
    discardMock.mockReset();
  });

  it("forgets the token once a login has rotated the session", async () => {
    resolveWith({ kind: "ok", data: { permissions: [], username: "ada" } });
    await login("ada", "secret");
    expect(discardMock).toHaveBeenCalledOnce();
  });

  it("forgets the token once a refused login has ended the session", async () => {
    resolveWith({ kind: "unauthenticated" });
    await expect(login("ada", "wrong")).rejects.toThrow("The username or password is incorrect.");
    expect(discardMock).toHaveBeenCalledOnce();
  });

  it.each([{ kind: "ok", data: undefined }, { kind: "unauthenticated" }, { kind: "forbidden" }])(
    "forgets the token once a logout ends the session ($kind)",
    async (result) => {
      resolveWith(result);
      await logout();
      expect(discardMock).toHaveBeenCalledOnce();
    },
  );

  it("forgets the token once a password change has ended every session", async () => {
    resolveWith({ kind: "ok", data: undefined });
    await expect(changePassword("old", "new")).resolves.toEqual({ kind: "changed" });
    expect(discardMock).toHaveBeenCalledOnce();
  });

  it("forgets the token when a rejected change turns out to have locked the account", async () => {
    apiFetchMock
      .mockResolvedValueOnce({ kind: "unauthenticated" } as never)
      .mockResolvedValueOnce({ kind: "unauthenticated" } as never);
    await expect(changePassword("wrong", "new")).resolves.toEqual({ kind: "locked" });
    expect(discardMock).toHaveBeenCalledOnce();
  });

  it("keeps the token while the session stands", async () => {
    apiFetchMock
      .mockResolvedValueOnce({ kind: "unauthenticated" } as never)
      .mockResolvedValueOnce({ kind: "ok", data: undefined } as never);
    await expect(changePassword("wrong", "new")).resolves.toEqual({
      kind: "current-password-rejected",
    });

    resolveWith({ kind: "failed", status: 500 });
    await expect(login("ada", "secret")).rejects.toThrow();
    await expect(logout()).rejects.toThrow();
    resolveWith({ kind: "csrf-expired" });
    await expect(logout()).rejects.toThrow();
    await expect(changePassword("old", "new")).resolves.toEqual({ kind: "csrf-expired" });
    await getCurrentUser().catch(() => undefined);

    expect(discardMock).not.toHaveBeenCalled();
  });
});

/** The decoder `apiFetch` was handed on its `n`th call, applied to a real response body. */
async function decodeWithCall(n: number, argument: 2 | 3, body: unknown) {
  const decoder = apiFetchMock.mock.calls[n]?.[argument] as
    ((response: Response) => Promise<unknown>) | undefined;
  expect(decoder).toBeTypeOf("function");
  return decoder!(Response.json(body));
}

describe("the user decoder", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    resolveWith({ kind: "ok", data: null });
  });

  const confined = {
    idleTimeoutSeconds: 900,
    passwordChangeRequired: true,
    permissions: [],
    username: "ada",
  };

  it("reads the change-required flag from the session check", async () => {
    await getCurrentUser();
    await expect(decodeWithCall(0, 2, confined)).resolves.toStrictEqual(confined);
  });

  it("reads the change-required flag from the login response", async () => {
    const [username, password] = TEST_LOGIN;
    await login(username, password);
    await expect(decodeWithCall(0, 2, confined)).resolves.toStrictEqual(confined);
  });

  it("reads an unflagged session's Permissions, in the order reported", async () => {
    await getCurrentUser();
    await expect(
      decodeWithCall(0, 2, {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        permissions: ["audit:read", "user:read", "user:write"],
        username: "grace",
      }),
    ).resolves.toStrictEqual({
      idleTimeoutSeconds: 900,
      passwordChangeRequired: false,
      permissions: ["audit:read", "user:read", "user:write"],
      username: "grace",
    });
  });

  it("reads every Permission the backend can grant", async () => {
    const every = [
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
    ];
    await getCurrentUser();
    await expect(
      decodeWithCall(0, 2, {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        permissions: every,
        username: "root",
      }),
    ).resolves.toMatchObject({ permissions: every });
  });

  it("ignores a role field a body might still carry", async () => {
    await getCurrentUser();
    await expect(
      decodeWithCall(0, 2, {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        permissions: [],
        role: "ADMIN",
        username: "ada",
      }),
    ).resolves.toStrictEqual({
      idleTimeoutSeconds: 900,
      passwordChangeRequired: false,
      permissions: [],
      username: "ada",
    });
  });

  it("reads a body that is not JSON at all, such as a proxy's error page, as a decode failure", async () => {
    await getCurrentUser();
    const decoder = apiFetchMock.mock.calls[0]?.[2] as (response: Response) => Promise<unknown>;
    await expect(decoder(new Response("<html>Bad Gateway</html>"))).rejects.toThrow(SyntaxError);
  });

  // Each refusal names the field it failed on, so a test cannot pass on a
  // different field's failure than the one it set up.
  it.each([
    [
      "a missing username",
      { idleTimeoutSeconds: 900, passwordChangeRequired: false, permissions: [] },
      "UserResponse.username",
    ],
    [
      "a missing flag",
      { idleTimeoutSeconds: 900, permissions: [], username: "ada" },
      "UserResponse.passwordChangeRequired",
    ],
    [
      "missing Permissions",
      { idleTimeoutSeconds: 900, passwordChangeRequired: false, username: "ada" },
      "UserResponse.permissions is not an array",
    ],
    [
      "Permissions that are not a list",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        permissions: "user:read",
        username: "ada",
      },
      "UserResponse.permissions is not an array",
    ],
    [
      "a non-boolean flag",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: NOT_A_BOOLEAN,
        permissions: [],
        username: "ada",
      },
      "UserResponse.passwordChangeRequired",
    ],
    // The idle sign-out is timed by this field, so a body without a whole
    // number of seconds is refused rather than guessed at.
    [
      "a missing idle timeout",
      { passwordChangeRequired: false, permissions: [], username: "ada" },
      "UserResponse.idleTimeoutSeconds is not an integer",
    ],
    [
      "a fractional idle timeout",
      { idleTimeoutSeconds: 1.5, passwordChangeRequired: false, permissions: [], username: "ada" },
      "UserResponse.idleTimeoutSeconds is not an integer",
    ],
    [
      "an idle timeout sent as a string",
      {
        idleTimeoutSeconds: "900",
        passwordChangeRequired: false,
        permissions: [],
        username: "ada",
      },
      "UserResponse.idleTimeoutSeconds is not an integer",
    ],
    [
      "a non-string username",
      { idleTimeoutSeconds: 900, passwordChangeRequired: false, permissions: [], username: 7 },
      "UserResponse.username",
    ],
    [
      "an unknown Permission, which the route guards would otherwise read",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        permissions: ["user:read", "superuser"],
        username: "ada",
      },
      "UserResponse.permissions holds an unknown value",
    ],
    [
      "a Permission in the wrong case",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        permissions: ["User:Read"],
        username: "ada",
      },
      "UserResponse.permissions holds an unknown value",
    ],
    [
      "a Permission that is not a string",
      { idleTimeoutSeconds: 900, passwordChangeRequired: false, permissions: [7], username: "ada" },
      "UserResponse.permissions holds an unknown value",
    ],
    ["a non-object body", ["ada"], "UserResponse is not an object"],
    ["a null body", null, "UserResponse is not an object"],
  ])("refuses %s", async (_, body, message) => {
    await getCurrentUser();
    await expect(decodeWithCall(0, 2, body)).rejects.toThrow(
      expect.objectContaining({ name: "DecodeError", message: expect.stringContaining(message) }),
    );
  });
});

describe("changePassword", () => {
  const [current, next] = ["current-value-1", "next-value-22"] as const;

  beforeEach(() => {
    apiFetchMock.mockReset();
  });

  it("posts current and new password, decoding only a failure body", async () => {
    resolveWith({ kind: "ok", data: undefined });

    await expect(changePassword(current, next)).resolves.toEqual({ kind: "changed" });
    expect(apiFetchMock).toHaveBeenCalledTimes(1);
    expect(apiFetchMock).toHaveBeenCalledWith(
      "/api/auth/change-password",
      {
        body: JSON.stringify({ currentPassword: current, newPassword: next }),
        headers: { "Content-Type": "application/json" },
        method: "POST",
      },
      undefined,
      expect.any(Function),
    );
  });

  it("names the unmet rule from a policy refusal", async () => {
    resolveWith({ kind: "failed", status: 400, detail: "The new password is too short" });
    await expect(changePassword(current, next)).resolves.toEqual({
      kind: "policy-violation",
      message: "The new password is too short",
    });
  });

  it.each([
    ["a 400 with no rule", { kind: "failed", status: 400 }],
    ["another status carrying a message", { kind: "failed", status: 500, detail: "boom" }],
    ["a transport failure", { kind: "failed" }],
  ])("reports %s as a plain failure", async (_name, result) => {
    resolveWith(result);
    await expect(changePassword(current, next)).resolves.toEqual({ kind: "failed" });
  });

  it("reports a persistent CSRF rejection as a token problem", async () => {
    resolveWith({ kind: "csrf-expired" });
    await expect(changePassword(current, next)).resolves.toEqual({ kind: "csrf-expired" });
  });

  it("reports an authorization refusal as forbidden, without probing the session", async () => {
    resolveWith({ kind: "forbidden" });
    await expect(changePassword(current, next)).resolves.toEqual({ kind: "forbidden" });
    expect(apiFetchMock).toHaveBeenCalledTimes(1);
  });

  it("reads a 401 that leaves the session standing as a wrong current password", async () => {
    apiFetchMock
      .mockResolvedValueOnce({ kind: "unauthenticated" } as never)
      .mockResolvedValueOnce({ kind: "ok", data: undefined } as never);

    await expect(changePassword(current, next)).resolves.toEqual({
      kind: "current-password-rejected",
    });
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/auth/me");
  });

  it("reads a 401 that ended the session as a lockout", async () => {
    apiFetchMock
      .mockResolvedValueOnce({ kind: "unauthenticated" } as never)
      .mockResolvedValueOnce({ kind: "unauthenticated" } as never);

    await expect(changePassword(current, next)).resolves.toEqual({ kind: "locked" });
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/auth/me");
  });

  it("does not claim a lockout when the session check itself fails", async () => {
    apiFetchMock
      .mockResolvedValueOnce({ kind: "unauthenticated" } as never)
      .mockResolvedValueOnce({ kind: "failed" } as never);

    await expect(changePassword(current, next)).resolves.toEqual({
      kind: "current-password-rejected",
    });
  });

  it("reads the rule's statement out of a PasswordRuleViolation body, and nothing else", async () => {
    resolveWith({ kind: "ok", data: undefined });
    await changePassword(current, next);

    await expect(
      decodeWithCall(0, 3, {
        message: "The new password must not contain the user name",
        rule: "CONTAINS_USER_NAME",
      }),
    ).resolves.toBe("The new password must not contain the user name");
    // Anything else throws, which `apiFetch` turns into a `failed` with no `detail`.
    await expect(decodeWithCall(0, 3, { status: 400 })).rejects.toThrow(
      "PasswordRuleViolation.message is not a string",
    );
    await expect(decodeWithCall(0, 3, { message: 7 })).rejects.toThrow(
      "PasswordRuleViolation.message is not a string",
    );
    await expect(decodeWithCall(0, 3, "too short")).rejects.toThrow(
      "PasswordRuleViolation is not an object",
    );
  });
});

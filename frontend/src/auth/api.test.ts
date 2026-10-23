import { beforeEach, describe, expect, it, vi } from "vitest";

import { apiFetch } from "@/lib/http";

import { changePassword, getCurrentUser, login, logout, PERMISSIONS } from "./api";
import { TEST_USER_ID } from "./auth-context.testHelpers";

vi.mock("@/lib/http");

const apiFetchMock = vi.mocked(apiFetch);
const TEST_LOGIN = ["ada", "secret"] as const;
/** A truthy non-boolean, as a malformed response might carry the flag. */
const NOT_A_BOOLEAN: unknown = 1;
/**
 * The backend's Permission enum, value for value, less the token-only
 * `user:write` and `group:write`, which no session can hold.
 */
const BACKEND_PERMISSIONS = [
  "account:unlock",
  "audit:read",
  "connector:read",
  "group:read",
  "ops:read",
  "patient:read",
  "user:read",
];

function resolveWith(result: object) {
  apiFetchMock.mockResolvedValue(result as never);
}

describe("auth API", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
  });

  it("treats an unauthenticated session check as a guest", async () => {
    resolveWith({ kind: "unauthenticated" });

    await expect(getCurrentUser()).resolves.toEqual({ kind: "guest" });
    expect(apiFetchMock).toHaveBeenCalledWith("/api/auth/me", {}, expect.any(Function));
  });

  it("returns the authenticated user", async () => {
    resolveWith({ kind: "ok", data: { permissions: [], username: "ada" } });

    await expect(getCurrentUser()).resolves.toEqual({
      kind: "signed-in",
      user: { permissions: [], username: "ada" },
    });
  });

  it.each([
    ["an authorization refusal", { kind: "forbidden" }, { kind: "forbidden" }],
    ["a CSRF token that could not be fetched", { kind: "csrf-expired" }, { kind: "csrf-expired" }],
    ["a server failure", { kind: "failed", status: 503 }, { kind: "failed" }],
    ["a transport failure", { kind: "failed" }, { kind: "failed" }],
  ])(
    "resolves %s on the session check as its own outcome, claiming no verdict",
    async (_name, result, outcome) => {
      resolveWith(result);
      await expect(getCurrentUser()).resolves.toEqual(outcome);
    },
  );

  it("requests typed user data when signing in", async () => {
    const [username, password] = TEST_LOGIN;
    resolveWith({ kind: "ok", data: { permissions: [], username } });

    await expect(login(username, password)).resolves.toEqual({
      kind: "signed-in",
      user: { permissions: [], username },
    });
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

  it("reads refused credentials as a refusal, a verdict rather than an error", async () => {
    resolveWith({ kind: "unauthenticated" });
    await expect(login("ada", "wrong")).resolves.toEqual({ kind: "refused" });
  });

  it.each([
    ["a persistent CSRF rejection", { kind: "csrf-expired" }, { kind: "csrf-expired" }],
    ["an authorization refusal", { kind: "forbidden" }, { kind: "forbidden" }],
    ["a server failure", { kind: "failed", status: 500 }, { kind: "failed" }],
    ["a transport failure", { kind: "failed" }, { kind: "failed" }],
  ])("resolves %s on login as its own outcome", async (_name, result, outcome) => {
    resolveWith(result);
    await expect(login("ada", "secret")).resolves.toEqual(outcome);
  });

  it("logs out without decoding a response body", async () => {
    resolveWith({ kind: "ok", data: undefined });

    await expect(logout()).resolves.toEqual({ kind: "signed-out" });
    expect(apiFetchMock).toHaveBeenCalledWith("/api/auth/logout", { method: "DELETE" });
  });

  it("treats an already-expired session as logged out", async () => {
    resolveWith({ kind: "unauthenticated" });
    await expect(logout()).resolves.toEqual({ kind: "signed-out" });
  });

  it.each([
    ["a persistent CSRF rejection", { kind: "csrf-expired" }, { kind: "csrf-expired" }],
    ["a server failure", { kind: "failed", status: 500 }, { kind: "failed" }],
    ["a transport failure", { kind: "failed" }, { kind: "failed" }],
  ])("resolves %s on logout as its own outcome", async (_name, result, outcome) => {
    resolveWith(result);
    await expect(logout()).resolves.toEqual(outcome);
  });

  // A 403 that survived the transport's re-fetch-and-retry: the session the
  // logout names is already gone (or the retry's fresh anonymous one is refused),
  // so the User is signed out — which is what they asked for.
  it("treats a logout refused 403 after the retry as logged out", async () => {
    resolveWith({ kind: "forbidden" });
    await expect(logout()).resolves.toEqual({ kind: "signed-out" });
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
    id: TEST_USER_ID,
    idleTimeoutSeconds: 900,
    passwordChangeRequired: true,
    superuser: false,
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

  it.each([true, false])(
    "reads a session's Superuser status, %s, from the session check",
    async (flag) => {
      await getCurrentUser();
      await expect(decodeWithCall(0, 2, { ...confined, superuser: flag })).resolves.toMatchObject({
        superuser: flag,
      });
    },
  );

  it("reads a session's Superuser status from the login response", async () => {
    await login(...TEST_LOGIN);
    await expect(decodeWithCall(0, 2, { ...confined, superuser: true })).resolves.toMatchObject({
      superuser: true,
    });
  });

  it("reads an unflagged session's Permissions, in the order reported", async () => {
    await getCurrentUser();
    await expect(
      decodeWithCall(0, 2, {
        id: TEST_USER_ID,
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        superuser: false,
        permissions: ["audit:read", "user:read", "account:unlock"],
        username: "grace",
      }),
    ).resolves.toStrictEqual({
      id: TEST_USER_ID,
      idleTimeoutSeconds: 900,
      passwordChangeRequired: false,
      superuser: false,
      permissions: ["audit:read", "user:read", "account:unlock"],
      username: "grace",
    });
  });

  // The backend's Permission enum, value for value, less what only a connector
  // token carries: a name no session holds is refused like any unknown one,
  // never passed through.
  it("knows exactly the Permissions the backend can grant a session", () => {
    expect(PERMISSIONS).toEqual(BACKEND_PERMISSIONS);
  });

  it("reads every Permission the backend can grant", async () => {
    await getCurrentUser();
    await expect(
      decodeWithCall(0, 2, {
        id: TEST_USER_ID,
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        superuser: false,
        permissions: BACKEND_PERMISSIONS,
        username: "root",
      }),
    ).resolves.toMatchObject({ permissions: BACKEND_PERMISSIONS });
  });

  it("ignores a role field a body might still carry", async () => {
    await getCurrentUser();
    await expect(
      decodeWithCall(0, 2, {
        id: TEST_USER_ID,
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        superuser: false,
        permissions: [],
        role: "ADMIN",
        username: "ada",
      }),
    ).resolves.toStrictEqual({
      id: TEST_USER_ID,
      idleTimeoutSeconds: 900,
      passwordChangeRequired: false,
      superuser: false,
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
      { idleTimeoutSeconds: 900, passwordChangeRequired: false, superuser: false, permissions: [] },
      "UserResponse.username",
    ],
    [
      "a missing flag",
      { idleTimeoutSeconds: 900, permissions: [], username: "ada" },
      "UserResponse.passwordChangeRequired",
    ],
    [
      "missing Permissions",
      { idleTimeoutSeconds: 900, passwordChangeRequired: false, superuser: false, username: "ada" },
      "UserResponse.permissions is not an array",
    ],
    [
      "Permissions that are not a list",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        superuser: false,
        permissions: "user:read",
        username: "ada",
      },
      "UserResponse.permissions is not an array",
    ],
    [
      "a missing Superuser status",
      { idleTimeoutSeconds: 900, passwordChangeRequired: false, permissions: [], username: "ada" },
      "UserResponse.superuser",
    ],
    [
      "a non-boolean Superuser status",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        permissions: [],
        superuser: NOT_A_BOOLEAN,
        username: "ada",
      },
      "UserResponse.superuser",
    ],
    [
      "a non-boolean flag",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: NOT_A_BOOLEAN,
        superuser: false,
        permissions: [],
        username: "ada",
      },
      "UserResponse.passwordChangeRequired",
    ],
    // The idle sign-out is timed by this field, so a body without a whole
    // number of seconds is refused rather than guessed at.
    [
      "a missing idle timeout",
      { passwordChangeRequired: false, superuser: false, permissions: [], username: "ada" },
      "UserResponse.idleTimeoutSeconds is not an integer",
    ],
    [
      "a fractional idle timeout",
      {
        idleTimeoutSeconds: 1.5,
        passwordChangeRequired: false,
        superuser: false,
        permissions: [],
        username: "ada",
      },
      "UserResponse.idleTimeoutSeconds is not an integer",
    ],
    [
      "an idle timeout sent as a string",
      {
        idleTimeoutSeconds: "900",
        passwordChangeRequired: false,
        superuser: false,
        permissions: [],
        username: "ada",
      },
      "UserResponse.idleTimeoutSeconds is not an integer",
    ],
    [
      "a non-string username",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        superuser: false,
        permissions: [],
        username: 7,
      },
      "UserResponse.username",
    ],
    [
      "an unknown Permission, which the route guards would otherwise read",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        superuser: false,
        permissions: ["user:read", "superuser"],
        username: "ada",
      },
      "UserResponse.permissions holds an unknown value",
    ],
    [
      "user:write, which only a connector token carries and no session holds",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        superuser: false,
        permissions: ["user:read", "user:write"],
        username: "ada",
      },
      "UserResponse.permissions holds an unknown value",
    ],
    [
      "a Permission in the wrong case",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        superuser: false,
        permissions: ["User:Read"],
        username: "ada",
      },
      "UserResponse.permissions holds an unknown value",
    ],
    [
      "a Permission that is not a string",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        superuser: false,
        permissions: [7],
        username: "ada",
      },
      "UserResponse.permissions holds an unknown value",
    ],
    // The Accounts page recognises the session's own row by this id, so a body
    // without one is refused rather than leaving every row looking like another's.
    [
      "a missing stable id",
      {
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        superuser: false,
        permissions: [],
        username: "ada",
      },
      "UserResponse.id",
    ],
    [
      "a non-string stable id",
      {
        id: 7,
        idleTimeoutSeconds: 900,
        passwordChangeRequired: false,
        superuser: false,
        permissions: [],
        username: "ada",
      },
      "UserResponse.id",
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
  /** What the decoder reads out of the backend's answer that the password check refused. */
  const ANSWERED = { kind: "current-password" } as const;

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
    resolveWith({
      kind: "failed",
      status: 400,
      detail: { kind: "rule", message: "The new password is too short" },
    });
    await expect(changePassword(current, next)).resolves.toEqual({
      kind: "policy-violation",
      message: "The new password is too short",
    });
  });

  it.each([
    ["a 400 with no rule", { kind: "failed", status: 400 }],
    [
      "another status carrying a message",
      { kind: "failed", status: 500, detail: { kind: "rule", message: "boom" } },
    ],
    [
      "a 400 carrying the password check's refusal",
      { kind: "failed", status: 400, detail: ANSWERED },
    ],
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

  // The backend marks the 401 its password check answered. A 401 without that
  // mark was refused before the check, for want of a live session: nothing was
  // compared or counted, so it is the ordinary Expired session.
  it.each([
    ["no body", { kind: "unauthenticated" }],
    ["a body that is not the password check's", { kind: "unauthenticated", detail: undefined }],
  ])("reads a 401 with %s as an expired session, without probing", async (_name, result) => {
    resolveWith(result);

    await expect(changePassword(current, next)).resolves.toEqual({ kind: "expired" });
    expect(apiFetchMock).toHaveBeenCalledTimes(1);
  });

  it("reads a 401 that leaves the session standing as a wrong current password", async () => {
    apiFetchMock
      .mockResolvedValueOnce({ kind: "unauthenticated", detail: ANSWERED } as never)
      .mockResolvedValueOnce({ kind: "ok", data: undefined } as never);

    await expect(changePassword(current, next)).resolves.toEqual({
      kind: "current-password-rejected",
    });
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/auth/me");
  });

  it("reads a 401 that ended the session as a lockout", async () => {
    apiFetchMock
      .mockResolvedValueOnce({ kind: "unauthenticated", detail: ANSWERED } as never)
      .mockResolvedValueOnce({ kind: "unauthenticated" } as never);

    await expect(changePassword(current, next)).resolves.toEqual({ kind: "locked" });
    expect(apiFetchMock).toHaveBeenLastCalledWith("/api/auth/me");
  });

  // A probe that did not answer cannot tell a wrong password from a lockout,
  // so it claims neither: the change could not be confirmed.
  it.each([
    ["fails", { kind: "failed", status: 503 }],
    ["fails in transport", { kind: "failed" }],
    ["is refused", { kind: "forbidden" }],
    ["loses its token", { kind: "csrf-expired" }],
  ])(
    "reports a 401 whose session check %s as unconfirmed, not a wrong password",
    async (_name, probe) => {
      apiFetchMock
        .mockResolvedValueOnce({ kind: "unauthenticated", detail: ANSWERED } as never)
        .mockResolvedValueOnce(probe as never);

      await expect(changePassword(current, next)).resolves.toEqual({ kind: "failed" });
    },
  );

  it("reads the rule's statement out of a PasswordRuleViolation body, and nothing else", async () => {
    resolveWith({ kind: "ok", data: undefined });
    await changePassword(current, next);

    await expect(
      decodeWithCall(0, 3, {
        message: "The new password must not contain the user name",
        rule: "CONTAINS_USER_NAME",
      }),
    ).resolves.toEqual({
      kind: "rule",
      message: "The new password must not contain the user name",
    });
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

  it("reads a 401 body as the password check's refusal only when it says so", async () => {
    resolveWith({ kind: "ok", data: undefined });
    await changePassword(current, next);
    const decoder = apiFetchMock.mock.calls[0]?.[3] as (response: Response) => Promise<unknown>;
    const answer = (body: unknown) => decoder(Response.json(body, { status: 401 }));

    await expect(answer({ refused: "current-password" })).resolves.toEqual(ANSWERED);
    // Anything else throws, which `apiFetch` turns into a 401 with no `detail`.
    await expect(answer({ refused: "something-else" })).rejects.toThrow(
      "PasswordChangeRefusal.refused is not current-password",
    );
    await expect(answer({ message: "The current password is incorrect." })).rejects.toThrow(
      "PasswordChangeRefusal.refused is not a string",
    );
    await expect(
      decoder(new Response("<html>Unauthorized</html>", { status: 401 })),
    ).rejects.toThrow(SyntaxError);
  });
});

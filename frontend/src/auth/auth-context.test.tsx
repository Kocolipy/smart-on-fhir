import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import * as http from "@/lib/http";

import * as authApi from "./api";
import { AuthProvider } from "./auth-context";
import { useAuthState } from "./auth-context-value";

vi.mock("./api");
vi.mock("@/lib/http");

const api = vi.mocked(authApi);

const CONFINED: authApi.AuthUser = {
  idleTimeoutSeconds: 900,
  passwordChangeRequired: true,
  permissions: [],
  username: "ada",
};
const USER: authApi.AuthUser = {
  idleTimeoutSeconds: 900,
  passwordChangeRequired: false,
  permissions: [],
  username: "ada",
};
const CREDENTIALS = ["ada", "chosen-1"] as const;

const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;

/**
 * Mounts the provider and waits for its session check to settle. The check's
 * own transition is behind it, so a token count starts from zero.
 */
async function mounted() {
  const hook = renderHook(() => useAuthState(), { wrapper });
  await waitFor(() => expect(hook.result.current.status).not.toBe("checking"));
  vi.mocked(http.discardCsrfToken).mockClear();
  return hook;
}

/** The sign-in reason the route guards read, plus the status. */
const signInReasonAndStatus = (state: ReturnType<typeof useAuthState>) => ({
  signInReason: state.signInReason,
  status: state.status,
});

describe("AuthProvider", () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it("refuses to read the session outside the provider", () => {
    // React reports the render error on the console as well as throwing it.
    vi.spyOn(console, "error").mockImplementation(() => undefined);

    expect(() => renderHook(() => useAuthState())).toThrow(
      /^useAuth must be used inside AuthProvider\.$/,
    );
  });

  it("starts as a cold guest when there is no session", async () => {
    api.getCurrentUser.mockResolvedValue(null);
    const { result } = await mounted();

    expect(signInReasonAndStatus(result.current)).toEqual({ signInReason: null, status: "guest" });
    expect(result.current.user).toBeNull();
  });

  it("treats a failed session check as a cold guest", async () => {
    api.getCurrentUser.mockRejectedValue(new Error("offline"));
    const { result } = await mounted();

    expect(signInReasonAndStatus(result.current)).toEqual({ signInReason: null, status: "guest" });
  });

  it.each([
    ["finds a session", () => Promise.resolve(USER)],
    ["fails", () => Promise.reject(new Error("offline"))],
  ])(
    "changes nothing, the token included, when its check %s after the provider unmounted",
    async (_name, settle) => {
      let answer: () => void = () => undefined;
      api.getCurrentUser.mockReturnValue(
        new Promise((resolve, reject) => {
          answer = () => void settle().then(resolve, reject);
        }),
      );
      const { unmount } = renderHook(() => useAuthState(), { wrapper });

      unmount();
      await act(async () => answer());

      expect(http.discardCsrfToken).not.toHaveBeenCalled();
    },
  );

  it("holds a flagged session's user as reported", async () => {
    api.getCurrentUser.mockResolvedValue(CONFINED);
    const { result } = await mounted();

    expect(result.current.status).toBe("authenticated");
    expect(result.current.user).toEqual(CONFINED);
  });

  it("ends the session on a successful change and records why", async () => {
    api.getCurrentUser.mockResolvedValue(CONFINED);
    api.changePassword.mockResolvedValue({ kind: "changed" });
    const { result } = await mounted();

    let outcome: authApi.PasswordChangeOutcome | undefined;
    await act(async () => {
      outcome = await result.current.changePassword("old-value", "new-value");
    });

    expect(outcome).toEqual({ kind: "changed" });
    expect(api.changePassword).toHaveBeenCalledWith("old-value", "new-value");
    expect(signInReasonAndStatus(result.current)).toEqual({
      signInReason: "password-changed",
      status: "guest",
    });
    expect(result.current.user).toBeNull();
  });

  it("ends the session on a lockout and records why, forgetting the token", async () => {
    api.getCurrentUser.mockResolvedValue(USER);
    api.changePassword.mockResolvedValue({ kind: "locked" });
    const { result } = await mounted();

    await act(async () => {
      await result.current.changePassword("wrong-value", "new-value");
    });

    expect({
      ...signInReasonAndStatus(result.current),
      tokensForgotten: vi.mocked(http.discardCsrfToken).mock.calls.length,
      user: result.current.user,
    }).toEqual({ signInReason: "locked", status: "guest", tokensForgotten: 1, user: null });
  });

  it.each([
    { kind: "current-password-rejected" },
    { kind: "policy-violation", message: "rule" },
    { kind: "forbidden" },
    { kind: "csrf-expired" },
    { kind: "failed" },
  ] as authApi.PasswordChangeOutcome[])(
    "keeps the session and its token on a $kind refusal",
    async (refusal) => {
      api.getCurrentUser.mockResolvedValue(CONFINED);
      api.changePassword.mockResolvedValue(refusal);
      const { result } = await mounted();

      let outcome: authApi.PasswordChangeOutcome | undefined;
      await act(async () => {
        outcome = await result.current.changePassword("old-value", "new-value");
      });

      expect(outcome).toEqual(refusal);
      expect(result.current.status).toBe("authenticated");
      expect(result.current.user).toEqual(CONFINED);
      expect(result.current.signInReason).toBeNull();
      expect(http.discardCsrfToken).not.toHaveBeenCalled();
    },
  );

  it("clears the password-change reason on the next login", async () => {
    api.getCurrentUser.mockResolvedValue(CONFINED);
    api.changePassword.mockResolvedValue({ kind: "changed" });
    api.login.mockResolvedValue(USER);
    const { result } = await mounted();

    await act(async () => {
      await result.current.changePassword("old-value", "new-value");
    });
    await act(async () => {
      await result.current.login(...CREDENTIALS);
    });

    expect(api.login).toHaveBeenCalledWith(...CREDENTIALS);
    expect(signInReasonAndStatus(result.current)).toEqual({
      signInReason: null,
      status: "authenticated",
    });
    expect(result.current.user).toEqual(USER);
  });

  it("signs in, forgetting the pre-login session's token", async () => {
    api.getCurrentUser.mockResolvedValue(null);
    api.login.mockResolvedValue(USER);
    const { result } = await mounted();

    await act(async () => {
      await result.current.login(...CREDENTIALS);
    });

    expect({
      status: result.current.status,
      tokensForgotten: vi.mocked(http.discardCsrfToken).mock.calls.length,
    }).toEqual({ status: "authenticated", tokensForgotten: 1 });
  });

  it("refuses wrong credentials, forgetting the token of the session the backend ended", async () => {
    api.getCurrentUser.mockResolvedValue(null);
    api.login.mockResolvedValue(null);
    const { result } = await mounted();

    let refusal: unknown;
    await act(async () => {
      refusal = await result.current.login(...CREDENTIALS).catch((error: unknown) => error);
    });

    expect({
      message: (refusal as Error).message,
      status: result.current.status,
      tokensForgotten: vi.mocked(http.discardCsrfToken).mock.calls.length,
    }).toEqual({
      message: "The username or password is incorrect.",
      status: "guest",
      tokensForgotten: 1,
    });
  });

  it("keeps the token when a login fails without reaching a verdict", async () => {
    api.getCurrentUser.mockResolvedValue(null);
    api.login.mockRejectedValue(new Error("Unable to sign in. Please try again."));
    const { result } = await mounted();

    await act(async () => {
      await result.current.login(...CREDENTIALS).catch(() => undefined);
    });

    expect(http.discardCsrfToken).not.toHaveBeenCalled();
  });

  it("logs out, forgetting the ended session's token", async () => {
    api.getCurrentUser.mockResolvedValue(USER);
    api.logout.mockResolvedValue(undefined);
    const { result } = await mounted();

    await act(async () => {
      await result.current.logout();
    });

    expect(http.discardCsrfToken).toHaveBeenCalledOnce();
  });

  it("keeps the session and its token when the logout request fails", async () => {
    api.getCurrentUser.mockResolvedValue(USER);
    api.logout.mockRejectedValue(new Error("Unable to sign out. Please try again."));
    const { result } = await mounted();

    await act(async () => {
      await result.current.logout().catch(() => undefined);
    });

    expect({
      status: result.current.status,
      tokensForgotten: vi.mocked(http.discardCsrfToken).mock.calls.length,
    }).toEqual({ status: "authenticated", tokensForgotten: 0 });
  });

  it("forgets the token on a successful change", async () => {
    api.getCurrentUser.mockResolvedValue(CONFINED);
    api.changePassword.mockResolvedValue({ kind: "changed" });
    const { result } = await mounted();

    await act(async () => {
      await result.current.changePassword("old-value", "new-value");
    });

    expect(http.discardCsrfToken).toHaveBeenCalledOnce();
  });

  it("forgets the CSRF token of a session the backend has ended", async () => {
    api.getCurrentUser.mockResolvedValue(USER);
    const { result } = await mounted();

    act(() => result.current.expireSession());

    expect(http.discardCsrfToken).toHaveBeenCalledOnce();
  });

  it("clears the expiry reason on the next login", async () => {
    api.getCurrentUser.mockResolvedValue(USER);
    api.login.mockResolvedValue(USER);
    const { result } = await mounted();

    act(() => result.current.expireSession());
    expect(signInReasonAndStatus(result.current)).toEqual({
      signInReason: "expired",
      status: "guest",
    });

    await act(async () => {
      await result.current.login(...CREDENTIALS);
    });
    expect(result.current.signInReason).toBeNull();
  });

  it("an expiry after a change says the session expired, not that it changed", async () => {
    api.getCurrentUser.mockResolvedValue(CONFINED);
    api.changePassword.mockResolvedValue({ kind: "changed" });
    const { result } = await mounted();

    await act(async () => {
      await result.current.changePassword("old-value", "new-value");
    });
    act(() => result.current.expireSession());

    expect(signInReasonAndStatus(result.current)).toEqual({
      signInReason: "expired",
      status: "guest",
    });
  });

  it("logs out to a cold guest, clearing the sign-in reason", async () => {
    api.getCurrentUser.mockResolvedValue(CONFINED);
    api.changePassword.mockResolvedValue({ kind: "changed" });
    api.login.mockResolvedValue(USER);
    api.logout.mockResolvedValue(undefined);
    const { result } = await mounted();

    await act(async () => {
      await result.current.changePassword("old-value", "new-value");
    });
    act(() => result.current.expireSession());
    await act(async () => {
      await result.current.login(...CREDENTIALS);
    });
    await act(async () => {
      await result.current.changePassword("old-value", "new-value");
    });
    await act(async () => {
      await result.current.logout();
    });

    expect(api.logout).toHaveBeenCalledTimes(1);
    expect(signInReasonAndStatus(result.current)).toEqual({ signInReason: null, status: "guest" });
    expect(result.current.user).toBeNull();
  });

  it("signs an idle session out: logs out, forgets the token and records why", async () => {
    api.getCurrentUser.mockResolvedValue(USER);
    api.logout.mockResolvedValue(undefined);
    const { result } = await mounted();

    await act(async () => {
      await result.current.signOutForInactivity();
    });

    expect(api.logout).toHaveBeenCalledOnce();
    expect(http.discardCsrfToken).toHaveBeenCalledOnce();
    expect(signInReasonAndStatus(result.current)).toEqual({
      signInReason: "inactive",
      status: "guest",
    });
    expect(result.current.user).toBeNull();
  });

  it("clears an idle session even when its logout fails", async () => {
    api.getCurrentUser.mockResolvedValue(USER);
    api.logout.mockRejectedValue(new Error("Unable to sign out. Please try again."));
    const { result } = await mounted();

    await act(async () => {
      await result.current.signOutForInactivity();
    });

    expect(http.discardCsrfToken).toHaveBeenCalledOnce();
    expect(signInReasonAndStatus(result.current)).toEqual({
      signInReason: "inactive",
      status: "guest",
    });
    expect(result.current.user).toBeNull();
  });

  it("clears the Idle sign-out reason on the next login, and replaces it on a later expiry", async () => {
    api.getCurrentUser.mockResolvedValue(USER);
    api.logout.mockResolvedValue(undefined);
    api.login.mockResolvedValue(USER);
    const { result } = await mounted();

    await act(async () => {
      await result.current.signOutForInactivity();
    });
    await act(async () => {
      await result.current.login(...CREDENTIALS);
    });
    expect(result.current.signInReason).toBeNull();

    await act(async () => {
      await result.current.signOutForInactivity();
    });
    act(() => result.current.expireSession());
    expect(signInReasonAndStatus(result.current)).toEqual({
      signInReason: "expired",
      status: "guest",
    });
  });
});

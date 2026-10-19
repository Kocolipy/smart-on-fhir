import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import {
  bare,
  CURRENT_PASSWORD_REFUSED,
  deferred,
  fakeBackend,
  json,
  UNREACHABLE,
  type Answers,
  requestKey,
  type FakeBackend,
} from "@/lib/fake-backend.testHelpers";

import type { LoginOutcome, LogoutOutcome, PasswordChangeOutcome } from "./api";
import { AuthProvider } from "./auth-context";
import { AUTH_REQUESTS, testUser } from "./auth-context.testHelpers";
import { useAuthState } from "./auth-context-value";

// The provider is the subject, so it runs for real over the backend layer: the
// session check, the session actions and the token all go over the wire, and
// the token being forgotten shows there too — the next unsafe request fetches
// a fresh one first.

const { change: CHANGE, login: LOGIN, logout: LOGOUT, me: ME, token: TOKEN } = AUTH_REQUESTS;

const CONFINED = testUser({ passwordChangeRequired: true });
const USER = testUser();
const CREDENTIALS = ["ada", "chosen-1"] as const;
const [OLD_VALUE, NEW_VALUE] = ["old-value", "new-value"];

/** A signed-in session, and one with the change-required flag. */
const SIGNED_IN: Answers = { [ME]: json(USER) };
const SIGNED_IN_CONFINED: Answers = { [ME]: json(CONFINED) };
const GUEST: Answers = { [ME]: bare(401) };

const wrapper = ({ children }: { children: ReactNode }) => <AuthProvider>{children}</AuthProvider>;

/** Mounts the provider over a backend answering `answers`, and waits for its session check to settle. */
async function mounted(answers: Answers) {
  const backend = fakeBackend(answers);
  const hook = renderHook(() => useAuthState(), { wrapper });
  await waitFor(() => expect(hook.result.current.status).not.toBe("checking"));
  return { backend, result: hook.result };
}

/** How many times the session was checked. */
const checks = (backend: FakeBackend) =>
  backend.requests().filter((sent) => requestKey(sent) === ME).length;

/** The sign-in reason the route guards read, plus the status. */
const signInReasonAndStatus = (state: ReturnType<typeof useAuthState>) => ({
  signInReason: state.signInReason,
  status: state.status,
});

/**
 * Runs one session action and reports what it settled to: the outcome it
 * resolved, the status after it, and whether it forgot the token it was sent
 * with.
 */
async function settle<T>(
  { backend, result }: Awaited<ReturnType<typeof mounted>>,
  action: (state: ReturnType<typeof useAuthState>) => Promise<T>,
) {
  let outcome: T | undefined;
  await act(async () => {
    outcome = await action(result.current);
  });
  return {
    outcome,
    status: result.current.status,
    tokenForgotten: await backend.tokenForgotten(),
  };
}

describe("AuthProvider", () => {
  it("refuses to read the session outside the provider", () => {
    // React reports the render error on the console as well as throwing it.
    vi.spyOn(console, "error").mockImplementation(() => undefined);

    expect(() => renderHook(() => useAuthState())).toThrow(
      /^useAuth must be used inside AuthProvider\.$/,
    );
  });

  it("starts as a cold guest when there is no session", async () => {
    const { result } = await mounted(GUEST);

    expect(signInReasonAndStatus(result.current)).toEqual({ signInReason: null, status: "guest" });
    expect(result.current.user).toBeNull();
  });

  // A check that did not answer says nothing about whether a live session
  // stands: only a 401 makes a Guest, so this one ends nothing and forgets no
  // token, and no login route is taken. (A check is a GET, so it never comes
  // back `csrf-expired`: no token is fetched for it.)
  it.each([
    ["a 403", bare(403), "forbidden"],
    ["a 500", bare(500), "failed"],
    ["no answer at all", UNREACHABLE, "failed"],
  ] as const)(
    "leaves the session unknown, not a Guest, when its check meets %s",
    async (_name, answer, kind) => {
      const backend = fakeBackend({ [ME]: answer });
      await backend.holdToken();
      const { result } = renderHook(() => useAuthState(), { wrapper });
      await waitFor(() => expect(result.current.status).not.toBe("checking"));

      expect({
        checkFailure: result.current.checkFailure,
        status: result.current.status,
        tokenForgotten: await backend.tokenForgotten(),
      }).toEqual({ checkFailure: { kind }, status: "unchecked", tokenForgotten: false });
    },
  );

  it.each([
    ["signs in", json(USER), "authenticated"],
    ["makes a Guest", bare(401), "guest"],
    ["leaves the session unknown", bare(500), "unchecked"],
  ] as const)(
    "checking again after an unanswered check %s by the same rules",
    async (_name, answer, status) => {
      const backend = fakeBackend({ [ME]: [bare(500), answer] });
      const { result } = renderHook(() => useAuthState(), { wrapper });
      await waitFor(() => expect(result.current.status).toBe("unchecked"));

      act(() => result.current.retrySessionCheck());
      await waitFor(() => expect(checks(backend)).toBe(2));
      await waitFor(() => expect(result.current.status).not.toBe("checking"));

      expect(result.current.status).toBe(status);
    },
  );

  it("checks again on every retry, not only the first", async () => {
    const backend = fakeBackend({ [ME]: bare(500) });
    const { result } = renderHook(() => useAuthState(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe("unchecked"));

    for (const run of [2, 3]) {
      act(() => result.current.retrySessionCheck());
      await waitFor(() => expect(result.current.status).toBe("unchecked"));
      await waitFor(() => expect(checks(backend)).toBe(run));
    }

    expect(checks(backend)).toBe(3);
  });

  it.each([
    ["finds a session", json(USER)],
    ["fails", bare(500)],
  ])(
    "changes nothing, the token included, when its check %s after the provider unmounted",
    async (_name, answer) => {
      const check = deferred();
      const backend = fakeBackend({ [ME]: check.answer });
      await backend.holdToken();
      const { unmount } = renderHook(() => useAuthState(), { wrapper });

      unmount();
      await check.settle(answer);

      expect(await backend.tokenForgotten()).toBe(false);
    },
  );

  it("holds a flagged session's user as reported", async () => {
    const { result } = await mounted(SIGNED_IN_CONFINED);

    expect(result.current.status).toBe("authenticated");
    expect(result.current.user).toEqual(CONFINED);
  });

  it("ends the session on a successful change and records why", async () => {
    const { backend, result } = await mounted({ ...SIGNED_IN_CONFINED, [CHANGE]: bare(204) });

    let outcome: PasswordChangeOutcome | undefined;
    await act(async () => {
      outcome = await result.current.changePassword(OLD_VALUE, NEW_VALUE);
    });

    expect(outcome).toEqual({ kind: "changed" });
    expect(backend.requests()).toContainEqual({
      body: { currentPassword: OLD_VALUE, newPassword: NEW_VALUE },
      method: "POST",
      path: "/api/auth/change-password",
    });
    expect(signInReasonAndStatus(result.current)).toEqual({
      signInReason: "password-changed",
      status: "guest",
    });
    expect(result.current.user).toBeNull();
  });

  it("ends the session on a lockout and records why, forgetting the token", async () => {
    // The 401 ended the session too, so the check that follows it is refused.
    const session = await mounted({
      [CHANGE]: CURRENT_PASSWORD_REFUSED,
      [ME]: [json(USER), bare(401)],
    });

    const settled = await settle(session, (auth) =>
      auth.changePassword("wrong-value", "new-value"),
    );

    expect({
      ...signInReasonAndStatus(session.result.current),
      tokenForgotten: settled.tokenForgotten,
      user: session.result.current.user,
    }).toEqual({ signInReason: "locked", status: "guest", tokenForgotten: true, user: null });
  });

  it("ends a session that had already expired as expired, forgetting the token", async () => {
    // Refused before the password check, so the 401 carries no mark of it.
    // The session is not checked again: a gone session would read as a lockout.
    const session = await mounted({ ...SIGNED_IN_CONFINED, [CHANGE]: bare(401) });

    const settled = await settle(session, (auth) => auth.changePassword(OLD_VALUE, NEW_VALUE));

    expect({
      ...signInReasonAndStatus(session.result.current),
      outcome: settled.outcome,
      tokenForgotten: settled.tokenForgotten,
      user: session.result.current.user,
    }).toEqual({
      outcome: { kind: "expired" },
      signInReason: "expired",
      status: "guest",
      tokenForgotten: true,
      user: null,
    });
  });

  it.each([
    [
      "current-password-rejected",
      // The session survives the 401, so the check that follows it still answers.
      { [CHANGE]: CURRENT_PASSWORD_REFUSED },
      { kind: "current-password-rejected" },
    ],
    [
      "policy-violation",
      { [CHANGE]: json({ message: "rule", rule: "CONTAINS_USER_NAME" }, 400) },
      { kind: "policy-violation", message: "rule" },
    ],
    ["forbidden", { [CHANGE]: bare(403) }, { kind: "forbidden" }],
    ["failed", { [CHANGE]: bare(500) }, { kind: "failed" }],
  ] as [string, Answers, PasswordChangeOutcome][])(
    "keeps the session and its token on a %s refusal",
    async (_name, answers, refusal) => {
      const session = await mounted({ ...SIGNED_IN_CONFINED, ...answers });

      const settled = await settle(session, (auth) => auth.changePassword(OLD_VALUE, NEW_VALUE));

      expect(settled).toEqual({ outcome: refusal, status: "authenticated", tokenForgotten: false });
      expect(session.result.current.user).toEqual(CONFINED);
      expect(session.result.current.signInReason).toBeNull();
    },
  );

  // No token could be had, so there is none to keep or forget: the session is what stands.
  it("keeps the session when no token can be had for the change", async () => {
    const { result } = await mounted({ ...SIGNED_IN_CONFINED, [TOKEN]: bare(500) });

    let outcome: PasswordChangeOutcome | undefined;
    await act(async () => {
      outcome = await result.current.changePassword(OLD_VALUE, NEW_VALUE);
    });

    expect({ outcome, ...signInReasonAndStatus(result.current) }).toEqual({
      outcome: { kind: "csrf-expired" },
      signInReason: null,
      status: "authenticated",
    });
  });

  it("clears the password-change reason on the next login", async () => {
    const { backend, result } = await mounted({
      ...SIGNED_IN_CONFINED,
      [CHANGE]: bare(204),
      [LOGIN]: json(USER),
    });

    await act(async () => {
      await result.current.changePassword(OLD_VALUE, NEW_VALUE);
    });
    await act(async () => {
      await result.current.login(...CREDENTIALS);
    });

    expect(backend.requests()).toContainEqual({
      body: { password: CREDENTIALS[1], username: CREDENTIALS[0] },
      method: "POST",
      path: "/api/auth/login",
    });
    expect(signInReasonAndStatus(result.current)).toEqual({
      signInReason: null,
      status: "authenticated",
    });
    expect(result.current.user).toEqual(USER);
  });

  it("signs in, forgetting the pre-login session's token", async () => {
    const session = await mounted({ ...GUEST, [LOGIN]: json(USER) });

    expect(await settle(session, (auth) => auth.login(...CREDENTIALS))).toEqual({
      outcome: { kind: "signed-in", user: USER },
      status: "authenticated",
      tokenForgotten: true,
    });
  });

  it("refuses wrong credentials, forgetting the token of the session the backend ended", async () => {
    const session = await mounted({ ...GUEST, [LOGIN]: bare(401) });

    expect(await settle(session, (auth) => auth.login(...CREDENTIALS))).toEqual({
      outcome: { kind: "refused" },
      status: "guest",
      tokenForgotten: true,
    });
  });

  it.each([
    ["forbidden", bare(403), { kind: "forbidden" }],
    ["failed", bare(500), { kind: "failed" }],
  ] as const)(
    "resolves a %s login without a verdict, keeping the session and its token",
    async (_name, answer, failure: LoginOutcome) => {
      const session = await mounted({ ...GUEST, [LOGIN]: answer });

      expect(await settle(session, (auth) => auth.login(...CREDENTIALS))).toEqual({
        outcome: failure,
        status: "guest",
        tokenForgotten: false,
      });
    },
  );

  it("resolves a login no token can be had for without a verdict, keeping the session", async () => {
    const { result } = await mounted({ ...GUEST, [TOKEN]: bare(500) });

    let outcome: LoginOutcome | undefined;
    await act(async () => {
      outcome = await result.current.login(...CREDENTIALS);
    });

    expect({ outcome, status: result.current.status }).toEqual({
      outcome: { kind: "csrf-expired" },
      status: "guest",
    });
  });

  it("logs out, forgetting the ended session's token", async () => {
    const session = await mounted({ ...SIGNED_IN, [LOGOUT]: bare(204) });

    expect(await settle(session, (auth) => auth.logout())).toEqual({
      outcome: { kind: "signed-out" },
      status: "guest",
      tokenForgotten: true,
    });
  });

  it("resolves a failed logout, keeping the session and its token", async () => {
    const session = await mounted({ ...SIGNED_IN, [LOGOUT]: bare(500) });

    expect(await settle(session, (auth) => auth.logout())).toEqual({
      outcome: { kind: "failed" },
      status: "authenticated",
      tokenForgotten: false,
    });
  });

  it("resolves a logout no token can be had for, keeping the session", async () => {
    const { result } = await mounted({ ...SIGNED_IN, [TOKEN]: bare(500) });

    let outcome: LogoutOutcome | undefined;
    await act(async () => {
      outcome = await result.current.logout();
    });

    expect({ outcome, status: result.current.status }).toEqual({
      outcome: { kind: "csrf-expired" },
      status: "authenticated",
    });
  });

  it("forgets the token on a successful change", async () => {
    const session = await mounted({ ...SIGNED_IN_CONFINED, [CHANGE]: bare(204) });

    const { tokenForgotten: forgotten } = await settle(session, (auth) =>
      auth.changePassword(OLD_VALUE, NEW_VALUE),
    );

    expect(forgotten).toBe(true);
  });

  it("forgets the CSRF token of a session the backend has ended", async () => {
    const { backend, result } = await mounted(SIGNED_IN);
    await backend.holdToken();

    act(() => result.current.expireSession());

    expect(await backend.tokenForgotten()).toBe(true);
  });

  it("clears the expiry reason on the next login", async () => {
    const { result } = await mounted({ ...SIGNED_IN, [LOGIN]: json(USER) });

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
    const { result } = await mounted({ ...SIGNED_IN_CONFINED, [CHANGE]: bare(204) });

    await act(async () => {
      await result.current.changePassword(OLD_VALUE, NEW_VALUE);
    });
    act(() => result.current.expireSession());

    expect(signInReasonAndStatus(result.current)).toEqual({
      signInReason: "expired",
      status: "guest",
    });
  });

  it("logs out to a cold guest, clearing the sign-in reason", async () => {
    const { backend, result } = await mounted({
      ...SIGNED_IN_CONFINED,
      [CHANGE]: bare(204),
      [LOGIN]: json(USER),
      [LOGOUT]: bare(204),
    });

    await act(async () => {
      await result.current.changePassword(OLD_VALUE, NEW_VALUE);
    });
    act(() => result.current.expireSession());
    await act(async () => {
      await result.current.login(...CREDENTIALS);
    });
    await act(async () => {
      await result.current.changePassword(OLD_VALUE, NEW_VALUE);
    });
    await act(async () => {
      await result.current.logout();
    });

    expect(backend.requests().filter(({ method }) => method === "DELETE")).toHaveLength(1);
    expect(signInReasonAndStatus(result.current)).toEqual({ signInReason: null, status: "guest" });
    expect(result.current.user).toBeNull();
  });

  it("signs an idle session out: logs out, forgets the token and records why", async () => {
    const session = await mounted({ ...SIGNED_IN, [LOGOUT]: bare(204) });

    const { tokenForgotten: forgotten } = await settle(session, (auth) =>
      auth.signOutForInactivity(),
    );

    expect(session.backend.requests()).toContainEqual({
      method: "DELETE",
      path: "/api/auth/logout",
    });
    expect(forgotten).toBe(true);
    expect(signInReasonAndStatus(session.result.current)).toEqual({
      signInReason: "inactive",
      status: "guest",
    });
    expect(session.result.current.user).toBeNull();
  });

  it("forgets the token of an idle session even when its logout fails", async () => {
    const session = await mounted({ ...SIGNED_IN, [LOGOUT]: bare(500) });

    const { tokenForgotten: forgotten } = await settle(session, (auth) =>
      auth.signOutForInactivity(),
    );

    expect(forgotten).toBe(true);
  });

  it.each([
    ["a failure", { [LOGOUT]: bare(500) }],
    ["no token to be had", { [TOKEN]: bare(500) }],
  ] as [string, Answers][])(
    "clears an idle session even when its logout meets %s",
    async (_name, answers) => {
      const session = await mounted({ ...SIGNED_IN, ...answers });

      await act(() => session.result.current.signOutForInactivity());

      expect(signInReasonAndStatus(session.result.current)).toEqual({
        signInReason: "inactive",
        status: "guest",
      });
      expect(session.result.current.user).toBeNull();
    },
  );

  it("clears the Idle sign-out reason on the next login, and replaces it on a later expiry", async () => {
    const { result } = await mounted({ ...SIGNED_IN, [LOGIN]: json(USER), [LOGOUT]: bare(204) });

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

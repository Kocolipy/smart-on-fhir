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
import {
  fakeHub,
  HUB_URL,
  type HubAnswer,
  type HubOptions,
  REGISTRATION_CLIENT_URI,
  requestsSince,
  REVOCATION_ENDPOINT,
} from "@/lib/fake-hub.testHelpers";
import { connectFhircast, FHIRCAST_STORAGE_KEYS, type FhircastRelease } from "@/lib/fhircast";

import type { LoginOutcome, LogoutOutcome, PasswordChangeOutcome } from "./api";
import { AuthProvider } from "./auth-context";
import { AUTH_REQUESTS, testUser } from "./auth-context.testHelpers";
import { useAuthState } from "./auth-context-value";
import { adoptLaunchLanding, ehrContext } from "./ehr-context";

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

  it("ends the session on a successful change and records why, forgetting the token", async () => {
    const session = await mounted({ ...SIGNED_IN_CONFINED, [CHANGE]: bare(204) });

    const settled = await settle(session, (auth) => auth.changePassword(OLD_VALUE, NEW_VALUE));

    expect(settled.outcome).toEqual({ kind: "changed" });
    expect(session.backend.requests()).toContainEqual({
      body: { currentPassword: OLD_VALUE, newPassword: NEW_VALUE },
      method: "POST",
      path: "/api/auth/change-password",
    });
    expect({
      ...signInReasonAndStatus(session.result.current),
      tokenForgotten: settled.tokenForgotten,
      user: session.result.current.user,
    }).toEqual({
      signInReason: "password-changed",
      status: "guest",
      tokenForgotten: true,
      user: null,
    });
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

  it("forgets the CSRF token of a session the backend has ended", async () => {
    const { backend, result } = await mounted(SIGNED_IN);
    await backend.holdToken();

    act(() => result.current.expireSession());

    expect(await backend.tokenForgotten()).toBe(true);
  });

  /** Each way a session ends with a reason, and the answers that let it end so. */
  const ENDINGS = [
    [
      "a successful change",
      { ...SIGNED_IN_CONFINED, [CHANGE]: bare(204) },
      (auth: ReturnType<typeof useAuthState>) => auth.changePassword(OLD_VALUE, NEW_VALUE),
      "password-changed",
    ],
    [
      "an expiry",
      SIGNED_IN,
      (auth: ReturnType<typeof useAuthState>) => {
        auth.expireSession();
        return Promise.resolve();
      },
      "expired",
    ],
    [
      "an Idle sign-out",
      { ...SIGNED_IN, [LOGOUT]: bare(204) },
      (auth: ReturnType<typeof useAuthState>) => auth.signOutForInactivity(),
      "inactive",
    ],
  ] as const;

  it.each(ENDINGS)(
    "clears the reason %s recorded on the next login",
    async (_name, answers, end, reason) => {
      const { result } = await mounted({ ...answers, [LOGIN]: json(USER) });

      await act(async () => {
        await end(result.current);
      });
      expect(result.current.signInReason).toBe(reason);

      await act(async () => {
        await result.current.login(...CREDENTIALS);
      });
      expect({ ...signInReasonAndStatus(result.current), user: result.current.user }).toEqual({
        signInReason: null,
        status: "authenticated",
        user: USER,
      });
    },
  );

  it.each(ENDINGS.filter(([name]) => name !== "an expiry"))(
    "says an expiry after %s ended the session by expiry",
    async (_name, answers, end) => {
      const { result } = await mounted(answers);

      await act(async () => {
        await end(result.current);
      });
      act(() => result.current.expireSession());

      expect(signInReasonAndStatus(result.current)).toEqual({
        signInReason: "expired",
        status: "guest",
      });
    },
  );

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
});

describe("AuthProvider and the EHR context", () => {
  const PATIENT = "eq081-VQEgP8drUUqCWzHfw3";

  /** The tab landed from an Epic launch with `PATIENT` in context, as the page loads. */
  const landedFromEpic = () => {
    window.history.replaceState(null, "", `/#patient=${PATIENT}`);
    adoptLaunchLanding();
  };

  it("keeps it when the start-up check finds the session signed in, as on a reload", async () => {
    landedFromEpic();

    await mounted(SIGNED_IN);

    expect(ehrContext()).toEqual({ patient: PATIENT });
  });

  it("clears it on sign-out", async () => {
    landedFromEpic();
    const session = await mounted({ ...SIGNED_IN, [LOGOUT]: bare(204) });

    await settle(session, (auth) => auth.logout());

    expect(ehrContext()).toBeNull();
  });

  it("clears it when the session expires", async () => {
    landedFromEpic();
    const { result } = await mounted(SIGNED_IN);

    act(() => result.current.expireSession());

    expect(ehrContext()).toBeNull();
  });

  it("clears it on an Idle sign-out", async () => {
    landedFromEpic();
    const session = await mounted({ ...SIGNED_IN, [LOGOUT]: bare(204) });

    await settle(session, (auth) => auth.signOutForInactivity());

    expect(ehrContext()).toBeNull();
  });

  it("clears it on a password change", async () => {
    landedFromEpic();
    const session = await mounted({ ...SIGNED_IN, [CHANGE]: bare(204) });

    await settle(session, (auth) => auth.changePassword(OLD_VALUE, NEW_VALUE));

    expect(ehrContext()).toBeNull();
  });

  it("clears it on a password Login", async () => {
    landedFromEpic();
    const session = await mounted({ ...SIGNED_IN, [LOGIN]: json(USER) });

    await settle(session, (auth) => auth.login(...CREDENTIALS));

    expect(ehrContext()).toBeNull();
  });

  it("keeps it through a login that reached no verdict", async () => {
    landedFromEpic();
    const session = await mounted({ ...SIGNED_IN, [LOGIN]: bare(500) });

    await settle(session, (auth) => auth.login(...CREDENTIALS));

    expect(ehrContext()).toEqual({ patient: PATIENT });
  });
});

describe("AuthProvider and what the FHIRcast module kept", () => {
  /** What connecting to the hub left in the tab (ADR 0016). */
  const keptByFhircast = () => {
    for (const key of Object.values(FHIRCAST_STORAGE_KEYS)) {
      window.sessionStorage.setItem(key, `${key}-value`);
    }
  };

  const stillKept = () =>
    Object.values(FHIRCAST_STORAGE_KEYS).filter((key) => window.sessionStorage.getItem(key));

  it("forgets it on sign-out", async () => {
    keptByFhircast();
    const session = await mounted({ ...SIGNED_IN, [LOGOUT]: bare(204) });

    await settle(session, (auth) => auth.logout());

    expect(stillKept()).toEqual([]);
  });

  it("forgets it when the session expires", async () => {
    keptByFhircast();
    const { result } = await mounted(SIGNED_IN);

    act(() => result.current.expireSession());

    expect(stillKept()).toEqual([]);
  });

  it("forgets it on a password Login", async () => {
    keptByFhircast();
    const session = await mounted({ ...SIGNED_IN, [LOGIN]: json(USER) });

    await settle(session, (auth) => auth.login(...CREDENTIALS));

    expect(stillKept()).toEqual([]);
  });

  it("keeps it while the session stands", async () => {
    keptByFhircast();

    await mounted(SIGNED_IN);

    expect(stillKept()).toEqual(Object.values(FHIRCAST_STORAGE_KEYS));
  });
});

describe("AuthProvider and the FHIRcast connection", () => {
  const RELEASE: FhircastRelease = {
    accessToken: `smart-${crypto.randomUUID()}`,
    expiresAt: "2026-10-24T10:00:00Z",
    hubTopic: "topic-1",
    hubUrl: HUB_URL,
    scope: ["fhircast/Patient-open.read"],
  };

  /** Signed in over `answers`, connected to a hub answering `options`, and what the hub was sent from then on. */
  async function connectedSession(answers: Answers, options: HubOptions = {}) {
    const session = await mounted({ ...SIGNED_IN, ...answers });
    const hub = fakeHub(options);
    expect((await connectFhircast(RELEASE)).kind).toBe("connected");
    const sent = requestsSince(hub);
    const since = () => sent().map(({ method, url }) => `${method} ${url}`);
    return { hub, session, since };
  }

  const TORN_DOWN = [
    `POST ${HUB_URL}`,
    `POST ${REVOCATION_ENDPOINT}`,
    `DELETE ${REGISTRATION_CLIENT_URI}`,
  ];

  it("tears it down at the hub on sign-out", async () => {
    const { session, since } = await connectedSession({ [LOGOUT]: bare(204) });

    await settle(session, (auth) => auth.logout());

    await waitFor(() => expect(since()).toEqual(TORN_DOWN));
  });

  it("tears down at the hub, on sign-out, what the page before a reload kept", async () => {
    const session = await mounted({ ...SIGNED_IN, [LOGOUT]: bare(204) });
    const hub = fakeHub();
    // The page before the reload: the FHIRcast module as it loaded then,
    // which kept its connection in the tab and went, tearing nothing down.
    vi.resetModules();
    const before = await import("@/lib/fhircast");
    const outcome = await before.connectFhircast(RELEASE);
    if (outcome.kind === "connected") outcome.close();
    const sent = requestsSince(hub);

    await settle(session, (auth) => auth.logout());

    await waitFor(() =>
      expect(sent().map(({ method, url }) => `${method} ${url}`)).toEqual(TORN_DOWN),
    );
  });

  it("tears it down at the hub when the session expires", async () => {
    const { session, since } = await connectedSession({});

    act(() => session.result.current.expireSession());

    await waitFor(() => expect(since()).toEqual(TORN_DOWN));
  });

  it("leaves it standing when a logout may have left the session standing", async () => {
    const { hub, session, since } = await connectedSession({ [LOGOUT]: bare(500) });

    await settle(session, (auth) => auth.logout());
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect([since(), hub.openSockets()]).toEqual([[], 1]);
  });

  it("signs out without waiting on a hub that never answers", async () => {
    const never = new Promise<HubAnswer>(() => undefined);
    const { session } = await connectedSession(
      { [LOGOUT]: bare(204) },
      { deletion: never, revocation: never, unsubscription: never },
    );

    const { outcome, status } = await settle(session, (auth) => auth.logout());

    expect([outcome?.kind, status]).toEqual(["signed-out", "guest"]);
  });
});

import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { discardCsrfToken } from "@/lib/http";

import type { AuthUser } from "./api";
import { useSessionTransitions } from "./session-transitions";

vi.mock("@/lib/http");

const USER: AuthUser = {
  idleTimeoutSeconds: 900,
  passwordChangeRequired: false,
  permissions: ["counter:read"],
  username: "ada",
};

/**
 * The session as the hook holds it, beside how many times the token has been
 * forgotten: a transition is right only when both moved in the same step.
 */
function snapshot(transitions: ReturnType<typeof useSessionTransitions>) {
  return {
    session: transitions.session,
    tokensForgotten: vi.mocked(discardCsrfToken).mock.calls.length,
  };
}

/** The hook, signed in as `USER`, with the token count reset after it. */
function signedIn() {
  const hook = renderHook(() => useSessionTransitions());
  act(() => hook.result.current.signIn(USER));
  vi.mocked(discardCsrfToken).mockClear();
  return hook;
}

describe("useSessionTransitions", () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it("starts checking, holding no user and no reason, with the token untouched", () => {
    const { result } = renderHook(() => useSessionTransitions());

    expect(snapshot(result.current)).toEqual({
      session: { signInReason: null, status: "checking", user: null },
      tokensForgotten: 0,
    });
  });

  it("signing in holds the user, clears the reason and forgets the token", () => {
    const { result } = renderHook(() => useSessionTransitions());
    act(() => result.current.end("expired"));
    vi.mocked(discardCsrfToken).mockClear();

    act(() => result.current.signIn(USER));

    expect(snapshot(result.current)).toEqual({
      session: { signInReason: null, status: "authenticated", user: USER },
      tokensForgotten: 1,
    });
  });

  it.each(["expired", "inactive", "password-changed", "locked"] as const)(
    "ending for %s drops the user, records why and forgets the token",
    (reason) => {
      const { result } = signedIn();

      act(() => result.current.end(reason));

      expect(snapshot(result.current)).toEqual({
        session: { signInReason: reason, status: "guest", user: null },
        tokensForgotten: 1,
      });
    },
  );

  it("ending with no reason leaves a cold guest and forgets the token", () => {
    const { result } = signedIn();

    act(() => result.current.end(null));

    expect(snapshot(result.current)).toEqual({
      session: { signInReason: null, status: "guest", user: null },
      tokensForgotten: 1,
    });
  });

  it("ending a guest's session replaces its reason and forgets the token", () => {
    const { result } = signedIn();
    act(() => result.current.end("expired"));
    vi.mocked(discardCsrfToken).mockClear();

    act(() => result.current.end(null));

    expect(snapshot(result.current)).toEqual({
      session: { signInReason: null, status: "guest", user: null },
      tokensForgotten: 1,
    });
  });

  it("keeps each transition the same function across renders", () => {
    const { rerender, result } = renderHook(() => useSessionTransitions());
    const { end, signIn } = result.current;

    rerender();

    // Compared by identity: `toEqual` would call any two functions equal.
    expect([result.current.end === end, result.current.signIn === signIn]).toEqual([true, true]);
  });
});

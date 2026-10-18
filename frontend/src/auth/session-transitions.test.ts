import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";

import { fakeBackend, type FakeBackend } from "@/lib/fake-backend.testHelpers";

import { testUser } from "./auth-context.testHelpers";
import { useSessionTransitions } from "./session-transitions";

// Whether a transition forgot the token is read off the wire, over the backend
// layer: a token is held before the transition, and the next unsafe request
// fetches a fresh one only if the transition forgot it.

const USER = testUser({ permissions: ["counter:read"] });

let backend: FakeBackend;

/**
 * The session as the hook holds it, beside whether the token held before the
 * transition was forgotten: a transition is right only when both moved in the
 * same step.
 */
async function snapshot(transitions: ReturnType<typeof useSessionTransitions>) {
  return { session: transitions.session, tokenForgotten: await backend.tokenForgotten() };
}

/** The hook, signed in as `USER`, holding a token from after it. */
async function signedIn() {
  const hook = renderHook(() => useSessionTransitions());
  act(() => hook.result.current.signIn(USER));
  await backend.holdToken();
  return hook;
}

describe("useSessionTransitions", () => {
  beforeEach(async () => {
    backend = fakeBackend();
    await backend.holdToken();
  });

  it("starts checking, holding no user and no reason, with the token untouched", async () => {
    const { result } = renderHook(() => useSessionTransitions());

    expect(await snapshot(result.current)).toEqual({
      session: { checkFailure: null, signInReason: null, status: "checking", user: null },
      tokenForgotten: false,
    });
  });

  it("signing in holds the user, clears the reason and forgets the token", async () => {
    const { result } = renderHook(() => useSessionTransitions());
    act(() => result.current.end("expired"));
    await backend.holdToken();

    act(() => result.current.signIn(USER));

    expect(await snapshot(result.current)).toEqual({
      session: { checkFailure: null, signInReason: null, status: "authenticated", user: USER },
      tokenForgotten: true,
    });
  });

  it.each(["expired", "inactive", "password-changed", "locked"] as const)(
    "ending for %s drops the user, records why and forgets the token",
    async (reason) => {
      const { result } = await signedIn();

      act(() => result.current.end(reason));

      expect(await snapshot(result.current)).toEqual({
        session: { checkFailure: null, signInReason: reason, status: "guest", user: null },
        tokenForgotten: true,
      });
    },
  );

  it("ending with no reason leaves a cold guest and forgets the token", async () => {
    const { result } = await signedIn();

    act(() => result.current.end(null));

    expect(await snapshot(result.current)).toEqual({
      session: { checkFailure: null, signInReason: null, status: "guest", user: null },
      tokenForgotten: true,
    });
  });

  it("ending a guest's session replaces its reason and forgets the token", async () => {
    const { result } = await signedIn();
    act(() => result.current.end("expired"));
    await backend.holdToken();

    act(() => result.current.end(null));

    expect(await snapshot(result.current)).toEqual({
      session: { checkFailure: null, signInReason: null, status: "guest", user: null },
      tokenForgotten: true,
    });
  });

  // A check that did not answer changed no session, so the token it may still
  // be worth is kept: forgetting it would be a session-end transition.
  it.each(["forbidden", "csrf-expired", "failed"] as const)(
    "a check that came back %s leaves the session unknown, recording why, with the token kept",
    async (kind) => {
      const { result } = renderHook(() => useSessionTransitions());

      act(() => result.current.checkFailed({ kind }));

      expect(await snapshot(result.current)).toEqual({
        session: { checkFailure: { kind }, signInReason: null, status: "unchecked", user: null },
        tokenForgotten: false,
      });
    },
  );

  it("checking again returns an unknown session to checking, with the token kept", async () => {
    const { result } = renderHook(() => useSessionTransitions());
    act(() => result.current.checkFailed({ kind: "failed" }));

    act(() => result.current.recheck());

    expect(await snapshot(result.current)).toEqual({
      session: { checkFailure: null, signInReason: null, status: "checking", user: null },
      tokenForgotten: false,
    });
  });

  it("keeps each transition the same function across renders", () => {
    const { rerender, result } = renderHook(() => useSessionTransitions());
    const { checkFailed, end, recheck, signIn } = result.current;

    rerender();

    // Compared by identity: `toEqual` would call any two functions equal.
    expect([
      result.current.checkFailed === checkFailed,
      result.current.end === end,
      result.current.recheck === recheck,
      result.current.signIn === signIn,
    ]).toEqual([true, true, true, true]);
  });
});

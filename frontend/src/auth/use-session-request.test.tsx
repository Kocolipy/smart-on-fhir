import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { bare, deferred, fakeBackend, json, type Answers } from "@/lib/fake-backend.testHelpers";
import { fakeHub, HUB_URL, requestsSince } from "@/lib/fake-hub.testHelpers";
import { connectFhircast, type FhircastRelease } from "@/lib/fhircast";

import { AuthProvider } from "./auth-context";
import { AuthContext, useAuthState, type AuthContextState } from "./auth-context-value";
import { AUTH_REQUESTS, authState, signedIn, testUser } from "./auth-context.testHelpers";
import { adoptLaunchLanding, ehrContext } from "./ehr-context";
import { useSessionRequest } from "./use-session-request";

// The hook reads one thing from the session — `expireSession` — so it runs
// against a hand-built auth state; the backend it requests from is the
// backend layer's.

const state: AuthContextState = authState(signedIn());

const wrapper = ({ children }: { children: ReactNode }) => (
  <AuthContext.Provider value={state}>{children}</AuthContext.Provider>
);

/** The hook's request, over a backend answering `answers`. */
function request(answers: Answers) {
  const backend = fakeBackend(answers);
  const send = renderHook(() => useSessionRequest(), { wrapper }).result.current;
  return { requests: backend.requests, send };
}

const decode = (response: Response) => response.json() as Promise<number>;

describe("useSessionRequest", () => {
  beforeEach(() => {
    vi.mocked(state.expireSession).mockReset();
  });

  it("passes a successful result through untouched", async () => {
    const { send } = request({ "GET /api/example": json(7) });

    await expect(send("/api/example", {}, decode)).resolves.toEqual({ data: 7, kind: "ok" });
    expect(state.expireSession).not.toHaveBeenCalled();
  });

  it("sends the path and request it is handed, and reads the answer with its decoder", async () => {
    const { requests, send } = request({ "POST /api/example/change": json(1) });

    const result = await send("/api/example/change", { method: "POST" }, decode);

    expect({ requests: requests(), result }).toEqual({
      requests: [{ method: "POST", path: "/api/example/change" }],
      result: { data: 1, kind: "ok" },
    });
  });

  it("reads no body for a request sent without a decoder", async () => {
    const { send } = request({ "GET /api/ping": json(1) });

    await expect(send("/api/ping")).resolves.toEqual({ data: undefined, kind: "ok" });
  });

  it("ends the session itself on a 401, handing the caller a plain failure", async () => {
    const { send } = request({ "GET /api/example": bare(401) });

    const result = await send("/api/example", {}, decode);

    expect(state.expireSession).toHaveBeenCalledOnce();
    expect(result).toEqual({ kind: "failed", status: 401 });
  });

  it.each([
    ["csrf-expired", { "GET /api/auth/csrf": bare(500) }, { method: "POST" }],
    ["forbidden", { "GET /api/example": bare(403) }, {}],
  ] as const)("leaves a %s result for the caller to report", async (kind, answers, init) => {
    const { send } = request(answers);

    await expect(send("/api/example", init, decode)).resolves.toEqual({ kind });
    expect(state.expireSession).not.toHaveBeenCalled();
  });

  it("leaves a failed result for the caller to report", async () => {
    const { send } = request({ "GET /api/example": bare(503) });

    await expect(send("/api/example", {}, decode)).resolves.toEqual({
      kind: "failed",
      status: 503,
    });
    expect(state.expireSession).not.toHaveBeenCalled();
  });
});

// Whose `401` it is: the hook runs inside the real provider here, over the
// backend layer, so a request is held in flight while the session changes
// under it, and its `401` lands on whatever session stands by then.
describe("useSessionRequest, as the session changes under a request", () => {
  const { login: LOGIN, logout: LOGOUT, me: ME } = AUTH_REQUESTS;
  const USER = testUser();
  const PATIENT = "eq081-VQEgP8drUUqCWzHfw3";
  const RELEASE: FhircastRelease = {
    accessToken: `smart-${crypto.randomUUID()}`,
    expiresAt: "2026-10-24T10:00:00Z",
    hubTopic: "topic-1",
    hubUrl: HUB_URL,
    scope: ["fhircast/Patient-open.read"],
  };

  const providerWrapper = ({ children }: { children: ReactNode }) => (
    <AuthProvider>{children}</AuthProvider>
  );

  /** Signed in as `USER`, with a read of `/api/example` held until the test answers it. */
  async function signedInWithReadHeld() {
    const read = deferred();
    fakeBackend({
      [LOGIN]: json(USER),
      [LOGOUT]: bare(204),
      [ME]: json(USER),
      "GET /api/example": read.answer,
    });
    const { result } = renderHook(() => ({ auth: useAuthState(), send: useSessionRequest() }), {
      wrapper: providerWrapper,
    });
    await waitFor(() => expect(result.current.auth.status).toBe("authenticated"));
    const sent = result.current.send("/api/example");
    return { read, result, sent };
  }

  it("ends the session it was sent under with expired, on a 401 while it stands", async () => {
    const { read, result, sent } = await signedInWithReadHeld();

    await read.settle(bare(401));

    expect({
      outcome: await sent,
      signInReason: result.current.auth.signInReason,
      status: result.current.auth.status,
    }).toEqual({
      outcome: { kind: "failed", status: 401 },
      signInReason: "expired",
      status: "guest",
    });
  });

  it("leaves no expired reason on a 401 landing after the user signed out", async () => {
    const { read, result, sent } = await signedInWithReadHeld();
    await act(async () => {
      await result.current.auth.logout();
    });

    await read.settle(bare(401));

    expect({
      outcome: await sent,
      signInReason: result.current.auth.signInReason,
      status: result.current.auth.status,
    }).toEqual({ outcome: { kind: "failed", status: 401 }, signInReason: null, status: "guest" });
  });

  it("leaves a fresh sign-in standing, its EHR context and FHIRcast connection too, on an old session's 401", async () => {
    const { read, result } = await signedInWithReadHeld();
    await act(async () => {
      await result.current.auth.logout();
    });
    await act(async () => {
      await result.current.auth.login("ada", "chosen-1");
    });
    window.history.replaceState(null, "", `/#patient=${PATIENT}`);
    adoptLaunchLanding();
    const hub = fakeHub();
    expect((await connectFhircast(RELEASE)).kind).toBe("connected");
    const sentToHub = requestsSince(hub);

    await read.settle(bare(401));

    expect({
      ehrContext: ehrContext(),
      hub: { open: hub.openSockets(), sent: sentToHub() },
      status: result.current.auth.status,
    }).toEqual({
      ehrContext: { patient: PATIENT },
      hub: { open: 1, sent: [] },
      status: "authenticated",
    });
  });
});

import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { bare, fakeBackend, json, type Answers } from "@/lib/fake-backend.testHelpers";

import { AuthContext, type AuthContextState } from "./auth-context-value";
import { authState, signedIn } from "./auth-context.testHelpers";
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
    const { send } = request({ "GET /api/count": json(7) });

    await expect(send("/api/count", {}, decode)).resolves.toEqual({ data: 7, kind: "ok" });
    expect(state.expireSession).not.toHaveBeenCalled();
  });

  it("sends the path and request it is handed, and reads the answer with its decoder", async () => {
    const { requests, send } = request({ "POST /api/count/increment": json(1) });

    const result = await send("/api/count/increment", { method: "POST" }, decode);

    expect({ requests: requests(), result }).toEqual({
      requests: [{ method: "POST", path: "/api/count/increment" }],
      result: { data: 1, kind: "ok" },
    });
  });

  it("reads no body for a request sent without a decoder", async () => {
    const { send } = request({ "GET /api/ping": json(1) });

    await expect(send("/api/ping")).resolves.toEqual({ data: undefined, kind: "ok" });
  });

  it("ends the session itself on a 401, handing the caller a plain failure", async () => {
    const { send } = request({ "GET /api/count": bare(401) });

    const result = await send("/api/count", {}, decode);

    expect(state.expireSession).toHaveBeenCalledOnce();
    expect(result).toEqual({ kind: "failed", status: 401 });
  });

  it.each([
    ["csrf-expired", { "GET /api/auth/csrf": bare(500) }, { method: "POST" }],
    ["forbidden", { "GET /api/count": bare(403) }, {}],
  ] as const)("leaves a %s result for the caller to report", async (kind, answers, init) => {
    const { send } = request(answers);

    await expect(send("/api/count", init, decode)).resolves.toEqual({ kind });
    expect(state.expireSession).not.toHaveBeenCalled();
  });

  it("leaves a failed result for the caller to report", async () => {
    const { send } = request({ "GET /api/count": bare(503) });

    await expect(send("/api/count", {}, decode)).resolves.toEqual({
      kind: "failed",
      status: 503,
    });
    expect(state.expireSession).not.toHaveBeenCalled();
  });
});

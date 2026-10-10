import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { apiFetch } from "@/lib/http";

import { AuthContext, type AuthContextState } from "./auth-context-value";
import { useSessionRequest } from "./use-session-request";

vi.mock("@/lib/http", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/http")>()),
  apiFetch: vi.fn(),
}));

const apiFetchMock = vi.mocked(apiFetch);

const state: AuthContextState = {
  changePassword: vi.fn(),
  expireSession: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  signInReason: null,
  signOutForInactivity: vi.fn(),
  status: "authenticated",
  user: {
    idleTimeoutSeconds: 900,
    passwordChangeRequired: false,
    permissions: [],
    username: "ada",
  },
};

const wrapper = ({ children }: { children: ReactNode }) => (
  <AuthContext.Provider value={state}>{children}</AuthContext.Provider>
);

const request = () => renderHook(() => useSessionRequest(), { wrapper }).result.current;

const decode = (response: Response) => response.json() as Promise<number>;

describe("useSessionRequest", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    vi.mocked(state.expireSession).mockReset();
  });

  it("passes a successful result through untouched", async () => {
    apiFetchMock.mockResolvedValue({ data: 7, kind: "ok" } as never);

    await expect(request()("/api/count", {}, decode)).resolves.toEqual({ data: 7, kind: "ok" });
    expect(state.expireSession).not.toHaveBeenCalled();
  });

  it("forwards the path, request and decoder to the transport", async () => {
    apiFetchMock.mockResolvedValue({ data: 1, kind: "ok" } as never);

    await request()("/api/count/increment", { method: "POST" }, decode);

    expect(apiFetchMock).toHaveBeenCalledWith(
      "/api/count/increment",
      { method: "POST" },
      expect.any(Function),
    );
  });

  it("calls the transport without a decoder for a no-body request", async () => {
    apiFetchMock.mockResolvedValue({ data: undefined, kind: "ok" } as never);

    await request()("/api/ping");

    expect(apiFetchMock).toHaveBeenCalledWith("/api/ping", {});
  });

  it("ends the session itself when the transport reports it is unauthenticated", async () => {
    apiFetchMock.mockResolvedValue({ kind: "unauthenticated" } as never);

    const result = await request()("/api/count", {}, decode);

    expect(state.expireSession).toHaveBeenCalledOnce();
    expect(result).not.toMatchObject({ kind: "unauthenticated" });
  });

  it.each([{ kind: "csrf-expired" }, { kind: "forbidden" }])(
    "leaves a $kind result for the caller to report",
    async (refusal) => {
      apiFetchMock.mockResolvedValue(refusal as never);

      await expect(request()("/api/count", {}, decode)).resolves.toEqual(refusal);
      expect(state.expireSession).not.toHaveBeenCalled();
    },
  );

  it("leaves a failed result for the caller to report", async () => {
    apiFetchMock.mockResolvedValue({ kind: "failed", status: 503 } as never);

    await expect(request()("/api/count", {}, decode)).resolves.toEqual({
      kind: "failed",
      status: 503,
    });
    expect(state.expireSession).not.toHaveBeenCalled();
  });
});

import { act, renderHook } from "@testing-library/react";
import { useState, type ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { jsonDecoder, readObject } from "@/lib/decode";
import { apiFetch, CSRF_EXPIRED_MESSAGE, FORBIDDEN_MESSAGE } from "@/lib/http";

import type { Permission } from "./api";
import { AuthContext, type AuthContextState } from "./auth-context-value";
import { useGatedWrite, type SupersededRead } from "./use-gated-write";

vi.mock("@/lib/http", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/http")>()),
  apiFetch: vi.fn(),
}));

const apiFetchMock = vi.mocked(apiFetch);

const decodeCount = jsonDecoder((body: unknown) =>
  readObject(body, "CountResponse").integer("count"),
);

/** The operation most tests send: a counter increment answering the new count. */
const INCREMENT = {
  decode: decodeCount,
  method: "POST",
  path: "/api/count/increment",
} as const;

const expireSession = vi.fn();

function authState(permissions: Permission[]): AuthContextState {
  return {
    changePassword: vi.fn(),
    expireSession,
    login: vi.fn(),
    logout: vi.fn(),
    signInReason: null,
    signOutForInactivity: vi.fn(),
    status: "authenticated",
    user: { idleTimeoutSeconds: 900, passwordChangeRequired: false, permissions, username: "ada" },
  };
}

/** A wrapper rendering the hook under a session holding exactly `permissions`. */
const holding =
  (permissions: Permission[] = []) =>
  ({ children }: { children: ReactNode }) => (
    <AuthContext.Provider value={authState(permissions)}>{children}</AuthContext.Provider>
  );

const renderWrite = (permissions?: Permission[]) =>
  renderHook(() => useGatedWrite(), { wrapper: holding(permissions) });

/**
 * A write plus the one read it supersedes, as a session's own `useState`
 * would carry it — a plain object with a getter never schedules the
 * re-render a real `GatedRead`'s state update always does, so a test
 * exercising the interplay with `supersedes` needs real state behind it.
 */
function useHarness() {
  const [readError, setReadError] = useState<string | null>(null);
  const clearError = vi.fn(() => setReadError(null));
  const read: SupersededRead = { clearError, error: readError };
  const write = useGatedWrite({ supersedes: [read] });
  return { clearError, setReadError, write };
}

const renderHarness = () => renderHook(() => useHarness(), { wrapper: holding() });

/** Answers the next request with `result`, as `apiFetch` would classify it. */
function answerWith(result: object) {
  apiFetchMock.mockResolvedValueOnce(result as never);
}

/** Holds the next request open until the test answers it. */
function deferredAnswer() {
  let settle!: (result: object) => void;
  apiFetchMock.mockReturnValueOnce(
    new Promise((resolve) => {
      settle = resolve;
    }) as never,
  );
  return (result: object) => settle(result);
}

/** An `after` step held open until the test lets it resolve. */
function deferredAfter() {
  let settle!: () => void;
  const promise = new Promise<void>((resolve) => {
    settle = resolve;
  });
  return { after: () => promise, settle };
}

describe("useGatedWrite", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    expireSession.mockReset();
  });

  it("is not pending, with no error, before any write runs", () => {
    const { result } = renderWrite();

    expect(result.current).toMatchObject({ error: null, pending: false });
  });

  it("sends an operation without a body as just its method, with no decoder", async () => {
    answerWith({ data: undefined, kind: "ok" });
    const { result } = renderWrite();

    await act(() => result.current.run({ method: "DELETE", path: "/api/admin/connectors/c-1" }));

    expect(apiFetchMock.mock.calls).toEqual([["/api/admin/connectors/c-1", { method: "DELETE" }]]);
  });

  it("sends an operation's body as JSON, with its decoder", async () => {
    answerWith({ data: 1, kind: "ok" });
    const { result } = renderWrite();

    await act(() =>
      result.current.run({
        body: { displayName: "Okta" },
        decode: decodeCount,
        method: "POST",
        path: "/api/admin/connectors",
      }),
    );

    expect(apiFetchMock.mock.calls).toEqual([
      [
        "/api/admin/connectors",
        {
          body: '{"displayName":"Okta"}',
          headers: { "Content-Type": "application/json" },
          method: "POST",
        },
        decodeCount,
      ],
    ]);
  });

  it("sends an empty JSON object as a body, not as no body", async () => {
    answerWith({ data: undefined, kind: "ok" });
    const { result } = renderWrite();

    await act(() => result.current.run({ body: {}, method: "POST", path: "/api/x" }));

    expect(apiFetchMock.mock.calls[0]?.[1]).toEqual({
      body: "{}",
      headers: { "Content-Type": "application/json" },
      method: "POST",
    });
  });

  it("is pending while the request is in flight, and not once it settles", async () => {
    const { result } = renderWrite();
    const settle = deferredAnswer();

    let ran: Promise<void> = Promise.resolve();
    act(() => {
      ran = result.current.run(INCREMENT);
    });
    expect(result.current.pending).toBe(true);

    await act(async () => {
      settle({ data: 1, kind: "ok" });
      await ran;
    });
    expect(result.current.pending).toBe(false);
  });

  it("calls the success handler exactly once, with the decoded data", async () => {
    answerWith({ data: 42, kind: "ok" });
    const { result } = renderWrite();
    const onOk = vi.fn();

    await act(() => result.current.run(INCREMENT, { onOk }));

    expect(onOk).toHaveBeenCalledTimes(1);
    expect(onOk).toHaveBeenCalledWith(42);
    expect(result.current.error).toBeNull();
  });

  it("ends the session once on a 401, and shows the page's failure copy meanwhile", async () => {
    answerWith({ kind: "unauthenticated" });
    const { result } = renderWrite();
    const onOk = vi.fn();

    await act(() =>
      result.current.run(INCREMENT, { messages: { default: "Unable to update." }, onOk }),
    );

    expect(expireSession).toHaveBeenCalledOnce();
    expect(onOk).not.toHaveBeenCalled();
    expect(result.current.error).toBe("Unable to update.");
  });

  it("sends nothing for a session lacking the operation's Permission, and says so", async () => {
    const { result } = renderWrite(["counter:read"]);
    const onOk = vi.fn();

    await act(() => result.current.run({ ...INCREMENT, permission: "counter:write" }, { onOk }));

    expect(apiFetchMock).not.toHaveBeenCalled();
    expect(onOk).not.toHaveBeenCalled();
    expect(result.current.error).toBe(FORBIDDEN_MESSAGE);
  });

  it("still runs the `after` step for a write refused for want of its Permission", async () => {
    const { result } = renderWrite([]);
    const after = vi.fn(() => Promise.resolve());

    await act(() => result.current.run({ ...INCREMENT, permission: "counter:write" }, { after }));

    expect(after).toHaveBeenCalledOnce();
  });

  it("sends the operation for a session holding its Permission", async () => {
    answerWith({ data: 5, kind: "ok" });
    const { result } = renderWrite(["counter:write"]);
    const onOk = vi.fn();

    await act(() => result.current.run({ ...INCREMENT, permission: "counter:write" }, { onOk }));

    expect(onOk).toHaveBeenCalledWith(5);
  });

  it.each([
    [400, "Refused: check the values and try again."],
    [404, "It no longer exists. Reload the page for the current list."],
    [409, "Refused: the request conflicts with the resource's current state."],
    [503, "Unable to complete the action. Please try again."],
  ])("maps a %i refusal to the hook's own default copy", async (status, message) => {
    answerWith({ kind: "failed", status });
    const { result } = renderWrite();

    await act(() => result.current.run(INCREMENT));

    expect(result.current.error).toBe(message);
  });

  it("maps forbidden and csrf-expired through the shared seam copy, not a page's own", async () => {
    const { result } = renderWrite();

    const messages = { 403: "a page's 403 sentence", default: "a page's default sentence" };

    answerWith({ kind: "forbidden" });
    await act(() => result.current.run(INCREMENT, { messages }));
    expect(result.current.error).toBe(FORBIDDEN_MESSAGE);

    answerWith({ kind: "csrf-expired" });
    await act(() => result.current.run(INCREMENT, { messages }));
    expect(result.current.error).toBe(CSRF_EXPIRED_MESSAGE);
  });

  it("lets a page's own copy for a status win over the hook's default", async () => {
    answerWith({ kind: "failed", status: 404 });
    const { result } = renderWrite();

    await act(() =>
      result.current.run(INCREMENT, {
        messages: { 404: "grace no longer exists. Reload the page for the current list." },
      }),
    );

    expect(result.current.error).toBe(
      "grace no longer exists. Reload the page for the current list.",
    );
  });

  it("lets a page's own default win over the hook's, for a status neither names", async () => {
    answerWith({ kind: "failed", status: 503 });
    const { result } = renderWrite();

    await act(() =>
      result.current.run(INCREMENT, {
        messages: { default: "Unable to update the counter. Please try again." },
      }),
    );

    expect(result.current.error).toBe("Unable to update the counter. Please try again.");
  });

  it.each([400, 404, 409])(
    "lets a page's own default win over the hook's own default for a %i refusal",
    async (status) => {
      answerWith({ kind: "failed", status });
      const { result } = renderWrite();

      await act(() =>
        result.current.run(INCREMENT, {
          messages: { default: "Unable to update the counter. Please try again." },
        }),
      );

      expect(result.current.error).toBe("Unable to update the counter. Please try again.");
    },
  );

  it("maps an undefined status to the hook's own last resort, not a page's per-status copy", async () => {
    answerWith({ kind: "failed" });
    const { result } = renderWrite();

    await act(() =>
      result.current.run(INCREMENT, {
        messages: { 400: "a 400-specific sentence that must not show here" },
      }),
    );

    expect(result.current.error).toBe("Unable to complete the action. Please try again.");
  });

  it("withdraws the superseded read's error the moment a write starts", async () => {
    const { result } = renderHarness();
    act(() => result.current.setReadError("stale failure"));
    expect(result.current.write.error).toBe("stale failure");
    const clearErrorSpy = result.current.clearError;
    const settle = deferredAnswer();

    let ran: Promise<void> = Promise.resolve();
    act(() => {
      ran = result.current.write.run(INCREMENT);
    });

    expect(clearErrorSpy).toHaveBeenCalledTimes(1);
    expect(result.current.write.error).toBeNull();

    await act(async () => {
      settle({ data: 1, kind: "ok" });
      await ran;
    });
  });

  it("falls back to a superseded read's current error before any write has run", () => {
    const { result } = renderHarness();

    act(() => result.current.setReadError("the mount read failed"));

    expect(result.current.write.error).toBe("the mount read failed");
  });

  it("shows its own refusal over a superseded read's stale one, with no `after` step", async () => {
    answerWith({ kind: "failed", status: 409 });
    const { result } = renderHarness();

    await act(() => result.current.write.run(INCREMENT));
    // The read was cleared on start and never touched again by this write, so
    // a later, unrelated change to it does not displace the write's own.
    act(() => result.current.setReadError("a read error unrelated to this write"));

    expect(result.current.write.error).toBe(
      "Refused: the request conflicts with the resource's current state.",
    );
  });

  it("lets a later `after` step's failure replace an earlier successful write's outcome", async () => {
    answerWith({ data: 1, kind: "ok" });
    const { result } = renderHarness();
    const { after, settle: settleAfter } = deferredAfter();

    let ran: Promise<void> = Promise.resolve();
    act(() => {
      ran = result.current.write.run(INCREMENT, { after });
    });

    await act(async () => {
      result.current.setReadError("the reload failed");
      settleAfter();
      await ran;
    });

    expect(result.current.write.error).toBe("the reload failed");
  });

  it("lets an earlier refusal stand when the later `after` step reports nothing new", async () => {
    answerWith({ kind: "failed", status: 400 });
    const { result } = renderHarness();

    await act(() =>
      result.current.write.run(INCREMENT, {
        after: () => Promise.resolve(),
        messages: { 400: "Refused: check the values and try again." },
      }),
    );

    expect(result.current.write.error).toBe("Refused: check the values and try again.");
  });

  it("prefers the latest of two failures: the `after` step's over the write's own", async () => {
    answerWith({ kind: "failed", status: 500 });
    const { result } = renderHarness();
    const { after, settle: settleAfter } = deferredAfter();

    let ran: Promise<void> = Promise.resolve();
    act(() => {
      ran = result.current.write.run(INCREMENT, { after });
    });

    await act(async () => {
      result.current.setReadError("the reload failed too, and that is the newer news");
      settleAfter();
      await ran;
    });

    expect(result.current.write.error).toBe("the reload failed too, and that is the newer news");
  });

  it("returns to its own precedence on a later write that supplies no `after`", async () => {
    answerWith({ data: 1, kind: "ok" });
    answerWith({ kind: "failed", status: 409 });
    const { result } = renderHarness();

    // First write passes `after`: the superseded read's error would lead.
    await act(() => result.current.write.run(INCREMENT, { after: () => Promise.resolve() }));
    // Second write passes none: its own refusal must lead again, even though
    // the read still carries an unrelated error from a moment ago.
    act(() => result.current.setReadError("a read error unrelated to this write"));
    await act(() => result.current.write.run(INCREMENT));

    expect(result.current.write.error).toBe(
      "Refused: the request conflicts with the resource's current state.",
    );
  });
});

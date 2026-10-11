import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { jsonDecoder, readObject } from "@/lib/decode";
import { apiFetch, CSRF_EXPIRED_MESSAGE, FORBIDDEN_MESSAGE } from "@/lib/http";

import type { Permission } from "./api";
import { AuthContext, type AuthContextState } from "./auth-context-value";
import type { WriteDeclaration } from "./declarations";
import { usePageStatus, useStatusReporter } from "./page-status";
import { useGatedWrite } from "./use-gated-write";

vi.mock("@/lib/http", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/http")>()),
  apiFetch: vi.fn(),
}));

const apiFetchMock = vi.mocked(apiFetch);

const decodeCount = jsonDecoder((body: unknown) =>
  readObject(body, "CountResponse").integer("count"),
);

/** The write most tests send: a counter increment answering the new count. */
const INCREMENT: WriteDeclaration<[], number> = {
  decode: decodeCount,
  method: "POST",
  path: () => "/api/count/increment",
  permission: "counter:write",
};

/** A write answering no content, its path built from its argument. */
const DELETE_CONNECTOR: WriteDeclaration<[id: string]> = {
  method: "DELETE",
  path: (id) => `/api/admin/connectors/${id}`,
  permission: "counter:write",
};

/** A write whose JSON body is built from its argument. */
const CREATE_CONNECTOR: WriteDeclaration<[displayName: string], number> = {
  body: (displayName) => ({ displayName }),
  decode: decodeCount,
  method: "POST",
  path: () => "/api/admin/connectors",
  permission: "counter:write",
};

/** A write sending an empty JSON object as its body. */
const ROTATE: WriteDeclaration = {
  body: () => ({}),
  method: "POST",
  path: () => "/api/x",
  permission: "counter:write",
};

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

/**
 * The write as a page holds it — reporting into the page's own status — beside
 * one other source on that status (a read, say), which a test reports through
 * to see how the two share the error line.
 */
function useHarness() {
  const status = usePageStatus();
  const other = useStatusReporter(status);
  const write = useGatedWrite({ status });
  return { error: status.error, other, pending: write.pending, run: write.run };
}

/** Renders the harness under a session holding exactly `permissions`. */
const renderWrite = (permissions: Permission[] = ["counter:write"]) =>
  renderHook(() => useHarness(), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <AuthContext.Provider value={authState(permissions)}>{children}</AuthContext.Provider>
    ),
  });

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

describe("useGatedWrite", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    expireSession.mockReset();
  });

  it("is not pending, with no error, before any write runs", () => {
    const { result } = renderWrite();

    expect(result.current).toMatchObject({ error: null, pending: false });
  });

  it("sends a write without a body as just its method, at the path built from its arguments, with no decoder", async () => {
    answerWith({ data: undefined, kind: "ok" });
    const { result } = renderWrite();

    await act(() => result.current.run(DELETE_CONNECTOR, { args: ["c-1"] }));

    expect(apiFetchMock.mock.calls).toEqual([["/api/admin/connectors/c-1", { method: "DELETE" }]]);
  });

  it("sends the body built from its arguments as JSON, with its decoder", async () => {
    answerWith({ data: 1, kind: "ok" });
    const { result } = renderWrite();

    await act(() => result.current.run(CREATE_CONNECTOR, { args: ["Okta"] }));

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

    await act(() => result.current.run(ROTATE));

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

  it("stays pending through its `after` step", async () => {
    answerWith({ data: 1, kind: "ok" });
    const { result } = renderWrite();
    let settleAfter!: () => void;
    const after = () =>
      new Promise<void>((resolve) => {
        settleAfter = resolve;
      });

    let ran: Promise<void> = Promise.resolve();
    await act(async () => {
      ran = result.current.run(INCREMENT, { after });
    });
    expect(result.current.pending).toBe(true);

    await act(async () => {
      settleAfter();
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

  it("sends nothing for a session lacking the declaration's Permission, and says so", async () => {
    const { result } = renderWrite(["counter:read"]);
    const onOk = vi.fn();

    await act(() => result.current.run(INCREMENT, { onOk }));

    expect(apiFetchMock).not.toHaveBeenCalled();
    expect(onOk).not.toHaveBeenCalled();
    expect(result.current.error).toBe(FORBIDDEN_MESSAGE);
  });

  it("still runs the `after` step for a write refused for want of its Permission", async () => {
    const { result } = renderWrite([]);
    const after = vi.fn(() => Promise.resolve());

    await act(() => result.current.run(INCREMENT, { after }));

    expect(after).toHaveBeenCalledOnce();
  });

  it("sends the write for a session holding its Permission", async () => {
    answerWith({ data: 5, kind: "ok" });
    const { result } = renderWrite(["counter:write"]);
    const onOk = vi.fn();

    await act(() => result.current.run(INCREMENT, { onOk }));

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

  it("withdraws every failure on the page status the moment a write starts", async () => {
    const { result } = renderWrite();
    act(() => result.current.other.report("the mount read failed"));
    const settle = deferredAnswer();

    let ran: Promise<void> = Promise.resolve();
    act(() => {
      ran = result.current.run(INCREMENT);
    });

    expect(result.current.error).toBeNull();
    await act(async () => {
      settle({ data: 1, kind: "ok" });
      await ran;
    });
  });

  it("withdraws its own earlier refusal once a later write succeeds", async () => {
    answerWith({ kind: "failed", status: 409 });
    answerWith({ data: 1, kind: "ok" });
    const { result } = renderWrite();

    await act(() => result.current.run(INCREMENT));
    await act(() => result.current.run(INCREMENT));

    expect(result.current.error).toBeNull();
  });

  it("lets a failure its `after` step reports replace the write's own, as the latest", async () => {
    answerWith({ kind: "failed", status: 500 });
    const { result } = renderWrite();

    await act(() =>
      result.current.run(INCREMENT, {
        after: async () => result.current.other.report("the reload failed too"),
      }),
    );

    expect(result.current.error).toBe("the reload failed too");
  });

  it("lets its refusal stand when its `after` step succeeds", async () => {
    answerWith({ kind: "failed", status: 400 });
    const { result } = renderWrite();

    await act(() =>
      result.current.run(INCREMENT, {
        after: async () => result.current.other.report(null),
      }),
    );

    expect(result.current.error).toBe("Refused: check the values and try again.");
  });
});

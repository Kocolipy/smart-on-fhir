import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { jsonDecoder, readObject } from "@/lib/decode";
import {
  bare,
  deferred,
  fakeBackend,
  json,
  UNREACHABLE,
  type Answers,
} from "@/lib/fake-backend.testHelpers";
import { CSRF_EXPIRED_MESSAGE, FORBIDDEN_MESSAGE } from "@/lib/http";

import type { Permission } from "./api";
import { AuthContext, type AuthContextState } from "./auth-context-value";
import { authState as buildAuthState, signedIn } from "./auth-context.testHelpers";
import type { WriteDeclaration } from "./declarations";
import { useStatusReporter } from "./page-status";
import { InPageStatus, shownLine } from "./page-status.testHelpers";
import { useGatedWrite } from "./use-gated-write";

// The hook reads the session's Permissions and drives `expireSession`, so it
// runs against a hand-built auth state a test states them in; the backend it
// writes to is the backend layer's.

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
  return buildAuthState(signedIn({ permissions }), { expireSession });
}

/**
 * The write as a page holds it — reporting into the page status around it —
 * beside one other source on that status (a read, say), which a test reports
 * through to see how the two share the error line.
 */
function useHarness() {
  const other = useStatusReporter();
  const write = useGatedWrite();
  return { other, pending: write.pending, run: write.run };
}

const INCREMENTED = "POST /api/count/increment";

/**
 * Renders the harness under a session holding exactly `permissions`, inside a
 * page status, over a backend answering `answers`.
 */
function renderWrite(answers: Answers = {}, permissions: Permission[] = ["counter:write"]) {
  const backend = fakeBackend(answers);
  const { result } = renderHook(() => useHarness(), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <AuthContext.Provider value={authState(permissions)}>
        <InPageStatus>{children}</InPageStatus>
      </AuthContext.Provider>
    ),
  });
  return { backend, result };
}

describe("useGatedWrite", () => {
  beforeEach(() => {
    expireSession.mockReset();
  });

  it("is not pending, with no error, before any write runs", () => {
    const { result } = renderWrite();

    expect({ error: shownLine(), pending: result.current.pending }).toEqual({
      error: null,
      pending: false,
    });
  });

  it("sends a write without a body as just its method, at the path built from its arguments, with no decoder", async () => {
    // No body to decode: a decoder would fail on it, and the write with it.
    const { backend, result } = renderWrite({ "DELETE /api/admin/connectors/c-1": bare(204) });
    const onOk = vi.fn();

    await act(() => result.current.run(DELETE_CONNECTOR, { args: ["c-1"], onOk }));

    expect({ ok: onOk.mock.calls, sent: backend.requests({ contentType: true }) }).toEqual({
      ok: [[undefined]],
      sent: [{ contentType: null, method: "DELETE", path: "/api/admin/connectors/c-1" }],
    });
  });

  it("sends the body built from its arguments as JSON, with its decoder", async () => {
    const { backend, result } = renderWrite({ "POST /api/admin/connectors": json({ count: 1 }) });
    const onOk = vi.fn();

    await act(() => result.current.run(CREATE_CONNECTOR, { args: ["Okta"], onOk }));

    expect({ ok: onOk.mock.calls, sent: backend.requests({ contentType: true }) }).toEqual({
      ok: [[1]],
      sent: [
        {
          body: { displayName: "Okta" },
          contentType: "application/json",
          method: "POST",
          path: "/api/admin/connectors",
        },
      ],
    });
  });

  it("sends an empty JSON object as a body, not as no body", async () => {
    const { backend, result } = renderWrite({ "POST /api/x": bare(204) });

    await act(() => result.current.run(ROTATE));

    expect({ line: shownLine(), sent: backend.requests({ contentType: true }) }).toEqual({
      line: null,
      sent: [{ body: {}, contentType: "application/json", method: "POST", path: "/api/x" }],
    });
  });

  it("is pending while the request is in flight, and not once it settles", async () => {
    const reply = deferred();
    const { result } = renderWrite({ [INCREMENTED]: reply.answer });

    let ran: Promise<void> = Promise.resolve();
    act(() => {
      ran = result.current.run(INCREMENT);
    });
    expect(result.current.pending).toBe(true);

    await reply.settle(json({ count: 1 }));
    await act(() => ran);
    expect(result.current.pending).toBe(false);
  });

  it("stays pending through its `after` step", async () => {
    const { result } = renderWrite({ [INCREMENTED]: json({ count: 1 }) });
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
    const { result } = renderWrite({ [INCREMENTED]: json({ count: 42 }) });
    const onOk = vi.fn();

    await act(() => result.current.run(INCREMENT, { onOk }));

    expect(onOk).toHaveBeenCalledTimes(1);
    expect(onOk).toHaveBeenCalledWith(42);
    expect(shownLine()).toBeNull();
  });

  it("ends the session once on a 401, and shows the page's failure copy meanwhile", async () => {
    const { result } = renderWrite({ [INCREMENTED]: bare(401) });
    const onOk = vi.fn();

    await act(() =>
      result.current.run(INCREMENT, { messages: { default: "Unable to update." }, onOk }),
    );

    expect(expireSession).toHaveBeenCalledOnce();
    expect(onOk).not.toHaveBeenCalled();
    expect(shownLine()).toBe("Unable to update.");
  });

  it("sends nothing for a session lacking the declaration's Permission, and says so", async () => {
    const { backend, result } = renderWrite({}, ["counter:read"]);
    const onOk = vi.fn();

    await act(() => result.current.run(INCREMENT, { onOk }));

    expect(backend.requests({ tokenFetches: true })).toEqual([]);
    expect(onOk).not.toHaveBeenCalled();
    expect(shownLine()).toBe(FORBIDDEN_MESSAGE);
  });

  it("still runs the `after` step for a write refused for want of its Permission", async () => {
    const { result } = renderWrite({}, []);
    const after = vi.fn(() => Promise.resolve());

    await act(() => result.current.run(INCREMENT, { after }));

    expect(after).toHaveBeenCalledOnce();
  });

  it("sends the write for a session holding its Permission", async () => {
    const { result } = renderWrite({ [INCREMENTED]: json({ count: 5 }) }, ["counter:write"]);
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
    const { result } = renderWrite({ [INCREMENTED]: bare(status) });

    await act(() => result.current.run(INCREMENT));

    expect(shownLine()).toBe(message);
  });

  it("maps forbidden and csrf-expired through the shared seam copy, not a page's own", async () => {
    const { backend, result } = renderWrite({ [INCREMENTED]: bare(403) });

    const messages = { 403: "a page's 403 sentence", default: "a page's default sentence" };

    await act(() => result.current.run(INCREMENT, { messages }));
    expect(shownLine()).toBe(FORBIDDEN_MESSAGE);

    // Refused again, and no fresh token to be had for the retry.
    backend.answer({ "GET /api/auth/csrf": bare(500) });
    await act(() => result.current.run(INCREMENT, { messages }));
    expect(shownLine()).toBe(CSRF_EXPIRED_MESSAGE);
  });

  it("lets a page's own copy for a status win over the hook's default", async () => {
    const { result } = renderWrite({ [INCREMENTED]: bare(404) });

    await act(() =>
      result.current.run(INCREMENT, {
        messages: { 404: "grace no longer exists. Reload the page for the current list." },
      }),
    );

    expect(shownLine()).toBe("grace no longer exists. Reload the page for the current list.");
  });

  it("lets a page's own default win over the hook's, for a status neither names", async () => {
    const { result } = renderWrite({ [INCREMENTED]: bare(503) });

    await act(() =>
      result.current.run(INCREMENT, {
        messages: { default: "Unable to update the counter. Please try again." },
      }),
    );

    expect(shownLine()).toBe("Unable to update the counter. Please try again.");
  });

  it.each([400, 404, 409])(
    "lets a page's own default win over the hook's own default for a %i refusal",
    async (status) => {
      const { result } = renderWrite({ [INCREMENTED]: bare(status) });

      await act(() =>
        result.current.run(INCREMENT, {
          messages: { default: "Unable to update the counter. Please try again." },
        }),
      );

      expect(shownLine()).toBe("Unable to update the counter. Please try again.");
    },
  );

  it("maps an undefined status to the hook's own last resort, not a page's per-status copy", async () => {
    // No answer at all, so no status either.
    const { result } = renderWrite({ [INCREMENTED]: UNREACHABLE });

    await act(() =>
      result.current.run(INCREMENT, {
        messages: { 400: "a 400-specific sentence that must not show here" },
      }),
    );

    expect(shownLine()).toBe("Unable to complete the action. Please try again.");
  });

  it("withdraws every failure on the page status the moment a write starts", async () => {
    const reply = deferred();
    const { result } = renderWrite({ [INCREMENTED]: reply.answer });
    act(() => result.current.other.report("the mount read failed"));

    let ran: Promise<void> = Promise.resolve();
    act(() => {
      ran = result.current.run(INCREMENT);
    });

    expect(shownLine()).toBeNull();
    await reply.settle(json({ count: 1 }));
    await act(() => ran);
  });

  it("withdraws its own earlier refusal once a later write succeeds", async () => {
    const { result } = renderWrite({ [INCREMENTED]: [bare(409), json({ count: 1 })] });

    await act(() => result.current.run(INCREMENT));
    await act(() => result.current.run(INCREMENT));

    expect(shownLine()).toBeNull();
  });

  it("lets a failure its `after` step reports replace the write's own, as the latest", async () => {
    const { result } = renderWrite({ [INCREMENTED]: bare(500) });

    await act(() =>
      result.current.run(INCREMENT, {
        after: async () => result.current.other.report("the reload failed too"),
      }),
    );

    expect(shownLine()).toBe("the reload failed too");
  });

  it("lets its refusal stand when its `after` step succeeds", async () => {
    const { result } = renderWrite({ [INCREMENTED]: bare(400) });

    await act(() =>
      result.current.run(INCREMENT, {
        after: async () => result.current.other.report(null),
      }),
    );

    expect(shownLine()).toBe("Refused: check the values and try again.");
  });
});

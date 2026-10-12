import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { jsonDecoder, readObject } from "@/lib/decode";
import { FORBIDDEN_MESSAGE } from "@/lib/http";

import type { Permission } from "./api";
import { AuthContext, type AuthContextState } from "./auth-context-value";
import type { ReadDeclaration } from "./declarations";
import { usePageStatus, useStatusReporter } from "./page-status";
import { useGatedRead } from "./use-gated-read";

const decodeCount = jsonDecoder((body: unknown) =>
  readObject(body, "CountResponse").integer("count"),
);

/** A read whose path is its one argument, so a test can move it. */
const COUNT_AT: ReadDeclaration<number, [path: string]> = {
  decode: decodeCount,
  path: (path) => path,
  permission: "counter:read",
};

/** A read naming no argument, as most listings are declared. */
const COUNT: ReadDeclaration<number> = {
  decode: decodeCount,
  path: () => "/api/count",
  permission: "counter:read",
};

const FAILURE = "Unable to load the counter. Please try again.";

/** Every request the hook sends, held open until the test answers it. */
let sent: { path: string; answer: (response: Response) => void }[];

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

interface Options {
  failureMessage: string;
  path: string;
}

/** The read as a page holds it: the hook, reporting into the page's own status. */
function useRead({ failureMessage, path }: Options) {
  const status = usePageStatus();
  const read = useGatedRead(COUNT_AT, { args: [path], failureMessage, status });
  return { ...read, error: status.error };
}

/**
 * Renders the hook under a session holding `permissions`. `withPermissions`
 * and `withOptions` re-render it as the session or the page would change it.
 */
function renderRead({
  options = {},
  permissions = ["counter:read"],
}: { options?: Partial<Options>; permissions?: Permission[] } = {}) {
  let auth = authState(permissions);
  let current: Options = { failureMessage: FAILURE, path: "/api/count", ...options };
  const wrapper = ({ children }: { children: ReactNode }) => (
    <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>
  );
  const hook = renderHook(({ options: given }: { options: Options }) => useRead(given), {
    initialProps: { options: current },
    wrapper,
  });
  return {
    result: hook.result,
    withOptions(change: Partial<Options>) {
      current = { ...current, ...change };
      hook.rerender({ options: current });
    },
    withPermissions(held: Permission[]) {
      auth = authState(held);
      hook.rerender({ options: current });
    },
  };
}

/** Answers the oldest open request. */
async function answer(response: Response) {
  const next = sent.shift();
  if (next === undefined) throw new Error("no request is open");
  await act(async () => next.answer(response));
}

/** Answers the newest open request, ahead of any older one still open. */
async function answerNewest(response: Response) {
  const next = sent.pop();
  if (next === undefined) throw new Error("no request is open");
  await act(async () => next.answer(response));
}

const count = (value: number) => Response.json({ count: value });

describe("useGatedRead", () => {
  beforeEach(() => {
    sent = [];
    expireSession.mockReset();
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (path: string) =>
          new Promise<Response>((resolve) => {
            sent.push({ answer: resolve, path });
          }),
      ),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("sends nothing and shows nothing without the Permission", async () => {
    const { result } = renderRead({ permissions: [] });
    await act(async () => {});

    expect(fetch).not.toHaveBeenCalled();
    expect(result.current).toMatchObject({ error: null, view: { kind: "not-permitted" } });
  });

  it("is loading, with nothing failed, until the read answers", () => {
    const { result } = renderRead();

    expect(sent.map((request) => request.path)).toEqual(["/api/count"]);
    expect(result.current).toMatchObject({ error: null, view: { kind: "loading" } });
  });

  it("reads the path its declaration builds from the arguments, with a plain GET", () => {
    renderRead({ options: { path: "/api/elsewhere" } });

    expect(fetch).toHaveBeenCalledTimes(1);
    const [path, init] = vi.mocked(fetch).mock.calls[0];
    expect(path).toBe("/api/elsewhere");
    expect(init?.method).toBeUndefined();
  });

  it("reads a declaration that takes no arguments without being handed any", async () => {
    const { result } = renderHook(
      () => useGatedRead(COUNT, { failureMessage: FAILURE, status: usePageStatus() }),
      {
        wrapper: ({ children }: { children: ReactNode }) => (
          <AuthContext.Provider value={authState(["counter:read"])}>
            {children}
          </AuthContext.Provider>
        ),
      },
    );

    expect(sent.map((request) => request.path)).toEqual(["/api/count"]);
    await answer(count(4));
    expect(result.current.view).toEqual({ data: 4, kind: "ready" });
  });

  it("shows the decoded data once the read succeeds", async () => {
    const { result } = renderRead();
    await answer(count(7));

    expect(result.current).toMatchObject({ error: null, view: { data: 7, kind: "ready" } });
  });

  it("shows the seam's copy for a refusal and keeps the session", async () => {
    const { result } = renderRead();
    await answer(new Response(null, { status: 403 }));

    expect(result.current).toMatchObject({ error: FORBIDDEN_MESSAGE, view: { kind: "failed" } });
    expect(expireSession).not.toHaveBeenCalled();
  });

  it("shows the page's failure copy for a plain failure", async () => {
    const { result } = renderRead();
    await answer(new Response(null, { status: 500 }));

    expect(result.current).toMatchObject({ error: FAILURE, view: { kind: "failed" } });
  });

  it("treats a success body that does not decode as a failure, not as data", async () => {
    const { result } = renderRead();
    await answer(Response.json({ count: "seven" }));

    expect(result.current).toMatchObject({ error: FAILURE, view: { kind: "failed" } });
  });

  it("ends the session on an unauthenticated read and reports a failure", async () => {
    const { result } = renderRead();
    await answer(new Response(null, { status: 401 }));

    expect(expireSession).toHaveBeenCalledTimes(1);
    expect(result.current).toMatchObject({ error: FAILURE, view: { kind: "failed" } });
  });

  it("keeps the last data as stale when a re-read is refused, and is ready again when one succeeds", async () => {
    const { result } = renderRead();
    await answer(count(3));

    let reloaded: Promise<void> = Promise.resolve();
    act(() => {
      reloaded = result.current.reload();
    });
    expect(sent.map((request) => request.path)).toEqual(["/api/count"]);
    // A re-read is not a first load: what was read stays on screen meanwhile.
    expect(result.current.view).toEqual({ data: 3, kind: "ready" });
    await answer(new Response(null, { status: 403 }));
    await act(() => reloaded);
    expect(result.current).toMatchObject({
      error: FORBIDDEN_MESSAGE,
      view: { data: 3, kind: "stale" },
    });

    act(() => {
      reloaded = result.current.reload();
    });
    await answer(count(4));
    await act(() => reloaded);
    expect(result.current).toMatchObject({ error: null, view: { data: 4, kind: "ready" } });
  });

  describe("when an older read answers after a newer one", () => {
    it("discards the mount read's answer once a re-read has superseded it", async () => {
      const { result } = renderRead();
      act(() => void result.current.reload());

      await answerNewest(count(2));
      await answer(count(1)); // the mount read, answering last

      expect(result.current.view).toEqual({ data: 2, kind: "ready" });
    });

    it("discards a superseded read's failure from the error line", async () => {
      const { result } = renderRead();
      act(() => void result.current.reload());

      await answerNewest(count(2));
      await answer(new Response(null, { status: 500 })); // the mount read, answering last

      expect(result.current.error).toBeNull();
    });

    it("lands only the newest of two re-reads", async () => {
      const { result } = renderRead();
      await answer(count(1));
      act(() => void result.current.reload());
      act(() => void result.current.reload());

      await answerNewest(count(3));
      await answer(count(2)); // the first re-read, answering last

      expect(result.current.view).toEqual({ data: 3, kind: "ready" });
    });

    it("sends every re-read rather than joining the one in flight", async () => {
      const { result } = renderRead();
      act(() => void result.current.reload());

      expect(sent.map((request) => request.path)).toEqual(["/api/count", "/api/count"]);
    });
  });

  it("settles a re-read's promise once its own answer is in", async () => {
    const { result } = renderRead();
    await answer(count(1));
    let settled = false;
    act(() => {
      void result.current.reload().then(() => {
        settled = true;
      });
    });
    await act(async () => {});
    expect(settled).toBe(false);

    await answer(count(2));

    expect(settled).toBe(true);
  });

  it("settles, sending nothing, a re-read asked for as the Permission is withdrawn", async () => {
    const hook = renderRead();
    await answer(count(1));
    let settled = false;
    act(() => {
      void hook.result.current.reload().then(() => {
        settled = true;
      });
      hook.withPermissions([]);
    });
    await act(async () => {});

    expect({ sent: vi.mocked(fetch).mock.calls.length, settled }).toEqual({
      sent: 1,
      settled: true,
    });
  });

  it("does not re-read while gated off", async () => {
    const { result } = renderRead({ permissions: [] });

    await act(() => result.current.reload());

    expect(fetch).not.toHaveBeenCalled();
    expect(result.current.view).toEqual({ kind: "not-permitted" });
  });

  it("replaces the data locally", async () => {
    const { result } = renderRead();
    await answer(count(3));

    act(() => result.current.update((current) => (current ?? 0) + 10));

    expect(result.current.view).toEqual({ data: 13, kind: "ready" });
  });

  it("holds data a response states as read while the first read is in flight", () => {
    const { result } = renderRead();

    act(() => result.current.update(() => 6));

    expect(result.current.view).toEqual({ data: 6, kind: "ready" });
  });

  it("holds data a later response states as stale after a failed first read", async () => {
    const { result } = renderRead();
    await answer(new Response(null, { status: 500 }));

    act(() => result.current.update((current) => (current ?? 0) + 10));

    expect(result.current.view).toEqual({ data: 10, kind: "stale" });
  });

  it("resets and asks nothing when the Permission is withdrawn", async () => {
    const hook = renderRead();
    await answer(new Response(null, { status: 500 }));

    hook.withPermissions([]);

    expect(hook.result.current).toMatchObject({ error: null, view: { kind: "not-permitted" } });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("sends no re-read once the Permission is withdrawn", async () => {
    const hook = renderRead();
    await answer(count(1));

    hook.withPermissions([]);
    await act(() => hook.result.current.reload());

    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("reads afresh when the Permission is granted", async () => {
    const hook = renderRead({ permissions: [] });

    hook.withPermissions(["counter:read"]);
    expect(hook.result.current.view).toEqual({ kind: "loading" });
    await answer(count(5));

    expect(hook.result.current.view).toEqual({ data: 5, kind: "ready" });
  });

  it("drops an answer that lands after the Permission was withdrawn", async () => {
    const hook = renderRead();

    hook.withPermissions([]);
    await answer(count(9));

    expect(hook.result.current.view).toEqual({ kind: "not-permitted" });
  });

  it("resets to loading and reads the new path when the path changes", async () => {
    const hook = renderRead();
    await answer(new Response(null, { status: 500 }));

    hook.withOptions({ path: "/api/other" });

    expect(hook.result.current).toMatchObject({ error: null, view: { kind: "loading" } });
    expect(sent.map((request) => request.path)).toEqual(["/api/other"]);
    await answer(count(2));
    expect(hook.result.current.view).toEqual({ data: 2, kind: "ready" });
  });

  it("drops the old path's answer once the path has moved", async () => {
    const hook = renderRead();

    hook.withOptions({ path: "/api/other" });
    await answer(count(1)); // the old path's
    expect(hook.result.current.view).toEqual({ kind: "loading" });

    await answer(count(2)); // the new path's
    expect(hook.result.current.view).toEqual({ data: 2, kind: "ready" });
  });

  it("drops a re-read's answer once the path has moved", async () => {
    const hook = renderRead();
    await answer(count(1));

    let reloaded: Promise<void> = Promise.resolve();
    act(() => {
      reloaded = hook.result.current.reload();
    });
    hook.withOptions({ path: "/api/other" });
    await answer(count(8)); // the re-read of the old path
    await act(() => reloaded);

    expect(hook.result.current.view).toEqual({ kind: "loading" });
    expect(sent.map((request) => request.path)).toEqual(["/api/other"]);
  });

  it("re-reads the current path after the path has moved", async () => {
    const hook = renderRead();
    await answer(count(1));
    hook.withOptions({ path: "/api/other" });
    await answer(count(2));

    let reloaded: Promise<void> = Promise.resolve();
    act(() => {
      reloaded = hook.result.current.reload();
    });
    expect(sent.map((request) => request.path)).toEqual(["/api/other"]);
    await answer(count(3));
    await act(() => reloaded);
    expect(hook.result.current.view).toEqual({ data: 3, kind: "ready" });
  });

  it("reports a refusal with the failure copy current when it lands", async () => {
    const hook = renderRead();
    await answer(count(1));
    const failureMessage = "Unable to load the tally.";

    hook.withOptions({ failureMessage });
    await answer(new Response(null, { status: 500 }));

    expect(hook.result.current).toMatchObject({
      error: failureMessage,
      view: { data: 1, kind: "stale" },
    });
  });

  it("leaves another source's failure on the page status when its own read succeeds", async () => {
    const { result } = renderHook(
      () => {
        const status = usePageStatus();
        const other = useStatusReporter(status);
        const read = useGatedRead(COUNT, { failureMessage: FAILURE, status });
        return { error: status.error, other, read };
      },
      {
        wrapper: ({ children }: { children: ReactNode }) => (
          <AuthContext.Provider value={authState(["counter:read"])}>
            {children}
          </AuthContext.Provider>
        ),
      },
    );

    act(() => result.current.other.report("Something else failed."));
    await answer(count(1));

    expect(result.current.error).toBe("Something else failed.");
  });
});

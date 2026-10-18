import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { jsonDecoder, readObject } from "@/lib/decode";
import {
  bare,
  deferred,
  fakeBackend,
  json,
  type Deferred,
  type FakeBackend,
  type WireAnswer,
} from "@/lib/fake-backend.testHelpers";
import { FORBIDDEN_MESSAGE } from "@/lib/http";

import type { Permission } from "./api";
import { AuthContext, type AuthContextState } from "./auth-context-value";
import { authState as buildAuthState, signedIn } from "./auth-context.testHelpers";
import type { ReadDeclaration } from "./declarations";
import { useStatusReporter } from "./page-status";
import { InPageStatus, shownLine } from "./page-status.testHelpers";
import { useGatedRead, type GatedRead } from "./use-gated-read";

// The hook reads the session's Permissions, and a test withdraws one mid-read,
// so it runs against a hand-built auth state; its reads go to the backend layer.

const decodeCount = jsonDecoder((body: unknown) =>
  readObject(body, "CountResponse").integer("count"),
);

/** A read whose path is its one argument, so a test can move it. */
const COUNT_AT: ReadDeclaration<number, [path: string]> = {
  decode: decodeCount,
  path: (path) => path,
  permission: "counter:read",
};

/** The same read, declared to treat a `404` as nothing there rather than as a failure. */
const COUNT_OR_ABSENT_AT: ReadDeclaration<number, [path: string], true> = {
  ...COUNT_AT,
  absentOnNotFound: true,
};

/** A read naming no argument, as most listings are declared. */
const COUNT: ReadDeclaration<number> = {
  decode: decodeCount,
  path: () => "/api/count",
  permission: "counter:read",
};

const FAILURE = "Unable to load the counter. Please try again.";

/**
 * The backend's replies, one per request in the order the hook sends them,
 * each held open until the test settles it.
 */
let replies: Deferred[];
/** How many replies each test holds: more than any test sends requests, so none repeats. */
const REPLIES_HELD = 20;
/** Which replies the test has settled, by the request's place in the order sent. */
let settled: Set<number>;
let backend: FakeBackend;

/** The requests sent and not yet answered, oldest first, by their place in the order sent. */
const open = () =>
  backend
    .requests()
    .map((_, place) => place)
    .filter((place) => !settled.has(place));

/** The paths of the requests still open, oldest first. */
const openPaths = () => open().map((place) => backend.requests()[place]!.path);

const expireSession = vi.fn();

function authState(permissions: Permission[]): AuthContextState {
  return buildAuthState(signedIn({ permissions }), { expireSession });
}

interface Options {
  failureMessage: string;
  path: string;
}

/** The read as a page holds it, reporting into the page status around it. */
function useRead(
  declaration: ReadDeclaration<number, [path: string], boolean>,
  { failureMessage, path }: Options,
) {
  return useGatedRead(declaration, { args: [path], failureMessage });
}

/** What the page shows for the read: the line the page status draws, and the read's view. */
const shown = (result: { current: GatedRead<number, boolean> }) => ({
  error: shownLine(),
  view: result.current.view,
});

/** A session holding `permissions`, and the page status a read reports to. */
const inSession =
  (permissions: Permission[]) =>
  ({ children }: { children: ReactNode }) => (
    <AuthContext.Provider value={authState(permissions)}>
      <InPageStatus>{children}</InPageStatus>
    </AuthContext.Provider>
  );

/**
 * Renders the hook under a session holding `permissions`. `withPermissions`
 * and `withOptions` re-render it as the session or the page would change it.
 */
function renderRead({
  declaration = COUNT_AT,
  options = {},
  permissions = ["counter:read"],
}: {
  declaration?: ReadDeclaration<number, [path: string], boolean>;
  options?: Partial<Options>;
  permissions?: Permission[];
} = {}) {
  let auth = authState(permissions);
  let current: Options = { failureMessage: FAILURE, path: "/api/count", ...options };
  const wrapper = ({ children }: { children: ReactNode }) => (
    <AuthContext.Provider value={auth}>
      <InPageStatus>{children}</InPageStatus>
    </AuthContext.Provider>
  );
  const hook = renderHook(
    ({ options: given }: { options: Options }) => useRead(declaration, given),
    {
      initialProps: { options: current },
      wrapper,
    },
  );
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

/** Answers the request at `place` in the order sent. */
async function answerAt(place: number | undefined, given: WireAnswer) {
  if (place === undefined) throw new Error("no request is open");
  settled.add(place);
  await replies[place]!.settle(given);
}

/** Answers the oldest open request. */
const answer = (given: WireAnswer) => answerAt(open()[0], given);

/** Answers the newest open request, ahead of any older one still open. */
const answerNewest = (given: WireAnswer) => {
  const places = open();
  return answerAt(places[places.length - 1], given);
};

const count = (value: number) => json({ count: value });

describe("useGatedRead", () => {
  beforeEach(() => {
    expireSession.mockReset();
    replies = Array.from({ length: REPLIES_HELD }, deferred);
    settled = new Set();
    backend = fakeBackend({ "GET /api/*": replies.map((reply) => reply.answer) });
  });

  it("sends nothing and shows nothing without the Permission", async () => {
    const { result } = renderRead({ permissions: [] });
    await act(async () => {});

    expect(backend.requests()).toEqual([]);
    expect(shown(result)).toMatchObject({ error: null, view: { kind: "not-permitted" } });
  });

  it("is loading, with nothing failed, until the read answers", () => {
    const { result } = renderRead();

    expect(openPaths()).toEqual(["/api/count"]);
    expect(shown(result)).toMatchObject({ error: null, view: { kind: "loading" } });
  });

  it("reads the path its declaration builds from the arguments, with a plain GET", () => {
    renderRead({ options: { path: "/api/elsewhere" } });

    expect(backend.requests()).toEqual([{ method: "GET", path: "/api/elsewhere" }]);
  });

  it("reads a declaration that takes no arguments without being handed any", async () => {
    const { result } = renderHook(() => useGatedRead(COUNT, { failureMessage: FAILURE }), {
      wrapper: inSession(["counter:read"]),
    });

    expect(openPaths()).toEqual(["/api/count"]);
    await answer(count(4));
    expect(result.current.view).toEqual({ data: 4, kind: "ready" });
  });

  it("shows the decoded data once the read succeeds", async () => {
    const { result } = renderRead();
    await answer(count(7));

    expect(shown(result)).toMatchObject({ error: null, view: { data: 7, kind: "ready" } });
  });

  it("shows the seam's copy for a refusal and keeps the session", async () => {
    const { result } = renderRead();
    await answer(bare(403));

    expect(shown(result)).toMatchObject({ error: FORBIDDEN_MESSAGE, view: { kind: "failed" } });
    expect(expireSession).not.toHaveBeenCalled();
  });

  it("shows the page's failure copy for a plain failure", async () => {
    const { result } = renderRead();
    await answer(bare(500));

    expect(shown(result)).toMatchObject({ error: FAILURE, view: { kind: "failed" } });
  });

  it("treats a success body that does not decode as a failure, not as data", async () => {
    const { result } = renderRead();
    await answer(json({ count: "seven" }));

    expect(shown(result)).toMatchObject({ error: FAILURE, view: { kind: "failed" } });
  });

  it("ends the session on an unauthenticated read and reports a failure", async () => {
    const { result } = renderRead();
    await answer(bare(401));

    expect(expireSession).toHaveBeenCalledTimes(1);
    expect(shown(result)).toMatchObject({ error: FAILURE, view: { kind: "failed" } });
  });

  it("keeps the last data as stale when a re-read is refused, and is ready again when one succeeds", async () => {
    const { result } = renderRead();
    await answer(count(3));

    let reloaded: Promise<void> = Promise.resolve();
    act(() => {
      reloaded = result.current.reload();
    });
    expect(openPaths()).toEqual(["/api/count"]);
    // A re-read is not a first load: what was read stays on screen meanwhile.
    expect(result.current.view).toEqual({ data: 3, kind: "ready" });
    await answer(bare(403));
    await act(() => reloaded);
    expect(shown(result)).toMatchObject({
      error: FORBIDDEN_MESSAGE,
      view: { data: 3, kind: "stale" },
    });

    act(() => {
      reloaded = result.current.reload();
    });
    await answer(count(4));
    await act(() => reloaded);
    expect(shown(result)).toMatchObject({ error: null, view: { data: 4, kind: "ready" } });
  });

  it("fails a read that is not found when its declaration does not opt in", async () => {
    const { result } = renderRead();
    await answer(bare(404));

    expect(shown(result)).toMatchObject({ error: FAILURE, view: { kind: "failed" } });
  });

  describe("when the declaration treats not found as absent", () => {
    const renderAbsentRead = () => renderRead({ declaration: COUNT_OR_ABSENT_AT });

    it("shows a read that is not found as absent, reporting nothing", async () => {
      const { result } = renderAbsentRead();
      await answer(bare(404));

      expect(shown(result)).toEqual({ error: null, view: { kind: "absent" } });
    });

    it("is ready once a re-read finds what was absent", async () => {
      const { result } = renderAbsentRead();
      await answer(bare(404));

      act(() => void result.current.reload());
      await answer(count(2));

      expect(shown(result)).toEqual({ error: null, view: { data: 2, kind: "ready" } });
    });

    it("is absent, keeping nothing as stale, once a re-read finds what was read gone", async () => {
      const { result } = renderAbsentRead();
      await answer(count(3));

      act(() => void result.current.reload());
      await answer(bare(404));

      expect(shown(result)).toEqual({ error: null, view: { kind: "absent" } });
    });

    it("clears an earlier failure's line once a re-read finds nothing there", async () => {
      const { result } = renderAbsentRead();
      await answer(bare(500));

      act(() => void result.current.reload());
      await answer(bare(404));

      expect(shown(result)).toEqual({ error: null, view: { kind: "absent" } });
    });

    it("keeps the absent view's answer only while it is the newest read", async () => {
      const { result } = renderAbsentRead();
      act(() => void result.current.reload());

      await answerNewest(count(2));
      await answer(bare(404)); // the mount read, answering last

      expect(result.current.view).toEqual({ data: 2, kind: "ready" });
    });

    it.each([
      ["a refusal", bare(403), FORBIDDEN_MESSAGE],
      ["a plain failure", bare(500), FAILURE],
      ["an unauthenticated read", bare(401), FAILURE],
      ["a success body that does not decode", json({ count: "seven" }), FAILURE],
    ])("still fails %s", async (_, given, line) => {
      const { result } = renderAbsentRead();
      await answer(given);

      expect(shown(result)).toEqual({ error: line, view: { kind: "failed" } });
    });

    it("keeps the last data as stale when a re-read fails other than not found", async () => {
      const { result } = renderAbsentRead();
      await answer(count(3));

      act(() => void result.current.reload());
      await answer(bare(500));

      expect(shown(result)).toEqual({ error: FAILURE, view: { data: 3, kind: "stale" } });
    });

    it("holds data a later response states as read after finding nothing there", async () => {
      const { result } = renderAbsentRead();
      await answer(bare(404));

      act(() => result.current.update((current) => (current ?? 0) + 10));

      expect(result.current.view).toEqual({ data: 10, kind: "ready" });
    });
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
      await answer(bare(500)); // the mount read, answering last

      expect(shownLine()).toBeNull();
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

      expect(openPaths()).toEqual(["/api/count", "/api/count"]);
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

    expect({ sent: backend.requests().length, settled }).toEqual({
      sent: 1,
      settled: true,
    });
  });

  it("does not re-read while gated off", async () => {
    const { result } = renderRead({ permissions: [] });

    await act(() => result.current.reload());

    expect(backend.requests()).toEqual([]);
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
    await answer(bare(500));

    act(() => result.current.update((current) => (current ?? 0) + 10));

    expect(result.current.view).toEqual({ data: 10, kind: "stale" });
  });

  it("resets and asks nothing when the Permission is withdrawn", async () => {
    const hook = renderRead();
    await answer(bare(500));

    hook.withPermissions([]);

    expect(shown(hook.result)).toMatchObject({ error: null, view: { kind: "not-permitted" } });
    expect(backend.requests()).toHaveLength(1);
  });

  it("sends no re-read once the Permission is withdrawn", async () => {
    const hook = renderRead();
    await answer(count(1));

    hook.withPermissions([]);
    await act(() => hook.result.current.reload());

    expect(backend.requests()).toHaveLength(1);
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
    await answer(bare(500));

    hook.withOptions({ path: "/api/other" });

    expect(shown(hook.result)).toMatchObject({ error: null, view: { kind: "loading" } });
    expect(openPaths()).toEqual(["/api/other"]);
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
    expect(openPaths()).toEqual(["/api/other"]);
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
    expect(openPaths()).toEqual(["/api/other"]);
    await answer(count(3));
    await act(() => reloaded);
    expect(hook.result.current.view).toEqual({ data: 3, kind: "ready" });
  });

  it("reports a refusal with the failure copy current when it lands", async () => {
    const hook = renderRead();
    await answer(count(1));
    const failureMessage = "Unable to load the tally.";

    hook.withOptions({ failureMessage });
    await answer(bare(500));

    expect(shown(hook.result)).toMatchObject({
      error: failureMessage,
      view: { data: 1, kind: "stale" },
    });
  });

  it("leaves another source's failure on the page status when its own read succeeds", async () => {
    const { result } = renderHook(
      () => {
        const other = useStatusReporter();
        const read = useGatedRead(COUNT, { failureMessage: FAILURE });
        return { other, read };
      },
      { wrapper: inSession(["counter:read"]) },
    );

    act(() => result.current.other.report("Something else failed."));
    await answer(count(1));

    expect(shownLine()).toBe("Something else failed.");
  });
});

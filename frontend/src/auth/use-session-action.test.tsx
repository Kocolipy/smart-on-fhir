import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import {
  bare,
  deferred,
  fakeBackend,
  json,
  type Answers,
  type WireAnswer,
} from "@/lib/fake-backend.testHelpers";

import { AuthProvider } from "./auth-context";
import { AUTH_REQUESTS, testUser } from "./auth-context.testHelpers";
import { useAuthState } from "./auth-context-value";
import { useStatusReporter } from "./page-status";
import { InPageStatus, shownLine } from "./page-status.testHelpers";
import {
  useSessionAction,
  type SessionActionName,
  type SessionActionResult,
} from "./use-session-action";

// A session action's outcome is what the backend answered, so the hook runs
// under the real `AuthProvider` over the backend layer: each test states the
// answer, and the action's own classification turns it into the outcome the
// hook reads.

const { change: CHANGE, login: LOGIN, logout: LOGOUT, me: ME, token: TOKEN } = AUTH_REQUESTS;

/** An answer that never comes, so the action stays out. */
const NEVER = new Promise<WireAnswer>(() => undefined);

const inProvider = ({ children }: { children: ReactNode }) => (
  <AuthProvider>{children}</AuthProvider>
);

/**
 * Renders the named session action for a signed-in session over a backend
 * answering `answers`, once the session check has settled.
 */
async function renderAction<N extends SessionActionName>(name: N, answers: Answers = {}) {
  const backend = fakeBackend({ [ME]: json(testUser()), ...answers });
  const hook = renderHook(
    () => ({ action: useSessionAction(name), status: useAuthState().status }),
    { wrapper: inProvider },
  );
  await waitFor(() => expect(hook.result.current.status).toBe("authenticated"));
  return { backend, result: hook.result };
}

type Rendered = Awaited<ReturnType<typeof renderAction>>["result"];

/**
 * Runs whichever action `renderAction` rendered with the arguments any of the
 * three takes, for the tables that span all three: the union of the three
 * signatures is not callable without a cast.
 */
function runAny(result: Rendered) {
  return (result.current.action.run as (...args: string[]) => Promise<SessionActionResult>)(
    "old-value",
    "new-value",
  );
}

describe("useSessionAction", () => {
  describe("the pending window", () => {
    it("is pending while the action is out", async () => {
      const { result } = await renderAction("logout", { [LOGOUT]: NEVER });

      await act(async () => {
        void result.current.action.run();
      });

      expect(result.current.action.pending).toBe(true);
    });

    it("is pending no longer once the action settles", async () => {
      const reply = deferred();
      const { result } = await renderAction("logout", { [LOGOUT]: reply.answer });
      await act(async () => {
        void result.current.action.run();
      });

      await reply.settle(bare(500));

      expect(result.current.action.pending).toBe(false);
    });

    it("starts idle", async () => {
      const { result } = await renderAction("logout");

      expect(result.current.action).toMatchObject({ error: null, pending: false });
    });
  });

  it("hands the action the arguments it was run with", async () => {
    const { backend, result } = await renderAction("login", { [LOGIN]: bare(401) });

    const [username, password] = ["ada", "secret-value"];

    await act(() => result.current.action.run(username, password));

    expect(backend.requests().filter(({ method }) => method === "POST")).toEqual([
      {
        body: { password, username },
        method: "POST",
        path: "/api/auth/login",
      },
    ]);
  });

  describe("an outcome that leaves the visitor on the page", () => {
    it("is the one line of copy the page shows", async () => {
      const { result } = await renderAction("logout", { [LOGOUT]: bare(500) });

      await act(() => result.current.action.run());

      expect(result.current.action.error).toBe(
        "Unable to sign out. Please try again. You are still signed in.",
      );
    });

    it("resolves the run as one that left the visitor on the page", async () => {
      const { result } = await renderAction("logout", { [LOGOUT]: bare(500) });

      let settled: SessionActionResult | undefined;
      await act(async () => {
        settled = await result.current.action.run();
      });

      expect(settled).toBe("stayed");
    });

    it("is withdrawn as soon as the next run starts", async () => {
      const { result } = await renderAction("logout", { [LOGOUT]: [bare(500), NEVER] });
      await act(() => result.current.action.run());

      await act(async () => {
        void result.current.action.run();
      });

      expect(result.current.action.error).toBeNull();
    });

    // Which line each outcome gets is `session-action-copy.test.ts`'s table;
    // these prove each action reads its own.
    it.each<[string, Answers, SessionActionName, string]>([
      [
        "a refused login",
        { [LOGIN]: bare(401) },
        "login",
        "The username or password is incorrect.",
      ],
      [
        "a CSRF-expired logout",
        { [TOKEN]: bare(500) },
        "logout",
        "Your security token expired. Please try again. You are still signed in.",
      ],
      [
        // The session survives the 401, so the check that follows it still answers.
        "a password change with the wrong current password",
        { [CHANGE]: bare(401) },
        "changePassword",
        "The current password is incorrect.",
      ],
    ])("says what %s came to", async (_name, answers, name, line) => {
      const { result } = await renderAction(name, answers);

      await act(() => runAny(result));

      expect(result.current.action.error).toBe(line);
    });
  });

  describe("an outcome that ends or starts a session", () => {
    const moving: [string, Answers, SessionActionName][] = [
      ["a login that signed in", { [LOGIN]: json(testUser()) }, "login"],
      ["a logout that signed out", { [LOGOUT]: bare(204) }, "logout"],
      ["a password change that changed it", { [CHANGE]: bare(204) }, "changePassword"],
      [
        // The 401 ended the session too, so the check that follows it is refused.
        "a password change that locked the account",
        { [CHANGE]: bare(401), [ME]: [json(testUser()), bare(401)] },
        "changePassword",
      ],
    ];

    it.each(moving)("shows nothing for %s: the guards move the visitor on", async (_n, a, name) => {
      const { result } = await renderAction(name, a);

      await act(() => runAny(result));

      expect(result.current.action).toMatchObject({ error: null, pending: false });
    });

    it.each(moving)("resolves %s as one that moved the visitor on", async (_n, a, name) => {
      const { result } = await renderAction(name, a);

      let settled: SessionActionResult | undefined;
      await act(async () => {
        settled = await runAny(result);
      });

      expect(settled).toBe("moved-on");
    });

    it("withdraws an earlier refusal's line", async () => {
      const { result } = await renderAction("logout", { [LOGOUT]: [bare(500), bare(204)] });
      await act(() => result.current.action.run());

      await act(() => result.current.action.run());

      expect(result.current.action.error).toBeNull();
    });
  });

  /**
   * Inside the signed-in layout the action is handed a reporter on the page
   * status, and its line goes there, under the status's latest-event rule,
   * beside whatever else on the page reports.
   */
  describe("reporting to a page status", () => {
    const SIGN_OUT_FAILED = "Unable to sign out. Please try again. You are still signed in.";

    /** Sign out reporting to a page status, beside one other source on it. */
    async function renderReporting(logout: Answers[string]) {
      fakeBackend({ [LOGOUT]: logout, [ME]: json(testUser()) });
      const { result } = renderHook(
        () => {
          const other = useStatusReporter();
          const signOut = useSessionAction("logout", useStatusReporter());
          return { other, signOut, status: useAuthState().status };
        },
        {
          wrapper: ({ children }: { children: ReactNode }) => (
            <AuthProvider>
              <InPageStatus>{children}</InPageStatus>
            </AuthProvider>
          ),
        },
      );
      await waitFor(() => expect(result.current.status).toBe("authenticated"));
      return result;
    }

    it("shows a refusal's line on the page status", async () => {
      const result = await renderReporting(bare(500));

      await act(() => result.current.signOut.run());

      expect(shownLine()).toBe(SIGN_OUT_FAILED);
    });

    it("shows nothing on the page status for an outcome that moved the visitor on", async () => {
      const result = await renderReporting(bare(204));

      await act(() => result.current.signOut.run());

      expect(shownLine()).toBeNull();
    });

    it("withdraws only its own line as the next run starts, so another source's shows again", async () => {
      const result = await renderReporting([bare(500), NEVER]);
      act(() => result.current.other.report("The listing failed."));
      await act(() => result.current.signOut.run());

      await act(async () => {
        void result.current.signOut.run();
      });

      expect(shownLine()).toBe("The listing failed.");
    });
  });
});

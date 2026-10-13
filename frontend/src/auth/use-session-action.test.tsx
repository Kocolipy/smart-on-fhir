import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { LogoutOutcome } from "./api";
import { AuthContext, type AuthContextState } from "./auth-context-value";
import { authState, signedIn } from "./auth-context.testHelpers";
import { useStatusReporter } from "./page-status";
import { InPageStatus, shownLine } from "./page-status.testHelpers";
import {
  useSessionAction,
  type SessionActionName,
  type SessionActionResult,
} from "./use-session-action";

type Actions = Pick<AuthContextState, SessionActionName>;

/** Renders the named session action under a session whose actions are `actions`. */
function renderAction<N extends SessionActionName>(name: N, actions: Partial<Actions>) {
  const value = authState(signedIn(), actions);
  return renderHook(() => useSessionAction(name), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
    ),
  });
}

/**
 * Runs whichever action `renderAction` rendered with no arguments, for the
 * tables that span all three: the doubles take none, but the union of the three
 * signatures is not callable without them.
 */
function runAny(result: ReturnType<typeof renderAction>["result"]) {
  return (result.current.run as () => Promise<SessionActionResult>)();
}

/** An outcome the test settles by hand, so the pending window can be observed. */
function deferred<T>() {
  let settle: (value: T) => void = () => undefined;
  const promise = new Promise<T>((resolve) => {
    settle = resolve;
  });
  return { promise, settle };
}

describe("useSessionAction", () => {
  describe("the pending window", () => {
    it("is pending while the action is out", async () => {
      const outcome = deferred<LogoutOutcome>();
      const { result } = renderAction("logout", { logout: vi.fn(() => outcome.promise) });

      await act(async () => {
        void result.current.run();
      });

      expect(result.current.pending).toBe(true);
    });

    it("is pending no longer once the action settles", async () => {
      const outcome = deferred<LogoutOutcome>();
      const { result } = renderAction("logout", { logout: vi.fn(() => outcome.promise) });
      await act(async () => {
        void result.current.run();
      });

      await act(async () => outcome.settle({ kind: "failed" }));

      expect(result.current.pending).toBe(false);
    });

    it("starts idle", () => {
      const { result } = renderAction("logout", {});

      expect(result.current).toMatchObject({ error: null, pending: false });
    });
  });

  it("hands the action the arguments it was run with", async () => {
    const login = vi.fn<Actions["login"]>().mockResolvedValue({ kind: "refused" });
    const { result } = renderAction("login", { login });

    await act(() => result.current.run("ada", "secret-value"));

    expect(login.mock.calls).toEqual([["ada", "secret-value"]]);
  });

  describe("an outcome that leaves the visitor on the page", () => {
    it("is the one line of copy the page shows", async () => {
      const { result } = renderAction("logout", {
        logout: vi.fn<Actions["logout"]>().mockResolvedValue({ kind: "failed" }),
      });

      await act(() => result.current.run());

      expect(result.current.error).toBe(
        "Unable to sign out. Please try again. You are still signed in.",
      );
    });

    it("resolves the run as one that left the visitor on the page", async () => {
      const { result } = renderAction("logout", {
        logout: vi.fn<Actions["logout"]>().mockResolvedValue({ kind: "failed" }),
      });

      let settled: SessionActionResult | undefined;
      await act(async () => {
        settled = await result.current.run();
      });

      expect(settled).toBe("stayed");
    });

    it("is withdrawn as soon as the next run starts", async () => {
      const logout = vi
        .fn<Actions["logout"]>()
        .mockResolvedValueOnce({ kind: "failed" })
        .mockReturnValueOnce(new Promise<LogoutOutcome>(() => undefined));
      const { result } = renderAction("logout", { logout });
      await act(() => result.current.run());

      await act(async () => {
        void result.current.run();
      });

      expect(result.current.error).toBeNull();
    });

    // Which line each outcome gets is `session-action-copy.test.ts`'s table;
    // these prove each action reads its own.
    it.each<[string, Partial<Actions>, SessionActionName, string]>([
      [
        "a refused login",
        { login: vi.fn<Actions["login"]>().mockResolvedValue({ kind: "refused" }) },
        "login",
        "The username or password is incorrect.",
      ],
      [
        "a CSRF-expired logout",
        { logout: vi.fn<Actions["logout"]>().mockResolvedValue({ kind: "csrf-expired" }) },
        "logout",
        "Your security token expired. Please try again. You are still signed in.",
      ],
      [
        "a password change with the wrong current password",
        {
          changePassword: vi
            .fn<Actions["changePassword"]>()
            .mockResolvedValue({ kind: "current-password-rejected" }),
        },
        "changePassword",
        "The current password is incorrect.",
      ],
    ])("says what %s came to", async (_name, actions, name, line) => {
      const { result } = renderAction(name, actions);

      await act(() => runAny(result));

      expect(result.current.error).toBe(line);
    });
  });

  describe("an outcome that ends or starts a session", () => {
    const moving: [string, Partial<Actions>, SessionActionName][] = [
      [
        "a login that signed in",
        {
          login: vi
            .fn<Actions["login"]>()
            .mockResolvedValue({ kind: "signed-in", user: signedIn().user! }),
        },
        "login",
      ],
      [
        "a logout that signed out",
        { logout: vi.fn<Actions["logout"]>().mockResolvedValue({ kind: "signed-out" }) },
        "logout",
      ],
      [
        "a password change that changed it",
        {
          changePassword: vi.fn<Actions["changePassword"]>().mockResolvedValue({ kind: "changed" }),
        },
        "changePassword",
      ],
      [
        "a password change that locked the account",
        {
          changePassword: vi.fn<Actions["changePassword"]>().mockResolvedValue({ kind: "locked" }),
        },
        "changePassword",
      ],
    ];

    it.each(moving)("shows nothing for %s: the guards move the visitor on", async (_n, a, name) => {
      const { result } = renderAction(name, a);

      await act(() => runAny(result));

      expect(result.current).toMatchObject({ error: null, pending: false });
    });

    it.each(moving)("resolves %s as one that moved the visitor on", async (_n, a, name) => {
      const { result } = renderAction(name, a);

      let settled: SessionActionResult | undefined;
      await act(async () => {
        settled = await runAny(result);
      });

      expect(settled).toBe("moved-on");
    });

    it("withdraws an earlier refusal's line", async () => {
      const logout = vi
        .fn<Actions["logout"]>()
        .mockResolvedValueOnce({ kind: "failed" })
        .mockResolvedValueOnce({ kind: "signed-out" });
      const { result } = renderAction("logout", { logout });
      await act(() => result.current.run());

      await act(() => result.current.run());

      expect(result.current.error).toBeNull();
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
    function renderReporting(logout: Actions["logout"]) {
      const value = authState(signedIn(), { logout });
      return renderHook(
        () => {
          const other = useStatusReporter();
          const signOut = useSessionAction("logout", useStatusReporter());
          return { other, signOut };
        },
        {
          wrapper: ({ children }: { children: ReactNode }) => (
            <AuthContext.Provider value={value}>
              <InPageStatus>{children}</InPageStatus>
            </AuthContext.Provider>
          ),
        },
      );
    }

    it("shows a refusal's line on the page status", async () => {
      const { result } = renderReporting(
        vi.fn<Actions["logout"]>().mockResolvedValue({ kind: "failed" }),
      );

      await act(() => result.current.signOut.run());

      expect(shownLine()).toBe(SIGN_OUT_FAILED);
    });

    it("shows nothing on the page status for an outcome that moved the visitor on", async () => {
      const { result } = renderReporting(
        vi.fn<Actions["logout"]>().mockResolvedValue({ kind: "signed-out" }),
      );

      await act(() => result.current.signOut.run());

      expect(shownLine()).toBeNull();
    });

    it("withdraws only its own line as the next run starts, so another source's shows again", async () => {
      const logout = vi
        .fn<Actions["logout"]>()
        .mockResolvedValueOnce({ kind: "failed" })
        .mockReturnValueOnce(new Promise<LogoutOutcome>(() => undefined));
      const { result } = renderReporting(logout);
      act(() => result.current.other.report("The listing failed."));
      await act(() => result.current.signOut.run());

      await act(async () => {
        void result.current.signOut.run();
      });

      expect(shownLine()).toBe("The listing failed.");
    });
  });
});

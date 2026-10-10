import { act, fireEvent, render, screen } from "@testing-library/react";
import { useEffect, type ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import * as http from "@/lib/http";
import { Login } from "@/pages/login";

import * as authApi from "./api";
import { AuthProvider } from "./auth-context";
import { useAuth } from "./auth-context-value";
import { GuestRoute, ProtectedRoute } from "./route-guards";
import { LOGIN_PATH } from "./session-route";
import { useSessionRequest } from "./use-session-request";

vi.mock("./api");
vi.mock("@/lib/http");

const api = vi.mocked(authApi);

/**
 * A deliberately odd idle bound: a clock that signs out at exactly this many
 * seconds is reading the backend's figure, not a constant of its own.
 */
const IDLE_SECONDS = 437;
const LIMIT_MS = IDLE_SECONDS * 1000;
/** The warning opens a minute before the limit. */
const WARN_AT_MS = LIMIT_MS - 60_000;

const user = (idleTimeoutSeconds = IDLE_SECONDS): authApi.AuthUser => ({
  idleTimeoutSeconds,
  passwordChangeRequired: false,
  permissions: [],
  username: "ada",
});

const INACTIVE_MESSAGE = "You were signed out because you were inactive. Please sign in again.";

/**
 * Another tab's end of the activity channel. Synchronous, and like the real
 * one it never delivers a message back to the object that posted it.
 */
class FakeBroadcastChannel {
  static open: FakeBroadcastChannel[] = [];
  onmessage: ((event: MessageEvent) => void) | null = null;
  readonly posted: unknown[] = [];

  constructor(readonly name: string) {
    FakeBroadcastChannel.open.push(this);
  }

  postMessage(data: unknown) {
    this.posted.push(data);
    for (const other of FakeBroadcastChannel.open) {
      if (other !== this && other.name === this.name) other.onmessage?.({ data } as MessageEvent);
    }
  }

  close() {
    FakeBroadcastChannel.open = FakeBroadcastChannel.open.filter((channel) => channel !== this);
  }
}

/** The SPA's own channel, the one the provider opened. */
const spaChannel = () => {
  const [channel] = FakeBroadcastChannel.open;
  expect(channel).toBeDefined();
  return channel!;
};

/** A second tab listening on the same channel the SPA opened. */
const otherTab = () => new FakeBroadcastChannel(spaChannel().name);

const advance = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });

/** A feature page that polls the backend through the session seam every ten seconds. */
function PollingPage() {
  const request = useSessionRequest();
  useEffect(() => {
    const poll = setInterval(() => void request("/api/poll"), 10_000);
    return () => clearInterval(poll);
  }, [request]);
  return <p>Accounts page</p>;
}

/** A page submitting a password change through the context, as the real one does. */
function PasswordChangeButton() {
  const { changePassword } = useAuth();
  return (
    <>
      <p>Accounts page</p>
      <button onClick={() => void changePassword("wrong-value", "new-value")} type="button">
        Change password
      </button>
    </>
  );
}

async function signedIn(
  current: authApi.AuthUser = user(),
  page: ReactNode = <p>Accounts page</p>,
) {
  api.getCurrentUser.mockResolvedValue(current);
  render(
    <MemoryRouter initialEntries={["/accounts"]}>
      <AuthProvider>
        <Routes>
          <Route
            path={LOGIN_PATH}
            element={
              <GuestRoute>
                <Login />
              </GuestRoute>
            }
          />
          <Route path="/accounts" element={<ProtectedRoute>{page}</ProtectedRoute>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
  await act(async () => {});
  expect(screen.getByText("Accounts page")).toBeInTheDocument();
  // The start-up check and its sign-in are behind us; count from here.
  api.getCurrentUser.mockClear();
  vi.mocked(http.discardCsrfToken).mockClear();
}

/** What the stay's `GET /api/auth/me` comes back as, through the transport. */
const stayAnswers = (result: http.ApiResult<void>) =>
  vi.mocked(http.apiFetch).mockResolvedValue(result);

const warning = () => screen.queryByRole("alertdialog");

/** Asserts the inactivity sign-out happened and the user landed on Login saying why. */
function expectSignedOutForInactivity() {
  expect(api.logout).toHaveBeenCalledOnce();
  expect(http.discardCsrfToken).toHaveBeenCalled();
  expect(screen.getByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
  expect(screen.getByRole("status")).toHaveTextContent(new RegExp(`^${INACTIVE_MESSAGE}$`));
  expect(screen.queryByText("Accounts page")).not.toBeInTheDocument();
}

describe("IdleSignOut", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.useFakeTimers();
    FakeBroadcastChannel.open = [];
    vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);
    api.logout.mockResolvedValue(undefined);
    stayAnswers({ data: undefined, kind: "ok" });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  describe("the idle limit", () => {
    it("signs out at the backend's idle limit with no input, and not before", async () => {
      await signedIn();

      await advance(LIMIT_MS - 1);
      expect(api.logout).not.toHaveBeenCalled();
      expect(screen.getByText("Accounts page")).toBeInTheDocument();

      await advance(1);
      expectSignedOutForInactivity();
    });

    it("times the clock by the login response's figure too", async () => {
      api.getCurrentUser.mockResolvedValue(null);
      api.login.mockResolvedValue(user(120));
      render(
        <MemoryRouter>
          <AuthProvider>
            <Routes>
              <Route
                path={LOGIN_PATH}
                element={
                  <GuestRoute>
                    <Login />
                  </GuestRoute>
                }
              />
              <Route
                path="/showcase"
                element={
                  <ProtectedRoute>
                    <p>Showcase page</p>
                  </ProtectedRoute>
                }
              />
            </Routes>
          </AuthProvider>
        </MemoryRouter>,
      );
      await act(async () => {});
      fireEvent.change(screen.getByLabelText("Username"), { target: { value: "ada" } });
      fireEvent.change(screen.getByLabelText("Password"), { target: { value: "secret-value" } });
      await act(async () => {
        fireEvent.submit(screen.getByRole("button", { name: "Sign in" }));
      });
      expect(screen.getByText("Showcase page")).toBeInTheDocument();

      await advance(120_000 - 1);
      expect(api.logout).not.toHaveBeenCalled();
      await advance(1);
      expect(api.logout).toHaveBeenCalledOnce();
    });

    it.each([
      ["a key press", () => fireEvent.keyDown(document)],
      ["a pointer press", () => fireEvent.pointerDown(document)],
      ["pointer movement", () => fireEvent.pointerMove(document)],
      ["a touch", () => fireEvent.touchStart(document)],
      ["a wheel turn", () => fireEvent.wheel(document)],
      // Scroll does not bubble; the listener is captured so an inner scroller counts.
      ["scrolling an inner element", () => fireEvent.scroll(screen.getByText("Accounts page"))],
    ])("restarts the clock on %s before the limit", async (_, input) => {
      await signedIn();
      await advance(200_000);

      input();

      await advance(LIMIT_MS - 1);
      expect(api.logout).not.toHaveBeenCalled();
      await advance(1);
      expectSignedOutForInactivity();
    });

    it("does not count a request as activity, so a poll cannot keep a page signed in", async () => {
      vi.mocked(http.apiFetch).mockResolvedValue({ data: undefined, kind: "ok" });
      await signedIn(user(), <PollingPage />);

      await advance(LIMIT_MS - 1);
      expect(vi.mocked(http.apiFetch).mock.calls.length).toBeGreaterThan(10);
      expect(api.logout).not.toHaveBeenCalled();
      await advance(1);
      expectSignedOutForInactivity();
    });

    it("ignores input within a second of the last activity, and counts it from then on", async () => {
      await signedIn();

      await advance(999);
      fireEvent.keyDown(document);
      await advance(WARN_AT_MS - 999);
      expect(warning()).toBeInTheDocument();
    });

    it("counts input a full second after the last activity", async () => {
      await signedIn();

      await advance(1_000);
      fireEvent.keyDown(document);
      await advance(WARN_AT_MS - 1);
      expect(warning()).not.toBeInTheDocument();
      await advance(1_000);
      expect(warning()).toBeInTheDocument();
    });

    it("signs straight out when a late timer finds the limit already passed", async () => {
      await signedIn();

      // The machine slept: the wall clock moved on without the timer firing.
      vi.setSystemTime(Date.now() + LIMIT_MS);
      await advance(WARN_AT_MS);

      expect(warning()).not.toBeInTheDocument();
      expectSignedOutForInactivity();
    });

    it("warns half-way through a limit shorter than two minutes", async () => {
      await signedIn(user(60));

      await advance(30_000 - 1);
      expect(warning()).not.toBeInTheDocument();
      await advance(1);
      expect(warning()).toBeInTheDocument();
      await advance(30_000);
      expect(api.logout).toHaveBeenCalledOnce();
    });

    it("never signs out a session with no idle bound", async () => {
      await signedIn(user(0));

      await advance(10 * 60 * 60 * 1000);

      expect(api.logout).not.toHaveBeenCalled();
      expect(FakeBroadcastChannel.open).toHaveLength(0);
    });

    it("still signs out where BroadcastChannel is unavailable", async () => {
      vi.stubGlobal("BroadcastChannel", undefined);
      await signedIn();

      await advance(200_000);
      fireEvent.keyDown(document);
      await advance(LIMIT_MS - 1);
      expect(api.logout).not.toHaveBeenCalled();
      await advance(1);
      expectSignedOutForInactivity();
    });

    it("keeps the session on a stay where BroadcastChannel is unavailable", async () => {
      vi.stubGlobal("BroadcastChannel", undefined);
      await signedIn();
      await advance(WARN_AT_MS);

      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Stay signed in" }));
      });

      expect(warning()).not.toBeInTheDocument();
      await advance(LIMIT_MS - 1);
      expect(api.logout).not.toHaveBeenCalled();
    });

    it("never fires for a session a password change locked, so login keeps saying why", async () => {
      api.changePassword.mockResolvedValue({ kind: "locked" });
      await signedIn(user(), <PasswordChangeButton />);
      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Change password" }));
      });

      await advance(LIMIT_MS);

      expect({
        loggedOut: api.logout.mock.calls.length,
        notice: screen.getByRole("status").textContent,
      }).toEqual({
        loggedOut: 0,
        notice:
          "Too many incorrect passwords: the account is now locked and your session has ended. An Admin must Unlock the account before you can sign in again.",
      });
    });

    it("releases the channel once signed out", async () => {
      await signedIn();

      await advance(LIMIT_MS);

      expect(FakeBroadcastChannel.open).toHaveLength(0);
    });

    it("stops listening once signed out, so later input cannot sign out again", async () => {
      const removed = vi.spyOn(document, "removeEventListener");
      await signedIn();
      // Signed out by a late timer, so no warning was open to mute the input.
      vi.setSystemTime(Date.now() + LIMIT_MS);
      await advance(WARN_AT_MS);
      expect(api.logout).toHaveBeenCalledOnce();

      await advance(2_000);
      fireEvent.keyDown(document);
      fireEvent.scroll(document);
      await advance(LIMIT_MS);

      expect(api.logout).toHaveBeenCalledOnce();
      // A browser removes a captured listener only when asked with `capture`
      // too; happy-dom ignores the flag, so the call itself is what is checked.
      for (const type of [
        "keydown",
        "pointerdown",
        "pointermove",
        "scroll",
        "touchstart",
        "wheel",
      ]) {
        expect(removed).toHaveBeenCalledWith(type, expect.any(Function), { capture: true });
      }
    });

    it("never opens the warning before its time", async () => {
      const showModal = vi.spyOn(HTMLDialogElement.prototype, "showModal");
      await signedIn();

      await advance(WARN_AT_MS - 1);
      expect(showModal).not.toHaveBeenCalled();
      await advance(1);
      expect(showModal).toHaveBeenCalledOnce();
    });
  });

  describe("the warning", () => {
    it("opens shortly before the limit as a focused alertdialog", async () => {
      await signedIn();

      await advance(WARN_AT_MS - 1);
      expect(warning()).not.toBeInTheDocument();
      await advance(1);

      const dialog = screen.getByRole("alertdialog", { name: "Are you still there?" });
      expect(dialog).toHaveAccessibleDescription(
        "You will be signed out soon because you have been inactive.",
      );
      expect(dialog).toHaveFocus();
      // Focusable from script, but no tab stop of its own: Tab goes to its buttons.
      expect(dialog).toHaveAttribute("tabindex", "-1");
      expect(screen.getByRole("button", { name: "Stay signed in" })).toBeEnabled();
      expect(api.logout).not.toHaveBeenCalled();
    });

    it("is not dismissed by passive input, which would leave the backend's clock running", async () => {
      await signedIn();
      await advance(WARN_AT_MS);

      fireEvent.keyDown(document);
      fireEvent.pointerMove(document);

      expect(warning()).toBeInTheDocument();
      await advance(LIMIT_MS - WARN_AT_MS);
      expectSignedOutForInactivity();
    });

    it("staying makes one authenticated request and restarts the clock", async () => {
      await signedIn();
      await advance(WARN_AT_MS);

      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Stay signed in" }));
      });

      expect(http.apiFetch).toHaveBeenCalledExactlyOnceWith("/api/auth/me", {});
      expect(warning()).not.toBeInTheDocument();
      // A full limit from the stay, not from the last input.
      await advance(WARN_AT_MS - 1);
      expect(warning()).not.toBeInTheDocument();
      await advance(LIMIT_MS - WARN_AT_MS);
      expect(api.logout).not.toHaveBeenCalled();
      await advance(1);
      expectSignedOutForInactivity();
    });

    it("shows the stay in progress while its request is out", async () => {
      await signedIn();
      await advance(WARN_AT_MS);
      let answer: (result: http.ApiResult<void>) => void = () => undefined;
      vi.mocked(http.apiFetch).mockReturnValue(
        new Promise((resolve) => {
          answer = resolve;
        }),
      );

      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Stay signed in" }));
      });
      expect(screen.getByRole("button", { name: "Staying signed in…" })).toBeDisabled();

      await act(async () => answer({ data: undefined, kind: "ok" }));
      expect(warning()).not.toBeInTheDocument();
    });

    it("stays on Escape, the dialog's keyboard dismissal", async () => {
      await signedIn();
      await advance(WARN_AT_MS);

      const cancel = new Event("cancel", { cancelable: true });
      await act(async () => {
        fireEvent(screen.getByRole("alertdialog"), cancel);
      });

      expect(cancel.defaultPrevented).toBe(true);
      expect(http.apiFetch).toHaveBeenCalledExactlyOnceWith("/api/auth/me", {});
      expect(warning()).not.toBeInTheDocument();
    });

    it("leaves a session the backend has already ended to the ordinary expiry path", async () => {
      await signedIn();
      await advance(WARN_AT_MS);
      stayAnswers({ kind: "unauthenticated" });

      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Stay signed in" }));
      });

      expect(api.logout).not.toHaveBeenCalled();
      expect(http.discardCsrfToken).toHaveBeenCalledOnce();
      expect(screen.getByRole("status")).toHaveTextContent(
        /^Your session ended\. Please sign in again\.$/,
      );
    });

    it.each([
      ["cannot reach the backend", { kind: "failed" }],
      ["is refused", { kind: "forbidden" }],
    ] as const)(
      "stays up when the stay request %s, and the limit decides",
      async (_, result: http.ApiResult<void>) => {
        await signedIn();
        await advance(WARN_AT_MS);
        stayAnswers(result);

        await act(async () => {
          fireEvent.click(screen.getByRole("button", { name: "Stay signed in" }));
        });

        expect(warning()).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Stay signed in" })).toBeEnabled();
        await advance(LIMIT_MS - WARN_AT_MS);
        expectSignedOutForInactivity();
      },
    );

    it("signs out on request, without claiming inactivity", async () => {
      await signedIn();
      await advance(WARN_AT_MS);

      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
      });

      expect(api.logout).toHaveBeenCalledOnce();
      expect(screen.getByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
    });

    it("keeps the session when a requested sign-out fails", async () => {
      await signedIn();
      await advance(WARN_AT_MS);
      api.logout.mockRejectedValue(new Error("Unable to sign out. Please try again."));

      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
      });

      expect(screen.getByText("Accounts page")).toBeInTheDocument();
    });
  });

  describe("across tabs", () => {
    it("restarts the clock on activity broadcast from another tab", async () => {
      await signedIn();
      const tab = otherTab();
      await advance(200_000);

      tab.postMessage("activity");

      await advance(LIMIT_MS - 1);
      expect(api.logout).not.toHaveBeenCalled();
      await advance(1);
      expectSignedOutForInactivity();
    });

    it("dismisses the warning when another tab is active", async () => {
      await signedIn();
      const tab = otherTab();
      await advance(WARN_AT_MS);
      expect(warning()).toBeInTheDocument();

      await act(async () => tab.postMessage("activity"));

      expect(warning()).not.toBeInTheDocument();
    });

    it("tells the other tabs about local input", async () => {
      await signedIn();
      const tab = otherTab();
      const heard = vi.fn();
      tab.onmessage = heard;
      await advance(200_000);

      fireEvent.keyDown(document);

      expect(heard).toHaveBeenCalledOnce();
    });

    it("tells the other tabs when the user stays signed in", async () => {
      await signedIn();
      const tab = otherTab();
      const heard = vi.fn();
      tab.onmessage = heard;
      await advance(WARN_AT_MS);

      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Stay signed in" }));
      });

      expect(heard).toHaveBeenCalledOnce();
    });
  });
});

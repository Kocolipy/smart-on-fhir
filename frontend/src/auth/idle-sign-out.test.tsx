import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { useEffect, type ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  bare,
  CURRENT_PASSWORD_REFUSED,
  fakeBackend,
  json,
  requestKey,
  UNREACHABLE,
  type Answers,
  type FakeBackend,
  type WireAnswer,
} from "@/lib/fake-backend.testHelpers";
import { Login } from "@/pages/login";

import type { AuthUser } from "./api";
import { AuthProvider } from "./auth-context";
import { AUTH_REQUESTS, testUser } from "./auth-context.testHelpers";
import { useAuth } from "./auth-context-value";
import { GuestRoute, ProtectedRoute } from "./route-guards";
import { DEFAULT_DESTINATION, LOGIN_PATH } from "./session-route";
import { useSessionRequest } from "./use-session-request";

// The real provider, guards and Login page over the backend layer, under fake
// timers: the logout, the stay and the session check all go over the wire.

const { change: CHANGE, login: LOGIN, logout: LOGOUT, me: ME } = AUTH_REQUESTS;

/**
 * A deliberately odd idle bound: a clock that signs out at exactly this many
 * seconds is reading the backend's figure, not a constant of its own.
 */
const IDLE_SECONDS = 437;
const LIMIT_MS = IDLE_SECONDS * 1000;
/** The warning opens a minute before the limit. */
const WARN_AT_MS = LIMIT_MS - 60_000;

const user = (idleTimeoutSeconds = IDLE_SECONDS): AuthUser => testUser({ idleTimeoutSeconds });

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

/** The backend the test runs over, set by {@link signedIn} or the test itself. */
let backend: FakeBackend;

/** How many times `request` was sent. */
const sent = (request: string) =>
  backend.requests().filter((sent) => requestKey(sent) === request).length;

/** How many logouts were sent. */
const logouts = () => sent(LOGOUT);

/** How many stays were sent: every session check after the start-up one. */
const stays = () => sent(ME) - 1;

/** Clicks the button named `name`, and lets the requests it sends settle. */
const click = (name: string) =>
  act(async () => {
    fireEvent.click(screen.getByRole("button", { name }));
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
  current: AuthUser = user(),
  page: ReactNode = <p>Accounts page</p>,
  answers: Answers = {},
) {
  backend = fakeBackend({ [LOGOUT]: bare(204), [ME]: json(current), ...answers });
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
  await advance(0);
  expect(screen.getByText("Accounts page")).toBeInTheDocument();
}

/** What the stay's `GET /api/auth/me` comes back as. */
const stayAnswers = (answer: WireAnswer | Promise<WireAnswer>) => backend.answer({ [ME]: answer });

const warning = () => screen.queryByRole("alertdialog");

/** Asserts the inactivity sign-out happened and the user landed on Login saying why. */
async function expectSignedOutForInactivity() {
  expect(logouts()).toBe(1);
  expect(await backend.tokenForgotten()).toBe(true);
  expect(screen.getByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
  expect(screen.getByRole("status")).toHaveTextContent(new RegExp(`^${INACTIVE_MESSAGE}$`));
  expect(screen.queryByText("Accounts page")).not.toBeInTheDocument();
}

describe("IdleSignOut", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeBroadcastChannel.open = [];
    vi.stubGlobal("BroadcastChannel", FakeBroadcastChannel);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  describe("the idle limit", () => {
    it("signs out at the backend's idle limit with no input, and not before", async () => {
      await signedIn();

      await advance(LIMIT_MS - 1);
      expect(logouts()).toBe(0);
      expect(screen.getByText("Accounts page")).toBeInTheDocument();

      await advance(1);
      await expectSignedOutForInactivity();
    });

    it("times the clock by the login response's figure too", async () => {
      backend = fakeBackend({
        [LOGOUT]: bare(204),
        [ME]: bare(401),
        [LOGIN]: json(user(120)),
      });
      render(
        <MemoryRouter initialEntries={[LOGIN_PATH]}>
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
                path={DEFAULT_DESTINATION}
                element={
                  <ProtectedRoute>
                    <p>Home page</p>
                  </ProtectedRoute>
                }
              />
            </Routes>
          </AuthProvider>
        </MemoryRouter>,
      );
      await advance(0);
      fireEvent.change(screen.getByLabelText("Username"), { target: { value: "ada" } });
      fireEvent.change(screen.getByLabelText("Password"), { target: { value: "secret-value" } });
      await act(async () => {
        fireEvent.submit(screen.getByRole("button", { name: "Sign in" }));
      });
      expect(screen.getByText("Home page")).toBeInTheDocument();

      await advance(120_000 - 1);
      expect(logouts()).toBe(0);
      await advance(1);
      expect(logouts()).toBe(1);
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
      expect(logouts()).toBe(0);
      await advance(1);
      await expectSignedOutForInactivity();
    });

    it("does not count a request as activity, so a poll cannot keep a page signed in", async () => {
      await signedIn(user(), <PollingPage />, { "GET /api/poll": bare(204) });

      await advance(LIMIT_MS - 1);
      expect(sent("GET /api/poll")).toBeGreaterThan(10);
      expect(logouts()).toBe(0);
      await advance(1);
      await expectSignedOutForInactivity();
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
      await expectSignedOutForInactivity();
    });

    it("warns half-way through a limit shorter than two minutes", async () => {
      await signedIn(user(60));

      await advance(30_000 - 1);
      expect(warning()).not.toBeInTheDocument();
      await advance(1);
      expect(warning()).toBeInTheDocument();
      await advance(30_000);
      expect(logouts()).toBe(1);
    });

    it("never signs out a session with no idle bound", async () => {
      await signedIn(user(0));

      await advance(10 * 60 * 60 * 1000);

      expect(logouts()).toBe(0);
      expect(FakeBroadcastChannel.open).toHaveLength(0);
    });

    it("still signs out where BroadcastChannel is unavailable", async () => {
      vi.stubGlobal("BroadcastChannel", undefined);
      await signedIn();

      await advance(200_000);
      fireEvent.keyDown(document);
      await advance(LIMIT_MS - 1);
      expect(logouts()).toBe(0);
      await advance(1);
      await expectSignedOutForInactivity();
    });

    it("keeps the session on a stay where BroadcastChannel is unavailable", async () => {
      vi.stubGlobal("BroadcastChannel", undefined);
      await signedIn();
      await advance(WARN_AT_MS);

      await click("Stay signed in");

      expect(warning()).not.toBeInTheDocument();
      await advance(LIMIT_MS - 1);
      expect(logouts()).toBe(0);
    });

    it("never fires for a session a password change locked, so login keeps saying why", async () => {
      // The 401 ended the session too, so the check that follows it is refused.
      await signedIn(user(), <PasswordChangeButton />, {
        [ME]: [json(user()), bare(401)],
        [CHANGE]: CURRENT_PASSWORD_REFUSED,
      });
      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Change password" }));
      });

      await advance(LIMIT_MS);

      expect({
        loggedOut: logouts(),
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
      expect(logouts()).toBe(1);

      await advance(2_000);
      fireEvent.keyDown(document);
      fireEvent.scroll(document);
      await advance(LIMIT_MS);

      expect(logouts()).toBe(1);
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
      expect(logouts()).toBe(0);
    });

    it("is not dismissed by passive input, which would leave the backend's clock running", async () => {
      await signedIn();
      await advance(WARN_AT_MS);

      fireEvent.keyDown(document);
      fireEvent.pointerMove(document);

      expect(warning()).toBeInTheDocument();
      await advance(LIMIT_MS - WARN_AT_MS);
      await expectSignedOutForInactivity();
    });

    it("staying makes one authenticated request and restarts the clock", async () => {
      await signedIn();
      await advance(WARN_AT_MS);

      await click("Stay signed in");

      expect(stays()).toBe(1);
      expect(warning()).not.toBeInTheDocument();
      // A full limit from the stay, not from the last input.
      await advance(WARN_AT_MS - 1);
      expect(warning()).not.toBeInTheDocument();
      await advance(LIMIT_MS - WARN_AT_MS);
      expect(logouts()).toBe(0);
      await advance(1);
      await expectSignedOutForInactivity();
    });

    it("shows the stay in progress while its request is out", async () => {
      await signedIn();
      await advance(WARN_AT_MS);
      let answer: (given: WireAnswer) => void = () => undefined;
      stayAnswers(
        new Promise((resolve) => {
          answer = resolve;
        }),
      );

      await click("Stay signed in");
      expect(screen.getByRole("button", { name: "Staying signed in…" })).toBeDisabled();

      await act(async () => answer(json(user())));
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
      expect(stays()).toBe(1);
      expect(warning()).not.toBeInTheDocument();
    });

    it("leaves a session the backend has already ended to the ordinary expiry path", async () => {
      await signedIn();
      await advance(WARN_AT_MS);
      await backend.holdToken();
      stayAnswers(bare(401));

      await click("Stay signed in");

      expect(logouts()).toBe(0);
      expect(await backend.tokenForgotten()).toBe(true);
      expect(screen.getByRole("status")).toHaveTextContent(
        /^Your session ended\. Please sign in again\.$/,
      );
    });

    it.each([
      ["cannot reach the backend", UNREACHABLE],
      ["fails", bare(500)],
      ["is refused", bare(403)],
    ] as const)(
      "stays up when the stay request %s, and the limit decides",
      async (_, answer: WireAnswer) => {
        await signedIn();
        await advance(WARN_AT_MS);
        stayAnswers(answer);

        await act(async () => {
          fireEvent.click(screen.getByRole("button", { name: "Stay signed in" }));
        });

        expect(warning()).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Stay signed in" })).toBeEnabled();
        await advance(LIMIT_MS - WARN_AT_MS);
        await expectSignedOutForInactivity();
      },
    );

    it("signs out on request, without claiming inactivity", async () => {
      await signedIn();
      await advance(WARN_AT_MS);

      await click("Sign out");

      expect(logouts()).toBe(1);
      expect(screen.getByRole("heading", { name: "Welcome back" })).toBeInTheDocument();
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
    });

    it("keeps the session when a requested sign-out fails", async () => {
      await signedIn();
      await advance(WARN_AT_MS);
      backend.answer({ [LOGOUT]: bare(500) });

      await click("Sign out");

      expect(screen.getByText("Accounts page")).toBeInTheDocument();
    });

    // Running the requested Sign out — pending, which outcome signed out, its
    // copy, the line withdrawn on the next try — is `use-session-action.test.tsx`'s;
    // these prove the dialog renders what it hands back.
    it("says in the dialog that the User is still signed in when a requested sign-out fails", async () => {
      await signedIn();
      await advance(WARN_AT_MS);
      backend.answer({ [LOGOUT]: bare(500) });

      await click("Sign out");

      expect(within(warning()!).getByRole("alert").textContent).toBe(
        "Unable to sign out. Please try again. You are still signed in.",
      );
    });

    it("opens with no error line", async () => {
      await signedIn();
      await advance(WARN_AT_MS);

      expect(within(warning()!).queryByRole("alert")).not.toBeInTheDocument();
    });

    it("shows the requested sign-out in progress while its logout is out", async () => {
      await signedIn();
      await advance(WARN_AT_MS);
      backend.answer({ [LOGOUT]: new Promise<WireAnswer>(() => undefined) });

      await click("Sign out");

      expect(screen.getByRole("button", { name: "Signing out…" })).toBeDisabled();
    });

    it("still ends the session at the limit when the inactivity logout fails", async () => {
      await signedIn();
      backend.answer({ [LOGOUT]: bare(500) });

      await advance(LIMIT_MS);

      await expectSignedOutForInactivity();
    });
  });

  describe("across tabs", () => {
    it("restarts the clock on activity broadcast from another tab", async () => {
      await signedIn();
      const tab = otherTab();
      await advance(200_000);

      tab.postMessage("activity");

      await advance(LIMIT_MS - 1);
      expect(logouts()).toBe(0);
      await advance(1);
      await expectSignedOutForInactivity();
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

      await click("Stay signed in");

      expect(heard).toHaveBeenCalledOnce();
    });
  });
});

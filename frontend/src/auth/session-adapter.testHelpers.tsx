// Test support only: excluded from the production project, so never hot-reloaded,
// and its one component is private to the adapter.
/* eslint-disable react-refresh/only-export-components */
import { act, render, waitFor } from "@testing-library/react";
import { useEffect, type ReactElement } from "react";
import { MemoryRouter, type InitialEntry } from "react-router-dom";

import { TooltipProvider } from "@/components/ui/tooltip";

import {
  bare,
  fakeBackend,
  json,
  requestKey,
  type Answers,
  type FakeBackend,
  type SentRequest,
  type WireAnswer,
} from "@/lib/fake-backend.testHelpers";

import type { AuthUser, Permission } from "./api";
import { AuthProvider } from "./auth-context";
import { AUTH_REQUESTS, testUser } from "./auth-context.testHelpers";
import { useAuthState, type AuthStatus } from "./auth-context-value";
import type { SessionEndReason } from "./sign-in-reason";

// The render layer of the test adapter. A page suite renders the real page
// inside the real `AuthProvider`, for a session it states, and fakes nothing
// but `fetch`: the backend layer (`@/lib/fake-backend.testHelpers`) answers the
// start-up session check from that session, the CSRF token from its stand-in,
// and every other request from the wire answers the suite gives. So the page's
// declarations, `apiFetch`'s classification and the decoders all run under it,
// and the auth state's shape is nobody's business but `src/auth`'s.

export {
  bare,
  CURRENT_PASSWORD_REFUSED,
  deferred,
  json,
  UNREACHABLE,
  type Answers,
  type WireAnswer,
} from "@/lib/fake-backend.testHelpers";

/**
 * The session the browser holds, as the start-up `GET /api/auth/me` finds it.
 * `checking` is a check that never answers; `unchecked` one that answers
 * neither `200` nor `401`.
 */
export type StartingSession =
  | { status: "authenticated"; user: AuthUser }
  | { status: "checking" }
  | { status: "guest" }
  | { status: "unchecked"; answer: WireAnswer };

/** A session signed in as {@link testUser} holding exactly `permissions`, with `user` overriding the rest. */
export function holding(
  permissions: readonly Permission[],
  user: Partial<Omit<AuthUser, "permissions">> = {},
): StartingSession {
  return { status: "authenticated", user: testUser({ ...user, permissions }) };
}

/** No signed-in session: the start-up check answers `401`. */
export const GUEST: StartingSession = { status: "guest" };

/** A start-up check still waiting on an answer that never comes. */
export const STILL_CHECKING: StartingSession = { status: "checking" };

const NEVER: Promise<WireAnswer> = new Promise(() => undefined);

/** How the backend answers `GET /api/auth/me` for the session it holds. */
function meAnswer(session: StartingSession): WireAnswer | Promise<WireAnswer> {
  switch (session.status) {
    case "authenticated":
      return json(session.user);
    case "checking":
      return NEVER;
    case "guest":
      return bare(401);
    case "unchecked":
      return session.answer;
  }
}

const ME_REQUEST = AUTH_REQUESTS.me;

/**
 * `answers`, with the start-up check answered from `session` first. Any later
 * check gets the suite's own answer to it, or else the session's again.
 */
function withSessionCheck(session: StartingSession, answers: Answers): Answers {
  const later = answers[ME_REQUEST] ?? meAnswer(session);
  return {
    ...answers,
    [ME_REQUEST]: [meAnswer(session), ...(Array.isArray(later) ? later : [later])],
  };
}

/** What the page sent: the start-up check is the session's, not the page's. */
function withoutSessionCheck(sent: readonly SentRequest[]): readonly SentRequest[] {
  const check = sent.findIndex((request) => requestKey(request) === ME_REQUEST);
  return check === -1 ? sent : [...sent.slice(0, check), ...sent.slice(check + 1)];
}

/** What the session has come to, as the adapter reads it off the provider. */
interface SessionReading {
  signInReason: SessionEndReason | null;
  status: AuthStatus;
}

/** Reports the session to the adapter on every change, so a suite can ask what it came to. */
function SessionProbe({ onRead }: { onRead: (reading: SessionReading) => void }) {
  const { signInReason, status } = useAuthState();
  useEffect(() => onRead({ signInReason, status }), [onRead, signInReason, status]);
  return null;
}

/**
 * Renders `ui` as `session` sees it: inside a router at `at` and the real
 * `AuthProvider`, over the fake backend answering the session's start-up check
 * and then `answers`. Resolves once the start-up check has settled — except
 * for {@link STILL_CHECKING}, which never does — so the page renders for the
 * session from the first assertion.
 *
 * Read what the page sent with `requests()` (the start-up check left out),
 * change an answer mid-test with `answer()`, and see whether the page ended
 * the session with `session()`.
 */
export async function renderAsSession(
  ui: ReactElement,
  session: StartingSession,
  { answers = {}, at = "/" }: { answers?: Answers; at?: InitialEntry } = {},
) {
  const backend = fakeBackend(withSessionCheck(session, answers));

  let reading: SessionReading = { signInReason: null, status: "checking" };
  const onRead = (next: SessionReading) => {
    reading = next;
  };
  const rendered = render(
    <MemoryRouter initialEntries={[at]}>
      <TooltipProvider>
        <AuthProvider>
          <SessionProbe onRead={onRead} />
          {ui}
        </AuthProvider>
      </TooltipProvider>
    </MemoryRouter>,
  );
  if (session.status !== "checking") {
    await waitFor(() => {
      if (reading.status === "checking") throw new Error("the session check is pending");
    });
  }
  // Let the effects the settled session starts — a page's first reads — send.
  await act(async () => undefined);

  return {
    ...rendered,
    answer: backend.answer,
    requests: (...shown: Parameters<FakeBackend["requests"]>): readonly SentRequest[] =>
      withoutSessionCheck(backend.requests(...shown)),
    session: (): SessionReading => reading,
  };
}

// Test support only: excluded from the production project, so never hot-reloaded,
// and its one component is private to the adapter.
/* eslint-disable react-refresh/only-export-components */
import { act, render, waitFor } from "@testing-library/react";
import { useEffect, type ReactElement } from "react";
import { MemoryRouter, type InitialEntry } from "react-router-dom";
import { onTestFinished, vi } from "vitest";

import { stubFetchWithCsrf } from "@/lib/http.testHelpers";

import { ME_PATH, type AuthUser, type Permission } from "./api";
import { AuthProvider } from "./auth-context";
import { testUser } from "./auth-context.testHelpers";
import { useAuthState, type AuthStatus } from "./auth-context-value";
import type { SessionEndReason } from "./sign-in-reason";

// A page suite renders the real page inside the real `AuthProvider`, for a
// session it states, and fakes nothing but `fetch`: the start-up session check
// and the CSRF token are answered from that session, and every other request
// from the wire answers the suite gives. So the page's declarations, `apiFetch`'s
// classification and the decoders all run under it, and the auth state's shape
// is nobody's business but `src/auth`'s.

/** A fetch that never reached the backend: the network failed, so `fetch` rejects. */
export const UNREACHABLE = { unreachable: true } as const;

/** What the backend answers one request with: a status and, for a JSON answer, its body. */
export type WireAnswer = { status: number; body?: unknown } | typeof UNREACHABLE;

/** A JSON answer: `body` serialized, under `status` (`200` unless named). */
export const json = (body: unknown, status = 200): WireAnswer => ({ body, status });

/** An answer with a status and no body: `204`, or a refusal the page reads by status alone. */
export const bare = (status: number): WireAnswer => ({ status });

/** An answer now, or one the test settles later (see {@link deferred}). */
type Answer = WireAnswer | Promise<WireAnswer>;

/**
 * What the backend answers, by `"METHOD /path"` — the path whole, query
 * included. A key ending in `*` answers every path it is a prefix of that no
 * exact key names. A list answers in turn, its last answer repeating, so a
 * re-read or a retry is stated by what it gets back.
 */
export type Answers = Record<string, Answer | readonly Answer[]>;

/** One request the page sent, as the backend saw it: method, path and parsed JSON body. */
interface SentRequest {
  body?: unknown;
  method: string;
  path: string;
}

/** An answer the test gives when it chooses, so it controls the order outcomes land in. */
export interface Deferred {
  answer: Promise<WireAnswer>;
  /** Gives the answer, and lets everything it causes render. */
  settle: (answer: WireAnswer) => Promise<void>;
}

export function deferred(): Deferred {
  let resolve!: (answer: WireAnswer) => void;
  const answer = new Promise<WireAnswer>((settle) => {
    resolve = settle;
  });
  return {
    answer,
    settle: (given) =>
      act(async () => {
        resolve(given);
        // A macrotask, so the whole fetch → decode → state chain has drained.
        await new Promise((done) => setTimeout(done, 0));
      }),
  };
}

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
function meAnswer(session: StartingSession): Answer {
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

async function toResponse(answer: Answer): Promise<Response> {
  const given = await answer;
  if ("unreachable" in given) throw new TypeError("Failed to fetch");
  return given.body === undefined
    ? new Response(null, { status: given.status })
    : Response.json(given.body, { status: given.status });
}

const parseBody = (body: RequestInit["body"]): unknown =>
  typeof body === "string" ? JSON.parse(body) : undefined;

/**
 * The fake backend behind `fetch`: the session's own check, then `answers`.
 * Every request the page sends is recorded; one nothing answers is recorded
 * as unanswered and fails the test when it finishes.
 */
function fakeBackend(session: StartingSession, initial: Answers) {
  let answers: Answers = { ...initial };
  const turns = new Map<string, number>();
  const sent: SentRequest[] = [];
  const unanswered: string[] = [];
  let sessionChecked = false;

  const keyFor = (request: string): string | undefined => {
    if (request in answers) return request;
    return Object.keys(answers)
      .filter((key) => key.endsWith("*") && request.startsWith(key.slice(0, -1)))
      .sort((a, b) => b.length - a.length)[0];
  };

  const next = (key: string): Answer => {
    const given = answers[key];
    if (!Array.isArray(given)) return given as Answer;
    const turn = turns.get(key) ?? 0;
    turns.set(key, turn + 1);
    return given[Math.min(turn, given.length - 1)] as Answer;
  };

  const respond = (path: string, init?: RequestInit): Promise<Response> => {
    const method = (init?.method ?? "GET").toUpperCase();
    const request = `${method} ${path}`;
    // The start-up check is the session's, not the page's: answered from it,
    // and not among the page's requests.
    if (!sessionChecked && request === `GET ${ME_PATH}`) {
      sessionChecked = true;
      return toResponse(meAnswer(session));
    }
    const body = parseBody(init?.body);
    sent.push(body === undefined ? { method, path } : { body, method, path });
    const key = keyFor(request);
    if (key !== undefined) return toResponse(next(key));
    // Any later check of the session stands as the session itself answers it.
    if (request === `GET ${ME_PATH}`) return toResponse(meAnswer(session));
    unanswered.push(request);
    return toResponse(bare(501));
  };

  return {
    /** Replaces the answers to the keys named, each starting again from its first turn. */
    answer: (more: Answers) => {
      answers = { ...answers, ...more };
      for (const key of Object.keys(more)) turns.delete(key);
    },
    requests: (): readonly SentRequest[] => [...sent],
    respond,
    unanswered,
  };
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
 * `AuthProvider`, with `fetch` faked by the CSRF stand-in and a backend that
 * answers the session's start-up check and then `answers`. Resolves once the
 * start-up check has settled — except for {@link STILL_CHECKING}, which never
 * does — so the page renders for the session from the first assertion.
 *
 * Read what was sent with `requests()`, change an answer mid-test with
 * `answer()`, and see whether the page ended the session with `session()`.
 */
export async function renderAsSession(
  ui: ReactElement,
  session: StartingSession,
  { answers = {}, at = "/" }: { answers?: Answers; at?: InitialEntry } = {},
) {
  const backend = fakeBackend(session, answers);
  stubFetchWithCsrf(backend.respond);
  onTestFinished(() => {
    vi.unstubAllGlobals();
    if (backend.unanswered.length > 0) {
      throw new Error(`No answer was given for: ${backend.unanswered.join(", ")}`);
    }
  });

  let reading: SessionReading = { signInReason: null, status: "checking" };
  const onRead = (next: SessionReading) => {
    reading = next;
  };
  const rendered = render(
    <MemoryRouter initialEntries={[at]}>
      <AuthProvider>
        <SessionProbe onRead={onRead} />
        {ui}
      </AuthProvider>
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
    requests: backend.requests,
    session: (): SessionReading => reading,
  };
}

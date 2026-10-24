import { act } from "@testing-library/react";
import { onTestFinished, vi } from "vitest";

import { apiFetch, discardCsrfToken } from "./http";

// The backend layer of the test adapter: a fake backend behind the global
// `fetch`, and nothing else — no router, no provider, no React tree. A suite
// states what the backend answers, keyed by request, and reads back what was
// sent; the real `apiFetch`, the declarations and the decoders all run above
// it. The render layer (`renderAsSession`, `src/auth/session-adapter.testHelpers.tsx`)
// is built on this for the page suites; a suite that brings its own router or
// provider (`App`, the `src/auth/` suites) uses it directly.

/** A fetch that never reached the backend: the network failed, so `fetch` rejects. */
export const UNREACHABLE = { unreachable: true } as const;

/** What the backend answers one request with: a status and, for a JSON answer, its body. */
export type WireAnswer = { status: number; body?: unknown } | typeof UNREACHABLE;

/** A JSON answer: `body` serialized, under `status` (`200` unless named). */
export const json = (body: unknown, status = 200): WireAnswer => ({ body, status });

/** An answer with a status and no body: `204`, or a refusal read by status alone. */
export const bare = (status: number): WireAnswer => ({ status });

/**
 * The password check's `401` on `POST /api/auth/change-password` — a wrong
 * current password or a lockout — carrying the mark the backend gives it. A
 * bare `401` there is a request that found no live session.
 */
export const CURRENT_PASSWORD_REFUSED: WireAnswer = json({ refused: "current-password" }, 401);

/** An answer now, or one the test settles later (see {@link deferred}). */
type Answer = WireAnswer | Promise<WireAnswer>;

/**
 * What the backend answers, by `"METHOD /path"` — the path whole, query
 * included. A key ending in `*` answers every path it is a prefix of that no
 * exact key names. A list answers in turn, its last answer repeating, so a
 * re-read or a retry is stated by what it gets back.
 */
export type Answers = Record<string, Answer | readonly Answer[]>;

/** One request sent, as the backend saw it: method, path and parsed JSON body. */
export interface SentRequest {
  body?: unknown;
  /** The `Content-Type` it named, or `null` — present only when asked for. */
  contentType?: string | null;
  method: string;
  path: string;
}

/** Which of what was sent `requests()` shows beyond method, path and body. */
interface RequestsShown {
  /** The `Content-Type` each request named, for a suite proving a bodiless one names none. */
  contentType?: boolean;
  /** The token fetches, the one way to see whether a token was fetched afresh. */
  tokenFetches?: boolean;
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

/** Where the backend hands out the session's CSRF token, as `apiFetch` asks for it. */
const CSRF_TOKEN_REQUEST = "GET /api/auth/csrf";

/** The `"METHOD /path"` key a request sent is answered by, and a suite counts it by. */
export const requestKey = ({ method, path }: SentRequest): string => `${method} ${path}`;

const isTokenFetch = (request: SentRequest): boolean => requestKey(request) === CSRF_TOKEN_REQUEST;

/** The token the stand-in hands out. */
const TEST_CSRF_TOKEN = "test-token";

/** The header the stand-in names, as the backend's session repository does. */
const TEST_CSRF_HEADER = "X-CSRF-TOKEN";

/** The stand-in's answer to a token fetch. */
const TOKEN_GRANT = json({ headerName: TEST_CSRF_HEADER, token: TEST_CSRF_TOKEN });

/**
 * The unsafe request `holdToken` and `tokenForgotten` send. The backend answers
 * it `204`, and it is left out of what was sent: no suite stated it.
 */
const PROBE_PATH = "/test-only/token-probe";

/** Methods the backend exempts from the CSRF check, as `apiFetch` does. */
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/** Whether an unsafe request carries the token the stand-in issued, in the header it named. */
const carriesToken = (method: string, init?: RequestInit): boolean =>
  SAFE_METHODS.has(method) || new Headers(init?.headers).get(TEST_CSRF_HEADER) === TEST_CSRF_TOKEN;

/**
 * Whether a request names its body JSON. The backend reads a request body only
 * under `Content-Type: application/json` and refuses any other `415`
 * (`/frontend/docs/BACKEND_CONTRACT.md`).
 */
const saysJson = (init?: RequestInit): boolean =>
  new Headers(init?.headers).get("Content-Type")?.startsWith("application/json") === true;

/** The backend's refusal of a request that breaks its contract, before any answer is read. */
function contractRefusal(
  method: string,
  body: unknown,
  init?: RequestInit,
): WireAnswer | undefined {
  if (!carriesToken(method, init)) return bare(403);
  if (body !== undefined && !saysJson(init)) return bare(415);
  return undefined;
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

/** The suite's answers as the backend reads them, and the requests none of them answered. */
interface AnswerBook {
  /** The answer to one request, or the contract refusal it earned instead. */
  answerFor: (request: string, refusal: WireAnswer | undefined) => Answer;
  /** Replaces the answers to the keys named, each starting again from its first turn. */
  replace: (more: Answers) => void;
  unanswered: readonly string[];
}

function answerBook(initial: Answers): AnswerBook {
  let answers: Answers = { ...initial };
  const turns = new Map<string, number>();
  const unanswered: string[] = [];

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

  return {
    answerFor: (request, refusal) => {
      // The token endpoint answers only to its own key, never to a prefix one.
      if (request === CSRF_TOKEN_REQUEST) return request in answers ? next(request) : TOKEN_GRANT;
      if (refusal !== undefined) return refusal;
      const key = keyFor(request);
      if (key !== undefined) return next(key);
      unanswered.push(request);
      return bare(501);
    },
    replace: (more) => {
      answers = { ...answers, ...more };
      for (const key of Object.keys(more)) turns.delete(key);
    },
    unanswered,
  };
}

/** A request to another origin than the backend's: a URL with a scheme. */
const ABSOLUTE_URL = /^[a-z][a-z0-9+.-]*:\/\//i;

/** What answers a request to another origin, when a suite installed one. */
type OtherOrigin = (url: string, init?: RequestInit) => Promise<Response>;

let otherOrigins: OtherOrigin | undefined;

/**
 * Another origin's server — the local FHIRcast hub's (`fake-hub.testHelpers.ts`) — beside the
 * fake backend, for the rest of the test: every request to an absolute URL goes to `serve`. With
 * none installed such a request fails the test by name, as an unanswered backend request does.
 */
export function serveOtherOrigin(serve: OtherOrigin): void {
  otherOrigins = serve;
  onTestFinished(() => {
    otherOrigins = undefined;
  });
}

function otherOrigin(url: string, init?: RequestInit): Promise<Response> {
  if (otherOrigins === undefined) {
    return Promise.reject(new Error(`No other origin was installed to answer ${url}`));
  }
  return otherOrigins(url, init);
}

export interface FakeBackend {
  /** Replaces the answers to the keys named, each starting again from its first turn. */
  answer: (more: Answers) => void;
  /** Sends one unsafe request through `apiFetch`, so a token is held from here on. */
  holdToken: () => Promise<void>;
  /**
   * Every request sent, in order: method, path and parsed JSON body. The token
   * fetches, and each request's `Content-Type`, are left out unless asked for.
   */
  requests: (shown?: RequestsShown) => readonly SentRequest[];
  /**
   * Whether the token `apiFetch` held was forgotten, read off the wire: sends
   * the next unsafe request through it and says whether a fresh
   * `GET /api/auth/csrf` went out first. Meaningful only once a token was held
   * (see `holdToken`); a session action sent unsafe has fetched one itself.
   */
  tokenForgotten: () => Promise<boolean>;
}

/**
 * Stubs the global `fetch` with a fake backend that answers from `initial`,
 * for the rest of the test.
 *
 * It keeps the backend's side of the synchronizer-token contract
 * (`/frontend/docs/BACKEND_CONTRACT.md`) with a stand-in: `GET /api/auth/csrf`
 * hands out a token unless the suite answers that key itself, and an unsafe
 * request that does not carry the token is refused `403` before any answer is
 * read, so a caller that skipped the token cannot pass. A body sent without
 * naming it JSON is refused `415`, as the backend refuses it. Any token an
 * earlier test left in `apiFetch`'s memory is forgotten first.
 *
 * Every request is recorded. One nothing answers is answered `501` and fails
 * the test by name when it finishes, so nothing passes on a request the suite
 * never stated.
 */
export function fakeBackend(initial: Answers = {}): FakeBackend {
  const book = answerBook(initial);
  const sent: { contentType: string | null; request: SentRequest }[] = [];

  const respond = (path: string, init?: RequestInit): Promise<Response> => {
    // An absolute URL is another origin's — the local FHIRcast hub's — never the backend's.
    if (ABSOLUTE_URL.test(path)) return otherOrigin(path, init);
    const method = (init?.method ?? "GET").toUpperCase();
    const body = parseBody(init?.body);
    const refusal = contractRefusal(method, body, init);
    if (path === PROBE_PATH) return toResponse(refusal ?? bare(204));
    sent.push({
      contentType: new Headers(init?.headers).get("Content-Type"),
      request: body === undefined ? { method, path } : { body, method, path },
    });
    return toResponse(book.answerFor(requestKey({ method, path }), refusal));
  };

  discardCsrfToken();
  vi.stubGlobal("fetch", vi.fn(respond));
  onTestFinished(() => {
    vi.unstubAllGlobals();
    if (book.unanswered.length > 0) {
      throw new Error(`No answer was given for: ${book.unanswered.join(", ")}`);
    }
  });

  const tokenFetches = () => sent.filter(({ request }) => isTokenFetch(request)).length;
  const holdToken = async () => {
    await apiFetch(PROBE_PATH, { method: "POST" });
  };

  return {
    answer: book.replace,
    holdToken,
    requests: (shown = {}) =>
      sent
        .filter(({ request }) => shown.tokenFetches === true || !isTokenFetch(request))
        .map(({ contentType, request }) =>
          shown.contentType === true ? { ...request, contentType } : request,
        ),
    tokenForgotten: async () => {
      const before = tokenFetches();
      await holdToken();
      return tokenFetches() > before;
    },
  };
}

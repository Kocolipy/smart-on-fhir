import { vi } from "vitest";

import { discardCsrfToken } from "./http";

/** The token a stubbed `GET /api/auth/csrf` hands out. */
const TEST_CSRF_TOKEN = "test-token";

/** The header that stub names, as the backend's session repository does. */
const TEST_CSRF_HEADER = "X-CSRF-TOKEN";

/** Methods the backend exempts from the CSRF check, as `apiFetch` does. */
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

type Backend = (input: string, init?: RequestInit) => Promise<Response>;

/**
 * Whether an unsafe request carries the token the stub issued, in the header it
 * named. A safe one needs none.
 */
const carriesToken = (init?: RequestInit): boolean =>
  SAFE_METHODS.has((init?.method ?? "GET").toUpperCase()) ||
  new Headers(init?.headers).get(TEST_CSRF_HEADER) === TEST_CSRF_TOKEN;

/**
 * Stubs the global `fetch` with `backend`, behind a stand-in for the CSRF token
 * endpoint, and forgets any token an earlier test left in memory.
 *
 * The stand-in keeps the backend's side of the synchronizer-token contract
 * (`/frontend/docs/BACKEND_CONTRACT.md`): an unsafe request that does not
 * carry the token it issued is refused `403` here, before `backend` sees it,
 * so a caller that skipped the token cannot pass a test.
 *
 * For a test about something other than CSRF: `apiFetch` fetches a token before
 * its first unsafe request, and answering that here keeps `backend` — usually a
 * `mockResolvedValueOnce` sequence — seeing exactly the requests the test is
 * about. Assert on `backend` for those; the returned mock records every call,
 * the token fetches included.
 */
export function stubFetchWithCsrf(backend: Backend) {
  discardCsrfToken();
  const outer = vi.fn((input: string, init?: RequestInit) => {
    if (input === "/api/auth/csrf") {
      return Promise.resolve(
        Response.json({ headerName: TEST_CSRF_HEADER, token: TEST_CSRF_TOKEN }),
      );
    }
    if (!carriesToken(init)) return Promise.resolve(new Response(null, { status: 403 }));
    return backend(input, init);
  });
  vi.stubGlobal("fetch", outer);
  return outer;
}

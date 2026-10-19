/**
 * The one place the SPA talks to the backend.
 *
 * It owns session credentials, CSRF recovery, HTTP status meaning, and successful
 * response decoding. Feature modules receive semantic results rather than raw
 * `Response` objects.
 */

/** Methods the backend exempts from the CSRF check. */
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * Where the session's CSRF token comes from: a public `GET` answering
 * `{ headerName, token }` in its body. It creates a session for a guest, so a
 * login form can obtain the token its own submission needs.
 */
const CSRF_TOKEN_PATH = "/api/auth/csrf";

/** Plain-object headers only, so a request can be merged without a `Headers` copy. */
export type ApiRequestInit = Omit<RequestInit, "headers"> & {
  headers?: Record<string, string>;
};

/**
 * `E` is what a caller-supplied failure decoder reads out of an unsuccessful
 * response's body. It defaults to `never`, so a result requested without one
 * carries no `detail` to read.
 */
export type ApiResult<T, E = never> =
  | { kind: "ok"; data: T }
  | { kind: "unauthenticated"; detail?: E }
  | { kind: "forbidden" }
  | { kind: "csrf-expired" }
  | { kind: "failed"; status?: number; detail?: E };

/**
 * Copy for a `csrf-expired` result, owned here because the condition is a
 * transport one that no feature has an opinion about. Feature-specific copy for
 * a `failed` result stays with the feature that knows what failed.
 */
export const CSRF_EXPIRED_MESSAGE = "Your security token expired. Please try again.";

/**
 * Copy for a `forbidden` result: the backend refused the request on
 * authorization, so retrying it will not help. Owned here beside
 * `CSRF_EXPIRED_MESSAGE` so every feature says the same thing.
 */
export const FORBIDDEN_MESSAGE = "You don't have permission to do this.";

/**
 * Reads a body into its type, throwing when it does not fit. Build one from a
 * body decoder with `jsonDecoder` (`./decode`) rather than casting
 * `response.json()`.
 */
export type ApiDecoder<T> = (response: Response) => Promise<T> | T;

/** The token as the backend hands it out, with the header it is to be sent in. */
interface CsrfGrant {
  headerName: string;
  token: string;
}

/**
 * The current session's CSRF token, in memory only.
 *
 * Never in a cookie, `localStorage` or React state: the backend binds the token
 * to the HTTP session, so it is worth exactly as long as that session, and the
 * standard this follows forbids putting it where script-readable storage or a
 * cookie would outlive it. `undefined` until the first unsafe request asks for
 * one, and again whenever the session changes.
 */
let csrf: CsrfGrant | undefined;

const isUnsafe = (method?: string): boolean => !SAFE_METHODS.has((method ?? "GET").toUpperCase());

/**
 * Whether a token answer is well formed: both fields non-empty strings.
 * `Object()` boxes a primitive and turns `null` into an empty object, so any
 * answer that is not an object simply lacks the fields.
 */
const isCsrfGrant = (body: unknown): body is CsrfGrant => {
  const { headerName, token } = Object(body) as Partial<CsrfGrant>;
  return (
    typeof headerName === "string" && headerName !== "" && typeof token === "string" && token !== ""
  );
};

/**
 * Forgets the token, because the session it belonged to has changed.
 *
 * Login rotates the session id and the backend drops the pre-login token;
 * logout and a password change end the session outright. Either way the token
 * held here is dead, and the next unsafe request fetches the new session's
 * before it is sent rather than spending a `403` to find that out.
 */
export function discardCsrfToken(): void {
  csrf = undefined;
}

/**
 * Fetches the session's token into memory, and says whether one arrived.
 *
 * Anything but a `2xx` carrying a well-formed `{ headerName, token }` — or no
 * response at all — leaves no token held, so a half-read answer can never be
 * sent as if it were one.
 */
async function fetchCsrfToken(): Promise<boolean> {
  discardCsrfToken();
  try {
    const response = await fetch(CSRF_TOKEN_PATH, { credentials: "include" });
    if (!response.ok) return false;
    const body: unknown = await response.json();
    if (!isCsrfGrant(body)) return false;
    csrf = { headerName: body.headerName, token: body.token };
    return true;
  } catch {
    return false;
  }
}

/** The request as sent: credentials always, and the token on an unsafe method only. */
function withCsrf(init: ApiRequestInit, grant: CsrfGrant | undefined): RequestInit {
  if (grant === undefined) return { credentials: "include", ...init };
  return {
    credentials: "include",
    ...init,
    headers: { ...init.headers, [grant.headerName]: grant.token },
  };
}

/**
 * A failure body is evidence, not a contract: one that does not decode leaves
 * the result a plain `failed` with its status, rather than turning it into a
 * transport failure that has lost the status too.
 */
async function readDetail<E>(response: Response, decode: ApiDecoder<E>): Promise<E | undefined> {
  try {
    return await decode(response);
  } catch {
    return undefined;
  }
}

/** The `detail` a failure decoder reads, or no `detail` key at all for a caller that passed none. */
async function detailOf<E>(
  response: Response,
  decode: ApiDecoder<E> | undefined,
): Promise<{ detail?: E }> {
  return decode === undefined ? {} : { detail: await readDetail(response, decode) };
}

export function apiFetch(path: string, init?: ApiRequestInit): Promise<ApiResult<void>>;
export function apiFetch<T>(
  path: string,
  init: ApiRequestInit,
  decode: ApiDecoder<T>,
): Promise<ApiResult<T>>;
export function apiFetch<T, E>(
  path: string,
  init: ApiRequestInit,
  decode: ApiDecoder<T> | undefined,
  decodeFailure: ApiDecoder<E>,
): Promise<ApiResult<T, E>>;

/**
 * Performs an API request and returns its meaning rather than a raw response.
 *
 * An unsafe request carries the session's CSRF token, fetched first if none is
 * held. A `403` is either a stale token or an authorization refusal. CSRF
 * applies only to unsafe methods, so a safe request's `403` is `forbidden` at
 * once. An unsafe request re-fetches the token and retries exactly once; a
 * `403` on the retry was sent with a token just issued, so it too is
 * `forbidden`. `csrf-expired` is left for the one case where no token could be
 * obtained at all. Successful body decoding is explicit, so no-content
 * responses remain type-safe, and so is failure body decoding: only a caller
 * passing `decodeFailure` gets a `detail`, on a `401` as on any other
 * unsuccessful status but `403`.
 */
export async function apiFetch<T, E>(
  path: string,
  init: ApiRequestInit = {},
  decode?: ApiDecoder<T>,
  decodeFailure?: ApiDecoder<E>,
): Promise<ApiResult<T | void, E>> {
  try {
    const unsafe = isUnsafe(init.method);
    if (unsafe && csrf === undefined && !(await fetchCsrfToken())) {
      return { kind: "csrf-expired" };
    }

    let response = await fetch(path, withCsrf(init, unsafe ? csrf : undefined));
    if (response.status === 403 && unsafe) {
      if (!(await fetchCsrfToken())) return { kind: "csrf-expired" };
      response = await fetch(path, withCsrf(init, csrf));
    }

    if (response.status === 401) {
      return { kind: "unauthenticated", ...(await detailOf(response, decodeFailure)) };
    }
    if (response.status === 403) return { kind: "forbidden" };
    if (!response.ok) {
      return {
        kind: "failed",
        status: response.status,
        ...(await detailOf(response, decodeFailure)),
      };
    }
    if (decode === undefined) return { kind: "ok", data: undefined };

    // A success body is a contract, unlike a failure body: one that does not
    // decode lands in the catch below as a plain `failed` with **no** `status`,
    // so a page shows its own failure copy instead of rendering partial data,
    // and nothing reads the `2xx` as meaningful.
    return { kind: "ok", data: await decode(response) };
  } catch {
    return { kind: "failed" };
  }
}

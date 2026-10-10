# 9. CSRF: a session-bound synchronizer token, never a cookie

Date: 2026-10-01

## Status

Accepted. Reverses the original design's double-submit cookie choice for CSRF.

## Context

The application chain used the double-submit cookie pattern:
`CookieCsrfTokenRepository.withHttpOnlyFalse()` behind `csrf.spa()`. Every
response could set a script-readable `XSRF-TOKEN` cookie, the SPA read it from
`document.cookie`, and it echoed the value in `X-XSRF-TOKEN`. Login and logout
re-issued the cookie, and the SPA re-seeded it by calling `GET /api/auth/me`.

An App-Standards compliance audit found that the access-control standard
(Standalone User Access Control Application Standard §3.1, and the shared recipe
"Common Security Headers and SPA CSRF Configuration") strictly prohibits
cookie-based CSRF. It requires:

- the Synchronizer Token Pattern bound to the HTTP session, using
  `HttpSessionCsrfTokenRepository` with the default XOR-masking handler;
- a dedicated, uncacheable endpoint that returns the token in the response body;
- no CSRF cookie at all.

The cookie also could not meet §3.5, which requires `HttpOnly=true` on CSRF
cookies. The SPA has to read a double-submit cookie, so that cookie cannot be
`HttpOnly`.

## Decision

- **Backend.** `HttpSessionCsrfTokenRepository` replaces the cookie repository,
  and the default `XorCsrfTokenRequestAttributeHandler` replaces `spa()`. The
  new endpoint `GET /api/auth/csrf` is `permitAll`. It returns
  `{ "headerName": "X-CSRF-TOKEN", "token": "…" }` with `Cache-Control: no-store`.
  For a guest it creates the session the token is bound to, so the login form
  can get the token its own submission needs. No response writes a CSRF cookie.
- **Login** rotates the session id (`ChangeSessionIdAuthenticationStrategy`).
  Rotation keeps the session's attributes, so login also deletes the pre-login
  token explicitly. A token fetched before login is refused after it.
- **Logout and the password change** invalidate the session, and the token goes
  with it. Logout stays CSRF-protected, as the standard forbids exempting it.
  On a session that has already expired, a logout is refused `403`. A retry
  with a freshly fetched token belongs to a new anonymous session, so it is
  refused `401`.
- **The SCIM chain** is unchanged. It is stateless and bearer-authenticated, and
  CSRF is disabled in that chain only.
- **Frontend.** `src/lib/http.ts` keeps the token in memory only. It fetches the
  token before the first unsafe request and sends it in the header the endpoint
  names. On a `403` to an unsafe request it fetches a new token and retries
  once, keeping the existing single-retry contract. `src/auth/` calls
  `discardCsrfToken()` whenever the session changes: after login, logout, a
  password change, and an expiry — and, since the 2026-10-09 addendum, after a
  refused login.
- **Held by the build.** The `be-csrf-cookie-token` Semgrep rule forbids
  `CookieCsrfTokenRepository`, `SpaCsrfTokenRequestHandler` and `csrf.spa()`.
  `SecurityConfigTests` asserts that the chain's `CsrfFilter` holds the session
  repository and the XOR handler.

## Consequences

- An unsafe request from a cold SPA costs one extra round trip, to fetch the
  token. Later requests reuse it until the session changes.
- Every guest who asks for a token gets a server-side session, which is why the
  endpoint exists. A plain page view still creates no session.
- A token is now worth exactly as long as its session. A test harness that
  shifts the application clock (the absolute session lifetime compares a
  session's real creation time with that clock) must stamp the session at the
  shifted time before using its token. See `PasswordChangeLifecycleIntegrationTests`
  and `AuditListingEndToEndIntegrationTests`.
- An API client outside the SPA uses the same handshake: call `GET
  /api/auth/csrf` with the session cookie, then send the token in the named
  header. `backend/README.md` shows it with `curl`.

## Addendum (2026-10-09): a refused login ends the session, and its token

A refused password Login now invalidates whatever session the browser held
before answering its bare `401` — the password analogue of ADR 0013's D24, so a
shared browser is never left signed in as the previous User after a sign-in
that signed nobody in. The token was bound to that session, so it ends with it:
`src/auth/api.ts` calls `discardCsrfToken()` when `login()` is answered
`unauthenticated`, and a retry after a wrong password fetches the next session's
token from `GET /api/auth/csrf` instead of meeting a `403`. A refused Login that
arrived with no session creates none. The token's design is otherwise unchanged:
session-bound, in memory only, never a cookie.

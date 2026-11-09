---
type: Decision Record
title: "ADR 0009: CSRF synchronizer token"
description: CSRF uses a session-bound synchronizer token fetched from GET /api/auth/csrf and held in memory by the SPA, never a cookie.
tags: [csrf, sessions, spa]
---

# 9. CSRF: a session-bound synchronizer token, never a cookie

Date: 2026-10-01

## Status

Accepted.

## Context

The application chain authenticates by session cookie, so every unsafe request
needs CSRF protection. The access-control standard (Standalone User Access
Control Application Standard §3.1, and the shared recipe "Common Security
Headers and SPA CSRF Configuration") strictly prohibits cookie-based CSRF. It
requires:

- the Synchronizer Token Pattern bound to the HTTP session, using
  `HttpSessionCsrfTokenRepository` with the default XOR-masking handler;
- a dedicated, uncacheable endpoint that returns the token in the response body;
- no CSRF cookie at all.

## Decision

- **Backend.** The chain's `CsrfFilter` holds `HttpSessionCsrfTokenRepository`
  and the default `XorCsrfTokenRequestAttributeHandler`. `GET /api/auth/csrf`
  is `permitAll`. It returns
  `{ "headerName": "X-CSRF-TOKEN", "token": "…" }` with `Cache-Control: no-store`.
  For a guest it creates the session the token is bound to, so the login form
  can get the token its own submission needs. No response writes a CSRF cookie.
- **Login** rotates the session id (`ChangeSessionIdAuthenticationStrategy`).
  Rotation keeps the session's attributes, so login also deletes the pre-login
  token explicitly. A token fetched before login is refused after it.
- **A refused Login** ends whatever session the browser held before answering
  its bare `401` (ADR 0001, step 5), so the token bound to it ends too. A refused
  Login that arrived with no session creates none.
- **Logout and the password change** invalidate the session, and the token goes
  with it. Logout is CSRF-protected, as the standard forbids exempting it.
  On a session that has already expired, a logout is refused `403`. A retry
  with a freshly fetched token belongs to a new anonymous session, so it is
  refused `401`.
- **The SCIM chain** is stateless and bearer-authenticated, and CSRF is disabled
  in that chain only.
- **Frontend.** `src/lib/http.ts` keeps the token in memory only. It fetches the
  token before the first unsafe request and sends it in the header the endpoint
  names. On a `403` to an unsafe request it fetches a new token and retries
  once, its single-retry contract. `src/auth/session-transitions.ts`
  calls `discardCsrfToken()` whenever the session changes, a refused login
  included, so a retry after a wrong password fetches the next session's token
  instead of meeting a `403`; a token is held only for the session that fetched
  it. `frontend/docs/BACKEND_CONTRACT.md` lists every change that discards it.
- **Held by the build.** The `be-csrf-cookie-token` Semgrep rule forbids
  `CookieCsrfTokenRepository`, `SpaCsrfTokenRequestHandler` and `csrf.spa()`.
  `SecurityConfigTests` asserts that the chain's `CsrfFilter` holds the session
  repository and the XOR handler.

## Consequences

- An unsafe request from a cold SPA costs one extra round trip, to fetch the
  token. Later requests reuse it until the session changes.
- Every guest who asks for a token gets a server-side session, which is why the
  endpoint exists. A plain page view still creates no session.
- A token is worth exactly as long as its session. A test harness that
  shifts the application clock (the absolute session lifetime compares a
  session's real creation time with that clock until a Login signs it in, and
  the Login's instant after) must stamp the session at the shifted time before
  using its token. See `PasswordChangeLifecycleIntegrationTests`
  and `AuditListingEndToEndIntegrationTests`.
- An API client outside the SPA uses the same handshake: call `GET
/api/auth/csrf` with the session cookie, then send the token in the named
  header. `backend/README.md` shows it with `curl`.

## Alternatives considered

**The double-submit cookie** (`CookieCsrfTokenRepository.withHttpOnlyFalse()`
behind `csrf.spa()`): a script-readable `XSRF-TOKEN` cookie the SPA echoes in
`X-XSRF-TOKEN`. Rejected: §3.1 prohibits cookie-based CSRF, and the cookie
cannot meet §3.5, which requires `HttpOnly=true` on CSRF cookies, because the
SPA has to read it.

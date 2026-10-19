---
type: Contract
title: Backend contract
description: The runtime contract the SPA honours with the backend — CSRF synchronizer token, 401 versus 403, JSON request bodies, the session bounds, one session per User, Epic Login's landing, logout's Clear-Site-Data, and the CSP.
tags: [csrf, sessions, csp, contract]
---

# Backend contract

This file is the authority on the runtime contract with the backend — read it
before changing anything that issues a request, and update it here, in the same
change, when the backend side moves. What the SPA has to honour:

- **Every unsafe request carries the session's CSRF token.** The backend uses
  the Synchronizer Token Pattern: the token is bound to the HTTP session, and a
  `POST` / `PUT` / `PATCH` / `DELETE` without it — or with another session's, or
  with one fetched before login rotated the session — comes back `403`. There is
  no CSRF cookie. `src/lib/http.ts` is the only place that deals with this:
  `apiFetch()` fetches the token from `GET /api/auth/csrf` (public; it opens a
  session for a guest, which is what the login form needs), holds it **in memory
  only**, sends it in the header that response names (`X-CSRF-TOKEN`) on unsafe
  methods only, and always sends `credentials: "include"`. The token is worth
  exactly as long as its session, so `src/auth/` calls `discardCsrfToken()`
  whenever the session changes — after login, a refused login, logout, a
  password change or the lockout it runs into, an expiry, an Idle sign-out, and
  the start-up `GET /api/auth/me` check settling the session either way — and
  the next unsafe request fetches the new session's before it is sent.
  `src/auth/session-transitions.ts` is the one place that call is made: each of
  its transitions (`signIn`, `end(reason)`) changes the auth state and forgets
  the token in the same step, so no caller can do one without the other. A
  start-up check that did not answer (anything but `200` or `401`) settles
  nothing: the status becomes `unchecked`, the token is kept, nobody is routed
  to login, and the guards show one line saying the session could not be
  checked, with a retry under the same rules. A refused login is one of them because the
  backend ends whatever session the browser held before answering its bare
  `401`, so a retry after a wrong password needs the next session's token
  rather than meeting a `403`. `/docs/adr/0009-csrf-synchronizer-token.md` records why the cookie
  design was retired.
- **`403` is not `401`, and not always CSRF.** A `403` is either a missing or
  stale CSRF token or an authorization refusal (a session lacking the
  operation's Permission, a session confined by a required password change, an
  administrator acting on its own account). CSRF applies only to unsafe methods, so `apiFetch` returns a safe
  request's `403` as `forbidden` at once, with no re-fetch. An unsafe request's
  `403` re-fetches the token and retries once; a `403` on the retry was sent
  with a fresh token, so it is `forbidden` too. `csrf-expired` is left for a
  token fetch that fails — it throws, answers anything but `2xx`, or carries no
  well-formed `{ headerName, token }`. Both preserve the auth state:
  pages show `FORBIDDEN_MESSAGE` ("You don't have permission to do this.") or
  `CSRF_EXPIRED_MESSAGE`, read from `useSessionRequest`, and neither ever ends
  the session. A `401` returns
  `unauthenticated`, which `useSessionRequest` acts on centrally: it ends the
  session and `ProtectedRoute` sends the user to login, the redirect carrying
  the sign-in reason `expired` so the login page says the session ended. Treating `403` as `401`
  looks like a random sign-out to the user.
- **The password change marks the `401` its password check gives.** A wrong
  current password, a lockout and an inactive account are all refused with
  one `401` carrying the one constant body `{"refused":"current-password"}`
  (`PasswordChangeRefusal`), which says the check answered and never why. A
  request with no live session — expired, revoked, never signed in — is
  refused by the filter chain before the check with the bare `401` every
  operation gives it, so nothing is compared and no failed attempt is counted.
  `changePassword` (`src/auth/api.ts`) reads a `401` without the mark as the
  outcome `expired` — the ordinary `401` path above, ending the session with
  the sign-in reason `expired` — and only a marked one sends it to probe
  `GET /api/auth/me`: a session still standing is a wrong current password, a
  session gone is the lockout that ended it. Probing on a bare `401` would read
  every expired session as a lockout. `apiFetch` hands a caller that passes a
  failure decoder the `401`'s body as `detail`, exactly as it does for any
  other unsuccessful status but `403`.
- **A patient read answers FHIR, and `404` there means "not there".**
  `GET /api/patient/{id}` answers `application/fhir+json; fhirVersion=5.0`
  whatever the status — an R5 `Patient`, or for every error an
  `OperationOutcome` with one issue and fixed diagnostics (`400` `invalid`,
  `401` `login`, `403` `forbidden`, `404` `not-found`, `405` `not-supported`,
  `5xx` `exception`; `/docs/domain/patient.md`, "Errors") — and the SPA sends
  no `Accept` header that would refuse it. The SPA reads none of an outcome:
  the status alone classifies it, so the `401` / `403` split holds here as
  everywhere. Its declaration (`PATIENT`, `src/pages/home-api.ts`) opts in to
  `absentOnNotFound`, so that `404` is the Patient card's "No patient in
  context" and reports nothing to the page status; every other failure — a
  `400`, a `403` (the card passes `forbiddenAsFailure`) or a `5xx` — is the
  card's failed state, "Couldn't load patient".
- **A request body is JSON, and says so.** The backend reads a request body
  through its JSON converter only, so a body sent without
  `Content-Type: application/json` is refused `415` before any handler runs.
  The SPA's bodies are built in two places, `src/auth/api.ts` (login, password
  change) and `useGatedWrite`, and both name that type; a bodiless request
  names none. The unit suites' fake backend keeps this rule, so a body sent
  under any other type fails there too.
- **Features reach the backend through `useSessionRequest`; only `src/auth/`
  reaches `apiFetch`.** A direct `fetch` call puts the CSRF handling in one more
  place that can drift, and a direct `apiFetch` call puts the session-ending
  rule back in the feature. Playwright's `page.request` bypasses both: copy
  `resetCounterViaApi()` in `test/e2e/auth.helpers.ts` for an API call from a
  spec.
- **Sessions expire after 15 minutes** of inactivity, the single default in
  every environment. The SPA never hardcodes that figure: `GET /api/auth/me`
  and the login response carry `idleTimeoutSeconds`, read off the session
  itself, and `src/auth/idle-sign-out.tsx` times its own sign-out by it. That
  sign-out counts only user input (pointer, key, touch, wheel, scroll) as
  activity, never a request, and shares it across tabs over a
  `BroadcastChannel`. A minute before the limit an `alertdialog` offers to stay
  signed in, which is a `GET /api/auth/me` sent through `useSessionRequest` —
  so a session already ended takes that seam's one `401` rule — and renews the
  backend's idle clock too; at the limit the SPA calls logout, discards the CSRF token and
  sends the user to login with the sign-in reason `inactive`. A session the backend ended first
  still takes the ordinary `401` path above.
- **Sessions are also capped at 8 hours from creation**, independent of the
  15-minute idle bound above: a session kept continuously active is still
  ended once it has existed that long. Both bounds apply to every
  authenticated session, a Superuser's included, and whichever is reached first ends
  it — there is no way to distinguish the two from the SPA's side; either one
  simply presents as the ordinary `401` → `unauthenticated` → sign-out path
  described above.
- **One session per User.** A login ends every other session the same User
  holds, so signing in from a second browser or profile signs the first
  out; the first presents as the ordinary `401` path above. Epic Login counts
  too: a password Login and an EHR launch for the same User share the rule.
- **Epic Login reaches the SPA as a full-page navigation, never a request the
  SPA makes.** Epic opens `GET /api/auth/epic/launch` in the clinician's
  browser; the backend redirects through `GET /api/auth/epic/authorize` to
  Epic and back to `GET /api/auth/epic/callback`, and on success answers
  `302 /` with the session already signed in (session id rotated, pre-login
  CSRF token dropped). The SPA does nothing special: its ordinary start-up
  `GET /api/auth/me` answers `authenticated`, and `/` renders Home. Only the
  failure landings are on the login page: a refused launch lands at
  `/login?signin=refused` with its session ended — whoever's it was — and the
  login page shows only the neutral "Sign-in from Epic was refused"; the
  refusal's reason never reaches the browser. The second outcome is Epic being
  unavailable — no answer within the backend's timeouts, or an Epic `5xx`, on
  discovery, the JWKS or the token call — which lands at
  `/login?signin=unavailable`, its session ended the same way, and the login page
  shows "Sign-in from Epic is temporarily unavailable. Try again shortly."
  instead. The two are distinct on purpose (D23): a refused clinician should not
  retry, an unavailable one should. Neither marker carries any detail, and
  either notice wins over router state. `src/auth/sign-in-reason.ts` is the
  only place that reads the markers, decides that precedence, or holds the
  copy; the login page renders what its `signInNoticeFor` returns. All four Epic routes (the three
  above and the public `jwks.json`) are `404` while `APP_EPIC_ENABLED` is off.
  The SPA never calls them, and they need no CSRF token: each is a `GET`.
- **Logout answers `Clear-Site-Data: "cache","cookies","storage"`** — on a
  successful logout and on the `401` a logout with no live session gets. The
  browser drops anything in `localStorage` / `sessionStorage` along with the
  session cookie. Nothing in the SPA may rely on storage surviving logout. The
  CSRF token is unaffected: it lives in memory only and in the session the
  logout ended, and the SPA discards it on logout anyway.
- **The CSP forbids inline script, `eval`, and every third-party origin** for
  scripts, styles, fonts, images and `fetch`. Self-host instead of adding a CDN,
  and prefer Vite plugins that keep their output out of an inline `<script>`.
  Inline _styles_ are allowed.

---
type: Contract
title: Backend contract
description: The runtime contract the SPA honours with the backend — CSRF synchronizer token, 401 versus 403, JSON request bodies, the session bounds, one session per User, Epic Login's landing and the EHR context its Launch context seeds, the FHIRcast release, the FHIRcast connection's session-long lifetime and what the tab keeps of it, logout's Clear-Site-Data and FHIRcast cleanup, and the CSP with its loopback hub entries.
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
  the next unsafe request fetches the new session's before it is sent. A token
  is held only for the session that fetched it: a token fetch still in flight
  when the token is discarded hands what it fetched to the request that asked
  for it, sent under that session too, but holds nothing, so a sign-in or
  sign-out landing mid-fetch never leaves the old session's token held for the
  new one. `apiFetch` notes each discard to tell, importing nothing from
  `src/auth/`.
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
  the session. The filter chain's `401` and `403` both carry the generic
  `ApiError` body (`code` `request-refused`), the same bytes for every cause;
  the SPA reads neither and classifies by the status alone. A `401` returns
  `unauthenticated`, which `useSessionRequest` acts on centrally: it ends the
  session and `ProtectedRoute` sends the user to login, the redirect carrying
  the sign-in reason `expired` so the login page says the session ended. A
  `401` ends only the session its request was sent under: the seam reads the
  session generation (`session-transitions.ts`) as it sends, and a `401`
  landing after a sign-out or a fresh sign-in — a read still in flight when
  the user signed out — changes nothing, so the login page shows no
  `expired` and a new session is never ended by its predecessor's answer. The
  caller still gets its `failed` `401`, which it discards or ignores as ever. Treating `403` as `401`
  looks like a random sign-out to the user.
- **The password change marks the `401` its password check gives.** A wrong
  current password, a lockout and an inactive account are all refused with
  one `401` carrying the one constant body `{"refused":"current-password"}`
  (`PasswordChangeRefusal`), which says the check answered and never why. A
  request with no live session — expired, revoked, never signed in — is
  refused by the filter chain before the check with the `401` every operation
  gives it — the generic `ApiError`, which carries no mark — so nothing is
  compared and no failed attempt is counted.
  `changePassword` (`src/auth/api.ts`) reads a `401` without the mark as the
  outcome `expired` — the ordinary `401` path above, ending the session with
  the sign-in reason `expired` — and only a marked one sends it to probe
  `GET /api/auth/me`: a session still standing is a wrong current password, a
  session gone is the lockout that ended it. Probing on an unmarked `401` would read
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
  everywhere. The Patient card sends it only for a patient in context, so a
  session with none sends no patient read at all. Its declaration (`PATIENT`,
  `src/pages/home-api.ts`) opts in to
  `absentOnNotFound`, so that `404` is the Patient card's "No patient in
  context" and reports nothing to the page status; every other failure — a
  `400`, a `403` (the card passes `forbiddenAsFailure`) or a `5xx` — is the
  card's failed state, "Couldn't load patient". The `{id}` is the patient's
  EHR's FHIR id: an id outside FHIR's id grammar, `[A-Za-z0-9\-.]{1,64}`
  (over 64 characters, markup, a line break, an empty segment, an encoded
  slash such as `a%2F%2Fb`), is the `400`;
  a well-formed id no patient is held under is the `404`. The SPA sends the
  in-context patient's id through `encodeURIComponent`.
- **A request body is JSON, and says so.** The backend reads a request body
  through its JSON converter only, so a body sent without
  `Content-Type: application/json` is refused `415` before any handler runs.
  The SPA's bodies are built in two places, `src/auth/api.ts` (login, password
  change) and `useGatedWrite`, and both name that type; a bodiless request
  names none. The unit suites' fake backend keeps this rule, so a body sent
  under any other type fails there too.
- **Unlock is the one write on a User, and only on a locked one.**
  `POST /api/admin/accounts/{id}/unlock` under `account:unlock` answers `200`
  with the User's row as it now stands, which the Accounts page puts in place
  of the old one (`UNLOCK_USER`, `src/pages/accounts-api.ts`). An Unlock of a
  User serving no lockout — the Bootstrap Admin's always, and most often one
  another administrator unlocked after the listing was read — is refused `409`
  with the generic `ApiError` (`request-refused`) and changes nothing; the
  caller's own account is `403`, an unknown id `404`. The SPA offers Unlock only
  on a locked row that is not the caller's and reads the status alone. "The
  caller's" is decided by stable id, as the backend decides it: `GET
/api/auth/me` and the login response carry `id`, the stable id the session is
  signed in to, and the caller's row is the one whose `id` equals it — never the
  one whose `userName` matches `username`, which a connector can rename
  mid-session or hand to another User.
- **A Group's Permissions are set by one `PUT`, the complete set, by the
  Superuser alone.** `PUT /api/admin/groups/{id}/permissions` takes a JSON
  array of delegatable Permission names (`GET /api/admin/permissions` lists
  them) and replaces the Group's set with it — an empty array clears it, a
  name sent twice is carried once, and there is no ETag or `If-Match`: the last
  writer wins. It answers `200` with the Group's row as it now stands, the same
  shape as a row of a `GET /api/admin/groups` page, which the Accounts page puts
  in place of the old one on the page shown (`SET_GROUP_PERMISSIONS`,
  `src/pages/accounts-api.ts`). Each
  refusal carries the generic `ApiError`: `400` (`invalid-request`) for a
  missing, non-JSON or non-array body, an element that is not a string, or a
  name that is not delegatable (`user:write`, `group:write`, `patient:read`, an
  unknown name), or a path id that is not a UUID; `404` (`request-refused`) for
  a Group that does not exist or was deleted; `409` (`request-refused`) for the
  Admin group, whose members' power is the Superuser rather than a Permission;
  and the generic `403` for a session that is not the Superuser's, whatever it
  holds. The SPA offers it only to `superuser: true`, never on the Admin group,
  and reads the status alone. Taking a Permission away ends every direct
  member's sessions after the commit, which those members' browsers meet as the
  ordinary `401` path above; adding one reaches them at their next sign-in.
- **Pages reach the backend through the session seam; only `src/auth/`
  reaches `apiFetch`.** A page requests through `useGatedRead` /
  `useGatedWrite`, which go through `useSessionRequest`. A direct `fetch` call
  puts the CSRF handling in one more place that can drift, and a direct
  `apiFetch` call puts the session-ending rule back in the page. Playwright's `page.request` bypasses both: copy
  `postAdminAction()` in `test/e2e/auth.helpers.ts`, which fetches the
  session's CSRF token and echoes it, for an API call from a spec.
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
- **Sessions are also capped at 8 hours from creation**, however active.
  Whichever bound is reached first ends the session, and the SPA cannot tell
  which: either presents as the ordinary `401` path above.
- **One session per User.** A login ends every other session the same User
  holds, so signing in from a second browser or profile signs the first
  out; the first presents as the ordinary `401` path above. Epic Login counts
  too: a password Login and an EHR launch for the same User share the rule.
- **Epic Login reaches the SPA as a full-page navigation, never a request the
  SPA makes.** Epic opens `GET /api/auth/epic/launch` in the clinician's
  browser; the backend redirects through `GET /api/auth/epic/authorize` to
  Epic and back to `GET /api/auth/epic/callback`, and on success answers
  `302 /` with the session already signed in (session id rotated, pre-login
  CSRF token dropped) and the Launch context in the fragment (next item), so
  the ordinary start-up `GET /api/auth/me` renders Home. Failures land on the
  login page, signed out, with a marker carrying no
  detail: `/login?signin=refused` (a refused launch; the reason never reaches
  the browser) or `/login?signin=unavailable` (Epic gave no answer within the
  backend's timeouts, or a `5xx`, on discovery, the JWKS or the token call).
  The two are distinct on purpose (ADR 0012, D23): a refused clinician should
  not retry, an unavailable one should. Either notice wins over router state. `src/auth/sign-in-reason.ts` is the
  only place that reads the markers, decides that precedence, or holds the
  copy; the login page renders what its `signInNoticeFor` returns. All four Epic routes (the three
  above and the public `jwks.json`) are `404` while `APP_EPIC_ENABLED` is off.
  The SPA never calls them, and they need no CSRF token: each is a `GET`.
- **An Epic landing carries the Launch context in its fragment, which seeds the
  tab's EHR context, and only the tab keeps it.** The success landing is `/#patient=<id>&encounter=<id>` with
  whichever of the token response's `patient` and `encounter` is a FHIR id,
  `[A-Za-z0-9\-.]{1,64}`, in that order, and the bare marker `/#launch` when
  neither is; the refused and unavailable landings carry no fragment. The
  backend keeps neither value — not in the session, Redis or the Epic tokens —
  and a fragment is never sent back to it. `src/auth/ehr-context.ts` is the
  only place that reads it: `adoptLaunchLanding()`, called by `main.tsx` before
  the router reads the URL, takes a fragment that is the bare marker or names
  `patient` or `encounter` as a landing, checks each value against the same
  grammar (a value outside it is ignored, the other kept), keeps the result in
  `sessionStorage` under `ehr-context` — per tab — as the tab's **EHR context**,
  and strips the fragment with `history.replaceState`. Any other fragment is
  left alone. The lifecycle: every landing, the bare marker included,
  **replaces** the tab's EHR context; a reload carries no fragment and **keeps** it; every session end
  (`end(reason)` in `session-transitions.ts`: sign-out, expiry, an Idle
  sign-out, a password change that ends the session, a refused login) and a
  password Login **clear** it. What is read back from storage is held to the
  grammar again. The EHR context is a subscribable store (`useEhrContext`), so
  Home, the Patient card and the FHIRcast card's gate re-render when it
  changes, not only on load. Home's Patient card reads the patient in context through
  the patient read below, and shows "No patient in context" with none; the
  FHIRcast card shows whenever the key exists, the bare marker's included; the
  encounter is held but unused. The read is not bound to the EHR context:
  `patient:read` reads any id, so editing the fragment changes only which
  patient the same User views (ADR 0012, D8).
- **Logout answers `Clear-Site-Data: "cache","cookies","storage"`** — on a
  successful logout and on the `401` a logout with no live session gets. The
  browser drops anything in `localStorage` / `sessionStorage` along with the
  session cookie — the tab's EHR context included, which the SPA clears on
  logout itself too. Nothing in the SPA may rely on storage surviving logout. The
  CSRF token is unaffected: it lives in memory only and in the session the
  logout ended, and the SPA discards it on logout anyway.
- **An Epic session's FHIRcast release is `GET /api/auth/epic/fhircast`** (ADR 0016) — self-service, no Permission, no CSRF token (a `GET`). It answers the
  session's SMART access token, `expiresAt`, the granted `scope` (sorted),
  `hubUrl` and `hubTopic`, with `Cache-Control: no-store`; never the `id_token`
  or a refresh token. `404` — one answer for every reason — means nothing is
  released: a password session, an Epic session that kept no hub, an expired
  access token, or Epic Login off. The SPA asks for it only in a signed-in tab
  an EHR launch began (the tab holds an EHR context), through
  `useSessionRequest` (`use-session-fhircast.ts`), so a `401` ends the session
  as anywhere else: once per session, and again on each Retry. The FHIRcast
  module (`src/lib/fhircast/`, its hub protocol `protocol.ts` and its socket
  `notifications.ts`) then talks to the hub directly; no hub call goes through
  `apiFetch`.
- **The FHIRcast connection lasts the session, not a page** (ADR 0016, "The
  connection's lifetime"). The FHIRcast module holds the session's connection
  as a module-level singleton, with an idempotent start and a Retry. The
  signed-in layout starts it while the session is signed in and the tab holds
  an EHR context, on whichever signed-in page the tab is on; it keeps running
  across navigation and Home remounts, so leaving Home and coming back neither
  registers, subscribes nor reconnects again, and an event sent meanwhile is
  the card's last event on return. The FHIRcast card is only a view of it.
  Only the end of the session stops it (`endFhircast`, next items), resetting
  it so the next session connects anew.
- **The Patient card follows FHIRcast** (ADR 0016, "The events and the EHR
  context"). Every event the connection hears moves the tab's EHR context,
  on whichever signed-in page the tab is on (`followPatientEvent`, routed by
  `use-session-fhircast.ts`, so `src/lib/` still imports nothing of
  `src/auth/`): a `Patient-open` naming a FHIR id sets the patient and drops
  the encounter; one naming none is ignored; a `Patient-close`, whatever it
  names, empties the context but keeps its key, so the FHIRcast card and its
  connection stay. An event never creates a context. A new patient in context
  is a new `GET /api/patient/{id}`: the card shows "Loading the patient…" at
  once, a `404` shows "No patient in context", any other failure "Couldn't load
  patient" on the error line, and a late answer for an earlier patient never
  lands; the same patient opened again reads nothing. The context is kept as
  last known across a reload and while the connection is reconnecting,
  disconnected, unavailable or blocked; the FHIRcast card's status says the
  Patient card may be out of date.
- **What the FHIRcast module keeps is in `sessionStorage`**
  (`src/lib/fhircast/storage.ts`), per tab, as one JSON entry under
  `fhircast-connection`: the Hub Registration and its one live
  Hub Subscription. That is the hub URL and topic, the exported ES384 private
  JWK and its `kid`, the `client_id`, the token and revocation endpoints, the
  `registration_client_uri` and `registration_access_token` when the hub
  answered them, and the subscription's channel endpoint, Hub Token and the
  instant each of the two runs out. Never the SMART access token. The SPA
  clears it, and the keys an earlier version kept apiece
  (`fhircast-smart-access-token`, `fhircast-hub-token`, `fhircast-client-id`,
  `fhircast-private-jwk`), wherever it clears the EHR context — every end of
  the session and a password Login — and logout's `Clear-Site-Data` drops them
  too. An accepted deviation (ADR 0011, "FHIRcast in the browser").
- **A reload resumes the FHIRcast connection** (ADR 0016, "A reload, and one
  live subscription"). On load the signed-in layout still fetches the release.
  The module then decodes what the tab kept: anything malformed or partial is
  taken for none and cleared, and every URL in it is held to `127.0.0.1` again.
  - Kept for another hub URL or topic than the release's: torn down,
    best-effort, forgotten, and the tab connects from scratch.
  - The Hub Token and lease both unexpired: the stored channel endpoint is
    reopened, with no request to the hub, and renewal is due by the stored
    expiries.
  - Either run out, or the hub denies the endpoint: a new Hub Token is minted
    with the stored key and `client_id`, and the tab subscribes again.
  - The token endpoint refusing the stored client: the registration is
    deleted, best-effort, and the tab registers anew with the release's SMART
    access token.

  A Retry resumes the same way. Whenever the connection moves to a new
  subscription, it unsubscribes the old endpoint with the old Hub Token, then
  revokes that token, once the new socket is confirmed; each step is bounded to
  3 seconds and never stops the connection.

- **Logout's FHIRcast cleanup is the SPA's, after the backend's logout** — the
  backend never reaches the loopback hub, and its logout revokes nothing there.
  Once a logout answers signed out (and on every other end of the session),
  the FHIRcast module (`endFhircast`) unsubscribes from the topic, revokes the
  Hub Token by RFC 7009, deletes its dynamic registration by RFC 7592 and
  closes the socket, in that order, for every connection the tab registered or
  resumed and for what the tab kept, even when this page load never resumed it.
  What the tab kept is read and cleared the moment the session ends, and the
  steps run from that and from what the module holds in memory. Revocation and deletion run only when the hub advertises them,
  each step is bounded to 3 seconds, and none is awaited by the logout: a hub
  that fails or hangs never keeps the session on screen. None of the steps uses
  the SMART access token or the CSRF token. A logout that may have left the
  session standing (ADR 0009) leaves the connection standing too. Best-effort,
  an accepted deviation (ADR 0011); `Clear-Site-Data` stays the backstop.
- **The CSP forbids inline script, `eval`, and every third-party origin but the
  loopback FHIRcast Hub** for scripts, styles, fonts, images and `fetch`.
  `connect-src` is `'self'` plus exactly `http://127.0.0.1:*`,
  `https://127.0.0.1:*`, `ws://127.0.0.1:*` and `wss://127.0.0.1:*`, for Epic's
  local hub, whose port and scheme Epic chooses; no Epic origin is admitted, and
  nothing else may connect to loopback. Self-host instead of adding a CDN, and
  prefer Vite plugins that keep their output out of an inline `<script>`. Inline
  _styles_ are allowed.
- **Chrome's Local Network Access prompt** guards a public origin's requests to
  `127.0.0.1`. A clinician who refuses it sees "Local hub blocked by browser" on
  the FHIRcast card, read from the `local-network-access` permission's `denied`
  state. Synapxe can push Chrome's `LocalNetworkAccessAllowedForUrls` enterprise
  policy, listing the application's origin, so the prompt never appears on
  managed workstations. A page served from a loopback origin, as in local
  development and the E2E run, never prompts.

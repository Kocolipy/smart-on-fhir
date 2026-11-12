---
type: Testing Guide
title: Frontend E2E guide
description: The Playwright projects and the order they run in, routing a new spec, calling the API from a spec, forcing a refused request, sharing backend state under fullyParallel, and the flakiness rules.
tags: [testing, e2e, playwright]
---

# E2E guide

## The projects, and the order they run in

Playwright specs in `test/e2e/`, run against `npm run dev` on `:5173`
(`webServer` starts it, `reuseExistingServer` outside CI reuses one you already
have up). The server Playwright starts sets `E2E_DEV_SERVER=1`, so it watches no
file and opens no HMR socket (`vite.config.ts`): edits made during a run are not
served, and no page reloads under a spec. First run on a machine needs `npx playwright install chromium`.

The `setup` project signs in the fixture `user` and the Bootstrap Admin once and saves separate
Playwright `storageState` files under the ignored `test/e2e/.auth/` directory.
The `user` and `admin` projects reuse those sessions; `guest`
and `dormancy` start with explicitly empty browser storage
and sign in for real. Permission-guard specs are split by identity so a spec can
never accidentally run with a more privileged session than it names.

The backend keeps **one session per User**: an accepted login ends every other
session that User holds. A spec that signs in as a seeded identity therefore
revokes the session the matching project replays from `.auth/`, or the one
another project's sign-in holds. So the projects run as one chain, `setup` →
`user` → `admin` → `routes` → `guest` → `dormancy`, each declaring the
previous one in `dependencies`:

- `routes` holds `console-errors.spec.ts`, which asserts that every signed-in
  route, Accounts among them, loads with no alert. It follows every project
  whose specs create and delete connectors (`admin`, and `fhircast` and `epic`
  when declared): Accounts reads each listed connector's tokens, and a
  connector another worker deleted between the listing and that read showed
  an alert there.
- `guest` follows `routes` (and the `fhircast`, `epic` and `packaged` projects
  below, when declared) because `login.spec.ts` signs in as the seeded Admin,
  and beside the `admin` project it turned that project's specs into 401s.
- `dormancy` runs last and alone: it signs in as the Helpdesk fixture User
  and consumes the `dormant` fixture's locked state, which only a backend
  restart re-arms.

`login.spec.ts` also opts out of `fullyParallel` with
`test.describe.configure({ mode: "default" })`, since its sign-ins would
otherwise revoke each other. A new spec that must sign in for real should do it
as a User it provisions (see `change-password.spec.ts`), not as a seeded one.
Running one project alone still runs every project ahead of it in the chain.
`playwright.config.ts` carries the reason beside each `dependencies` line;
update both together.

The `fhircast`, `epic` and `packaged` projects sit outside that chain, declared
only by the environment `make integration-test` sets up, so a bare `npm run
test:e2e` against a backend started some other way runs without them. Each
depends on `setup` and replays
the Admin session, so `fhircast` and `epic` run beside `admin`, `packaged`
after them for the same reason `routes` does, and `guest` lists each declared
one in its `dependencies` as well: `login.spec.ts` signing in as the seeded Admin
would end the session they replay. The routing arch test reads their
`testMatch` either way:

- **`fhircast`** holds `fhircast-card.spec.ts` (Home's FHIRcast card reaching
  `Connected` after a launch, showing a published `Patient-open` and then the
  `Patient-close` that replaces it, still showing an event published once the first lease has run
  out, the hub holding only the new subscription after a renewal, reconnecting
  after the shim drops its socket, the card's final state once the shim
  counts the SMART access token expired, a reload reopening the stored
  subscription's endpoint rather than subscribing again, coming back to the
  tab once its stored lease has ended and subscribing again, absent after
  a password Login, and no SMART access token released after a password Login
  from a stale `/login` tab over the session an EHR launch in another tab
  signed in), `fhircast-patient.spec.ts` (the Patient card
  following the hub: a `Patient-open` showing a patient after a launch that
  named none, replacing the launch's patient with another, published while on
  another page and shown on Home on return, and kept across a reload; a
  `Patient-close` emptying the card with the FHIRcast card still `Connected`,
  staying empty across a reload until the next `Patient-open`) and
  `fhircast-logout.spec.ts` (after "Sign out", the
  hub holding no subscription at the card's channel endpoint, and after a
  reload no subscription left at any endpoint the tab used), and **`epic`**
  holds `epic-launch.spec.ts`. Both are declared when
  `E2E_FHIRCAST_FHIR_BASE` names Medplum's FHIR base: the backend's Epic Login
  points at the local Epic stand-in (Medplum and the FHIRcast shim), and both
  run against it. Its hub accepts the launch's SMART access token as `Bearer`,
  as Epic's does, and denies a subscription made with it on its socket once
  that token expires or is revoked; the shim's test-only
  `POST /shim/test/expire` expires one at once, so a spec need not wait one
  out (`/backend/docs/configuration/epic-login.md`, "Local Epic stand-in").
  Each replays the Admin session only to issue a SCIM token; every sign-in
  either makes, an EHR launch through Medplum, is of a User it provisions
  itself. `epic` depends on `fhircast` as well, so the two never run
  at once: its unavailable case pauses the shim `fhircast`'s specs reach.
  `fhircast.helpers.ts` holds what their specs share: Medplum's sign-in page, a
  `SmartAppLaunch`, the clinicians the Medplum seed makes, and the backend
  fixture patients it makes available under their Epic ids (Tan Wei Ming, whom
  the default launch names, Siti Nurhaliza binte Ahmad, Kumaravel and Marcus
  Anthony de Souza),
  publishing `Patient-open` / `Patient-close` to a hub topic, dropping a
  channel endpoint's sockets through the shim's test-only
  `POST /shim/test/drop`, and ending a channel endpoint's lease at once
  through `POST /shim/test/end-lease`, so no case waits a lease out.
- **`packaged`** holds `packaged-csp.spec.ts` and `packaged-type.spec.ts` and
  is declared when
  `E2E_PACKAGED_BASE_URL` names the packaged JAR. Its `baseURL` is the JAR's
  origin, so its specs load the built bundle under the backend's CSP rather
  than Vite's dev server; it signs no one in. Its route walk loads Accounts,
  so it runs after the projects that write connectors, as `routes` does.

## Routing a new spec

Each project in `playwright.config.ts` names its specs explicitly with a
`testMatch` rather than globbing `*.spec.ts`. **A
spec matched by no project's `testMatch` runs in no project and is silently
skipped** — the suite still reports green. `test/arch/e2eSpecRouting.test.ts`
reads the routing table out of the config and fails on an unmatched or
doubly-matched spec, so adding a spec means adding it to a `testMatch`.

Route a spec by the identity it needs: `user` or `admin` replays that seeded
identity's saved session, and `guest` starts signed out for specs that exercise
sign-in itself. A spec that signs in as a development fixture User, or consumes a
fixture's one-shot state, goes in its own project appended to the end of the
chain above, as `dormancy` does. Do not call `login()` in each test. Per-test `login()` under
`fullyParallel` fires N concurrent logins that can throttle and time out, and
under one session per User each would revoke the session its project replays;
shared storage state collapses that to one.

A spec that signs in only Users it provisions still needs a project whose
replayed session can issue its connector: `workerConnector` creates it from
the project's `storageState`. So `login-lockout.spec.ts` sits in `admin`, not
beside `login.spec.ts` in `guest`, which replays no session, and whose own
sign-ins as the seeded Admin would end any Admin session it held for that
connector's cleanup.

A spec that loads pages with an empty console as its subject collects through
`recordConsoleErrors` in `test/e2e/console.helpers.ts`: `smoke.spec.ts` owns the
signed-out login page, `console-errors.spec.ts` every signed-in route, and
`packaged-type.spec.ts` the packaged bundle's IBM Plex Sans 500 load under the real CSP.

## Calling the API from a spec

A **safe** request needs nothing but the jar: `session-revocation.spec.ts`
reads `GET /api/auth/me` through `page.request` directly to show the session a
page held has ended. A case that asserts only a status and a body, with no
browser behaviour around it, belongs to the backend's suites rather than here:
the Permission policy itself is `AuthorizationContractTests`', what each
development fixture holds `DevelopmentFixturesIntegrationTests`', the response
shapes `AdminAccountEndpointTests`'.
A spec that asserts a Permission is refused must assert the exact
status — `403` means the session was accepted and the Permission refused, where a `401`
would mean the request arrived unauthenticated and the spec passed for the wrong
reason.

An **unsafe** request is different. `page.request` shares the browser context's
cookie jar but adds **no headers of
its own**, so it does not satisfy the backend's CSRF contract
(`docs/BACKEND_CONTRACT.md`) — an unsafe request made that way
returns `403` and the
spec fails somewhere unrelated to what it was testing. Fetch the session's token
from `GET /api/auth/csrf` and echo it, as `csrfHeaderFor()` in
`test/e2e/auth.helpers.ts` does — after any login, which rotates the session and
discards the pre-login token; add new API fixtures beside it rather than inlining a raw
`page.request.post`. `postAdminAction()` is the fixture for the administration
endpoints, and it deliberately RETURNS the response instead of asserting on it:
a caller lacking the Permission must be refused for it, and that is only proven with
a valid token present, since a missing one earns the same `403` from the CSRF
filter first.

A spec must not change a **seeded** account's lockout or change-required state
at all. A lockout ends only by an Unlock, and an Unlock leaves the User required
to change its password, which password history refuses the old one back for, so
a seeded identity put in either state cannot be restored for the specs that sign
in as it. `accounts-admin.spec.ts` therefore PROVISIONS the Users it acts on: it
creates a connector and issues a token through the page, reads the value off the
one-time disclosure, creates two throwaway Users over SCIM with it (straight to
the backend on `SERVER_PORT`, `8080` when unset — SCIM is not behind the Vite
`/api` proxy), has each settle on a password of its own
through `POST /api/auth/change-password`, locks both with refused logins, unlocks
one from the page, and unlocks the other behind the page's back so its Unlock
button meets the `409` an Unlock of an unlocked User gets. It deletes the Users
and the connector in a `finally`. It is `test.describe.serial` because its steps
depend on each other. The refusals of an Unlock — of a User serving no lockout,
of the caller's own account, of an id no User has — and the response shapes are
covered in the backend's `AdminAccountEndpointTests` and
`PasswordChangeLifecycleIntegrationTests`, not here.

The fixtures for that pattern live in `test/e2e/scim.helpers.ts`: a connector
and token (`createConnector`, or `workerConnector` for one shared by every test a
worker runs from a file), `provisionUser` / `settlePassword` / `deprovisionUser`,
and `scimPatch`, which sends the resource's current ETag as `If-Match`. Every
User and connector a spec creates is named with the `e2e-` prefix plus a
`runId()` suffix, and a spec deletes only what carries **its own** suffix. A
spec must never sweep by prefix during a run: specs run in parallel, so a prefix
sweep deletes a fixture another spec is still using. Leftovers from a run that
died before its `finally` are swept once, by `sweepLeftovers()` in
`auth.setup.ts`, before any other project starts.

The one exception is a `userName` another server holds, which the spec
cannot give an `e2e-` name: an EHR launch through Medplum signs in the
Practitioner id Medplum holds, so its User is named with that id — the seeded
clinician's in `fhircast-card.spec.ts`'s launch case, and in its other cases
and the `fhircast-patient` and `epic` specs that of the clinician the seed
makes for each case. The seed makes each at a fixed UUID in
`compose.yaml`, the only id Medplum takes from a client, so the name is the
same on every run. Such a User is provisioned only through
`provisionServerNamedUser`, which deletes any User already holding the exact
name — the leftover `sweepLeftovers()` cannot see — before provisioning its
own. That is safe only because one spec alone provisions that name; a second
spec must not share it, which is why the seed makes the `epic` spec clinicians
of its own rather than letting it reuse the `fhircast` launch's.

A spec that needs a known **backend defect** to be visible marks the test
`test.fail(true, "#<issue>: …")`: the suite stays green while the issue is open
and turns red the moment the fix lands, so the marker cannot outlive the bug.
Remove the marker in the fix's PR.

A lockout, a password change and a Group losing a Permission each **revoke the
sessions** their User holds. Proving revocation needs a session of its own rather
than the page's — sign in through
`submitLoginViaApi` on an `APIRequestContext` with an empty `storageState`, check
it answers 200 _before_ the action (an unauthenticated jar answers 401 too, so
the assertion is vacuous without it), then expect 401 after.

A **lockout** is imposed by failed logins rather than by an endpoint, so provoking
one means submitting real credentials — and an accepted login rotates the session
id of the cookie jar it arrives in, which would destroy the session the other
specs in this project replay. Submit those attempts through an `APIRequestContext`
built with an empty `storageState` (`submitLoginViaApi` in `auth.helpers.ts` takes
one for exactly this reason), never through `page.request`.

Two specs looking at the same listing also have to agree on what they may
assume: the environment may hold accounts nobody seeded (a stray second admin, in
this repo's dev database), so address a row with `getByRole("rowheader", { exact:
true, name })` — a substring match resolves `admin` to `admin2` as well and fails
on strict mode.

## Forcing a refused request

Two branches of `apiFetch` only exist because the backend distinguishes them, so
the spec has to make a real request come back refused rather than stub the
response:

- **`401`** — drop the session cookie from the browser context and put every
  other cookie back (`expireSession()` in `test/e2e/auth.helpers.ts`). The CSRF
  token lives in the session, so it goes too: an unsafe request sent with a
  token already held is first refused `403`, and `apiFetch`'s re-fetch — which
  opens a fresh, anonymous session — then lands the retry on the `401`; one sent
  with no token held fetches that anonymous session's first. The backend
  session stays valid, so a spec doing this cannot break one running beside
  it. `session.spec.ts` meets the `401` with the password change. The backend
  refuses it before the password check, for want of a live session, so no
  password moves and no failed attempt is counted, and its `401` carries none
  of the mark the password check puts on its own refusal — which is how the SPA
  reads it as an expired session instead of probing the session for a lockout
  (`frontend/docs/BACKEND_CONTRACT.md`).
- **`403`, persistently** — rewrite the `X-CSRF-TOKEN` header with
  `page.route`. A stale token does not work: `apiFetch` answers a `403` by
  re-fetching the token and retrying once, so the retry would succeed. Rewriting
  on every attempt makes the backend reject both, and a `403` that survives the
  re-fetch is reported as `forbidden`. `session.spec.ts` does this to the
  password change too: the backend refuses it for its token, so no password
  changes.
- **`401` from a session the backend really ended** — sign the same User in
  again elsewhere, or make the SCIM write that revokes its sessions
  (`session-revocation.spec.ts`). Use a provisioned User, and make the page's
  next request one the ended session gets refused, so the spec changes nothing
  another spec reads. Home sends nothing to fire, since no patient is in
  context, so `session-revocation.spec.ts` parks the page on Settings
  (`/settings`) and submits a password change: the backend refuses it for want
  of a session before the password check, so no password moves. Were the
  session still standing, the change would apply and the spec's later sign-ins
  with the old password would fail, so the probe cannot pass silently.
- **`401` for a request held while its session ended** — hold the request
  with `page.route` and capture the session cookie (`captureSessionCookie()`)
  before the sign-out ends that session. The browser attaches cookies as a
  held request goes on, not as the page sent it, so put the captured cookie
  in the jar, send the request on with `route.continue()`, and give the jar
  back what it held once the answer is in; a session signed in since would
  otherwise answer it. `stale-401.spec.ts` asserts the answer is the
  backend's `401`, so a request the backend let through fails the test.

A test that forces a failure has to be shown to **fire**: neuter the mechanism
(keep the session cookie, drop the header rewrite), confirm the test fails, then put it
back.

## Sharing backend state under `fullyParallel`

A spec that resets shared backend state does it in the one test that writes
that state, never in a `beforeEach`: the hook runs for every test in the file,
and under `fullyParallel` a sibling's reset lands between the writing test's
own steps. A spec that exercises the request machinery rather
than a feature only reads, or only makes requests the backend refuses:
`session.spec.ts` sends two password changes, one refused for want of a live
session and one for its token, so it changes nothing another spec reads.

## Flakiness — the rules that keep these tests green

A spec that flakes despite these rules is confirmed and ledgered in
`/docs/agents/flaky-tests.md`.

Playwright auto-waits and auto-retries every web-first assertion (`toBeVisible`,
`toHaveText`, `toBeEnabled`, `toHaveAttribute`), so lean on those instead of
timing hacks:

- **No `page.waitForTimeout()`.** A fixed sleep is a race — too short on a
  loaded machine, wasted time otherwise. Assert the end state; the assertion
  waits exactly as long as needed.
- **No `waitForLoadState("networkidle")`.** Wait for a concrete landmark
  element instead.
- **Assert the step you are on before acting on it.** StrictMode
  double-invokes mount effects in development, so an unguarded
  `useEffect(..., [])` fires twice and the loser's response lands whenever it
  lands. Assert the landmark of the state the app actually reached — a
  heading, or the `status` / `alert` text, as `change-password.spec.ts` does
  with `toHaveText("The current password is incorrect.")` — before acting on
  what follows it.
- **A full page reload wipes in-memory state, and a watching dev server causes
  them.** Vite reloads every connected page when its server restarts, its
  dependency optimizer runs again, or a watched `.html` changes. The server
  Playwright starts watches nothing; one you started yourself and Playwright
  reuses still does. Symptom: state the spec put on `window` is gone, or the
  failure screenshot shows the app back on its first screen, nothing in flight.
- **Prefer role and label selectors over CSS.** `getByRole("button", { name: "Sign out" })`,
  `getByLabel("Current password")`. `getByTestId` is fine for a value with no
  accessible name of its own.
- **Restore anything a spec mutates, or own it.** A spec either resets the
  shared state it changes, in the test that writes it, or acts only
  on fixtures it provisioned and deletes in a `finally` (see "Calling the API
  from a spec").

# Testing Guide

## Running the suites

| Command                 | What it runs                                           | Typical use                           |
| ----------------------- | ------------------------------------------------------ | ------------------------------------- |
| `npm test`              | vitest, single pass, everything but `test/e2e`         | after every change                    |
| `npm run test:watch`    | vitest in watch mode                                   | while writing a test                  |
| `npm test <path>`       | one file                                               | narrowing a failure                   |
| `npm test -- -t "name"` | one test by name                                       | narrowing further                     |
| `npm run typecheck`     | `tsc -b` over all three projects                       | after every change                    |
| `npm run test:arch`     | dependency-cruiser + `test/arch`                       | after every change                    |
| `npm run test:coverage` | vitest with v8 coverage                                | checking a gap, not a gate            |
| `npm run test:e2e`      | Playwright against `npm run dev`, and the packaged JAR | once an implementation is complete    |
| `npm run test:security` | Semgrep, local ruleset                                 | once an implementation is complete    |
| `npx fallow audit`      | dead code / complexity / duplication in the changeset  | once an implementation is complete    |
| `npm run test:mutation` | Stryker over the whole repo                            | deliberately, outside the loop        |
| scoped Stryker          | Stryker over the source one test covers                | after writing or changing a unit test |

The baseline gate, the conditional gates and their triggers are in AGENTS.md
under "Testing".

## Unit tests

Vitest with happy-dom and Testing Library; `test/setup.ts` pulls in
`@testing-library/jest-dom` and clears `sessionStorage` after every test, since
one window serves a whole file and the tab's EHR context lives there. It
ends the FHIRcast module's connections first (`endFhircast`), for the same
reason: the session's connection is a module-level singleton, which a later
test's start would otherwise take as already started, and a later test's end
of the session would tear the connections down at that test's fake hub.

Tests are **colocated** with the code they cover — `home.test.tsx` beside
`home.tsx` — and type-checked through `tsconfig.test.json`, which is the only
project that includes them. `tsconfig.json` excludes `*.test.*` and
`*.testHelpers.*` so production builds never see them.

`*.testHelpers.ts(x)` is the name for shared test support. It is excluded from
the production project exactly as `*.test.*` is, and is deliberately **not**
`*.helpers.ts(x)` — that name is reserved for production helpers, which stay
inside the `cq-no-devdep-in-prod` dependency rule.

**A unit test — new or changed — is not finished until scoped Stryker confirms
it kills mutants in the code it covers.** A weakened assertion still passes, and
still counts as covered; Stryker is the only gate that notices (see Mutation
testing).

### Assert exactly, not loosely

`toHaveTextContent` is a **substring** match, so it passes on copy with extra
text around the expected line — a stray suffix, a second notice. Anchor it
(`toHaveTextContent(/^No widgets exist\.$/)`, `listing-body.test.tsx`) or
compare `textContent` with `toBe`, as `login.test.tsx` does.

Playwright's `toHaveText` is exact by default, so E2E does not have this trap.

### Coverage excludes — why the list is explicit

`vitest.config.ts` lists each exclusion rather than globbing. v8 reports a file
with zero executable statements as 0%, so leaving those in drags the report
down with rows no test can ever cover: `*.css`, `*.d.ts`, the e2e and scripts
trees, `playwright.config.ts`, and `src/main.tsx` (a `createRoot` call against the real document, with
no branch to assert — the smoke E2E covers that it mounts). Drop a file from
that list the moment it gains coverable code.

## Architecture tests

`npm run test:arch` is two halves, and a new rule belongs in whichever half can
actually see the thing it governs:

- **`test/.dependency-cruiser.cjs`** — anything expressible as _module A must
  not import module B_. It matches resolved module edges, and reads the `@/`
  alias out of `tsconfig.json`.
- **`test/arch/*.test.ts`** — anything the module graph cannot see: a string
  that never becomes an import, a config file's routing table, a value written
  in CSS. Each file names its rule in a comment at the top, in the same
  `snake_case` id AGENTS.md uses.

For example:

- `designTokens.test.ts` — no colour literal outside `src/index.css`.
- `e2eSpecRouting.test.ts` — every Playwright spec is matched by exactly one
  project's `testMatch`.

### Why the arch suite reads sources through `node:fs`

`test/arch/sources.ts` reads files with `node:fs`, not
`import.meta.glob(..., { query: "?raw" })`. The glob is the obvious choice and
it is wrong here: vitest replaces every `.css` import with an empty module
(`test.css` is off by default) **before** the `?raw` query is honoured, so a
stylesheet globbed that way arrives as an empty string and the rule reading it
passes vacuously. `designTokens.test.ts` was written that way first, and its
"keeps `src/index.css` as the palette" case is the guard that caught it — a
rule with an exemption should assert that the exempted file still contains what
the exemption is for.

`sources.ts` resolves the repo root from `process.cwd()`, not
`import.meta.url`: under vitest the latter is a served URL, not a `file:` one,
and `fileURLToPath` throws on it.

### Prove a new rule fails

An architecture test that cannot fail is worse than none — it reads as
coverage. Plant the violation, watch the rule catch it, then remove it:

```bash
echo 'export const BAD = "#ff0000";' > src/lib/violation.ts
cp test/e2e/smoke.spec.ts test/e2e/unrouted.spec.ts
npx vitest --run test/arch          # both rules should fail
rm src/lib/violation.ts test/e2e/unrouted.spec.ts
```

## Mutation testing

`npm run test:mutation` runs Stryker over the whole repo — far too slow for
an implementation loop, so it is run deliberately, outside it. The in-loop run is scoped, and `--mutate` names
the **source** the test covers, never the test file:

```bash
npx stryker run --mutate 'src/pages/**/*.tsx,!src/pages/**/*.test.tsx'
```

A survivor means the test asserts too weakly to catch the bug it claims to
cover. Strengthen the assertion and re-run until the mutant dies — a survivor
you cannot kill is either a mutant with no observable effect, which belongs in
the `mutate` exclusions with its reason, or a gap in what the test set out to
prove.

The `mutation-testing` skill drives the run and triages the survivors. Two of
its defaults disagree with the rule above, so pass them explicitly:

```bash
/mutation-testing --target=src/pages/home.tsx --threshold=100
```

`--scope=changed` covers the whole changeset rather than the source one test
covers, and `--threshold=70` reports a pass with survivors still standing.

**The `--mutate` trap:** the flag _replaces_ the `mutate` array in
`stryker.config.json`, it does not narrow it. A glob without the `!` negations
mutates the test files too, which produces nonsense survivors. Always carry the
exclusions in the flag.

`stryker.config.json` excludes two things from mutation, and each has its
reason recorded in a `_comment_mutate` key beside the array:

- `src/main.tsx` — the composition root; every mutant is uncoverable or a
  restatement of what the smoke E2E proves.
- `src/components/ui/**` — vendored placeholder code, due to be deleted when
  the in-house shadcn package is published. Its mutants are edits to Tailwind
  class strings, and killing them means pinning assertions to markup that is
  about to be replaced.

`thresholds.break` is `null`, so a low score reports but does not fail; treat a
drop as a question about the test, not a number to chase.

## E2E

Playwright specs in `test/e2e/`, run against `npm run dev` on `:5173`
(`webServer` starts it, `reuseExistingServer` outside CI reuses one you already
have up). First run on a machine needs `npx playwright install chromium`.

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
`user` → `admin` → `guest` → `dormancy`, each declaring the
previous one in `dependencies`:

- `guest` follows `admin` (and the `epic` and `packaged` projects below, when
  declared) because `login.spec.ts` signs in as the seeded Admin,
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

Three projects sit outside that chain, declared only by the environment `make
integration-test` sets up, so a bare `npm run test:e2e` against a backend
started some other way runs without them. Each depends on `setup` and replays
the Admin session, so it runs beside `admin`, and `guest` lists each declared
one in its `dependencies` as well: `login.spec.ts` signing in as the seeded Admin
would end the session they replay. The routing arch test reads their
`testMatch` either way:

- **`fhircast`** holds `fhircast-launch.spec.ts`, `fhircast-hub.spec.ts`,
  `fhircast-card.spec.ts` (Home's FHIRcast card reaching `Connected` after a
  launch, showing a published `Patient-open` and then the `Patient-close` that
  replaces it, still showing an event published once the first Hub Token and
  lease have run out, the hub holding only the new subscription after a
  renewal, reconnecting after the shim drops its socket, a reload connecting
  again without registering again, coming back to the tab once its stored Hub
  Token has expired and reconnecting with a new one as the same client, and
  absent after a password Login), `fhircast-patient.spec.ts` (the Patient card
  following the hub: a `Patient-open` showing a patient after a launch that
  named none, replacing the launch's patient with another, published while on
  another page and shown on Home on return, and kept across a reload; a
  `Patient-close` emptying the card with the FHIRcast card still `Connected`,
  staying empty across a reload until the next `Patient-open`) and
  `fhircast-logout.spec.ts` (after "Sign out", the
  hub holding no subscription at the card's channel endpoint, the shim
  reporting the Hub Token revoked and the registration deleted, a token
  request with the old registration refused, and after a reload no
  subscription left at any endpoint the tab used and the stored key minting no
  Hub Token), and **`epic`** holds `epic-launch.spec.ts`. Both are declared when
  `E2E_FHIRCAST_FHIR_BASE` names Medplum's FHIR base: the backend's Epic Login
  points at the local Epic stand-in (Medplum and the DCR shim), and both run
  against it. Each replays the Admin session only to issue a SCIM token; every
  sign-in either makes, an EHR launch through Medplum, is of a User it
  provisions itself. `epic` depends on `fhircast` as well, so the two never run
  at once: its unavailable case pauses the shim `fhircast`'s specs reach.
  `fhircast.helpers.ts` holds what their specs share: Medplum's sign-in page, a
  `SmartAppLaunch`, the clinicians the Medplum seed makes, the patients the
  specs name of the four backend fixture patients it makes under their Epic ids
  (Tan Wei Ming, whom the default launch names, and Siti Nurhaliza binte Ahmad;
  Kumaravel and Marcus Anthony de Souza are seeded too),
  publishing `Patient-open` / `Patient-close` to a hub topic, dropping a
  channel endpoint's sockets through the shim's test-only `POST /dcr/test/drop`,
  and reading which hub tokens and leases its `GET /dcr/test/state` counts as
  run out, which a case outliving them waits on rather than sleeping.
- **`packaged`** holds `packaged-csp.spec.ts` and is declared when
  `E2E_PACKAGED_BASE_URL` names the packaged JAR. Its `baseURL` is the JAR's
  origin, so its specs load the built bundle under the backend's CSP rather
  than Vite's dev server; it signs no one in.

### Routing a new spec

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

### Calling the API from a spec

A **safe** request needs nothing but the jar: `roles-admin.spec.ts`,
`roles-user.spec.ts` and `dev-roles.spec.ts` call the Permission-guarded reads through `page.request` directly,
which is how the Permission policy is asserted on the server rather than only on the
SPA's redirect. A spec that asserts a Permission is refused must assert the exact
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
the backend — SCIM is not behind the Vite `/api` proxy; `E2E_BACKEND_URL`
overrides `http://localhost:8080`), has each settle on a password of its own
through `POST /api/auth/change-password`, locks both with refused logins, unlocks
one from the page, and unlocks the other behind the page's back so its Unlock
button meets the `409` an Unlock of an unlocked User gets. It deletes the Users
and the connector in a `finally`. It is `test.describe.serial` because its steps
depend on each other. `roles-admin.spec.ts` asserts only refusals against the
seeded accounts — an Unlock of a User serving no lockout is refused `409` and
changes nothing — and the statuses and response shapes are covered in
`AdminAccountEndpointTests`.

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
clinician's in the `fhircast` launch, and in the `fhircast-card` and `epic`
specs that of the clinician the seed makes for each case. The seed makes each at a fixed UUID in
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

### Forcing a refused request

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
  context, so `session-revocation.spec.ts` parks the page on
  `/change-password` and submits a password change: the backend refuses it for
  want of a session before the password check, so no password moves. Were the
  session still standing, the change would apply and the spec's later sign-ins
  with the old password would fail, so the probe cannot pass silently.

A test that forces a failure has to be shown to **fire**: neuter the mechanism
(keep the session cookie, drop the header rewrite), confirm the test fails, then put it
back.

### Sharing backend state under `fullyParallel`

A spec that resets shared backend state does it in the one test that writes
that state, never in a `beforeEach`: the hook runs for every test in the file,
and under `fullyParallel` a sibling's reset lands between the writing test's
own steps. A spec that exercises the request machinery rather
than a feature only reads, or only makes requests the backend refuses:
`session.spec.ts` sends two password changes, one refused for want of a live
session and one for its token, so it changes nothing another spec reads.

### Unit-testing a module that calls the API

Everything under `src/` reaches the backend through `apiFetch` in
`src/lib/http.ts`. Two kinds of suite test that seam itself:

- **`src/lib/http.test.ts`** stubs `fetch` and proves credentials, CSRF
  recovery, status classification and decoding, for any request.
- **The declaration suites** (`src/pages/*-api.test.ts`, `src/auth/api.test.ts`)
  prove each endpoint's path, decoder and Permission once.

**Every other suite fakes the backend one way: through the test adapter, and
nothing but `fetch`.** No suite above the transport mocks `@/lib/http` or
`src/auth/api.ts`, stubs `fetch` itself, or states its backend as an ordered
response chain. A suite that fakes auth internals breaks whenever they move,
and proves what it asserts only about the internals it faked.

#### The test adapter: two layers

**The backend layer** is `fakeBackend(answers?)` in
`src/lib/fake-backend.testHelpers.ts`: a fake backend behind the global `fetch`
for the rest of the test, and nothing else — no router, no provider. It answers
from the **wire answers** the suite gives, keyed `"METHOD /path"`:
`json(body, status?)`, `bare(status)`, `UNREACHABLE` for a network failure, or a
`deferred()` the test settles when it chooses. A list answers in turn, its last
answer repeating; a key ending in `*` answers every path it prefixes. It keeps
the backend's side of the contract with stand-ins: `GET /api/auth/csrf` hands out
a token (unless the suite answers that key itself, say `bare(500)` for a token
that cannot be had), an unsafe request that does not carry the token is refused
`403`, and a body not sent as `application/json` is refused `415`
(`docs/BACKEND_CONTRACT.md` holds both rules). A request
nothing answers is answered `501` and fails the test by name when it ends, so a
suite cannot pass on a request it never stated.

It hands back `requests()` — method, path and parsed JSON body of everything
sent, in order — and `answer()` to change an answer mid-test. The token fetches
are left out of `requests()` unless the suite asks for them with
`requests({ tokenFetches: true })`, and so is each request's `Content-Type`
unless it asks with `requests({ contentType: true })` — for a write proving it
names its body JSON, or names none when it sends none. `requestKey(sent)` gives
a sent request's `"METHOD /path"` key, to count one kind of request; the auth
endpoints' own keys are `AUTH_REQUESTS`, beside `testUser` in
`src/auth/auth-context.testHelpers.ts`, so no suite spells them again.

**The token being forgotten is proved on the wire**, never by counting
`discardCsrfToken` calls: the next unsafe request fetches a fresh token first.
`holdToken()` sends an unsafe request so a token is held, and `tokenForgotten()`
sends the next one and says whether a `GET /api/auth/csrf` went out before it. A
session action sent unsafe — login, logout, the password change — has fetched a
token itself, so a suite reads `tokenForgotten()` straight after it.

**The render layer** is `renderAsSession()` in
`src/auth/session-adapter.testHelpers.tsx`, built on the backend layer: it mounts
the real `AuthProvider` inside a `MemoryRouter`, for a session the suite states —
`holding(permissions, user?)` for one signed in (`EVERY_PERMISSION`, derived
from the Permission list in `src/auth/api.ts` and kept beside `testUser`, for a
Superuser's), `GUEST`, or `STILL_CHECKING`. It answers the start-up
`GET /api/auth/me` from that session and every other request from the suite's
wire answers, and re-exports the answer vocabulary so a page suite imports from
one place. It resolves once the start-up check has settled, and hands back the
backend layer's `requests()` (the start-up check left out) and `answer()`, plus
`session()` (its status and sign-in reason) to assert that a `401` ended the
session as `expired` or that a `403` left it standing. An empty `requests()` is
how a suite proves nothing was sent for a session lacking a Permission.

#### Which suite uses which layer

- **A page suite** (`src/pages/*.test.tsx`) renders the real page through the
  render layer. A signed-in page renders where the app renders it, inside the
  real signed-in layout, which owns its one error line: `renderSignedIn(path,
session, answers)` in `src/pages/signed-in-shell.testHelpers.tsx` routes every
  signed-in page under `SignedInShell` and renders the adapter at `path`. A
  suite for one part of a page — the Connectors panel — routes that part alone
  under the same layout with `renderInLayout(routes, path, session, answers)`,
  which `renderSignedIn` is built on. A suite's own wrapper takes the wire
  answers before the session, so `renderAccounts(answers, session)` and
  `renderShell(path, answers, session)` read alike. A suite whose answers never
  vary fixes them inside its wrapper and takes the session alone, as Home's
  `renderHolding(permissions)` does.
- **A suite that brings its own router or provider** uses the backend layer
  directly. `src/App.test.tsx` renders the whole app, which owns its router and
  `AuthProvider`, over an answer table per test. `auth-context.test.tsx` mounts
  the real provider as its subject, and `idle-sign-out.test.tsx` the real
  provider, guards and Login page under fake timers. `use-session-action.test.tsx`
  runs its hook under the real provider too, since the outcome it reads is the
  backend's answer as the session action classifies it.
- **The hooks that read the session rather than change it** — `use-gated-read`,
  `use-gated-write`, `use-session-request` — run against a hand-built
  `AuthContext` value, from `authState()` over one of the sessions in
  `src/auth/auth-context.testHelpers.ts` (`signedIn({ permissions })`,
  `CHECKING`, `unchecked()`), so a test states the Permissions held, withdraws
  one mid-test, and sees `expireSession` called; their requests still go to the
  backend layer. The gated read holds every answer open with a list of
  `deferred()`s under one `*` key, so a test settles them in any order.
  `route-guards` sends no request and needs only the hand-built value.

All of them sign in as the same User, `testUser(overrides?)` in
`src/auth/auth-context.testHelpers.ts`: `ada`, unflagged, with a 15-minute idle
limit. A suite that needs a `UserResponse` body — the login's — answers with
it too, rather than writing the User out again. Likewise every suite that
answers the Patient card's read takes its patient and request key,
`TAN_WEI_MING` and `TAN_WEI_MING_READ`, from
`src/pages/patient.testHelpers.ts`. A hook that reports to the page
status runs inside the real one, `InPageStatus` in
`src/auth/page-status.testHelpers.tsx`, and the suite reads the line back with
`shownLine()`.

The real declarations, `apiFetch` and the decoders all run under every one of
these suites, so a body that drifted from its type shows up as the page's
failure copy, and a page-wide rule — the single error line — is tested with
every panel present. Mocking a panel out of its page is how the Accounts page
once shipped two error lines (#82): the panel drawing the second was never
rendered in the page's own suite.

### Flakiness — the rules that keep these tests green

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
- **A full page reload wipes in-memory state, and `npm run dev` causes them.**
  Vite full-reloads every connected page when a watched `.html` under the root
  changes — see `server.watch.ignored` in `vite.config.ts`. Symptom: the failure
  screenshot shows the app back on its first screen, fully loaded, nothing in
  flight.
- **Prefer role and label selectors over CSS.** `getByRole("button", { name: "Sign out" })`,
  `getByLabel("Current password")`. `getByTestId` is fine for a value with no
  accessible name of its own.
- **Restore anything a spec mutates, or own it.** A spec either resets the
  shared state it changes, in the test that writes it, or acts only
  on fixtures it provisioned and deletes in a `finally` (see "Calling the API
  from a spec").

## Semgrep

`npm run test:security` scans with the local ruleset in `semgrep/rules/`. It is
local rather than a registry pack so the run stays offline and deterministic and
each rule carries the reason this project cares about it; a wider sweep is still
worth doing occasionally: `npx semgrep scan --config p/typescript --config
p/react`.

Adding a rule: give it an `fe-` prefixed id (registry ids can never collide with it),
put the reason this project cares in the `message`, and **verify it fires**
before committing — write the violating snippet in a scratch file outside the
repo and scan it:

```bash
npx semgrep scan --config semgrep/rules --metrics=off --no-git-ignore /tmp/scratch
```

A pattern starting with `{` has to be quoted in the YAML, or the parser reads it
as a flow mapping and the whole config is rejected.

## Fallow

`npx fallow audit` scopes to the files changed since the merge-base with the
branch's upstream and fails only on findings the changeset **introduced** —
inherited ones still print, attributed `introduced: false`. `--base <ref>` pins
a different base. `npx fallow review --brief` renders the same analysis as an
orientation brief that always exits 0 — for reading, not gating.

Dead code sits at zero here, so any unused export, file, or dependency in an
audit is one you just added. That is why `buttonVariants` in
`src/components/ui/button.tsx` is module-local rather than exported the way the
shadcn generator emits it — export it the moment something outside that file
composes on it.

**A file nothing imports needs an `entry` line in `.fallowrc.jsonc`.** Fallow
walks JS/TS imports, so anything reached only through a config file — a service
worker source, a script a CI job calls — reads as an unused file. Each `entry`
line carries its reason in a comment beside it, as `test/e2e/auth.setup.ts`
does (Playwright selects it by `testMatch` regex, an edge fallow does not
follow). `ignoreDependencies` lists `tailwindcss` for the same reason: it is
resolved by `@tailwindcss/vite` and by the `@import "tailwindcss"` in
`src/index.css`, never by a JS import statement.

**A suppression carries its reason above it** — a comment naming the consumer
the static graph cannot see, then `// fallow-ignore-next-line unused-export` on
the line before the export. `npx fallow suppressions` inventories every marker
and flags stale ones.

**`--production` is the lens for test-only exports.** A default run counts a
test file as a consumer, so an export nothing but its own test imports reads as
used. `npx fallow dead-code --production` drops test files from the consumer
set — read the result as a review question, not a delete list.

Boundary zones are deliberately unconfigured, so `fallow guard` has nothing to
say here: architecture rules are dependency-cruiser's job, under
`npm run test:arch`.

The tool's own documentation ships inside the package, at
`node_modules/fallow/skills/fallow/` — `SKILL.md` plus a `references/` folder
with the full CLI reference and a gotchas file. Read flags there rather than
guessing, and use `npx fallow explain <issue-type>` to understand a finding
without re-running the analysis.

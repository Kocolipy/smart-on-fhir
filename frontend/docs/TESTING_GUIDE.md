# Testing Guide

## Running the suites

| Command                 | What it runs                                          | Typical use                           |
| ----------------------- | ----------------------------------------------------- | ------------------------------------- |
| `npm test`              | vitest, single pass, everything but `test/e2e`        | after every change                    |
| `npm run test:watch`    | vitest in watch mode                                  | while writing a test                  |
| `npm test <path>`       | one file                                              | narrowing a failure                   |
| `npm test -- -t "name"` | one test by name                                      | narrowing further                     |
| `npm run typecheck`     | `tsc -b` over all three projects                      | after every change                    |
| `npm run test:arch`     | dependency-cruiser + `test/arch`                      | after every change                    |
| `npm run test:coverage` | vitest with v8 coverage                               | checking a gap, not a gate            |
| `npm run test:e2e`      | Playwright against `npm run dev`                      | once an implementation is complete    |
| `npm run test:security` | Semgrep, local ruleset                                | once an implementation is complete    |
| `npx fallow audit`      | dead code / complexity / duplication in the changeset | once an implementation is complete    |
| `npm run test:mutation` | Stryker over the whole repo                           | CI only                               |
| scoped Stryker          | Stryker over the source one test covers               | after writing or changing a unit test |

The baseline gate, the conditional gates and their triggers are in AGENTS.md
under "Testing".

## Unit tests

Vitest with happy-dom and Testing Library. Globals are on, so `describe` / `it`
/ `expect` need no import (they are imported explicitly anyway in the files
here, which costs nothing and survives a config change). `test/setup.ts` pulls
in `@testing-library/jest-dom`.

Tests are **colocated** with the code they cover — `showcase.test.tsx` beside
`showcase.tsx` — and type-checked through `tsconfig.test.json`, which is the only
project that includes them. `tsconfig.json` excludes `*.test.*` and
`*.testHelpers.*` so production builds never see them.

`*.testHelpers.ts(x)` is the name for shared test support. It is excluded from
the production project exactly as `*.test.*` is, and is deliberately **not**
`*.helpers.ts(x)` — that name is reserved for production helpers, which stay
inside the `cq-no-devdep-in-prod` dependency rule.

**A unit test — new or changed — is not finished until scoped Stryker confirms
it kills mutants in the code it covers.** A weakened assertion still passes, and
still counts as covered; Stryker is the only gate that notices. That is how the
trap below was found. The command is under Mutation testing.

### Assert exactly, not loosely

`toHaveTextContent` is a **substring** match. `toHaveTextContent("Clicked 1 time")`
passes on the buggy `"Clicked 1 times"` — so the assertion written to pin down
a pluralisation is exactly the one that does not. Anchor it:
`toHaveTextContent(/^Clicked 1 time$/)`. `src/pages/showcase.test.tsx` carries the
comment; Stryker is what found it (the `count === 1` mutant survived).

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

`npm run test:mutation` runs Stryker over the whole repo — CI only, far too
slow for an implementation loop. The in-loop run is scoped, and `--mutate` names
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
/mutation-testing --target=src/pages/showcase.tsx --threshold=100
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
The `user` and `admin` projects reuse those sessions; `guest`,
`connector-tokens` and `dormancy` start with explicitly empty browser storage
and sign in for real. Role-guard specs are split by identity so a spec can
never accidentally run with a more privileged session than it names.

The backend keeps **one session per User**: an accepted login ends every other
session that User holds. A spec that signs in as a seeded identity therefore
revokes the session the matching project replays from `.auth/`, or the one
another project's sign-in holds. So the projects run as one chain, `setup` →
`user` → `admin` → `guest` → `connector-tokens` → `dormancy`, each declaring the
previous one in `dependencies`:

- `guest` follows `admin` because `login.spec.ts` signs in as the seeded Admin,
  and beside the `admin` project it turned that project's specs into 401s.
- `connector-tokens` follows `guest` because `token-permissions.spec.ts` signs
  in as the same development Role Users `dev-roles.spec.ts` does.
- `dormancy` runs last and alone: it signs in as the Account admin fixture User
  and consumes the `dormant` fixture's locked state, which only a backend
  restart re-arms.

`login.spec.ts` also opts out of `fullyParallel` with
`test.describe.configure({ mode: "default" })`, since its two sign-ins would
otherwise revoke each other. A new spec that must sign in for real should do it
as a User it provisions (see `change-password.spec.ts`), not as a seeded one.
Running one project alone still runs every project ahead of it in the chain.
`playwright.config.ts` carries the reason beside each `dependencies` line;
update both together.

The `epic` project sits outside that chain. It holds `epic-launch.spec.ts`, Epic
Login's E2E gate, and is declared only when `E2E_EPIC_FHIR_BASE` is set, which
`make epic-integration-test` does after starting the SMART launcher and a backend
pointed at it; `npm run test:e2e` never declares it, so the main suite never
needs the launcher. It depends on `setup` alone: it replays the Admin session
only to issue a SCIM token, and the one sign-in it makes — the Epic launch — is
of a User it provisions itself. The routing arch test still reads its
`testMatch`, so the spec counts as routed either way.

### Routing a new spec

Each project in `playwright.config.ts` names its specs explicitly with a
`testMatch` rather than globbing `*.spec.ts`. **A
spec matched by no project's `testMatch` runs in no project and is silently
skipped** — the suite still reports green. `test/arch/e2eSpecRouting.test.ts`
reads the routing table out of the config and fails on an unmatched or
doubly-matched spec, so adding a spec means adding it to a `testMatch`.

Route a spec by the identity it needs: `user` or `admin` replays that seeded
identity's saved session, and `guest` starts signed out for specs that exercise
sign-in itself. A spec that signs in as a development Role User, or consumes a
fixture's one-shot state, goes in its own project appended to the end of the
chain above, as `connector-tokens` and `dormancy` do. Do not call `login()` in each test. Per-test `login()` under
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
(`/frontend/AGENTS.md`, "Backend contract") — an unsafe request made that way
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
at all. Unlock and a forced password change both leave the User required to
change its password, and password history refuses the old one back, so a seeded
identity put in either state cannot be restored for the specs that sign in as it.
`accounts-admin.spec.ts` therefore PROVISIONS the Users it acts on: it creates a
connector and issues a token through the page, reads the value off the one-time
disclosure, creates two throwaway Users over SCIM with it (straight to the
backend — SCIM is not behind the Vite `/api` proxy; `E2E_BACKEND_URL` overrides
`http://localhost:8080`), has each settle on a password of its own through
`POST /api/auth/change-password`, then locks and unlocks one and force-changes
the other from the page. It deletes the Users and the connector in a `finally`.
It is `test.describe.serial` because its steps depend on each other.
`roles-admin.spec.ts` asserts only idempotent actions and refusals against the
seeded accounts — an Unlock of a User serving no lockout requires no change — and
the statuses and response shapes are covered in `AdminAccountEndpointTests`.

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

A spec that needs a known **backend defect** to be visible marks the test
`test.fail(true, "#<issue>: …")`: the suite stays green while the issue is open
and turns red the moment the fix lands, so the marker cannot outlive the bug.
Remove the marker in the fix's PR.

A forced change **revokes the sessions it holds**. Proving
revocation needs a session of its own rather than the page's — sign in through
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
  token lives in the session, so it goes too: an unsafe request is first refused
  `403`, and `apiFetch`'s re-fetch — which opens a fresh, anonymous session —
  then lands the retry on the `401`. The backend session stays valid, so a spec
  doing this cannot break one running beside it.
- **`403`, persistently** — rewrite the `X-CSRF-TOKEN` header with
  `page.route`. A stale token does not work: `apiFetch` answers a `403` by
  re-fetching the token and retrying once, so the retry would succeed. Rewriting
  on every attempt makes the backend reject both, and a `403` that survives the
  re-fetch is reported as `forbidden`.
- **`401` from a session the backend really ended** — sign the same User in
  again elsewhere, or make the SCIM write that revokes its sessions
  (`session-revocation.spec.ts`). Use a provisioned User, and make the page's
  next request a read: an increment would move the counter `showcase.spec.ts`
  asserts exact values of.

A test that forces a failure has to be shown to **fire**: neuter the mechanism
(keep the session cookie, drop the header rewrite), confirm the test fails, then put it
back.

### Sharing backend state under `fullyParallel`

The counter is one value on the backend and `showcase.spec.ts` resets it,
so a spec running beside it must not assert a count. `session.spec.ts` only
makes requests the backend refuses, which leaves the count untouched.

### Unit-testing a module that calls the API

Everything under `src/` reaches the backend through `apiFetch` in
`src/lib/http.ts`. That module alone stubs `fetch` and proves credentials, CSRF
recovery, status classification, and decoding. Feature tests mock `apiFetch`
with an `ApiResult` (`ok`, `unauthenticated`, `forbidden`, `csrf-expired`, or
`failed`) and
assert only their own response to that meaning. This keeps raw `Response`
construction and token setup out of feature suites; `src/auth/api.test.ts`,
`src/pages/showcase.test.tsx` and `src/pages/accounts.test.tsx` are the patterns.
A suite that renders the whole app over a stubbed `fetch` (`src/App.test.tsx`)
wraps its stub in `stubFetchWithCsrf()` from `src/lib/http.testHelpers.ts`, which
answers the token endpoint so the stub sees only the requests the test is about.
A page that renders a `<Link>` needs a router in the test too — wrap it in
`MemoryRouter`, as `accounts.test.tsx` does, or the link throws on a null router
context.

### Flakiness — the rules that keep these tests green

Playwright auto-waits and auto-retries every web-first assertion (`toBeVisible`,
`toHaveText`, `toBeEnabled`, `toHaveAttribute`), so lean on those instead of
timing hacks:

- **No `page.waitForTimeout()`.** A fixed sleep is a race — too short on a
  loaded CI box, wasted time otherwise. Assert the end state; the assertion
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
- **Prefer role and label selectors over CSS.** `getByRole("button", { name: "Reset" })`,
  `getByLabel("Current password")`. `getByTestId` is fine for a value with no
  accessible name of its own, as `showcase.spec.ts` uses for the counter.
- **Restore anything a spec mutates, or own it.** A spec either resets the
  shared state it changes (`showcase.spec.ts` resets the counter) or acts only
  on fixtures it provisioned and deletes in a `finally` (see "Calling the API
  from a spec").

## Semgrep

`npm run test:security` scans with the local ruleset in `semgrep/rules/`. Adding
a rule: give it an `fe-` prefixed id (registry ids can never collide with it),
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

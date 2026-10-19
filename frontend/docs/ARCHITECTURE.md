# Architecture

What is here today, and where a new concern belongs.

## The layers

```
src/
  main.tsx            composition root — mounts React, imports index.css
  App.tsx             app root — BrowserRouter, AuthProvider, the route table
  index.css           Tailwind entry + the design tokens
  vite-env.d.ts       /// <reference types="vite/client" />
  auth/               session state, Permission checks, route guards, request seam
  pages/              one component per page, plus a shared *-api.ts for wire types
  components/         shared non-primitive components (error-boundary.tsx)
  components/ui/      shadcn primitives (placeholder — see "Component library")
  lib/                framework-agnostic helpers; a leaf
```

Imports run one way: `main` → `App` → `pages` → `auth` → `components/ui` →
`lib`. A layer may skip a step (a page may call `cn` directly, `auth/` reaches
straight into `lib/http`) but may never point back up.
`test/.dependency-cruiser.cjs` encodes exactly that, and `npm run test:arch`
runs it.

### Why `auth/` is its own folder and not a page

`src/auth/` is a _concern_, not a screen. This is the authoritative per-file
map; `/frontend/AGENTS.md` points here rather than repeating it.

- `api.ts` — the `/api/auth/*` calls, each mapping a status code to a domain
  outcome (`401` on `/me` is a guest, not an error; `401` on change-password is
  a wrong current password or a lockout, never a reason to end the session by
  itself), and the decoder for the session's `permissions`. The three **session
  actions** — `login`, `logout`, `changePassword` — and the start-up session
  check, `getCurrentUser`, each resolve to a closed outcome (`LoginOutcome`,
  `LogoutOutcome`, `PasswordChangeOutcome`, `SessionCheckOutcome`) for every
  request outcome, and none of them throws. The check's outcome is `signed-in`,
  `guest` (a `401`, the only verdict that makes a Guest), or a request failure
  that leaves the session unknown.
- `session-action-copy.ts` — the copy for every session-action outcome, and the
  only place it lives: `loginMessage`, `logoutMessage`,
  `passwordChangeMessage` and `sessionCheckMessage`, each over the outcomes that
  leave the visitor on the page, with the two transport outcomes read through
  the seam's `refusalMessage`. The session action (below) and the route guards
  are their only readers.
- `use-session-action.ts` — `useSessionAction`, the one way a page runs a
  session action. It owns the steps every caller used to repeat: withdraw the
  earlier line, hold the pending flag, await the outcome, then show nothing for
  one that ended or started a session (`signed-in`, `signed-out`, `changed`,
  `locked`; the guards move the visitor on) or else the line
  `session-action-copy.ts` gives. Which login and logout outcomes those are is
  decided there; for the password change it reads `sessionEndReasonFor`
  (`sign-in-reason.ts`), the rule the provider ends the session by, rather than
  repeating it. Pending clears once the action settles, whatever the outcome:
  on one that moved the session the guard replaces the page on the same
  update. Inside the signed-in layout a caller hands it a reporter on the page
  status (`useStatusReporter`, below), and the line goes to the layout's one
  error line: the shell's Sign out and the Change password page do, and render
  only `pending`. Outside that layout it holds the line itself and hands back
  `error`: the Login page and the idle warning's Sign out (in its own dialog)
  render `pending` and `error`. Either way the caller renders what it is handed,
  as a page does a gated write's; none of them reads an outcome or catches.
  `run` resolves `"stayed"` or `"moved-on"`, which the Change password page
  reads to clear its fields after a refusal.
- `permissions.ts` — the `holds` / `holdsAny` checks every guard and page
  reads, and the one rule for whether a session may open a page:
  `requirementOf` reads a page's `permissions` as its requirement, and
  `mayOpen` decides it. `resolveSessionRoute` applies them to a guarded route,
  and Home to each link. Which Permission a request requires is not here: it is
  declared on the endpoint (`declarations.ts`, below).
- `declarations.ts` — the shape of an endpoint as data: a `ReadDeclaration`
  (Permission, path, decoder, and the opt-in `absentOnNotFound`, which makes a
  `404` the read's `absent` view rather than a failure — see the gated read
  below) and a `WriteDeclaration` (the same, plus method
  and optional JSON body; the decoder only for a write answering a body). A path
  is a function of the call's arguments, so the API module that exports the
  declaration builds it and no page concatenates one. A page reads
  `declaration.permission` to decide whether to offer a control and hands the
  same declaration to the hook to send it, so the two cannot drift apart — each
  control asks about the declaration it sends, never a neighbour's that happens
  to share its Permission today; and a
  declaration is module-level data, so its decoder is one stable reference
  without a page hoisting it.
- `page-status.ts` — the signed-in page's single error line:
  `PageStatusScope`, which holds the status and hands its line to the one
  component that renders it, and `useStatusReporter`, a source's handle on it.
  Every gated read and write on the page reports its outcome there (and the
  Audit page its refused filters, the Change password page its mismatch, and
  the shell's Sign out and the password change their session-action line), and
  the module alone decides what shows: **the latest event wins**. A failure
  becomes the line; a success withdraws only its own source's failure, so one
  still standing from another source shows again; a write starting withdraws
  every failure, as the action supersedes them. No hook knows about any other.
  One line per page is held by the structure, not by convention: the status
  exists only inside the scope, which `SignedInShell` renders once around every
  signed-in page and which throws when rendered inside another; a reporter can
  report but never read, and throws outside a scope. The scope is reset on
  every path change, and a source that unmounts withdraws its failure and
  reports nothing after, so a failure never follows the user to the next page.
- `session-transitions.ts` — `useSessionTransitions`, the **session
  transitions**: the one place the auth state (status, user, sign-in reason)
  changes, as `signIn(user)` or `end(reason)`, and the only `src/auth` caller of
  `discardCsrfToken()`. Each transition changes the state and forgets the token
  in the same step, so the two cannot drift apart. Two transitions change the
  status without changing the session, so they keep the token: `checkFailed`,
  for a start-up check that did not answer (status `unchecked`), and `recheck`,
  its retry (back to `checking`).
- `auth-context.tsx` — the `AuthProvider`, which checks the session on mount
  (and again on `retrySessionCheck`) — `ok` signs in, `401` ends with no reason,
  anything else is `checkFailed` — and maps each session event onto a transition: a login, a refused login
  and a logout; an expiry, an Idle sign-out, a password change and the lockout
  a change can run into, each recording its sign-in reason (none for a logout
  or a refused login).
- `auth-context-value.ts` — the context object and the `useAuth` hook, split out
  so a consumer importing the hook does not pull in the provider component.
  `useAuth` deliberately exposes no way to _end_ a session, and no sign-in
  reason: that is `useAuthState`'s, internal to `src/auth`, for the guards.
- `sign-in-reason.ts` — the **sign-in reason**: the closed set of six (Expired
  session, Idle sign-out, password changed, locked by a password change, Epic
  refused, Epic unavailable), how each reaches the login page (the first four
  as `reason` in router state, the
  Epic two as the backend's `?signin=refused|unavailable` marker), which wins
  (an Epic marker over any router state), and the copy for each. Router state
  is decoded against the set, not cast, so a stale or foreign `reason` in the
  history entry says nothing. `signInNoticeFor` is all the login page calls.
- `session-route.ts` — `resolveSessionRoute`, the whole routing contract as one
  pure transition table: who waits, who renders, who is redirected where, and
  what the redirect carries.
- `route-guards.tsx` — `ProtectedRoute` and `GuestRoute`, two thin adapters over
  that table sharing one pending view and one `unchecked` view: a single error
  line from `sessionCheckMessage` and a "Check again" button. An `unchecked`
  session is not a Guest, so neither guard renders its page or redirects.
- `use-session-request.ts` — the seam features request through. It handles an
  `unauthenticated` result itself and returns a `SessionResult`, which has no
  `unauthenticated` member, so no page can forget to relay a session ending.
- `use-gated-read.ts` — `useGatedRead`, the Permission-gated read every listing
  goes through (see below).
- `use-gated-write.ts` — `useGatedWrite`, the one gated write every page action
  goes through (see below).
- `idle-sign-out.tsx` — `IdleSignOut`, mounted by `AuthProvider` for an
  authenticated session. It times the backend's own idle window
  (`idleTimeoutSeconds`), counts only user input as activity, shares it across
  tabs, and warns a minute before signing out (`docs/BACKEND_CONTRACT.md`). Its "stay" is a `GET /api/auth/me` through `useSessionRequest`,
  so a session already ended takes the seam's one `401` rule.
- `password-policy.ts` — the backend's password length bounds, mirrored so the
  change form can state the rule; the backend still decides.

The pages under `src/pages/` are screens that _consume_ this; they hold no
session or Permission logic themselves, and none decides where a visitor goes next. A
new protected area adds a route declaration, not a second copy of the guard.

`pages/accounts.tsx` is the widest page. It reads two read-only
projections — Users from `GET /api/admin/accounts`, Groups from
`GET /api/admin/groups` — and posts the two operations an administrator performs on a
User, Unlock and the forced password change, to
`/api/admin/accounts/{id}/unlock` and `/api/admin/accounts/{id}/force-password-change`
by the User's stable id. Each action replaces the one affected row from its
response rather than reloading the listing — the response _is_ that User's new
state, so a refetch would only add a request that could disagree with it.
Nothing the directory owns (`userName`, display name, `active`, Group
membership) has a control on the page, and the backend has no endpoint that
would accept one. The page hides Unlock and the forced change where the backend would
refuse them — on the administrator's own row, and Unlock on the Bootstrap Admin, which
cannot be locked and shows no lockout state.

Its connector panel, `pages/connectors.tsx`, lists, creates and deletes
connectors and issues, rotates and revokes their tokens through
`/api/admin/connectors/**`, re-reading the listing after every change because a
rotation, revocation or delete changes more than one token. A token's plaintext
lives only in that component's state, shown once in the disclosure panel, so
dismissing it, navigating away or reloading loses it for good. The panel reads
the session itself and offers each control by its own declaration's Permission,
and the token Permissions it may grant by the ones the session holds. It is a
panel of the Accounts page, not a page: like the page, it reports to the
signed-in layout's page status and renders no error line of its own, so its
listing and its changes share one line with Users and Groups, under the one
rule, the latest event wins. A rotation refused `409` (the token was revoked, has expired or was
already rotated) has its own copy.
`pages/accounts-api.ts` holds the wire types both files share and declares every
endpoint they call, each with its Permission.

`pages/audit.tsx` is the audit listing at `/audit`, routed for a session holding
`audit:read` alone. It reads `GET /api/admin/audit-events` a page of 50 at a
time, filtered by operation, outcome, actor id, resource id and a time range.
`pages/audit-api.ts` decodes every field and checks each filter before the
request is built, so a malformed one is refused on the page and nothing is sent;
its `AUDIT_EVENTS` declaration reads the path the filters parsed into. A refused
filter and a failed read share the layout's error line, the later of the two
showing.
Ids are shown as ids, never resolved to usernames, which would need `user:read`.
The page reads on mount, on a filter submit (every valid one, the filters
already shown included), on paging and on Refresh — a second Refresh
supersedes the first, so only the newest answer shows — and never on a timer:
every request renews the server session, so a page that polled would never
reach the idle timeout. The pager names a total only once a read of
the current query has stated one, and never fewer pages than the one shown, so
a page still loading or one that failed reads "Page 3", not "Page 3 of 1".

`pages/patient-card.tsx` is Home's Patient card, above the counter's. It reads
the default patient, `GET /api/patient/{DEFAULT_PATIENT_ID}`, through the
`PATIENT` declaration in `pages/home-api.ts` (`patient:read`, opting in to
`absent`), whose decoder reads the R5 `Patient` (typed by `@types/fhir`'s
`fhir5`) down to the name's `text`, the NRIC, the gender code and the birth
date. `DEFAULT_PATIENT_ID` copies the backend seeder's fixed id
(`DevPatientFixtures`), which owns it. The card shows the loading line, the
patient — every field a plain text node, never markup — with the age beside
the date of birth (completed years on today's date in Asia/Singapore, from
the card's injectable `Clock`), "No patient in context" for the `404`, and
nothing for any other failure, whose "Couldn't load patient" is on the
layout's error line; it offers no retry.

`pages/listing-body.tsx` is the one listing body Accounts, Connectors and Audit
render a gated read through: the loading line, the empty line, and a blank body
for a failure — whose copy is on the page's error line, so a failure is never
shown as empty or as still loading. Each page names its `staleRows` choice for a
refresh that fails: Connectors keeps the rows last read, Accounts and Audit hide
them.

Every request goes through `useSessionRequest`, so a `401` ends the session in
one place and a `403` never does: it reaches the page as `forbidden`, which shows
permission-denied copy. What each status means to an
administrator (`409` a refused change, `404` a User that has since gone) is
copy the page supplies, because only the page knows what was being attempted.

Every listing read — Users, Groups, connectors, the audit trail, Home's patient — goes
one level higher, through `auth/use-gated-read.ts`. A page names the read's
declaration (from its API module) with the declaration's arguments and its
failure copy, and the hook reports to the page status around it; it sends nothing without the Permission, resets
when the Permission or the path changes, and drops an answer for a key it has
moved off. Its `reload()` is "read for these arguments now": while the gate is
open it always sends, for the arguments the next render names, and **the
newest read wins** — every read the hook sends (on opening, on a key change,
on `reload()`, or from a write's `after`) supersedes the one in flight, so an
older answer arriving last is discarded from the view and the page status
alike. A `reload()` asked for in the same event as a change of arguments is
that change's one read, never a second, so Audit's submit sets its query and
reloads without asking whether the path moved. What it returns is one closed
`ReadView` — `not-permitted`, `loading`, `failed` (nothing was ever read),
`ready`, or `stale` (a refresh failed and the last data is kept); the refusal's
copy goes to the page status, and a read that closes (its path moved, its gate
shut) withdraws what it reported. A page switches on the view rather than
re-checking the read's Permission or rebuilding the states from loose flags.

A read whose `404` is an ordinary answer — the thing asked about may simply not
be there, and the page has a normal way to say so (the Patient card's "No
patient in context", its first user) —
opts in on its declaration with `absentOnNotFound: true`, and its view gains a
sixth member, `absent`. The opt-in belongs on the declaration, beside the path
it describes, because whether "not found" is news is a fact about the endpoint,
not about one page's use of it. `absent` is not an error: it reports nothing to
the page status, so like `ready` it clears a line an earlier read of its own
left. It keeps nothing either — a re-read that finds what was read gone moves
`ready` to `absent`, never to `stale`, as data for something not there is not
data to keep — and a re-read that finds it again moves `absent` to `ready`. It
follows the same rule as every other answer: only the newest read lands. An
`update()` while absent makes the data read, as it does while loading, since
the response stating it says something is there now. Every other result
(`forbidden`, a plain `failed`, an ended session) is unaffected by the flag,
and a read that does not opt in reads a `404` as `failed`, as before. The view
type follows the declaration (`ReadView<T, N>`), so a page whose read never
opts in has no `absent` case to handle. Opt in only where a `404` means
"nothing there"; where it means "this went away under you" — a User deleted
since the listing was read — it is a failure the page has copy for, and stays
one.

A page whose read is sent only once its Permission is held may also pass
`forbiddenAsFailure: true` beside its `failureMessage`: a `403` is then
reported with the page's failure copy rather than the seam's refusal copy,
still as `failed`, and still without ending the session. Unlike
`absentOnNotFound` it is the page's option, not the declaration's, because it
chooses copy rather than describing the endpoint. The Patient card is its one
user: a `403` there is "Couldn't load patient", as a `400` or a `5xx` is.

Every page action — Unlock, the forced change, connector and token changes —
goes through `auth/use-gated-write.ts`. A page names the
write's declaration — path, method, optional JSON body, optional success
decoder, and the Permission the backend requires, all declared by its API
module — with the declaration's arguments, what to do on success, the copy for
the statuses it cares about and, for Connectors, an `after` step (the listing
re-read) that runs inside the pending window. The hook sends the write
through `useSessionRequest` itself, and sends nothing for a session lacking
the declaration's Permission (it settles as `forbidden`, as the backend would
answer), the way a gated read sends nothing without its own. It owns the
pending flag and the mapping of a refusal to copy; it withdraws every failure
on the page status as it starts and reports its own outcome there, so an
`after` step that fails reports later and shows. The page keeps only what is
its own: which declaration, its success handling, its copy, and what to render
from `holds(user, declaration.permission)`. The hook has no branch for ADR
0006's `400 scimType: mutability`: that refusal belongs to the SCIM surface,
and none of the endpoints the pages call answers it.

`mb-transport-is-behind-the-session-seam` in `test/.dependency-cruiser.cjs`
enforces the direction: only `src/auth/` and `src/lib/` may import
`lib/http.ts`, so a page cannot opt out of the seam by calling `apiFetch`
itself.

### Why every request goes through `lib/http.ts`

The backend enforces a session-bound CSRF synchronizer token (`docs/BACKEND_CONTRACT.md`), so every unsafe request needs the session's token, in the
header `GET /api/auth/csrf` names, or it comes back `403`. `apiFetch()` is the
single place that knows this: it holds the token **in memory only**, fetches it
from that endpoint before the first unsafe request, adds the header on unsafe
methods only, and on an unsafe request's `403` re-fetches the token and retries
exactly once. A safe request's `403` cannot be CSRF, so it is not retried. Login,
a refused login (the backend ends the session the browser held before its `401`),
logout, a password change or the lockout it runs into, an expired session and an
Idle sign-out all change the session, so each discards the held token
(`discardCsrfToken()`), and the next unsafe request fetches the new session's
before it is sent. `src/auth/session-transitions.ts` is the one caller in
`src/auth`: each transition there forgets the token as it changes the auth
state, so no event can do one without the other.

Its interface returns an `ApiResult`: `ok` carries data from an explicit decoder,
`unauthenticated` means the session ended, `forbidden` is an authorization
refusal (a safe request's `403`, or a `403` that survived the re-fetch),
`csrf-expired` is a token fetch that itself failed, and `failed` covers every other
transport, HTTP, or decoding
failure. Features retain their own human-facing copy while sharing status
meaning. A no-content request omits the decoder, so its `ok` data is typed as
`void` rather than pretending every success is JSON.

A body is never cast to its type (`as T`). It is read by a hand-written decoder
that takes `unknown` and returns the value or throws `DecodeError`, built from
the primitives in `lib/decode.ts`, and `apiFetch` turns a success body that does
not decode into `failed` with no `status`. Each wire type's decoder lives beside
the type, in the module that owns it. A decoder that checks a time — the
Patient's birth date — asks `lib/time.ts` (below) whether it is one.

It lives in `lib/` because `auth/` sits above it and it must not know about
sessions. That places it under `mb-lib-is-a-leaf`, so it stays
dependency-free — no auth types, no React. Pages reach it only through
`useSessionRequest`, which strips the `unauthenticated` case after acting on it.

### Why every time goes through `lib/time.ts`

The SPA reads and shows every time in one zone, Asia/Singapore — the zone the
backend names for an operator, for its log timestamps and its job schedules —
whatever the viewer's machine zone or locale. Singapore is a fixed +08:00 with
no daylight saving, so `lib/time.ts` states the offset as a constant rather than
asking the machine's zone database, and it is the one module that knows it:

- **an instant from the backend** is shown as its Singapore date
  (`formatDate`, `YYYY-MM-DD`) or date and minute (`formatInstant`,
  `YYYY-MM-DD HH:mm`);
- **a time an administrator types** — the Audit page's From and To, which are
  `datetime-local` values — is read as Singapore time (`parseTypedTime`), so
  the time an event is shown at, typed back in, names that event's minute;
- **a calendar date** — a day with no time and no zone, as a FHIR `date` is —
  is valid only when the calendar has it (`isCalendarDate`, which the
  Patient decoder asks) and is shown `d MMM yyyy` (`formatCalendarDate`);
- **today** is Singapore's date (`today`), read through a `Clock` a component
  is handed, `systemClock` by default, so a test states the day it runs on and
  no page writes an inline `new Date()`.

Nothing in it goes through `toLocaleString` or the machine's zone, so what is
shown is the same on every machine and a test can assert an exact string. The
unit suite pins its machine zone to one that is neither UTC nor Singapore
(`vitest.config.ts`), where a page that slipped back to the machine's zone
shows a different date. `test/arch/timeModule.test.ts` holds
`no_time_outside_time_module`: no other source builds a `Date`, formats one
through `Intl` or a local getter, or names a zone.

### Why `components/ui/` is fenced off

`src/components/ui/` is not "the shared components folder" — it is a
placeholder for a package. The in-house shadcn library is what will live at
that import path, and the day it is published this folder gets deleted. That
only stays cheap while its contents depend on nothing but `cn()`, so the
`mb-ui-primitives-are-leaves` rule holds the boundary rather than trusting
everyone to remember.

Shared components that are _not_ library primitives — a page header, a layout
shell — do not belong here. Put them in `src/components/` (one level up), which
no rule constrains, or beside the page that owns them.

### Why `lib/` is a leaf

`mb-lib-is-a-leaf` keeps `src/lib/` importing nothing from `src/`. It is the
one folder every other layer may call, so an edge pointing out of it is a cycle
waiting to happen — and `cn()` in particular is imported by every primitive, so
anything it drags in is effectively in every bundle chunk. `http.ts` is held to
the same line: it takes a path, request options, and an optional decoder, and
knows nothing about auth or React, so `src/auth/` can consume its semantic
results without creating a cycle.

### Why there is no `src/types/`, `src/hooks/` or `src/utils/`

Those folders collect by _kind_ rather than by _concern_, so a feature ends up
smeared across four directories and nothing can be moved or deleted as a unit.
Types live in the file or folder that owns them. Shared helpers and shared
hooks go in `src/lib/` — that is also where `components.json` points the shadcn
CLI (`"hooks": "@/lib/hooks"`), so a generated hook lands in the right place
without anyone intervening. `mb-no-top-level-catchall-dirs` enforces the ban.

## The `@/` alias

`@/x` resolves to `src/x`, declared in four places that must agree:

| File                           | Mechanism                              |
| ------------------------------ | -------------------------------------- |
| `tsconfig.json`                | `compilerOptions.paths`                |
| `vite.config.ts`               | `resolve.alias`                        |
| `vitest.config.ts`             | `resolve.alias`                        |
| `test/.dependency-cruiser.cjs` | `options.tsConfig` (reads the `paths`) |

Vitest does not inherit `vite.config.ts` here — there are two separate config
files, because the unit suite deliberately does not load `@tailwindcss/vite`
(see below). So an alias change made in one has to be made in the other, and
missing dependency-cruiser is the quiet failure: aliased imports read as
unresolvable and every rule silently stops matching on them.

## Styling and the token pipeline

Tailwind v4, CSS-first. There is no `tailwind.config.js` and there should not
be one — `@tailwindcss/vite` in `vite.config.ts` is the whole build-side setup,
and `src/index.css` is the whole configuration:

1. `@import "tailwindcss"` pulls in the framework.
2. `@custom-variant dark (&:is(.dark *))` makes dark mode a class on an
   ancestor rather than a media query, so it can be toggled in-app later
   without touching a component.
3. `:root` and `.dark` hold the palette as raw `oklch()` values. **This is the
   only place a raw colour value belongs in the repo.**
4. `@theme inline` maps each palette variable onto a Tailwind colour utility,
   so `--color-card` becomes `bg-card`, `text-card`, `border-card`, and so on.
5. `@layer base` applies the default border colour and the body's
   background/foreground.

Components name the token, never the value. `test/arch/designTokens.test.ts`
fails the build on a hex, `rgb()`, `hsl()` or `oklch()` literal in any `src`
file other than `index.css` — Tailwind compiles arbitrary values like
`bg-[#0f172a]` happily, so without that test nothing would catch it.

Adding a shade means adding it in three spots: the `:root` block, the `.dark`
block, and `@theme inline`. That triple is deliberate — a token with no dark
value is a bug that only shows up for users in dark mode.

Vitest does **not** load `@tailwindcss/vite`: unit tests stub CSS imports, so
generating the utility stylesheet for every test file would be pure cost. The
one thing that needs real CSS is the E2E suite, which runs against the dev
server and therefore against `vite.config.ts` — and `smoke.spec.ts` asserts the
body's computed `background-color` resolved to something, which is what catches
a broken CSS pipeline.

## Build

`vite build` emits a single `vendor` chunk for everything under `node_modules`
and the app in `index`. `vite-plugin-compression2` writes `.gz` and `.br`
alongside each asset. There is **no PWA plugin and no service worker** — this
app is not installable and does not cache offline. Adding one means bringing
back `vite-plugin-pwa`, an `entry` line in `.fallowrc.jsonc` for the worker
source (nothing imports it, so fallow reads it as an unused file), and a
`registerType` decision.

## Dev-server reloads

`server.watch.ignored` lists `playwright-report/`, `coverage/` and `.claude/`.
Vite full-reloads _every connected page_ when a watched `.html` under the
project root changes, and it watches the whole root — so a finishing Playwright
run writing `playwright-report/index.html`, or `npm run test:coverage` writing
`coverage/index.html`, would reload whatever pages are open, including pages
another test is driving. Vite already ignores `**/test-results/**`; a new
directory that a test run writes into belongs on this list.

## Routing

`App.tsx` owns the whole route table, deliberately flat:

| Path               | Element                                                                                          | Notes                                                                                                                                                                                                                                                         |
| ------------------ | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/login`           | `<GuestRoute><Login /></GuestRoute>`                                                             | Guest login; authenticated Users go to Home, `/`                                                                                                                                                                                                              |
| `/`                | `<ProtectedRoute><Home /></ProtectedRoute>`                                                      | Home, for every authenticated User; a Guest goes to `/login`. The Patient card by `patient:read`, the counter by `counter:read` / `counter:write` (baseline, held by every User), a link to each entry with a `homeLink` exactly when its guard would open it |
| `/accounts`        | `<ProtectedRoute requiredPermissions={ADMINISTRATION_PERMISSIONS}><Accounts /></ProtectedRoute>` | any one of `user:read`, `group:read`, `connector:read`; each view and action by its own Permission                                                                                                                                                            |
| `/audit`           | `<ProtectedRoute requiredPermissions={[AUDIT_EVENTS.permission]}><Audit /></ProtectedRoute>`     | `audit:read` alone                                                                                                                                                                                                                                            |
| `/change-password` | `<ProtectedRoute><ChangePassword /></ProtectedRoute>`                                            | self-service change for any authenticated User; the only route a flagged session is offered                                                                                                                                                                   |
| `*`                | `<Navigate replace to="/" />`                                                                    | unknown paths fall back to `/`: Home for a User, `/login` for a Guest                                                                                                                                                                                         |

**Permission-based guards.** The SPA decides navigation, page guards and in-page
actions from the `permissions` `GET /api/auth/me` and the login response report
— there is no role. `PERMISSIONS` in `src/auth/api.ts` is the closed set the
backend's `Permission` enum defines, and a session reporting a name outside it
fails decoding. Each API module declares the Permission every endpoint it
owns requires (Users `user:read`, Groups `group:read`, connectors
`connector:read`, the audit trail `audit:read`, the counter `counter:read`,
Home's patient `patient:read`, a baseline Permission every User holds),
`accounts-api.ts` derives `ADMINISTRATION_PERMISSIONS` from its three listings,
and `src/auth/permissions.ts` holds the `holds` / `holdsAny` / `mayOpen`
checks; a route states its requirement as `requiredPermissions`, read off those
declarations in `signed-in-pages.tsx`, which `requirementOf` reads as "any one
of these", and a page hides each action
it lacks the Permission for (Unlock and the forced change `user:write`, connector
create and delete `connector:write`, token issue, rotate and revoke
`connector:token`, the counter's buttons `counter:write`). A view the session
cannot see is not requested either, so it never turns into a `403` on screen. A
deep link to a page the session holds no Permission for is redirected to
Home, `/`, exactly as any page it may not see.

**Change-password route.** The backend confines a password Login's session whose
password must be replaced (the change-required flag, see `/CONTEXT.md`): `GET
/api/auth/me` and the login response report `passwordChangeRequired: true` with
no Permission, and every endpoint but `POST /api/auth/change-password` and logout
answers `403`. An Epic Login is never confined, flag or not, so its `/me` reports
`passwordChangeRequired: false` and the launch lands on Home, `/`; the SPA
needs no rule of its own for that, since it acts only on what `/me` reports.
`AuthUser` carries that flag, and `resolveSessionRoute` confines such a session
to `/change-password`: any other path it asks for — Home, `/accounts`,
the login route, an unknown path (which falls back to Home first) — redirects
there, ahead of the Permission check and of any recorded return destination, so a
flagged Superuser is confined exactly as a flagged User is. The signed-in shell offers Sign out,
so a flagged User is never stuck on it. An unflagged User may open it too, for a
voluntary change, and Home links to it.

`pages/change-password.tsx` submits current and new password (plus a
confirmation checked in the browser) through `changePassword` on the auth
context, not through `useSessionRequest`: a `401` there is an answer about the
current password, and the seam would end the session on it unconditionally.
`src/auth/api.ts` maps the outcomes. `204` means every session of the User has
ended, the submitting one included, so the context clears its auth state and
records why, and the guard returns the visitor to login with "Your password was
changed" and no return destination — the next sign-in lands on Home, not
back on the change. A `400` carries the backend's `PasswordRuleViolation`, and
the page shows its statement of the unmet rule. The backend answers a wrong
current password and a lockout with the same bodiless `401`, so `api.ts` tells
them apart by asking `GET /api/auth/me` whether the session survived: a wrong
password leaves it standing; reaching the lockout threshold revokes every
session of the User, so the context ends the session as for a change, recording
the sign-in reason `locked`, and the guard returns the visitor to login — no
return destination — which says an Admin must Unlock the account. Only an
answer decides: a check that fails, or is refused, cannot tell the two apart,
so it claims neither — reading it as a wrong password could hide a lockout —
and the change resolves `failed`, leaving the session standing. Every
refusal that leaves the session standing clears all three fields and shows the
line `useSessionAction` hands back, from `session-action-copy.ts`. The inputs are
uncontrolled, because React mirrors a controlled input's value into the DOM
`value` attribute, and no message the page shows contains either value.

`resolveSessionRoute` is the pure transition table behind both guard adapters.
It sends a Guest to login with a return destination, confines a flagged
session to `/change-password`, renders an authenticated route for any session,
renders a Permission-guarded one for a session holding any of its Permissions,
and redirects every other session to Home, `/`. A Guest's redirect carries the
provider's sign-in reason as `reason` beside the return destination; after a
successful change it carries `reason: "password-changed"` instead of one, and
after a lockout `reason: "locked"`.
Spring Security remains authoritative for server operations: every operation
requires its own declared Permission, and a flagged session is refused everything but the change and
logout, even if client-side routing is bypassed, so the guard decides what is
_rendered_ and never what is _permitted_.

`BrowserRouter` means real paths, not hashes, so the backend has to serve
`index.html` for any unmatched path — that fallback is the backend's side of the
SPA contract, and a deep link like `/accounts` or `/login` 404s without it.

The four signed-in pages (Home, Accounts, Audit, Change password) are
children of one layout route in `App.tsx`: a `ProtectedRoute` around
`SignedInShell` (`pages/signed-in-shell.tsx`). The shell owns the header (who is
signed in and the page title), the page's one error line, the back link and Sign
out; a page renders none of them, only its body. The shell holds the page status
(`auth/page-status.ts`) and renders its line between the header and the page:
the page, its panels and Sign out report to it, the latest failure shows, and
the line starts empty on every path. Each signed-in page is declared exactly once, as an entry in
`SIGNED_IN_PAGES` (`pages/signed-in-pages.tsx`): its path, element, optional
`permissions` guard, optional `homeLink` (its link's label on Home), and the
shell metadata (title, back link, column width, centering). Home is handed the
other entries rather than importing the table, so the two never import each
other, and links each one carrying a `homeLink`, in table order, for a session
`mayOpen` says the entry's guard would let in. `App.tsx` maps the table to
`<Route>`s, and the shell finds the current entry with `matchPath` (an unknown
path falls back to the Home layout), so **adding a signed-in page means adding
one entry to that table** and nothing in `App.tsx` or the shell. (The router is
`BrowserRouter`, so route `handle` / `useMatches` is not available; the table is
the single source instead.) The back link is withheld from a session confined to
`/change-password`. An entry with `permissions` (Accounts, Audit) is wrapped in
its own `ProtectedRoute` inside the shell. Sign out shows a pending state while the logout is in flight.
When the logout fails or comes back CSRF-expired, the auth context's `logout`
resolves that outcome without ending anything, so the session stands and the
CSRF token stays with it (ADR-0009: the token is forgotten only through the
session transitions); `useSessionAction("logout")` reports its line
(`logoutMessage`, which says the User is still signed in) to the page status,
so it shows on the shell's one error line, replacing or replaced by the page's
own failures under the latest-event rule. The shell holds no Sign out error of
its own. The idle warning's own Sign out button is a manual Sign out too, run
through the same session action, so it cannot follow a different rule: a pending
state while the logout is out, and on a failed or CSRF-expired one the session
stands and the dialog shows the same line in an announced error line.
The Idle sign-out at the limit is a separate path and keeps its rule: a logout
that fails still ends the session there.

A new protected area is a new `<Route>` under that layout, wrapped in its own
`ProtectedRoute` when it needs a Permission. Lazy route chunks are unused; add
one when a page's code is heavy enough to be worth loading on demand — not before.

## What is deliberately absent

Nothing below exists yet. Each entry names where it goes, so the first person
to need it does not have to invent a convention.

| Concern                         | Where it goes                                                                        |
| ------------------------------- | ------------------------------------------------------------------------------------ |
| Global state                    | beside the feature that owns it; hoist to `src/lib/` only when a second one needs it |
| Server-state caching            | a query library wrapping `apiFetch`, wired in `App.tsx` beside `AuthProvider`        |
| Shared non-primitive components | `src/components/` (one level up from `ui/`), or beside the page that owns them       |
| Environment config              | `VITE_`-prefixed variables, read through `import.meta.env`, documented in README.md  |
| Lazy routes                     | `src/App.tsx`, when a page is heavy                                                  |
| PWA / service worker            | `vite-plugin-pwa` in `vite.config.ts` + a `.fallowrc.jsonc` `entry` line             |

Already present, and where it lives: routing in `src/App.tsx`, authentication in
`src/auth/` (the idle sign-out and its expiry warning included), typed HTTP
results in `src/lib/http.ts`, and the top-level error
boundary in `src/components/error-boundary.tsx`, wrapped around the whole tree
in `App.tsx`, outside the signed-in shell, so a shell failure is caught too. A
render error anywhere below it shows a generic "Something went wrong" fallback
with a reload action, logs to `console.error` only, and never puts the error's
message or stack in the DOM.

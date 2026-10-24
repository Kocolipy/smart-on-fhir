# Architecture

What is here today, and where a new concern belongs.

## The layers

```
src/
  main.tsx            composition root — adopts an Epic landing's Launch context, mounts React, imports index.css
  App.tsx             app root — BrowserRouter, AuthProvider, the route table
  index.css           Tailwind entry + the design tokens
  vite-env.d.ts       /// <reference types="vite/client" />
  auth/               session state, Permission checks, route guards, request seam
  pages/              one component per page, plus an API module per page declaring its endpoints
  components/         shared non-primitive components (error-boundary.tsx)
  components/ui/      shadcn primitives (placeholder — see "Why components/ui/ is fenced off")
  lib/                framework-agnostic helpers; a leaf
```

Imports run one way: `main` → `App` → `pages` → `auth` → `components/ui` →
`lib`. A layer may skip a step (a page may call `cn` directly, `auth/` reaches
straight into `lib/http`) but may never point back up.
`test/.dependency-cruiser.cjs` encodes that, and `npm run test:arch` runs it.

### `auth/`, file by file

`src/auth/` is a _concern_, not a screen. This is the authoritative per-file map.

- `api.ts` — the `/api/auth/*` calls, each mapping a status to a domain outcome
  (`401` on `/me` is a guest, not an error), the decoder for the session's
  `permissions` and `superuser`, and `PERMISSIONS`, the closed set the backend's
  `Permission` enum defines (a session reporting another name fails decoding).
  `superuser` is the Superuser's standing, a boolean beside the Permissions and
  never one of them. The three
  **session actions** — `login`, `logout`, `changePassword` — and the start-up
  check, `getCurrentUser`, each resolve to a closed outcome (`LoginOutcome`,
  `LogoutOutcome`, `PasswordChangeOutcome`, `SessionCheckOutcome`) and never
  throw.
- `session-action-copy.ts` — the copy for every session-action outcome, and the
  only place it lives (`loginMessage`, `logoutMessage`, `passwordChangeMessage`,
  `sessionCheckMessage`).
- `use-session-action.ts` — `useSessionAction`, the one way a page runs a
  session action: it withdraws the earlier line, holds the pending flag, and
  shows nothing for an outcome that moved the session (the guards move the
  visitor on) or else the copy's line. Inside the signed-in layout it reports
  to the page status; outside it (Login, the idle warning's Sign out) it hands
  back `error`. No caller reads an outcome or catches. `run` resolves `"stayed"`
  or `"moved-on"`.
- `permissions.ts` — `holds` / `holdsAny`, `SUPERUSER`, and the one rule for
  whether a session may open a page. `holds` answers a `Requirement`: a
  Permission, or the Superuser, which no number of Permissions satisfies. The one
  rule: `requirementOf` reads a page's `permissions` as "any
  one of these", `mayOpen` decides. Which Permission a _request_ needs is
  declared on the endpoint instead.
- `declarations.ts` — an endpoint as data: a `ReadDeclaration` (Permission,
  path, decoder, opt-in `absentOnNotFound`) and a `WriteDeclaration` (plus method
  and optional JSON body, and a Permission or the Superuser as its requirement). The API module that exports one builds its path from
  the call's arguments, so no page concatenates one. A page reads
  `declaration.permission` to offer a control and hands the same declaration to
  the hook that sends it, so the two cannot drift — each control asks about the
  declaration it sends, never a neighbour's that shares its Permission today.
- `page-status.ts` — the signed-in page's single error line: `PageStatusScope`
  holds it, `useStatusReporter` is a source's handle on it. Every gated read and
  write reports there, and **the latest event wins**: a failure becomes the
  line; a success withdraws only its own source's failure; a write starting
  withdraws every failure. The scope exists once, rendered by `SignedInShell`
  (it throws when nested; a reporter throws outside one and can never read). It
  resets on every path change, and a source that unmounts withdraws its failure.
- `session-transitions.ts` — `useSessionTransitions`, the one place the auth
  state (status, user, sign-in reason) changes, as `signIn(user)` or
  `end(reason)`, each forgetting the CSRF token in the same step (ADR 0009);
  `end` forgets the tab's Launch context too.
  `checkFailed` (status `unchecked`) and `recheck` change the status without
  changing the session, so they keep the token.
- `launch-context.ts` — the tab's **Launch context**, the patient and encounter
  an EHR launch put in context: `adoptLaunchLanding()` (called by `main.tsx`
  before the router reads the URL) reads an Epic landing's fragment, holds each
  value to FHIR's id grammar, keeps it in `sessionStorage` and strips the
  fragment; `launchContext()` reads it back, decoded rather than trusted;
  `landedFromEpic()` says whether the tab holds one at all, which is how Home
  knows an EHR launch began the session; and `clearLaunchContext()` forgets it,
  on every session end and a password Login, beside `clearFhircastStorage()`
  (`docs/BACKEND_CONTRACT.md`).
- `auth-context.tsx` — the `AuthProvider`: checks the session on mount and on
  `retrySessionCheck` (`ok` signs in, `401` ends, anything else is
  `checkFailed`) and maps each session event onto a transition with its sign-in
  reason.
- `auth-context-value.ts` — the context and `useAuth`, split out so the hook
  does not pull in the provider. `useAuth` exposes no way to _end_ a session and
  no sign-in reason; that is `useAuthState`'s, internal to `src/auth`.
- `sign-in-reason.ts` — the closed set of six **sign-in reasons**, how each
  reaches the login page (router state, or the backend's
  `?signin=refused|unavailable` marker for the Epic two), which wins (an Epic
  marker), and the copy. Router state is decoded against the set, never cast.
  `signInNoticeFor` is all the login page calls.
- `session-route.ts` — `resolveSessionRoute`, the whole routing contract as one
  pure transition table (see "Routing").
- `route-guards.tsx` — `ProtectedRoute` and `GuestRoute`, thin adapters over
  that table sharing one pending view and one `unchecked` view (an error line
  and "Check again"). An `unchecked` session is not a Guest, so neither guard
  renders its page or redirects.
- `use-session-request.ts` — the seam features request through. It acts on
  `unauthenticated` itself and returns a `SessionResult` with no such member,
  so no page can forget to relay a session ending.
- `use-fhircast-release.ts` — `useFhircastRelease`, the FHIRcast release
  (`GET /api/auth/epic/fhircast`) through that seam. Not a declaration: the
  operation is self-service and carries no Permission, and the card needs only
  the release or `null`.
- `use-gated-read.ts` / `use-gated-write.ts` — the gated read and write (below).
- `idle-sign-out.tsx` — `IdleSignOut`, mounted by `AuthProvider` for an
  authenticated session (`docs/BACKEND_CONTRACT.md` has the rule it keeps).
- `password-policy.ts` — the backend's password length bounds, mirrored so the
  form can state them; the backend still decides.

### The gated read and write

**Every listing read** — Users, Groups, connectors, the audit trail, Home's
patient — goes through `useGatedRead`, handed a declaration, its arguments and
the page's failure copy. It sends nothing without the Permission, resets when
the Permission or path changes, and **the newest read wins**: every read it
sends (on opening, a key change, `reload()`, or a write's `after`) supersedes
the one in flight, in the view and the page status alike. A `reload()` in the
same event as a change of arguments is that change's one read, not a second.
It returns one closed `ReadView` — `not-permitted`, `loading`, `failed`,
`ready`, or `stale` (a refresh failed, the last data kept) — so a page switches
on the view instead of re-checking flags.

- **`absentOnNotFound`** (on the declaration, because whether "not found" is
  news is a fact about the endpoint) adds a sixth view, `absent`, for an
  endpoint whose `404` means "nothing there". It reports nothing and keeps
  nothing (`ready` → `absent`, never `stale`). Opt in only where `404` means
  nothing there, never where it means "this went away under you". The view type
  follows the declaration (`ReadView<T, N>`), so a read that never opts in has
  no `absent` case.
- **`forbiddenAsFailure`** (the page's option, since it chooses copy) reports a
  `403` with the page's failure copy, still as `failed`, still without ending
  the session. The Patient card is its one user.

**Every page action** — Unlock, a Group's Permissions, connector and token changes —
goes through `useGatedWrite`, handed a declaration, its arguments, the success
handling, the copy for the statuses the page cares about, and optionally an
`after` step run inside the pending window (Connectors' listing re-read). It
sends nothing for a session lacking the Permission (settling `forbidden`), owns
the pending flag and the refusal copy, and withdraws every page-status failure
as it starts. It has no branch for ADR 0006's `400 scimType: mutability`: no
endpoint the pages call answers it.

`mb-transport-is-behind-the-session-seam` holds the direction: only `src/auth/`
and `src/lib/` may import `lib/http.ts`, so a page cannot call `apiFetch`.

### The pages

Pages consume `auth/`; they hold no session or Permission logic and never
decide where a visitor goes.

- `accounts.tsx` — the widest page: the Users and Groups projections, Unlock
  of a locked User by stable id, and — for the Superuser alone, on
  every Group but the Admin group — "Edit permissions". An action replaces the
  affected row from its response, which _is_ that User's or Group's new state,
  rather than re-reading. Nothing the directory owns has a control, and the page
  hides the actions the backend would refuse (its own row, the one whose stable
  id the session's `AuthUser.id` holds; Unlock on a User
  that is not locked, the Bootstrap Admin always among them; the Admin group's
  Permissions). An Unlock the backend still refuses `409` — another
  administrator unlocked the User first — says the User is no longer locked.
- `group-permissions-dialog.tsx` — the Accounts page's Group Permissions editor,
  not a page: a modal checkbox dialog fed by `GET /api/admin/permissions` and
  pre-ticked with the Group's current set. It hands the complete set to the
  page, which owns the `PUT` — the dialog closes once the save settles, and a
  reporter that unmounts withdraws what it reported, so the write's outcome must
  belong to the page's line.
- `connectors.tsx` — the Accounts page's connector panel, not a page: it lists,
  creates and deletes connectors and issues, rotates and revokes their tokens,
  re-reading the listing after every change (one change can touch more than
  one token). A token's plaintext lives only in component state, shown once.
  Create, delete and every token action are offered only to a Superuser (the
  declarations require it); a `connector:read` holder sees the listing alone. The
  issue form offers every token Permission, and a rotation refused `409` has its
  own copy.
- `accounts-api.ts` — the wire types both share and every endpoint they call;
  derives `ADMINISTRATION_PERMISSIONS` from its three listings.
- `audit.tsx` / `audit-api.ts` — the audit listing, 50 a page, filtered by
  operation, outcome, actor id, resource id and a time range. The API module
  checks each filter before the request is built, so a malformed one is refused
  on the page. Ids stay ids (resolving them would need `user:read`). It reads on
  mount, submit, paging and Refresh, **never on a timer**: every request renews
  the server session, so a polling page would never reach the idle timeout. The
  pager names a total only once a read of the current query has stated one.
- `home.tsx` — the Patient card, handed the tab's Launch context's patient
  (`launchContext()`), beside it the FHIRcast card in a tab an EHR launch began
  (`landedFromEpic()`), then the Pages card: a link to each page the session
  may open.
- `fhircast-card.tsx` — the FHIRcast card: asks for the release, connects
  through `lib/fhircast.ts`, and shows one of six generic states, closing its
  socket when Home goes. The module renews the Hub Token and the subscription
  unseen, and reconnects a dropped socket while the card shows `Reconnecting`;
  once its retries run out the card shows `Disconnected` and a Retry button,
  which connects again from the release. While connected or reconnecting it
  lists the last event the module hands on — name, time, and the Patient id
  when there is one — held in its own state only, so a reload, or a Retry,
  starts with none. It reports nothing to the page
  status: its state is its own line.
- `patient-card.tsx` / `home-api.ts` — the card takes the patient in context as
  an optional `patientId` prop. Without one it shows "No patient in context" and
  never calls the gated read; with one, a child component reads
  `GET /api/patient/{patientId}` through `PATIENT` (opting in to `absent`),
  decoding the R5 `Patient` (typed by `@types/fhir`'s `fhir5`). Home passes the
  Launch context's patient. Every field renders as a plain text node; the age is computed from the
  card's injectable `Clock`.
- `listing-body.tsx` — the one listing body: loading, empty, and a blank body
  for a failure (whose copy is on the error line). Each page names its
  `staleRows` choice: Connectors keeps the last rows, Accounts and Audit hide
  them.
- `change-password.tsx` — runs `changePassword` through `useSessionAction`, not
  `useSessionRequest`: its marked `401` is about the current password, and the
  seam would end the session on it (`docs/BACKEND_CONTRACT.md`). Its inputs are
  uncontrolled, because React mirrors a controlled input's value into the DOM
  `value` attribute.

### Why every request goes through `lib/http.ts`

`apiFetch()` is the one place that knows the CSRF rule
(`docs/BACKEND_CONTRACT.md`). It returns an `ApiResult`: `ok` carries data from
an explicit decoder (`void` when the request omits one), `unauthenticated` means
the session ended, `forbidden` is an authorization refusal, `csrf-expired` is a
token fetch that itself failed, and `failed` covers every other transport,
HTTP or decoding failure. Pages keep their own copy; the meaning is shared.

A body is never cast (`as T`). A hand-written decoder takes `unknown` and
returns the value or throws `DecodeError`, built from `lib/decode.ts`'s
primitives and living beside the wire type it decodes; a success body that does
not decode is `failed`.

It lives in `lib/` because `auth/` sits above it and it must not know about
sessions or React — `mb-lib-is-a-leaf` holds that.

**The one other module that reaches the network is `lib/fhircast.ts`** (ADR
0016). It talks to Epic's local FHIRcast hub on `127.0.0.1` — another origin,
to which none of `apiFetch`'s session, CSRF or status rules apply — so it uses
`fetch` and `WebSocket` itself: discovery, dynamic client registration with the
SMART access token, the `jwt-bearer` Hub Token, the subscription and the socket,
each URL the hub names held to loopback. It answers a closed `FhircastOutcome`
(`connected`, `unavailable`, `blocked`) and never surfaces what the hub said.
On a connected socket it holds every message to the FHIRcast STU3 notification
shape, the subscribed events and the session's topic, acknowledges a valid one
on the socket, and hands the caller only a `PatientEvent`: the event, its
`timestamp` and a grammar-checked Patient id. Nothing of a message is logged.
The release it starts from still comes from the backend through the session
seam (`auth/use-fhircast-release.ts`). `test/arch/rawNetwork.test.ts`
(`no_raw_network_outside_transport`) names these two modules and no others.

### Why every time goes through `lib/time.ts`

The SPA reads and shows every time in Asia/Singapore, the zone the backend
names for operators, whatever the viewer's machine. Singapore is a fixed +08:00,
so `lib/time.ts` states the offset as a constant and is the one module that
knows it: an instant is shown as `formatDate` / `formatInstant`; a typed
`datetime-local` (Audit's From and To) is read by `parseTypedTime`; a FHIR
`date` is checked by `isCalendarDate` and shown by `formatCalendarDate`; and
`today` reads a `Clock` a component is handed, so no page writes `new Date()`.

The unit suite pins its machine zone to one that is neither UTC nor Singapore
(`vitest.config.ts`), so a slip back to the machine's zone shows.
`test/arch/timeModule.test.ts` fails any other source that builds a `Date`,
formats through `Intl` or a local getter, or names a zone.

### Why `components/ui/` is fenced off

It is a placeholder for the in-house shadcn package, deleted the day that
publishes — cheap only while its contents depend on nothing but `cn()`, which
`mb-ui-primitives-are-leaves` holds. Shared non-primitive components go in
`src/components/` or beside the page that owns them.

### Why there is no `src/types/`, `src/hooks/` or `src/utils/`

Those collect by _kind_ rather than _concern_, smearing a feature across
folders. Types live where they are owned; shared helpers and hooks go in
`src/lib/` (where `components.json` points the shadcn CLI's hooks).
`mb-no-top-level-catchall-dirs` enforces the ban, and `mb-src-layout-is-declared`
and its `-for-importers` twin fail any file outside `auth/`, `components/`,
`lib/`, `pages/` and the composition root — so a new folder under `src/` is
added to those rules and to this document in the same change.
`lb-api-modules-are-data` keeps `pages/*-api.ts` free of React and components.

## The `@/` alias

`@/x` resolves to `src/x`, declared in four places that must agree:

| File                           | Mechanism                              |
| ------------------------------ | -------------------------------------- |
| `tsconfig.json`                | `compilerOptions.paths`                |
| `vite.config.ts`               | `resolve.alias`                        |
| `vitest.config.ts`             | `resolve.alias`                        |
| `test/.dependency-cruiser.cjs` | `options.tsConfig` (reads the `paths`) |

Vitest does not inherit `vite.config.ts` (the unit suite deliberately skips
`@tailwindcss/vite`). Missing dependency-cruiser is the quiet failure: aliased
imports read as unresolvable and every rule silently stops matching them.

## Styling and the token pipeline

`src/index.css` is the whole Tailwind v4 configuration; `@tailwindcss/vite` is
the whole build-side setup:

1. `@import "tailwindcss"`.
2. `@custom-variant dark (&:is(.dark *))` — dark mode is a class on an ancestor.
3. `:root` and `.dark` hold the palette as raw `oklch()` — **the only place a
   raw colour value belongs.**
4. `@theme inline` maps each variable onto utilities (`--color-card` →
   `bg-card`, `text-card`, …).
5. `@layer base` applies the default border colour and the body's colours.

A new shade goes in all three of `:root`, `.dark` and `@theme inline` — a token
with no dark value is a bug only dark mode shows. `test/arch/designTokens.test.ts`
fails a colour literal anywhere else; Tailwind compiles `bg-[#0f172a]` happily.
The unit suite stubs CSS, so only the E2E suite exercises the pipeline:
`smoke.spec.ts` asserts the body's computed `background-color` resolved.

## Build

`vite build` emits one `vendor` chunk for `node_modules` and the app in `index`,
with `.gz` and `.br` siblings. There is **no PWA plugin and no service worker**.
Adding one means `vite-plugin-pwa`, an `entry` line in `.fallowrc.jsonc` for the
worker source (nothing imports it), and a `registerType` decision.

A directory a test run writes `.html` into belongs on `vite.config.ts`'s
`server.watch.ignored`, or the dev server full-reloads every open page — those a
test is driving included.

## Routing

`App.tsx` owns the route table: `/login` behind `GuestRoute`, one layout route —
`ProtectedRoute` around `SignedInShell` — holding every signed-in page, and `*`
falling back to `/`.

**Each signed-in page is declared once**, as an entry in `SIGNED_IN_PAGES`
(`pages/signed-in-pages.tsx`): path, element, optional `permissions` guard,
optional `homeLink`, and the shell's metadata (title, back link, width,
centering). `App.tsx` maps the table to routes, wrapping an entry with
`permissions` in its own `ProtectedRoute`; the shell finds the current entry
with `matchPath`; Home is handed the other entries and links each with a
`homeLink` that `mayOpen` admits. **Adding a signed-in page is one entry
there**, nothing in `App.tsx` or the shell. (`BrowserRouter` has no route
`handle` / `useMatches`, so the table is the single source.)

`SignedInShell` owns the header, the page's one error line (the page-status
scope), the back link (withheld from a confined session) and Sign out; a page
renders only its body. A failed or CSRF-expired logout ends nothing — the
session and its token stand (ADR 0009) — and the line goes to the page status.
The idle warning's Sign out runs the same session action; the Idle sign-out at
the limit is a separate path, and ends the session even when its logout fails.

`resolveSessionRoute` is the transition table behind both guards. It sends a
Guest to login with a return destination and the provider's sign-in reason;
**confines a flagged session** (`passwordChangeRequired: true` on `/me`) to
`/change-password`, ahead of the Permission check and any return destination;
renders a Permission-guarded route for a session holding any of its
Permissions; and redirects every other session to Home. An Epic Login is never
confined, since `/me` reports it unflagged. The guards decide what is
_rendered_; Spring Security decides what is _permitted_.

`BrowserRouter` means real paths, so the backend serves `index.html` for any
unmatched path; a deep link 404s without that. Lazy route chunks are unused
until a page is heavy enough to earn one.

## What is deliberately absent

| Concern              | Where it goes                                                                        |
| -------------------- | ------------------------------------------------------------------------------------ |
| Global state         | beside the feature that owns it; hoist to `src/lib/` only when a second one needs it |
| Server-state caching | a query library wrapping `apiFetch`, wired in `App.tsx` beside `AuthProvider`        |
| Environment config   | `VITE_`-prefixed variables, read through `import.meta.env`, documented in README.md  |
| Lazy routes          | `src/App.tsx`, when a page is heavy                                                  |
| PWA / service worker | `vite-plugin-pwa` in `vite.config.ts` + a `.fallowrc.jsonc` `entry` line             |

The top-level error boundary (`src/components/error-boundary.tsx`) wraps the
whole tree in `App.tsx`, outside the shell. Its fallback offers a reload, logs
to `console.error` only, and never puts the error's message or stack in the DOM.

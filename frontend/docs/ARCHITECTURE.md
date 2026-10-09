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
  itself), and the decoder for the session's `permissions`.
- `permissions.ts` — the Permission each administrative view requires
  (`VIEW_PERMISSIONS`), `ADMINISTRATION_PERMISSIONS`, and the `holds` /
  `holdsAny` checks every guard and page reads.
- `auth-context.tsx` — the `AuthProvider`, which checks the session once on
  mount and owns the session status plus the expiry provenance.
- `auth-context-value.ts` — the context object and the `useAuth` hook, split out
  so a consumer importing the hook does not pull in the provider component.
  `useAuth` deliberately exposes no way to _end_ a session.
- `session-route.ts` — `resolveSessionRoute`, the whole routing contract as one
  pure transition table: who waits, who renders, who is redirected where, and
  what the redirect carries.
- `route-guards.tsx` — `ProtectedRoute` and `GuestRoute`, two thin adapters over
  that table sharing one pending view.
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
  tabs, and warns a minute before signing out (`/frontend/AGENTS.md`, "Backend
  contract").
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
dismissing it, navigating away or reloading loses it for good.
`pages/accounts-api.ts` holds the wire types and paths both files share.

`pages/audit.tsx` is the audit listing at `/audit`, routed for a session holding
`audit:read` alone. It reads `GET /api/admin/audit-events` a page of 50 at a
time, filtered by operation, outcome, actor id, resource id and a time range.
`pages/audit-api.ts` decodes every field and checks each filter before the
request is built, so a malformed one is refused on the page and nothing is sent.
Ids are shown as ids, never resolved to usernames, which would need `user:read`.
The page reads on mount, on a filter submit, on paging and on Refresh, and never
on a timer: every request renews the server session, so a page that polled
would never reach the idle timeout.

Every request goes through `useSessionRequest`, so a `401` ends the session in
one place and a `403` never does: it reaches the page as `forbidden`, which shows
permission-denied copy. What each status means to an
administrator (`409` a refused change, `404` a User that has since gone) is
copy the page supplies, because only the page knows what was being attempted.

Every listing read — Users, Groups, connectors, the audit trail and the Showcase counter — goes
one level higher, through `auth/use-gated-read.ts`. A page names the path, the
decoder, the Permission the read requires and its failure copy; the hook sends
nothing without the Permission, and otherwise returns the data or the refusal
and its message, resetting when the Permission or the path changes and
dropping an answer for a key it has moved off.

Every page action — Unlock, the forced change, connector and token changes, the
counter's buttons — goes through `auth/use-gated-write.ts`. A page names the
request (already behind `useSessionRequest`), what to do on success, and the
copy for the statuses it cares about; the hook owns the pending flag, the
page's single error line, the mapping of a refusal to copy, and withdrawing
the error of the read(s) the write supersedes the moment it starts
(`useGatedWrite({ supersedes: [groups, users] })` in `accounts.tsx`). The page
keeps only what is its own: the request, its success handling, and its copy.

`mb-transport-is-behind-the-session-seam` in `test/.dependency-cruiser.cjs`
enforces the direction: only `src/auth/` and `src/lib/` may import
`lib/http.ts`, so a page cannot opt out of the seam by calling `apiFetch`
itself.

### Why every request goes through `lib/http.ts`

The backend enforces a session-bound CSRF synchronizer token (`/frontend/AGENTS.md`,
"Backend contract"), so every unsafe request needs the session's token, in the
header `GET /api/auth/csrf` names, or it comes back `403`. `apiFetch()` is the
single place that knows this: it holds the token **in memory only**, fetches it
from that endpoint before the first unsafe request, adds the header on unsafe
methods only, and on an unsafe request's `403` re-fetches the token and retries
exactly once. A safe request's `403` cannot be CSRF, so it is not retried. Login,
logout, a password change and an expired session all change the session, so
each discards the held token (`discardCsrfToken()`), and the next unsafe request
fetches the new session's before it is sent.

Its interface returns an `ApiResult`: `ok` carries data from an explicit decoder,
`unauthenticated` means the session ended, `forbidden` is an authorization
refusal (a safe request's `403`, or a `403` that survived the re-fetch),
`csrf-expired` is a token fetch that itself failed, and `failed` covers every other
transport, HTTP, or decoding
failure. Features retain their own human-facing copy while sharing status
meaning. A no-content request omits the decoder, so its `ok` data is typed as
`void` rather than pretending every success is JSON.

It lives in `lib/` because `auth/` sits above it and it must not know about
sessions. That places it under `mb-lib-is-a-leaf`, so it stays
dependency-free — no auth types, no React. Pages reach it only through
`useSessionRequest`, which strips the `unauthenticated` case after acting on it.

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

| Path               | Element                                                                                          | Notes                                                                                                                                                         |
| ------------------ | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/`                | `<GuestRoute><Login /></GuestRoute>`                                                             | Guest login; authenticated Users go to `/showcase`                                                                                                            |
| `/showcase`        | `<ProtectedRoute><Showcase /></ProtectedRoute>`                                                  | every authenticated User; the counter by `counter:read` / `counter:write` (baseline, held by every User), the Accounts link by any Accounts view's Permission |
| `/accounts`        | `<ProtectedRoute requiredPermissions={ADMINISTRATION_PERMISSIONS}><Accounts /></ProtectedRoute>` | any one of `user:read`, `group:read`, `connector:read`; each view and action by its own Permission                                                            |
| `/audit`           | `<ProtectedRoute requiredPermissions={["audit:read"]}><Audit /></ProtectedRoute>`                | `audit:read` alone                                                                                                                                            |
| `/change-password` | `<ProtectedRoute><ChangePassword /></ProtectedRoute>`                                            | self-service change for any authenticated User; the only route a flagged session is offered                                                                   |
| `*`                | `<Navigate replace to="/" />`                                                                    | unknown paths fall back to login                                                                                                                              |

**Permission-based guards.** The SPA decides navigation, page guards and in-page
actions from the `permissions` `GET /api/auth/me` and the login response report
— there is no role. `src/auth/permissions.ts` holds the one table of what each
administrative view requires (`VIEW_PERMISSIONS`: Users `user:read`, Groups
`group:read`, connectors `connector:read`) and the `holds` / `holdsAny` checks; a
route states its requirement as `requiredPermissions`, which
`resolveSessionRoute` reads as "any one of these", and a page hides each action
it lacks the Permission for (Unlock and the forced change `user:write`, connector
create and delete `connector:write`, token issue, rotate and revoke
`connector:token`, the counter's buttons `counter:write`). A view the session
cannot see is not requested either, so it never turns into a `403` on screen. A
deep link to a page the session holds no Permission for is redirected to
`/showcase`, exactly as any page it may not see.

**Change-password route.** The backend confines a session whose password must be
replaced (the change-required flag, see `/CONTEXT.md`): `GET /api/auth/me` and
the login response report `passwordChangeRequired: true` with no Permission, and
every endpoint but `POST /api/auth/change-password` and logout answers `403`.
`AuthUser` carries that flag, and `resolveSessionRoute` confines such a session
to `/change-password`: any other path it asks for — `/showcase`, `/accounts`,
the login route, an unknown path (which falls back to login first) — redirects
there, ahead of the Permission check and of any recorded return destination, so a
flagged Superuser is confined exactly as a flagged User is. The page offers sign-out,
so a flagged User is never stuck on it. An unflagged User may open it too, for a
voluntary change, and the showcase links to it.

`pages/change-password.tsx` submits current and new password (plus a
confirmation checked in the browser) through `changePassword` on the auth
context, not through `useSessionRequest`: a `401` there is an answer about the
current password, and the seam would end the session on it unconditionally.
`src/auth/api.ts` maps the outcomes. `204` means every session of the User has
ended, the submitting one included, so the context clears its auth state and
records why, and the guard returns the visitor to login with "Your password was
changed" and no return destination — the next sign-in lands on `/showcase`, not
back on the change. A `400` carries the backend's `PasswordRuleViolation`, and
the page shows its statement of the unmet rule. The backend answers a wrong
current password and a lockout with the same bodiless `401`, so `api.ts` tells
them apart by asking `GET /api/auth/me` whether the session survived: a wrong
password leaves it standing; reaching the lockout threshold revokes every
session of the User, and the page then says an Admin must Unlock the account and
closes the form. Every refusal clears all three fields. The inputs are
uncontrolled, because React mirrors a controlled input's value into the DOM
`value` attribute, and no message the page shows contains either value.

`resolveSessionRoute` is the pure transition table behind both guard adapters.
It sends a Guest to login with a return destination, confines a flagged
session to `/change-password`, renders an authenticated route for any session,
renders a Permission-guarded one for a session holding any of its Permissions,
and redirects every other session to `/showcase`. After a successful change it sends the
visitor to login carrying `passwordChanged` instead of a return destination.
Spring Security remains authoritative for server operations: every operation
requires its own declared Permission, and a flagged session is refused everything but the change and
logout, even if client-side routing is bypassed, so the guard decides what is
_rendered_ and never what is _permitted_.

`BrowserRouter` means real paths, not hashes, so the backend has to serve
`index.html` for any unmatched path — that fallback is the backend's side of the
SPA contract, and a deep link like `/showcase` 404s without it.

A new protected area is a new `<Route>` wrapped in the existing
`ProtectedRoute`. Nested layouts and lazy route chunks are both unused; each page
renders its own sign-out, so there is no app shell. Add a layout route in
`App.tsx` when two pages need the same header or navigation, and a lazy chunk
when one page's code is heavy enough to be worth loading on demand — not before.

## What is deliberately absent

Nothing below exists yet. Each entry names where it goes, so the first person
to need it does not have to invent a convention.

| Concern                         | Where it goes                                                                        |
| ------------------------------- | ------------------------------------------------------------------------------------ |
| Global state                    | beside the feature that owns it; hoist to `src/lib/` only when a second one needs it |
| Server-state caching            | a query library wrapping `apiFetch`, wired in `App.tsx` beside `AuthProvider`        |
| Shared non-primitive components | `src/components/` (one level up from `ui/`), or beside the page that owns them       |
| Environment config              | `VITE_`-prefixed variables, read through `import.meta.env`, documented in README.md  |
| Nested layouts, lazy routes     | `src/App.tsx`, when pages share a header or nav, or a page is heavy                  |
| PWA / service worker            | `vite-plugin-pwa` in `vite.config.ts` + a `.fallowrc.jsonc` `entry` line             |

Already present, and where it lives: routing in `src/App.tsx`, authentication in
`src/auth/` (the idle sign-out and its expiry warning included), typed HTTP
results in `src/lib/http.ts`, and the top-level error
boundary in `src/components/error-boundary.tsx`, wrapped around the whole tree
in `App.tsx` because there is no app shell for it to sit inside.

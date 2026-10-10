# AGENTS.md — frontend

React + Vite + Tailwind baseline, and the full tooling gate around it. Its
pages sit behind a session-backed login: `react-router-dom` routes them, `src/auth/`
owns the session, and requests receive typed semantic results from
`src/lib/http.ts`. There is no global state library and no service worker — this
file describes what is actually here, not what is planned.

Monorepo-wide rules — layout, the path discipline, the SPA build contract, the
`npm ci` pin, line endings, ignore rules, and the shared agent docs — live in the
root `AGENTS.md`. This file covers only what is specific to this app. Run every
command below from `frontend/`.

## Commands

`npm run` lists every script; `package.json` is the source of truth for what each
one does. The invocations worth caching are the ones you cannot read off that
list:

```bash
npm ci                                # run first — node_modules is often stale, so vitest may be missing
npx playwright install chromium       # first E2E run on a machine only
npm test src/pages/showcase.test.tsx  # one file
npm test -- -t "name of test"         # one test by name
npx stryker run --mutate '<src-glob>,!<test-glob>'   # scoped Stryker (see docs/TESTING_GUIDE.md)
npx fallow audit                      # dead code / complexity / duplication in the changeset
npx fallow dead-code --trace <file>:<export>         # a symbol's real consumers, before deleting it
```

`npm run test:mutation` is a whole-repo Stryker run — far too slow for an
implementation loop, so it belongs to CI. Reach for scoped Stryker instead, which
"Testing" below specifies as a conditional gate.

## Architecture

The folders under `src/` plus the composition root, and the dependency direction
runs one way through them. `docs/ARCHITECTURE.md` holds the per-file map and
each page's endpoints and Permissions; this list holds the rules.

- **`src/components/ui/`** — the shadcn primitives. This is a **placeholder**
  for the in-house component library (see below). It may import `cn` from
  `src/lib/` and its own siblings, nothing else.
- **`src/lib/`** — framework-agnostic helpers any layer may call, and a leaf:
  it imports nothing from `src/`. Holds `cn()`, `http.ts` and `decode.ts`;
  `apiFetch` owns credentials, CSRF recovery, status classification, and typed
  success decoding behind one semantic result interface (see "Backend
  contract"). `decode.ts` holds the primitives every response decoder is built
  from: a body is never cast to its type (`as T`) but read by a hand-written
  decoder that takes `unknown` and returns the value or throws `DecodeError`,
  and `apiFetch` turns a success body that does not decode into `failed` with
  no `status`. Each wire type's decoder lives beside the type, in the module
  that owns it. Shared hooks belong here too — `components.json` points the
  shadcn CLI at `@/lib/hooks`.
- **`src/auth/`** — the session and Permission-based authorization: session
  state, the pure routing contract and its guards, the Permission checks
  (`permissions.ts`), the request seam (`use-session-request.ts`), the gated
  read and gated write every page listing and action goes through
  (`use-gated-read.ts`, `use-gated-write.ts`), the idle sign-out (see
  "Backend contract"), and the sign-in reason (`sign-in-reason.ts`): why a
  Guest is at the login page, how that reaches it, and what the page says. A gated read sends nothing for a session lacking its
  Permission; a gated write owns the pending flag, the page's single error line
  and the refusal copy.
- **`src/pages/`** — one component per page, plus `accounts-api.ts` for the
  wire types and paths the Accounts page and its connector panel share, and
  `audit-api.ts` for the Audit page's. A page
  requests through `useSessionRequest` — in practice through `useGatedRead` /
  `useGatedWrite` — never `apiFetch` directly; the
  `mb-transport-is-behind-the-session-seam` rule enforces it. The one exception
  in kind is `change-password.tsx`, which submits through the auth context's
  `changePassword`, because its `401` is about the current password and must
  not end the session through the seam. Free to import from `auth/`, `ui/` and
  `lib/`. Each view is rendered — and its listing requested — only for a
  session holding that view's Permission, and each action is offered only with
  its own. `active` and Group membership are the directory's, so no page has a
  control that writes them.
- **`src/components/`** — shared non-primitive components (`error-boundary.tsx`).
- **`src/App.tsx` / `src/main.tsx`** — the composition root. `main.tsx` mounts
  and owns the one `src/index.css` import; `App.tsx` owns the `BrowserRouter`,
  wraps everything in `AuthProvider`, and states what each route requires with
  `GuestRoute` (`/`), `ProtectedRoute` (`/showcase`, `/change-password`), and a
  Permission-guarded `ProtectedRoute requiredPermissions={ADMINISTRATION_PERMISSIONS}`
  (`/accounts`) and `requiredPermissions={["audit:read"]}` (`/audit`): each
  renders for a session holding any one of the listed Permissions, and a deep
  link from any other session is redirected to `/showcase`. A session with the
  change-required flag is confined to `/change-password` by the guards' shared
  transition table, whatever path it asks for and whatever it holds. Every such
  decision is a rendering decision only: the backend enforces each operation's
  Permission on its own. The outermost element is
  `ErrorBoundary`: a render error anywhere
  below shows a generic "Something went wrong" fallback with a reload action,
  logs to `console.error` only, and never puts the error's message or stack in
  the DOM.

`@/` resolves to `src/`, declared in four places that must agree — the table is
in `docs/ARCHITECTURE.md` under "The `@/` alias". Change all four together, or
the change breaks a different tool than the one being edited.

Four rules are review-blocking, and `npm run test:arch` enforces all four:

- **`src/components/ui/` imports only `src/lib/` and its siblings.** A
  primitive that reaches into a page cannot be swapped out for the published
  package.
- **`src/lib/` imports nothing from `src/`.**
- **There is no `src/types/`, `src/hooks/` or `src/utils/`.** Types live in the
  folder that owns them; shared helpers and hooks live in `src/lib/`.
- **Colors come from the tokens in `src/index.css`.** Tailwind utilities
  (`bg-card`, `text-muted-foreground`) in TSX, `var(--color-*)` in CSS. A new
  shade goes in the `:root` / `.dark` pair and the `@theme inline` block, which
  is the only place a raw `oklch()` belongs. `test/arch/designTokens.test.ts`
  catches a literal hex, `rgb()`, `hsl()` or `oklch()` elsewhere; Tailwind
  compiles `bg-[#0f172a]` without complaint, so nothing else would.

**Read `docs/ARCHITECTURE.md`** before adding a folder under `src/`, changing
the path alias, or touching the token pipeline. It has the folder map, the
reasoning behind each dependency rule, and where a new concern belongs.

## Backend contract

This section is the authority on the runtime contract with the backend — read it
before changing anything that issues a request, and update it here when the
backend side moves. What the SPA has to honour:

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
  password change, and an expiry — and the next unsafe request fetches the new
  session's before it is sent. A refused login is one of them because the
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
  signed in, which is a `GET /api/auth/me` and so renews the backend's idle
  clock too; at the limit the SPA calls logout, discards the CSRF token and
  sends the user to login with the sign-in reason `inactive`. A session the backend ended first
  still takes the ordinary `401` path below.
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
  `GET /api/auth/me` answers `authenticated`, and the guest route at `/` sends
  the clinician on to `/showcase`. A refused launch lands at
  `/?signin=refused` with its session ended — whoever's it was — and the
  login page shows only the neutral "Sign-in from Epic was refused"; the
  refusal's reason never reaches the browser. The second outcome is Epic being
  unavailable — no answer within the backend's timeouts, or an Epic `5xx`, on
  discovery, the JWKS or the token call — which lands at
  `/?signin=unavailable`, its session ended the same way, and the login page
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

## Component library

`src/components/ui/` holds hand-written stand-ins for `Button` and the `Card`
family — enough for the pages to render, and deliberately no more. They
follow the shadcn shape (a `cva` variant table, `cn()` merging a `className`
override) so that swapping them out is a delete plus an import rewrite. Keep
them cheap to delete: no `asChild` / Radix `Slot` (the real library owns that),
and no icon dependency.

**When the in-house shadcn package is published:** add it to `dependencies`,
delete `src/components/ui/`, and repoint `@/components/ui` at the package (or
change the imports). `components.json` is already configured for the shadcn CLI
— `src/index.css` as the token source, `@/lib/utils` as `cn`, `@/components/ui`
as the component target — so `npx shadcn@latest add <component>` also works if
a primitive is needed before the package lands.

## Testing

### Baseline gate

`npm run verify` is the baseline gate: one command, and a frontend change is
complete only when it exits zero. It runs the format check, lint, typecheck, the
unit tests, the architecture suite and the Semgrep scan. **`package.json` is the
source of truth for that list** — the root `Makefile`'s `verify-frontend` target
invokes the script rather than re-listing the steps, so the two cannot drift.

While iterating, run the pieces instead of the whole gate: `npm run typecheck`,
`npm test` and `npm run test:arch` are the fast inner loop, and establishing them
green _before_ touching code is what proves a later failure is yours.

### Conditional gates

Each carries its own trigger and its own completion criterion, and none of them
belongs in the baseline — a gate that runs on every change needs a binary bound,
and these do not have one until their trigger narrows the scope.

| Gate               | Command                                              | Trigger                                                   | Done when                                                                                                                                                                                                               |
| ------------------ | ---------------------------------------------------- | --------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **scoped Stryker** | `npx stryker run --mutate '<src-glob>,!<test-glob>'` | you wrote a unit test or changed an existing one          | every mutant killed, or a survivor carrying a justification earned by reading the mutated line and confirming the mutation leaves observable behavior unchanged — the survivor plus its reason go in the change summary |
| **Playwright E2E** | `npm run test:e2e`                                   | routes, request handling, or the session contract changed | the suite is green against live dependencies                                                                                                                                                                            |
| **fallow audit**   | `npx fallow audit`                                   | you added or deleted an export, a file, or a dependency   | zero findings — dead code sits at zero, so any it reports is one this changeset introduced                                                                                                                              |
| **Epic Login E2E** | `make epic-integration-test` (repo root)             | any change to Epic Login, on either side                  | `test/e2e/epic-launch.spec.ts` is green against the local SMART launcher, and the script exits zero                                                                                                                     |

**Epic Login E2E** drives a real SMART provider EHR launch through
`/api/auth/epic/launch`, the launcher's authorization and our callback to
`/showcase`, with the SMART Health IT launcher standing in for Epic. "Epic
Login" means anything on that path: `backend/.../auth/epic/`, the Epic routes'
security or session handling, `logInFromEpic`, the SPA's handling of the
launch landing, and the launcher setup itself. `make epic-integration-test`
starts Postgres, Redis and the launcher (compose profile `epic-launcher`), runs
the backend in the `dev` profile with a freshly generated key and the
`APP_EPIC_*` values in `backend/README.md`, "Local Epic launcher", and runs
`npm run test:e2e:epic`, the Playwright `epic` project. It is separate from the
**Playwright E2E** gate, which neither needs nor runs it; a change to Epic Login
that also pulls that gate's trigger runs both.

**Read `docs/TESTING_GUIDE.md`** before writing or changing a unit test, adding
an architecture or Semgrep rule, suppressing a fallow finding, adding a file
nothing imports, or writing or debugging an E2E spec. It has the per-command run
table, the vitest setup and colocation rules, the completion criterion a unit
test has to meet, why the arch suite reads sources through `node:fs` rather than
`import.meta.glob`, the Stryker `--mutate` trap, how a new Playwright spec gets
routed, the flakiness rules, and how to add a Semgrep rule and prove it fires.

## Fallow

**Trace before you delete.** `noUnusedLocals` catches unused _locals_ only — an
unused export, file, or dependency is invisible to `tsc`, and fallow's import
graph is what sees them. It cuts the other way too: that graph is syntactic, so
a symbol reached through a config file or a dynamic `import()` also reads as
unused. `npx fallow dead-code --trace <file>:<export>` (or
`--trace-dependency <name>`) prints the real consumer list in under a second —
delete on that evidence, never on a summary line.

## TypeScript

Strict, with `noUnusedLocals` / `noUnusedParameters` — an unused import is a
baseline failure, not a lint warning. Three TypeScript projects, and
`npm run typecheck` and `npm run build` each list all three explicitly, so a
project dropped from either invocation stops being checked at all:

- `tsconfig.json` — `src`, ES2020, DOM, excluding `*.test.*` and
  `*.testHelpers.*`. Sets `"types": []`, so no `@types/*` package is injected
  globally and browser source cannot accidentally reach `process` or `Buffer`.
  `src/vite-env.d.ts` pulls in `vite/client` by reference, which that array
  does not gate.
- `tsconfig.node.json` — `vite.config.ts`, `vitest.config.ts`,
  `playwright.config.ts`. ES2022, `"types": ["node"]` for their `process.env`
  reads.
- `tsconfig.test.json` — the colocated tests and `test/**`, excluding
  `test/e2e/**` (Playwright specs are checked by the Playwright run, not here).

Unused _exports_ are past what `tsc` can see — that is fallow's half, above.

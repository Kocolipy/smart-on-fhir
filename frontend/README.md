# front-end

React + TypeScript + Vite + Tailwind CSS v4, with the full tooling gate wired
up around a small session-authenticated app.

This is a **baseline repo**. The application content is deliberately thin — a
login page, a counter page for authenticated accounts, a self-service
change-password page, and an administrator-only accounts page that shows the
directory's Users and Groups read-only, unlocks a User or forces its password
change, and manages SCIM connectors and their tokens, all talking to the Spring
Boot backend over session cookies. What is actually built
out is the toolchain: type checking, linting, unit tests, architecture tests,
E2E, static security analysis, dead-code/complexity analysis, and mutation
testing.

## Setup

Node is pinned at the repo root (`/.nvmrc`, `/.tool-versions`) and declared in
`package.json`'s `engines`; `.npmrc` sets `engine-strict`, so a wrong Node fails
the install instead of warning. Activate the pin first:

```bash
nvm use                           # from the repo root; or: mise install
```

```bash
npm ci                            # exactly what package-lock.json records
npx playwright install chromium   # first time only, for npm run test:e2e
npm run dev                       # http://localhost:5173
```

Use `npm ci`, not `npm install` — the root `README.md` explains why, and
`npm install` belongs only to a deliberate dependency change whose lockfile
update you commit.

**No `.env` is required.** Nothing reads `import.meta.env` yet. When that
changes, the variable must be `VITE_`-prefixed (Vite only exposes that prefix
to the client) and documented here.

`npm run test:security` needs Semgrep on your `PATH`
(`pipx install semgrep`, or `pip install semgrep`).

## Scripts

`npm run` prints the full list. The ones whose name does not give them away:

| Command                 | Description                                             |
| ----------------------- | ------------------------------------------------------- |
| `npm run build`         | Type-check all three TS projects (`tsc -b`), then build |
| `npm run typecheck`     | `tsc -b` over app, node, and test projects              |
| `npm run test:arch`     | dependency-cruiser + the `test/arch` vitest suite       |
| `npm run test:security` | Semgrep, local ruleset in `semgrep/rules/`              |
| `npm run test:mutation` | Stryker mutation testing (whole repo — slow)            |
| `npm run analyze`       | Bundle visualizer → `dist/stats.html`                   |

Not npm scripts, but part of the gate:

```bash
npx fallow audit                                  # dead code / complexity / duplication
npx fallow dead-code --trace <file>:<export>      # a symbol's real consumers
```

Run a single test file or a single test by name:

```bash
npm test src/pages/showcase.test.tsx
npm test -- -t "counts each click"
```

## Project structure

```
src/
  main.tsx, App.tsx       composition root — mount, router, AuthProvider, the routes
  index.css               Tailwind entry + the design tokens
  auth/                   session state, Permission checks, route guards, request seam, gated read/write hooks, idle sign-out
  pages/                  one component per page, plus the admin API's wire shapes
  components/             shared components (error boundary)
  components/ui/          shadcn primitives (placeholder — see below)
  lib/                    cn(), typed HTTP results, response decoders
test/
  .dependency-cruiser.cjs module-boundary rules
  arch/                   architecture rules the module graph can't express
  e2e/                    Playwright specs, fixtures and the sign-in setup
semgrep/rules/            local Semgrep ruleset
docs/                     ARCHITECTURE.md, TESTING_GUIDE.md
```

`@/` resolves to `src/`. There is no `src/types/`, `src/hooks/` or `src/utils/`
— types live beside what owns them and shared helpers live in `src/lib/`.
`docs/ARCHITECTURE.md` has the per-file map and the reasoning behind each rule.

## Component library

`src/components/ui/` holds hand-written stand-ins for `Button` and the `Card`
family, shaped like shadcn so that swapping them for the in-house package is a
delete plus an import rewrite. `components.json` is already configured for the
shadcn CLI, so `npx shadcn@latest add <component>` works if a primitive is
needed before that package lands. `AGENTS.md` has the swap procedure and the
constraints that keep the placeholders cheap to delete.

## Styling

Tailwind CSS v4, CSS-first — there is **no `tailwind.config.js`**.
`@tailwindcss/vite` is the build-side setup and `src/index.css` is the
configuration: a `:root` / `.dark` palette in `oklch()`, mapped onto Tailwind
colour utilities by an `@theme inline` block. That makes `src/index.css` the only
file allowed to hold a raw colour value; everything else names a token, and
`npm run test:arch` fails on a literal colour anywhere else.

Dark mode is a `dark` class on an ancestor, not a media query, so it can be
toggled in-app. Nothing toggles it yet.

## Testing

`npm run verify` is the baseline gate every change must pass; scoped Stryker,
Playwright E2E and `fallow audit` are conditional gates, each with its own
trigger. `AGENTS.md` has the gates and their triggers; `docs/TESTING_GUIDE.md`
has the details of each.

Unit tests run on Vitest with happy-dom and Testing Library, colocated with the
code they cover.

E2E runs in Playwright projects split by identity: `setup` signs in the fixture
`user` and the Bootstrap Admin once and saves separate storage states, `user` verifies what a
baseline User (only the counter's baseline Permissions) sees and is refused,
`admin` drives the counter/session suites plus the Superuser's account
administration page and its endpoints, `guest` runs signed-out and smoke
coverage and signs in each development Role's User, and `connector-tokens` and
`dormancy` sign in as fixture Users for the token-Permission and dormancy
specs. `playwright.config.ts` is the list; `docs/TESTING_GUIDE.md` explains why
they run as one chain. Every spec that signs in needs the backend running — see
the root `README.md` and `make integration-test`.

The backend seeds the Bootstrap Admin with a password change required, so
`setup` makes that change on first run: it moves `admin` from the seed password
to `E2E_ADMIN_PASSWORD` (default `E2e-Bootstrap-Secret-4m`) and signs in with that.
On a database where `admin` still holds the seed password with nothing pending,
it makes the same change voluntarily. Password history refuses the seed
password afterwards, so this is one-way for that database. The specs read these
environment variables; the SPA reads none of them:

| Variable                      | Default                          | Used for                                                                                                                               |
| ----------------------------- | -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `E2E_ADMIN_PASSWORD`          | `E2e-Bootstrap-Secret-4m`        | the seeded Admin's password in the suite                                                                                               |
| `APP_LOCKOUT_MAX_ATTEMPTS`    | `3`                              | the backend's lockout threshold, mirrored by the lockout specs (`make integration-test` exports it from `backend/.env`)                |
| `APP_DEV_FIXTURES_PASSWORD`   | `Dev-Fixture-P@ssw0rd`           | the development Role Users' password (`make integration-test` exports it from `backend/.env`)                                          |
| `E2E_BACKEND_URL`             | `http://localhost:8080`          | SCIM calls that bypass the Vite proxy                                                                                                  |
| `E2E_EPIC_FHIR_BASE`          | none (unset)                     | the SMART launcher's FHIR base, the Epic spec's launch `iss`; declares the `epic` project (`make epic-integration-test` sets it)       |
| `E2E_EPIC_JWKS_URL`           | none — required by the Epic spec | our JWKS as the launcher container reaches it (`make epic-integration-test` sets it)                                                   |
| `E2E_EPIC_LAUNCHER_CONTAINER` | none — required by the Epic spec | the launcher's Docker container, paused for one callback to make its token endpoint unreachable (`make epic-integration-test` sets it) |

The backend's development role mapping seeds one User per Role when it runs with
`APP_DEV_FIXTURES_ENABLED=true` (as `backend/.env.example` sets): `account-admin`,
`auditor`, `connector-admin` and `monitoring`, the Superuser's being the Admin —
plus two Users in no Group: `user`, holding only baseline access, and `dormant`,
which the startup dormancy run locks for the dormancy spec.
`DEV_ROLES` and `loginAsRole()` in `test/e2e/auth.helpers.ts` sign in as each,
and `dev-roles.spec.ts` checks, for each one, its Permissions on `/api/auth/me`,
the pages and actions the SPA shows it, and that every Permission-guarded read
outside them is refused `403`.

## Backend contract

The SPA is served by the Spring Boot backend and shares its session cookie.
`AGENTS.md`'s "Backend contract" section is authoritative. `apiFetch()` owns
CSRF recovery and returns typed semantic results instead of raw responses:
`unauthenticated` expires auth state and returns the user to login, while
`forbidden` (an authorization refusal) and `csrf-expired` (a CSRF token that
could not be fetched) preserve the session and let the feature show
permission-denied or retry copy.

Epic Login reaches the SPA as a full-page navigation, never a request the SPA
makes. A refused EHR launch lands at `/?signin=refused`, signed out, and the
login page reads that marker off the URL and shows the neutral notice "Sign-in
from Epic was refused" — no reason, because the backend sends none. A launch
that found Epic unavailable — Epic too slow to answer, or answering with a server
error — lands at `/?signin=unavailable`, also signed out, and the page shows
"Sign-in from Epic is temporarily unavailable. Try again shortly." instead: the
clinician did nothing wrong and a relaunch may well succeed. Either notice wins
over a session-ended notice the history entry happens to carry. The page always
carries the line "Clinicians: open this application from Epic.", and its
password form is unchanged either way. A `signin` value the page does not know
shows nothing.

In development, `vite.config.ts` proxies `/api` to the backend on `:8080`, so
`npm run dev` needs the backend up for anything past the login form.

## Technology stack

- **React 19** with TypeScript (strict, `noUnusedLocals` / `noUnusedParameters`)
- **react-router-dom 7** for routing (`/` login, `/showcase` and
  `/change-password` authenticated, `/accounts` for a session holding any
  administrative view's Permission, `/audit` for one holding `audit:read`)
- **Vite 7** for development and building, with Brotli/gzip precompression
- **Tailwind CSS v4** (CSS-first, no config file) with shadcn-shaped tokens
- **Vitest 4** + Testing Library + happy-dom for unit tests
- **Playwright** for E2E, with per-identity projects reusing saved sessions
- **dependency-cruiser** for architecture rules
- **Semgrep** for static security analysis
- **fallow** for dead code, complexity and duplication
- **Stryker** for mutation testing
- **ESLint 9** (flat config) and **Prettier**

There is no global state library, no PWA support and no service worker.

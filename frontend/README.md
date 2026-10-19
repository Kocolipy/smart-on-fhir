# front-end

React + TypeScript + Vite + Tailwind CSS v4, with the full tooling gate wired
up around a small session-authenticated app: a login page, Home (the default
patient and a link to each page the account may open), a self-service
change-password page, an accounts page for administration, and a read-only
audit page, all talking to the Spring Boot backend over session cookies.

## Setup

Node is pinned at the repo root (`/.nvmrc`, `/.tool-versions`) and `.npmrc`
sets `engine-strict`, so a wrong Node fails the install. Activate the pin first:

```bash
nvm use                           # from the repo root; or: mise install
npm ci                            # never npm install — see the root AGENTS.md
npx playwright install chromium   # first time only, for npm run test:e2e
npm run dev                       # http://localhost:5173
```

`npm run dev` proxies `/api` to the backend on `:8080` (`vite.config.ts`), so it
needs the backend up for anything past the login form. `npm run test:security`
needs Semgrep on your `PATH` (`pipx install semgrep`).

**No `.env` is required.** Nothing reads `import.meta.env`. A new variable must
be `VITE_`-prefixed (Vite only exposes that prefix to the client) and
documented here.

## Scripts

`npm run` prints the full list. Beyond it:

```bash
npm test src/pages/home.test.tsx                  # one file
npm test -- -t "name of test"                     # one test by name
npx fallow audit                                  # dead code / complexity / duplication
npx fallow dead-code --trace <file>:<export>      # a symbol's real consumers
```

## Project structure

```
src/
  main.tsx, App.tsx       composition root — mount, router, AuthProvider, the routes
  index.css               Tailwind entry + the design tokens
  auth/                   session state, Permission checks, route guards, request seam, gated read/write hooks, idle sign-out
  pages/                  one component per page, plus an API module per page declaring its endpoints
  components/             shared components (error boundary)
  components/ui/          shadcn primitives (placeholder for the in-house library)
  lib/                    cn(), typed HTTP results, response decoders, time
test/
  .dependency-cruiser.cjs module-boundary rules
  arch/                   architecture rules the module graph can't express
  e2e/                    Playwright specs, fixtures and the sign-in setup
semgrep/rules/            local Semgrep ruleset
docs/                     ARCHITECTURE.md, TESTING_GUIDE.md, BACKEND_CONTRACT.md
```

`@/` resolves to `src/`. `docs/ARCHITECTURE.md` has the per-file map and the
reasoning behind each rule; `AGENTS.md` has the component-library swap procedure.

## Styling

Tailwind CSS v4, CSS-first — there is **no `tailwind.config.js`**.
`src/index.css` is the configuration: a `:root` / `.dark` palette in `oklch()`,
mapped onto Tailwind colour utilities by an `@theme inline` block, and the only
file allowed a raw colour value (`npm run test:arch` fails on one elsewhere).
Dark mode is a `dark` class on an ancestor, so it can be toggled in-app; nothing
toggles it yet.

## Testing

`npm run verify` is the baseline gate; `AGENTS.md` has the conditional gates and
their triggers, `docs/TESTING_GUIDE.md` the details. Every E2E spec that signs
in needs the backend running (`make integration-test` from the repo root).

The backend seeds the Bootstrap Admin with a password change required, so the
Playwright `setup` project makes that change on first run, moving `admin` to
`E2E_ADMIN_PASSWORD`. Password history refuses the seed password afterwards, so
this is one-way for that database. The specs read these variables; the SPA
reads none:

| Variable                      | Default                          | Used for                                                                                                                          |
| ----------------------------- | -------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| `E2E_ADMIN_PASSWORD`          | `E2e-Bootstrap-Secret-4m`        | the seeded Admin's password in the suite                                                                                          |
| `APP_LOCKOUT_MAX_ATTEMPTS`    | `3`                              | the backend's lockout threshold, mirrored by the lockout specs (`make integration-test` exports it from `backend/.env`)           |
| `APP_DEV_FIXTURES_PASSWORD`   | `Dev-Fixture-P@ssw0rd`           | the development Role Users' password (`make integration-test` exports it from `backend/.env`)                                     |
| `E2E_BACKEND_URL`             | `http://localhost:8080`          | SCIM calls that bypass the Vite proxy                                                                                             |
| `E2E_EPIC_FHIR_BASE`          | none (unset)                     | the SMART launcher's FHIR base, the Epic spec's launch `iss`; declares the `epic` project (`make integration-test` sets it)       |
| `E2E_EPIC_JWKS_URL`           | none — required by the Epic spec | our JWKS as the launcher container reaches it (`make integration-test` sets it)                                                   |
| `E2E_EPIC_LAUNCHER_CONTAINER` | none — required by the Epic spec | the launcher's Docker container, paused for one callback to make its token endpoint unreachable (`make integration-test` sets it) |
| `E2E_PACKAGED_BASE_URL`       | none (unset)                     | the packaged JAR's origin; declares the `packaged` project (`make integration-test` sets it)                                      |

The specs sign in as the backend's development fixtures (one User per
development Role, plus `user` and `dormant`; `backend/docs/configuration/role-mapping.md`)
through `DEV_ROLES` and `loginAsRole()` in `test/e2e/auth.helpers.ts`.

## Backend contract

`docs/BACKEND_CONTRACT.md` is the authority on the runtime contract: CSRF,
the `401`/`403` split, the session window, Epic Login's landing, and the CSP.

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
npm test src/pages/home.test.tsx      # one file
npm test -- -t "name of test"         # one test by name
npx stryker run --mutate '<src-glob>,!<test-glob>'   # scoped Stryker (see docs/TESTING_GUIDE.md)
npx fallow audit                      # dead code / complexity / duplication in the changeset
npx fallow dead-code --trace <file>:<export>         # a symbol's real consumers, before deleting it
```

`npm run test:mutation` is a whole-repo Stryker run — far too slow for an
implementation loop, so it is run deliberately, outside the loop. In the loop,
reach for scoped Stryker, which "Testing" below specifies as a conditional gate.

## Architecture

The folders under `src/` plus the composition root, with one dependency
direction through them: `main` → `App` → `pages` → `auth` → `components/ui` →
`lib`. `docs/ARCHITECTURE.md` holds the per-file map and
Permissions, and the reasoning; this list holds the rules.

- **`src/components/ui/`** — the shadcn primitives, a **placeholder** for the
  in-house component library (see below). Imports only `cn` from `src/lib/` and
  its own siblings.
- **`src/lib/`** — framework-agnostic helpers, and a leaf. `apiFetch`
  (`http.ts`) owns credentials, CSRF recovery, status classification and typed
  decoding. A response body is never cast (`as T`): it is read by a hand-written
  decoder built from `decode.ts`'s primitives, which lives beside the wire type
  it decodes. A shared hook goes here too.
- **`src/auth/`** — the session and Permission-based authorization. The auth
  state changes, and the CSRF token is forgotten, only through
  `session-transitions.ts`. Every page listing and action goes through the gated
  read or the gated write (`use-gated-read.ts`, `use-gated-write.ts`), each
  handed a **declaration** — the endpoint as data (path, decoder, Permission,
  and for a write the method and body), exported once by the API module that
  owns it. Each reports to the signed-in page's one error line
  (`page-status.ts`), where **the latest event wins**.
- **`src/pages/`** — one component per page, plus an API module per page that
  declares its endpoints. A page requests only through `useGatedRead` /
  `useGatedWrite`, reads `declaration.permission` — of the declaration that
  control sends — to decide whether to offer it, and never calls `apiFetch`
  (`mb-transport-is-behind-the-session-seam`). The one exception is
  `change-password.tsx`, whose marked `401` is about the current password and
  must not end the session. No page has a control that writes `active` or Group
  membership. A new signed-in page is one entry in `SIGNED_IN_PAGES`
  (`src/pages/signed-in-pages.tsx`).
- **`src/components/`** — shared non-primitive components.
- **`src/App.tsx` / `src/main.tsx`** — the composition root: the router,
  `AuthProvider`, the route table, and the outermost `ErrorBoundary`, which never
  puts an error's message or stack in the DOM. Every route guard decides only
  what is rendered; the backend enforces each operation's Permission on its own.

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

**Read `docs/BACKEND_CONTRACT.md`** before changing anything that issues a
request, reads a status, or touches the CSRF token, the session window, logout,
Epic Login's landing or the CSP — and update it in the same change when the
backend side moves. It is the authority on the runtime contract with the backend.

The closed sets this app decodes strictly from the API, and every exported
declaration's `permission`, are held to `/backend/docs/openapi.yaml` by the
SPA–API contract check, which lives outside this app because it reads both:
`make verify-contract` from the repo root (part of `make verify`, not of
`npm run verify`). Run it after changing any of them; `VOCABULARIES` in
`/scripts/spa-contract/check.mjs` lists exactly which sets it compares. A
declaration's `path` must build its absolute path from its arguments alone, so
the check can find its operation.

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

Each carries its own trigger and completion criterion; none has a binary bound
until its trigger narrows the scope, so none belongs in the baseline.

| Gate               | Command                                              | Trigger                                                                                                                                                                                                           | Done when                                                                                                                                                                                                               |
| ------------------ | ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **scoped Stryker** | `npx stryker run --mutate '<src-glob>,!<test-glob>'` | you wrote a unit test or changed an existing one                                                                                                                                                                  | every mutant killed, or a survivor carrying a justification earned by reading the mutated line and confirming the mutation leaves observable behavior unchanged — the survivor plus its reason go in the change summary |
| **E2E**            | `make integration-test` (repo root)                  | routes, request handling, the session contract, Epic Login on either side, `index.html`, `vite.config.ts`, a dependency that emits script or style, or the backend's CSP, response headers or SPA serving changed | the script exits zero, every Playwright project green                                                                                                                                                                   |
| **fallow audit**   | `npx fallow audit`                                   | you added or deleted an export, a file, or a dependency                                                                                                                                                           | zero findings — dead code sits at zero, so any it reports is one this changeset introduced                                                                                                                              |

**E2E** is one run of every Playwright project against one stack. `make
integration-test` runs the FHIRcast DCR shim's unit tests
(`backend/fhircast-shim`), packages the JAR with the SPA, starts SQL Server,
Redis, the SMART Health IT launcher standing in for Epic (compose profile
`epic-launcher`) and the local FHIRcast stand-in, Medplum and the DCR shim
(compose profile `fhircast`), and seeds Medplum. It runs the JAR twice in the
`dev` profile, on the one database and Redis, each with a freshly generated
key: on 8080 with the `APP_EPIC_*` values in
`backend/docs/configuration/epic-login.md`, "Local Epic launcher", and on 8081
with those in "Local FHIRcast stand-in". Then it runs `npm run test:e2e`.
Three projects exist only in that run:

- **`epic`** drives a real SMART provider EHR launch through
  `/api/auth/epic/launch`, the launcher's authorization and our callback to Home
  (`/`). "Epic Login" means anything on that path: `backend/.../auth/epic/`, the
  Epic routes' security or session handling, `logInFromEpic`, the SPA's handling
  of the launch landing, and the launcher setup itself.
- **`fhircast`** loads the 8081 JAR's origin and drives an EHR launch through
  Medplum to Home, and the FHIRcast hub through the helper that publishes
  `Patient-open` / `Patient-close`. The stand-in itself — the compose
  `fhircast` services, `backend/fhircast-shim` and its seed — is part of Epic
  Login for this gate's trigger.
- **`packaged`** loads the 8080 JAR's own origin. Every other project runs through
  `npm run dev`, which serves neither the built bundle nor the backend's CSP, so
  an inline script or a third-party origin breaks only here.

**Read `docs/TESTING_GUIDE.md`** before writing or changing a unit test, adding
an architecture or Semgrep rule, suppressing a fallow finding, adding a file
nothing imports, or writing or debugging an E2E spec. Above the transport, every
suite fakes the backend only through the test adapter (`fakeBackend`, or
`renderAsSession` for a page), never by mocking `apiFetch` or the auth context.

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

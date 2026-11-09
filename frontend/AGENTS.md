# AGENTS.md — frontend

React + Vite + Tailwind SPA behind a session-backed login: `react-router-dom`
routes the pages, `src/auth/` owns the session, and requests receive typed
semantic results from `src/lib/http.ts`. There is no global state library and no
service worker. Run every command below from `frontend/`.

## Commands

`npm run` lists every script, and `package.json` is the source of truth for
each. The ones you cannot read off that list:

```bash
npm ci                                # first: node_modules is often stale
npx playwright install chromium       # first E2E run on a machine only
npm test src/pages/home/home.test.tsx # one file
npm test -- -t "name of test"         # one test by name
npx stryker run --mutate '<src-glob>,!<test-glob>'   # scoped Stryker
npx fallow audit                      # dead code / complexity / duplication in the changeset
npx fallow dead-code --trace <file>:<export>         # a symbol's real consumers
```

`npm run test:mutation` is a whole-repo Stryker run, too slow for the loop; the
loop uses scoped Stryker (see Testing).

## Architecture

One dependency direction: `main` → `App` → `pages` → `auth` → `components/ui` →
`lib`, with `shell/` the signed-in layout every page renders inside.
**Read `docs/ARCHITECTURE.md`** before adding a folder under `src/`, changing the
path alias or touching the token pipeline: it has the folder map and the
reasoning behind each rule.

- **A response body is never cast** (`as T`): a hand-written decoder built from
  `src/lib/decode.ts`'s primitives reads it, beside the wire type it decodes.
- **The auth state changes, and the CSRF token is forgotten, only through
  `src/auth/session-transitions.ts`**, which owns the session generation: a `401`
  ends only the session its request was sent under.
- **Every request a page or the shell makes goes through `useGatedRead` or
  `useGatedWrite`**, handed a **declaration** — the endpoint as data, exported
  once by the API module that owns it — and a control is offered only when the
  session holds the declaration's `permission`. A page never calls `apiFetch`
  (`mb-transport-is-behind-the-session-seam`), except `change-password.tsx`,
  whose marked `401` is about the current password and must not end the
  session. The shell's FHIRcast start (`useSessionFhircast`, ADR 0016) is the
  one ungated request — a self-service release no `permission` expresses — still
  sent through the session seam so a `401` ends the session.
- **A page never imports `src/shell/`** (`mb-pages-do-not-import-the-shell`); a
  new signed-in page is one entry in `SIGNED_IN_PAGES` (`signed-in-pages.tsx`).
- **No page has a control that writes `active` or Group membership**: the
  directory owns both.
- **The outermost `ErrorBoundary` never puts an error's message or stack in the
  DOM**, and a route guard decides only what is rendered: the backend enforces
  each operation's Permission on its own.

`@/` resolves to `src/` in the places listed in `docs/ARCHITECTURE.md`, "The
`@/` alias"; change that whole list together.

These rules are review-blocking, and `npm run test:arch` enforces them:

- **`src/components/ui/` imports only `src/lib/` and its siblings**
  (`mb-ui-primitives-are-leaves`): a primitive stays swappable for a published
  package.
- **`src/lib/` imports nothing from `src/`.**
- **There is no `src/types/`, `src/hooks/` or `src/utils/`.** Types live in the
  folder that owns them; shared helpers and hooks in `src/lib/`.
- **Colors come from the tokens in `src/index.css`**: Tailwind utilities
  (`bg-card`) in TSX, `var(--color-*)` in CSS. A new shade goes in the `:root` /
  `.dark` pair and the `@theme inline` block, the only place a raw `oklch()`
  belongs. Tailwind compiles `bg-[#0f172a]` without complaint, so
  `test/arch/designTokens.test.ts` is what catches a literal color.

`src/components/ui/` is the in-house shadcn library in source form.
`docs/DESIGN.md` documents the visual language it was built against, but the
token _values_ live only in `src/index.css`: a DESIGN.md change that is not
also an `index.css` change never reaches the page.

## Backend contract

**Read `docs/BACKEND_CONTRACT.md`** before changing anything that issues a
request, reads a status, or touches the CSRF token, the session window, logout,
Epic Login's landing or the CSP — and update it in the same change when the
backend side moves.

The closed sets decoded strictly from the API, and every declaration's
`permission`, are held to `/backend/docs/openapi.yaml` by the root's
`make verify-contract`, which `npm run verify` does not run. A declaration's
`path` must build its absolute path from its arguments alone, so the check can
find its operation.

## Testing

### Baseline gate

`npm run verify` is the baseline gate: a frontend change is complete only when it
exits zero. It runs the format check, lint, typecheck, unit tests, architecture
suite and Semgrep scan, and **`package.json` is the source of truth for that
list**. Iterate with `npm run typecheck`, `npm test` and `npm run test:arch`,
and establish them green _before_ touching code, so a later failure is provably
yours.

### Conditional gates

| Gate               | Command                                              | Trigger                                                                                                                                                                                                           | Done when                                                                                                                                          |
| ------------------ | ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| **scoped Stryker** | `npx stryker run --mutate '<src-glob>,!<test-glob>'` | you wrote or changed a unit test                                                                                                                                                                                  | every mutant killed, or a survivor whose mutated line you read and confirmed leaves observable behaviour unchanged — both go in the change summary |
| **E2E**            | `make integration-test` (repo root)                  | routes, request handling, the session contract, Epic Login on either side, `index.html`, `vite.config.ts`, a dependency that emits script or style, or the backend's CSP, response headers or SPA serving changed | the script exits zero, every Playwright project green                                                                                              |
| **fallow audit**   | `npx fallow audit`                                   | you added or deleted an export, a file or a dependency                                                                                                                                                            | zero findings — dead code sits at zero, so any finding is this changeset's                                                                         |

**E2E** is one run of every Playwright project against one stack:
`make integration-test` packages the JAR with the SPA, starts SQL Server, Redis
and the local Epic stand-in (Medplum and the FHIRcast shim), runs the JAR in
the `local` profile, then `npm run test:e2e`. "Epic Login" in the trigger means
anything on that path: `backend/.../auth/epic/`, the Epic routes' security or
session handling, `logInFromEpic`, the SPA's handling of the launch landing, and
the stand-in itself (the compose `fhircast` services, `backend/fhircast-shim`
and its seed). Three projects run only there — `epic`, `fhircast` and
`packaged`, the one that loads the JAR's own origin, so an inline script or a
third-party origin breaks only there: `npm run dev` serves neither the built
bundle nor the backend's CSP.

**Read `docs/TESTING_GUIDE.md`** before writing or changing a unit test, adding
an architecture or Semgrep rule, suppressing a fallow finding, or adding a file
nothing imports. **Read `docs/E2E_GUIDE.md`** before writing or debugging an E2E
spec. Every suite fakes the backend only through the test adapter
(`fakeBackend`, or `renderAsSession` for a page), never by mocking `apiFetch` or
the auth context.

## Fallow

**Trace before you delete.** `tsc`'s `noUnusedLocals` sees unused locals only;
an unused export, file or dependency is visible only to fallow's import graph.
That graph is syntactic, so a symbol reached through a config file or a dynamic
`import()` also reads as unused. `npx fallow dead-code --trace <file>:<export>`
(or `--trace-dependency <name>`) prints the real consumers in under a second —
delete on that evidence, never on a summary line.

## TypeScript

Strict, with `noUnusedLocals` / `noUnusedParameters`: an unused import fails the
baseline. The TypeScript projects are listed explicitly by both `npm run
typecheck` and `npm run build`, so a project dropped from either stops being
checked:

- `tsconfig.json` — `src`, excluding `*.test.*` and `*.testHelpers.*`, with
  `"types": []` so browser source cannot reach `process` or `Buffer`
  (`src/vite-env.d.ts` references `vite/client`, which that array does not gate).
- `tsconfig.node.json` — `vite.config.ts`, `vitest.config.ts`,
  `playwright.config.ts`, `backend-origin.ts`, with `"types": ["node"]`.
- `tsconfig.test.json` — the colocated tests and `test/**`, excluding
  `test/e2e/**`, which the Playwright run checks.

# AGENTS.md

Monorepo-wide rules. Two self-contained apps at the root, each with its own
`AGENTS.md`, which governs inside its directory — **read it before changing that
app**:

- `frontend/AGENTS.md` — Vite + React + TypeScript SPA
- `backend/AGENTS.md` — Spring Boot 4 / Java 25 / Maven service

## Layout

```
frontend/ backend/  the two apps; each owns its manifest, build and tests
infra/              AWS CloudFormation template + deploy scripts (/infra/README.md)
scripts/            what the Makefile targets call, the SPA–API contract check, scripts/gate.sh
CONTEXT.md          domain glossary;  docs/domain/ its rules;  docs/adr/ decisions
docs/agents/        agent docs shared by both apps
graphify-out/       knowledge graph, tracked
Makefile            drives the two apps in sequence; `make help` lists every target
```

`infra/` is deployment material that no gate builds. Mind the name clash:
`make infra-up` / `infra-down` / `infra-logs` drive the **local Docker
dependencies** (SQL Server + Redis), not this directory.

## Paths

**Run every command from the app directory that owns it**: `npm` in
`frontend/`, `./mvnw` in `backend/`; each app resolves paths from its own root.

- **No `../` from inside an app** to the root or the other app: an app that
  reaches outside itself stops being independently buildable. The one sanctioned
  exception is `frontend.dist.dir` in `backend/pom.xml`, inert outside the
  release profile.
- **Shared material is referenced root-relative, never copied**
  (`/docs/agents/domain.md`), so it cannot be mistaken for a file inside an app.

## Build and validation

Each app owns one **baseline gate**, named in its `AGENTS.md`, plus
**conditional gates** (mutation testing, E2E…) with their own triggers and
completion criteria. `make verify` (repo root) runs both baselines, then the
**SPA–API contract check** (`make verify-contract`), which holds the vocabulary
the SPA mirrors by hand to `backend/docs/openapi.yaml`
(`scripts/spa-contract/README.md`).

**A change is complete only when the owning app's baseline gate is green, plus
every conditional gate its change triggered.** A change to the SPA contract runs
both apps' gates; a change to a mirrored vocabulary, a request declaration, or an
operation's `security` in `openapi.yaml` also runs `make verify-contract`. When a
gate cannot run, report the exact unverified scope and why.

Toolchain pins are enforced by the builds (`/README.md` has the table). Two bite
in ordinary work:

- **`npm ci`, never `npm install`**, in `frontend/`: `install` rewrites the
  lockfile. `node_modules` is often stale, and `engine-strict` fails a wrong
  Node outright.
- **`./mvnw`, never `mvn`**, in `backend/`: the wrapper pins and verifies Maven,
  and the Enforcer rejects a wrong JDK.

### Reading a long gate's result

A shell call that returns no output tells you nothing about the run. Run every
long gate through `scripts/gate.sh` from the repo root: `run` launches it
detached (`-C` sets the directory) into a log ending on a `GATE_EXIT=<status>`
**sentinel**; `read` waits for it and prints only the summary — sentinel, test
totals, Spring context count, findings line and, when red, the failure lines.

```bash
scripts/gate.sh run "${TMPDIR:-/tmp}/gate.log" make verify
scripts/gate.sh run "${TMPDIR:-/tmp}/gate-be.log" -C backend ./scripts/test-gates.sh fast
scripts/gate.sh read "${TMPDIR:-/tmp}/gate.log"
```

Green is `GATE_EXIT=0`, and for a gate with a scan, zero findings beside it.
Report the summary lines as evidence; open the log only when they do not explain
a failure. Call `read` without `--wait` where the runtime backgrounds a long call
and notifies you when it ends — that notification is the result. Where the
runtime kills long calls instead, pass `--wait <seconds>` under its limit; 124
means still running, so read again (`run` refuses a log with no sentinel). The
script's header documents every flag.

## Frontend/backend integration

```
frontend source -> frontend/dist -> backend/target/classes/static -> executable JAR
```

Both build directories are generated and ignored, and nothing generated is
copied back into a source directory. `make package` is the release path;
`./mvnw clean verify` packages no SPA (`backend/README.md`, "Bundle a
frontend"). Two agreements have their own
authorities:

- **Runtime** (CSRF, CSP, sessions): `frontend/docs/BACKEND_CONTRACT.md` — read
  it before changing request handling on either side.
- **Vocabulary** (Permissions, token Permissions, audit operations, lock causes,
  each request's Permission or Superuser requirement): `backend/docs/openapi.yaml`,
  held by the contract check, so a backend change to any lands with the SPA's.

## Line endings and ignores

`.gitattributes` pins the working tree to LF repo-wide (CRLF breaks shell
scripts, `.env` files and Maven); add new binary extensions there.

`.gitignore` at the root is the only ignore file. A pattern with a slash
anywhere but its end is anchored to the root, so an app-specific rule needs a
`/frontend/` or `/backend/` prefix, and one for both apps a `**/` prefix.

## Environment

- `frontend/` needs no `.env`; a new variable carries the `VITE_` prefix and is
  documented in `frontend/README.md`.
- `backend/` needs `.env` (copy `backend/.env.example`) and SQL Server + Redis:
  `make infra-up` starts both and creates the local database and its three
  database logins from `.env`. `SPRING_PROFILES_ACTIVE=local` runs the
  migrations at startup; outside `local`, `test` and `migrate`, Flyway is off.
  `backend/docs/configuration/database.md` has the logins.

**This remote is public.** Every credential, the Bootstrap Admin's first
password included, is read from the environment with no usable fallback, and a
credential you add takes none either; `application.yaml` gives fallbacks to
non-secret settings only.

## Agent

- **`/docs/agents/domain.md`** — read before exploring or changing domain
  behaviour, terminology or architecture: the glossary, the rules, the ADRs.
- **`/docs/agents/test-design.md`** — read before writing, changing or deleting
  a test, or removing a feature.
- **`/docs/agents/flaky-tests.md`** — read when a test has both failed and
  passed in your runs with no edit between them: confirm the flake, then ledger
  it.
- **`/docs/agents/issue-tracker.md`** — read before acting on an issue or PR.

Skills are fetched per machine, so every runtime's skill directory and
`skills-lock.json` are ignored. The one tracked skill is
`/.agents/skills/mutation-testing/`; each app's `AGENTS.md` has its invocation.

### graphify

`graphify-out/` holds a knowledge graph of the code. When the user types
`/graphify`, use the installed graphify skill first. Run every command as
`$(cat graphify-out/.graphify_python) -m graphify <command>`; if that file is
missing, run the skill's interpreter guard first.

**For a codebase question, query the graph before raw search**: `query
"<question>"`, `path "<A>" "<B>"` for a relationship, `explain "<concept>"` for
one concept. Each returns a scoped subgraph; read `graphify-out/GRAPH_REPORT.md`
only for broad architecture review or when those do not surface enough. Dirty
`graphify-out/` files are expected; skip the graph only when the task is about
its stale output or the user says so.

**Refresh before you report done**: run `update .` at the end of any turn that
changed code or docs, so the refresh lands with the change (it is AST-only and
free). A refresh that **fails** is reported, never forced: a rebuild with fewer
nodes is the shrink guard working, and `update --force` can drop nodes silently.
Hand it to your runtime's graph-maintenance agent.

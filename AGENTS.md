# AGENTS.md

Monorepo-wide rules. Two self-contained apps at the root, plus the shared agent
material both of them use.

App-specific rules live in the app's own `AGENTS.md` and take precedence inside
that directory:

- `frontend/AGENTS.md` — Vite + React + TypeScript
- `backend/AGENTS.md` — Spring Boot 4 / Java 25 / Maven

## Layout

```
frontend/         Vite + React + TypeScript SPA
backend/          Spring Boot 4 service (Java 25, Maven)
infra/            AWS CloudFormation template + deploy/cleanup scripts
scripts/          shell layer the Makefile targets call, and the SPA–API contract check
CONTEXT.md        domain glossary (see /docs/agents/domain.md)
docs/domain/      the behavior behind the glossary's terms, one file per area
docs/adr/         architectural decision records
docs/agents/      agent documentation shared by both apps
graphify-out/     knowledge graph, tracked (see "graphify" below)
.agents/ .claude/ .codex/ .kiro/  per-runtime agent tooling (see "Agent" below)
Makefile          cross-app orchestration; `make help` lists every target
```

Nothing compiles "the monorepo": each app owns its own dependency manifest,
build, and test tooling, and the root `Makefile` only drives the two of them in
sequence. `make help` is the default target, so read the target list from there
rather than from this file.

`infra/` is deployment material, not an app — neither app's gates build or test
it. Mind the name clash: `make infra-up` / `infra-down` / `infra-logs` start the
**local Docker dependencies** (SQL Server + Redis) and have nothing to do with
this directory. See `/infra/README.md`.

## Paths

**Run every command from the app directory that owns it.** `npm` only works in
`frontend/`, `./mvnw` only in `backend/`, and both apps' configs, scripts, and
tooling resolve paths relative to their own root.

Two rules follow from that:

- **Never introduce a parent-relative reference** (`../`) from inside an app to
  the repo root or to the other app. An app that reaches outside itself stops
  being independently buildable, and the reference silently changes meaning if
  the directory moves. The one sanctioned cross-app path is the SPA contract
  below, which is expressed inside `backend/` as a path it owns.
- **Shared material is referenced from the repo root, not copied.** Paths to
  `docs/agents/` in an app's `AGENTS.md` are written root-relative
  (`/docs/agents/domain.md`) so they cannot be mistaken for a file inside the
  app.

## Build and validation

Each app owns **one baseline gate** — a single command, named in the app's own
`AGENTS.md`, that decides whether a change to that app is complete. The root
holds no copy of what it runs: the command itself is the source of truth, and
this file only routes you to it.

| Scope         | Command                | From      |
| ------------- | ---------------------- | --------- |
| both          | `make verify`          | repo root |
| SPA–API check | `make verify-contract` | repo root |

`make verify` invokes each app's baseline gate in sequence, then the one check
of its own: the **SPA–API contract check** (`make verify-contract`). That check
belongs to neither app because it reads both — which "Paths" forbids either app
to do — so it lives at the root, in `scripts/verify-contract.sh` and
`scripts/spa-contract/`, and `make verify` is the two app gates plus exactly
that, never anything more.

The check holds the vocabulary the SPA mirrors by hand to
`backend/docs/openapi.yaml`, which the backend's contract tests already hold to
the running service, so the SPA is held to the backend by transitivity. It
covers two things: the closed sets the SPA decodes strictly (the Permissions,
the token Permissions, the audit operations and the lock causes), each against
its enums in the document; and the Permission, or `superuser`, every exported
request declaration under `frontend/src/` names, against its operation's
`security`. `VOCABULARIES` in `scripts/spa-contract/check.mjs` is the one list
of which set is held to which schema; a new closed set joins it there.

A value either side gains or loses fails the check, naming the mismatch. It
needs `npm ci` in `frontend/` and the pinned Node, runs its own tests
(`scripts/spa-contract/check.test.mjs`) first, and refuses to pass on finding no
declaration, or an enum or export it expects missing.

Beside the baseline each app defines **conditional gates** — mutation testing,
end-to-end suites — each with its own trigger and completion criterion, in the
owning app's `AGENTS.md`; read it before declaring work done.

**A change is complete only when the owning app's baseline gate is green, plus
every conditional gate whose trigger the change pulled.** A change touching only
one app runs only that app's gates; a change touching the SPA contract runs
both. A change to a mirrored vocabulary, a request declaration, an operation's
`security` or one of those enums in `openapi.yaml` also runs
`make verify-contract`, which `make verify` already includes. When a gate cannot
run, report the exact unverified scope and the reason.

Toolchain versions are pinned per tool and enforced by the builds themselves;
`/README.md` holds the pin table and the activation commands. Two pins bite
during ordinary work:

- **`npm ci` in `frontend/`, never `npm install`** — `install` re-resolves
  semver ranges and rewrites the lockfile, so it belongs only to a deliberate
  dependency change. `node_modules` is often stale, and `frontend/.npmrc` sets
  `engine-strict`, so a wrong Node fails the install outright.
- **`./mvnw` in `backend/`, never a bare `mvn`** — the wrapper downloads and
  checksum-verifies the one pinned Maven release, and the build's Enforcer
  rejects a wrong JDK or an older Maven.

### Reading a long gate's result

A full scan or build can outlast a shell's foreground window, and the shell's
output stream is lossy: **a call that returns no output tells you nothing about
the run.** So write the result where the shell cannot lose it — redirect to a
log and append a **sentinel** carrying the exit status:

```bash
make verify > "${TMPDIR:-/tmp}/gate.log" 2>&1; echo "GATE_EXIT=$?" >> "${TMPDIR:-/tmp}/gate.log"
```

Then wait for the sentinel and read the log in one call, rather than polling:

```bash
until grep -q GATE_EXIT "${TMPDIR:-/tmp}/gate.log" 2>/dev/null; do sleep 5; done
grep -E "inding|ERROR|GATE_EXIT" "${TMPDIR:-/tmp}/gate.log"
```

The gate is green on `GATE_EXIT=0`, and a gate that includes a scan is green
only with a zero findings count beside it. Report those lines, quoted from the
log, as the evidence. On empty output re-read the log — a relaunch only starts a
second run competing for the same file.

## Frontend/backend integration

One build contract, and no committed build output anywhere in it:

```
frontend source -> frontend/dist -> backend/target/classes/static -> executable JAR
```

`frontend/dist/` and `backend/target/` are both generated and ignored.
**Nothing generated is ever copied back into a source directory.**

The copy is the `with-frontend` Maven profile in `backend/pom.xml`, which is
**off by default**: `./mvnw clean verify` in `backend/` is a pure backend build
that needs no Node and packages no SPA. The release path is `make package` from
the repo root, which builds the SPA and then invokes the profile with an
explicit `-Dfrontend.dist.dir`. The profile's `validate`-phase enforcer fails
the build when `index.html` is absent from that directory, so a missing or
half-built frontend is an error rather than a silently stale SPA.

`frontend.dist.dir` defaults to `${project.basedir}/../frontend/dist`, the one
sanctioned parent-relative path in the tree: it is inert unless the profile is
active, so `backend/` stays independently buildable, and `make package`
overrides it explicitly rather than relying on it.

The **runtime** contract — CSRF (a session-bound synchronizer token the SPA
fetches from `GET /api/auth/csrf`; never a cookie), CSP, sessions — is a
separate agreement, and `frontend/docs/BACKEND_CONTRACT.md` is its
authority. Read it before changing request handling on either side.

The **vocabulary** the SPA mirrors from the API — its Permissions, the token
Permissions, the audit operations, the lock causes and each request's
Permission or Superuser requirement — is a third, and `backend/docs/openapi.yaml`
is its authority. The SPA–API contract check (`make verify-contract`, under
"Build and validation") holds the SPA to it, so a backend change to any of them
lands with the SPA's matching change or fails the gate.

## Line endings

`.gitattributes` at the root is repo-wide policy and the only copy — the apps do
not carry their own. CRLF in the working tree breaks shell scripts
(`/bin/bash^M: bad interpreter`), makes `.env` files unsourceable, and corrupts
Maven tooling, so the working tree is pinned to LF regardless of the machine's
git config. Add new binary extensions there, not in an app.

## Ignore rules

`.gitignore` at the root is the only ignore file; both apps' rules were merged
into it. Mind git's anchoring rule when editing: a pattern containing a slash
anywhere but the end is relative to the file's own directory, so an app-specific
rule needs an explicit `/frontend/` or `/backend/` prefix, and a rule that must
apply inside both apps needs a `**/` prefix. A bare name like `node_modules`
already matches at any depth.

## Environment

- `frontend/` needs **no** `.env`. Nothing reads `import.meta.env`. New
  variables must carry the `VITE_` prefix and be documented in
  `frontend/README.md`.
- `backend/` requires `.env`; copy `backend/.env.example` first. It also needs
  SQL Server and Redis: `make infra-up` from the repo root starts both and runs
  the one-shot `sqlserver-init` compose service, which creates the local
  database and its three database logins (owner, app, retention) from `.env`.
  `.env` sets `SPRING_PROFILES_ACTIVE=local`, the profile that runs the
  migrations at startup; outside `local`, `test` and `migrate`, Flyway is off.
  The logins have no fallback values; `backend/docs/configuration/database.md`
  has them.

`backend/src/main/resources/application.yaml` still carries working default
values for some non-database settings (the Bootstrap Admin's first password
among them) as env-var fallbacks. **This remote is public.** Any deployment that
does not set the env vars ships published credentials, so never rely on the
fallbacks and never add new ones.

## Agent

### Documentation

- **`/docs/agents/issue-tracker.md`** — issue creation, lookup, triage,
  comments, labels, closure. Read before acting on an issue or PR. One GitHub
  repository backs both apps.
- **`/docs/agents/domain.md`** — domain-documentation layout and how to consume
  it before exploring or changing domain behavior, terminology, or
  architecture.

Skills are fetched per machine, so each runtime's skill directory
(`.claude/skills/`, `.codex/skills/`, `.kiro/skills/`, the rest of `.agents/`)
and `skills-lock.json` are gitignored. The one exception is the shared
**`mutation-testing`** skill, tracked at `/.agents/skills/mutation-testing/` and
reviewed like any other doc — tool-agnostic, with `references/tool-adapters.md`
covering Stryker and PIT; each app's `AGENTS.md` documents its own invocation
and completion criterion.

### graphify

This project has a knowledge graph at `graphify-out/` with god nodes, community
structure, and cross-file relationships. When the user types `/graphify`, use
the installed graphify skill before doing anything else.

Invoke every Graphify command through the recorded interpreter:
`$(cat graphify-out/.graphify_python) -m graphify <command>`. If
`.graphify_python` is missing, follow the Graphify skill's interpreter guard
first.

**For a codebase question, query the graph before raw search.** With
`graphify-out/graph.json` present, run `query "<question>"` — or `path "<A>"
"<B>"` for a relationship and `explain "<concept>"` for a focused concept. Each
returns a scoped subgraph, usually far smaller than `GRAPH_REPORT.md` or raw
grep output. Read `graphify-out/GRAPH_REPORT.md` only for broad architecture
review, or when query/path/explain do not surface enough context. Dirty
`graphify-out/` files are expected after hooks or incremental updates and are no
reason to skip the graph; skip it only when the task is about stale graph output
itself, or the user says not to use it.

**Refresh before you report done**, by running `update .` through the recorded
interpreter at the end of any turn in which you changed code or docs — a
commit-triggered rule never fires mid-turn, and `graphify-out/` is tracked, so
the refresh belongs with the change that caused it. `update` is AST-only and
spends no API credit.

A refresh that **fails** is reported, not forced: a rebuild with fewer nodes is
graphify's shrink guard working as designed, and `update --force` past it
unattended can drop nodes silently. Hand it to whatever graph-maintenance agent
your runtime provides.

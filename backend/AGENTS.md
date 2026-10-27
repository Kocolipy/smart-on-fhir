# AGENTS.md — backend

Spring Boot 4 service on Java 25, built with Maven through the checked-in
wrapper. Always `./mvnw`: the release it pins lives in
`.mvn/wrapper/maven-wrapper.properties`, and the build's Enforcer rules in
`pom.xml` reject a wrong JDK or an older Maven. A JDK 25 must be on `PATH` — the
wrapper only launches Maven.

Monorepo-wide rules — layout, the path discipline, the SPA build contract this
service serves, line endings, ignore rules, the long-gate sentinel pattern, and
the shared agent docs — live in the root `AGENTS.md`. This file covers only what
is specific to this app. Run every command below from `backend/`.

## Verification

### Baseline gate

`./scripts/verify.sh` is the baseline gate: one command, and a backend change is
complete only when it exits zero. It runs the build, the tests, the ArchUnit
rules and the Semgrep scan. **The script is the source of truth for that list** —
the root `Makefile`'s `verify-backend` target invokes it rather than re-listing
the steps. It can outlast a shell's foreground window: read its result through
the root `AGENTS.md`'s sentinel-and-log pattern, run from `backend/`. Add or
update a focused regression test for every behavior change.

Two halves are worth knowing separately, because each has its own iteration loop:

- ArchUnit rules in `src/test/java/arch/ArchitectureTest.java`, which run inside `./mvnw clean verify`. Iterate with `./mvnw -Dtest=ArchitectureTest test`.
- `./scripts/semgrep.sh`, which scans **this app only** and exits non-zero on any finding: the checked-in rules in `semgrep/rules/`, each tested against `semgrep/tests/` before the scan, plus the `p/*` registry packs, whose contents track upstream — so a pack gaining a rule can turn this gate red with no commit here. `docs/TESTING_GUIDE.md` ("Semgrep scan") has how the scan is configured and why `.semgrepignore` is authoritative.

Run the gate as part of finishing the work, not as a separate pre-commit step. A red gate is a defect in the change, not in the gate. Move the class, adjust the design, or fix the flagged code. Suppress a Semgrep finding with `// nosemgrep: RULE_ID` plus a reason only when it is a false positive. Edit a rule, the ruleset list, or `.semgrepignore` only when the user asks for the architecture or the scan policy itself to change, and say so explicitly.

Changes to Redis-backed session persistence require an integration-level check against Redis; the controller tests use servlet mocks and do not exercise Redis.

### The FHIRcast shim

`fhircast-shim/` is not part of the service: it is the local Epic
stand-in's DCR shim, Medplum seed and the writer of the managed `.env` block
`make fhircast-up` keeps (`compose.yaml`, profile `fhircast`;
`docs/configuration/epic-login.md`, "Local Epic stand-in"), plain Node
with no dependencies, which compose runs on the pinned Node image. Maven never
builds it, so the baseline gate stays Node-free; the Semgrep scan covers it,
and `make integration-test` runs its unit tests first, the gate whose
`epic` and `fhircast` projects run against it. Iterate with
`node --test fhircast-shim/test/*.test.mjs`.

### Test suite policy

The inner loop under the baseline gate. `README.md` ("Test layers") holds the layers and their measured times; `scripts/test-gates.sh` holds the lists.

- Production authority: the Spring Boot service started by `BackendApplication` on SQL Server and Redis is the only path tests certify.
- Default verification: `./scripts/test-gates.sh fast` after every edit; `./scripts/test-gates.sh subsystem <package>` for the boundary you touched; `./scripts/test-gates.sh broad` before a push. The baseline gate stays the completion criterion.
- New tests: a class joins the fast gate's explicit list only when it boots no application (no `@SpringBootTest`) and starts no container, and reads no value from the process environment — an in-memory `ApplicationContextRunner` over named configuration classes, with any environment value injected by the test, is admitted; the subsystem gates select by package, so they need no edit. The fast gate fails before running anything, naming the entry, when a listed class has no source file under `src/test/java/`, so a deleted or renamed class leaves the list in the same change.
- Environment: the Surefire forks drop every `.env.example` key (`excludedEnvironmentVariables` in `pom.xml`), so a shell that sourced `.env` runs the same tests as a clean one. Add a new runtime variable there when you add it to `.env.example`. Epic is faked on loopback (`FakeEpic`); a test that reaches a real host is a defect.
- Removal standard: a broken test is corrected with source authority or removed with `git rm`, recoverable from history; skip markers and `@Disabled` stay out of the tree.
- Ledger: a suite-shaping change (a deletion, a gate edit, a measured runtime) records its numbers, from tool output, in the pull request that made it; `README.md` "Test layers" describes what each layer runs and stays free of counts and timings.

### Full-context tests

A `@SpringBootTest` class runs in a context Spring caches and reuses for every class with an identical configuration, and differing from all of them costs a boot. **Read `docs/TESTING_GUIDE.md` ("Full-context tests") before writing or changing one**: how to join an existing configuration, and how to write for a database other classes share.

Done when the gate log's context count (`grep -c 'Started .* in .* seconds'`) rises only for a configuration the behaviour genuinely needs.

### Conditional gate: mutation testing

Mutation testing checks that a test is **load-bearing**: that it fails when the behavior it names breaks. Its **trigger** is writing a unit test or changing an existing one — scoped to the tests you touched, never the whole module; the baseline gate never runs it. Target the touched test class and the production class it covers, with exactly this mutator set:

```bash
./mvnw org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses="com.example.backend.<package>.<ClassUnderTest>*" \
  -DtargetTests="com.example.backend.<package>.<TouchedTests>" \
  -Dmutators=STRONGER,NON_VOID_METHOD_CALLS,CONSTRUCTOR_CALLS,EXPERIMENTAL_NAKED_RECEIVER,EXPERIMENTAL_MEMBER_VARIABLE
```

A run takes tens of minutes: **read `docs/TESTING_GUIDE.md` ("Mutation testing (PIT)") before launching one** — it has the detached launch, why the mutator set is final, and how to read `mutations.xml`.

The tests are done when every mutant is KILLED, or a survivor carries a justification that names the test asserting the mutated behavior and says why that test still passes with the mutant alive — the mutation is masked by something the code does anyway, as when a domain record coerces the caller's null back to List.of(). A justification with no such test to name has found an unasserted line, not an equivalent mutant: a removed call to a void audit or log method reads as equivalent because there is no return value to trace, while in fact no test may assert the record at all. The surviving mutant plus the test that pins it belong in the change summary.

## Architecture constraints

Preserve these boundaries. **Read `docs/ARCHITECTURE.md` before changing any of them** — adding a protected operation, a login-state write, a way to end a session, a security-chain filter, or a reader of the session's attributes: it has each rule's mechanics, the ArchUnit test that holds it, and the reason.

- Spring Security owns authentication; authentication state lives in the HTTP session, which Spring Session persists in Redis.
- Login, CSRF and health, the Epic JWKS and Epic Login's three browser routes are public (the Epic ones `404` while Epic Login is off); everything else on the application chain is deny-by-default (ADR 0015).
- Every protected handler declares its Permission (or, for connector administration and setting a Group's Permissions, `hasRole('SUPERUSER')`) with `@PreAuthorize` on the handler method, and the chain repeats it as a URL rule. A new operation needs three things that agree: the declaration, the chain's rule, and the `security` requirement in `docs/openapi.yaml`.
- Authority is derived from Group membership, once, at login — never stored, never recomputed per request. A direct member of the Admin group is the Superuser (`ROLE_SUPERUSER`, ADR 0015).
- The Admin group and the Bootstrap Admin are recognised by the reservation marker, never by a name a connector can change.
- A credential never reaches a web adapter: the application layer hands out projections with no field a password hash could go in.
- The login path and the administration path share no application service. Only `LoginStateService` writes the login state, and the directory decides none of it.
- `auth` reaches the directory through `scim.domain` ports; nothing in `scim` mentions `auth`.
- Submitted credentials are authenticated only through `LoginService`, never by a web adapter calling `AuthenticationManager`.
- Deactivation and unlocking are separate capabilities, and the administration adapter writes no directory-owned field (`/docs/domain/authentication.md`).
- A login-state or administrative write is a narrow port operation, never a full-row write.
- Ending a User's sessions goes only through `SessionRevocationService`; ending the caller's own session only through `SessionEnding`.
- A security-chain filter that reads the signed-in session is a named web-layer bean, placed by `@Qualifier`, with its container registration disabled.
- The signed-in session's own attributes are read and written only through `auth.domain.SignedInSession`.
- Runtime credentials and environment-specific settings remain external configuration.

`src/test/java/arch/ArchitectureTest.java` is the executable form of these boundaries. Its module graph is an allowlist, `MODULE_DEPENDENCIES`: a new top-level package or a new dependency between modules fails the build until declared there, deliberately.

For domain terminology and architectural decisions, follow `/docs/agents/domain.md`. Record durable architectural choices as ADRs rather than expanding this file.

## Security-sensitive changes

Treat authentication, authorization rules, logout, session invalidation, cookie attributes, and credential handling as security-sensitive. Cover changed behavior with tests and keep production secrets out of tracked files.

The SPA depends on several of these at runtime: the session-bound CSRF synchronizer token from `GET /api/auth/csrf`, the `401` versus `403` split, the session window, and the CSP. `frontend/docs/BACKEND_CONTRACT.md` states what it relies on — read it before changing any of the four, and update it in the same change. The token never travels in a cookie: `CookieCsrfTokenRepository` and `csrf.spa()` are banned by the `be-csrf-cookie-token` Semgrep rule, and `/docs/adr/0009-csrf-synchronizer-token.md` records why.

## API contract

Before completing changes to controller routes, request or response bodies, status codes, authentication requirements, or validation constraints, update `docs/openapi.yaml`. Verify every affected operation and schema against the implementation.

The baseline gate verifies it too: the tests in `src/test/java/com/example/backend/contract/` hold every response their fixtures receive against the document, fail for any documented status no fixture produces, and compare the mapped routes with the documented operations. A route or status you add therefore needs both its documentation and a fixture that produces it. `docs/api-contract-check.md` says what is checked and how to read a failure.

The SPA mirrors part of the document by hand — the Permission, token-Permission, `AuditOperation` and `lockCause` enums, and the `security` of each operation it calls — and the root's SPA–API contract check (`make verify-contract` from the repo root, part of `make verify`) holds it there. A change to any of them is a change to the SPA too: `VOCABULARIES` in `/scripts/spa-contract/check.mjs` lists exactly which schemas it compares.

`docs/openapi.yaml` is larger than one read holds: search it for the path or schema you are changing rather than reading it whole.

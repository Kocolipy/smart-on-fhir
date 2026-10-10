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
the steps, so the two cannot drift. Add or update a focused regression test for
every behavior change.

Two halves are worth knowing separately, because each has its own iteration loop:

- ArchUnit rules in `src/test/java/arch/ArchitectureTest.java`, which run inside `./mvnw clean verify`. Iterate with `./mvnw -Dtest=ArchitectureTest test`.
- `./scripts/semgrep.sh`, which scans **this app only** and exits non-zero on any finding. It runs two halves: the checked-in local rules in `semgrep/rules/` (offline and deterministic, each rule carrying the reason this service cares about it) and the `p/*` registry packs for generic Java and OWASP coverage. The pack _list_ is fixed in the script, but the packs' _contents_ resolve from the registry at run time and track upstream, so a pack gaining a rule can turn this gate red with no commit here. The script owns both config lists; `.semgrepignore` owns the skipped paths, and the script's `--project-root .` is what makes that file authoritative — Semgrep resolves the project root from git (the monorepo root), and without the pin the built-in default skip list stays in force and silently drops every file under `src/test/java`. The frontend scans itself separately via `npm run test:security` — nothing scans the monorepo as a whole.

Run the gate as part of finishing the work, not as a separate pre-commit step. A red gate is a defect in the change, not in the gate. Move the class, adjust the design, or fix the flagged code. Suppress a Semgrep finding with `// nosemgrep: RULE_ID` plus a reason only when it is a false positive. Edit a rule, the ruleset list, or `.semgrepignore` only when the user asks for the architecture or the scan policy itself to change, and say so explicitly.

Changes to Redis-backed session persistence require an integration-level check against Redis; the controller tests use servlet mocks and do not exercise Redis.

### Full-context tests

A `@SpringBootTest` class runs in a context Spring caches and reuses for every class with an identical configuration; each context boots once and gets its own migrated Postgres database and Redis database on containers the JVM shares (`ContainerTestConfiguration` holds the mechanism). Joining an existing configuration costs a new class nothing; differing from all of them costs a boot. Write a new class to join:

- Start from `@SpringBootTest` + `@Import(ContainerTestConfiguration.class)`, the configuration most classes share, and add only what the behaviour under test needs. Each extra profile, property, imported configuration, `@MockitoBean` or `@DynamicPropertySource` makes a context of its own; a property set to its default value is the usual needless one.
- Declare a test configuration as a top-level class and import it, as `InMemorySessionRegistryConfiguration` and `DormancyTestClockConfiguration` are: the cache keys on the class, so a nested copy shares with nobody. Import `InMemorySessionRegistryConfiguration` only when the test reads `InMemoryAccountSessions`.
- Write for a database other classes in the context have already used: mint unique names (a SCIM `userName` or Group `displayName` collides across classes), read back only the rows the test made, remove them afterwards, and assert on those rows or on deltas rather than whole-table counts.
- A class that destroys shared state — deletes or reshapes a seeded fixture, the Admin group or the Bootstrap Admin — declares `@DirtiesContext(classMode = AFTER_CLASS)`, as `RolePropagationIntegrationTests` does, so the eviction states the reason. Isolation that rests on a coincidentally different property disappears when a cleanup removes it.

Done when the gate log's context count (`grep -c 'Started .* in .* seconds'`) rises only for a configuration the behaviour genuinely needs. The suite runs in two JVMs (`forkCount` in `pom.xml`); pass `-DforkCount=1` to debug in one.

### Reading a gate's result

The baseline gate can outlast a shell's foreground window; read its result
through the sentinel-and-log pattern in the root `AGENTS.md` ("Reading a long
gate's result"), substituting `./scripts/verify.sh` run from `backend/`.

### Conditional gate: mutation testing

Mutation testing checks that a test is **load-bearing**: that it fails when the behavior it names breaks. It sits outside the baseline gate, and its **trigger** is writing a unit test or changing an existing one — scoped to the tests you touched, never the whole module. PIT is configured in `pom.xml` and bound to no lifecycle phase, so the baseline gate never runs it.

Target the touched test class and the production class it covers, with the **expanded mutator set** below and nothing else. It is a superset of PIT's `DEFAULTS` (`-Dmutators` replaces the list, so `STRONGER` carries the defaults), and the extra operators mutate lines `DEFAULTS` leaves alone — a discarded return value such as `request.changeSessionId()` yields no default mutant, so session-fixation rotation could go untested under a 100% score. One run is therefore final; line coverage below 100% beside a 100% score points at a line no operator reached:

```bash
./mvnw org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses="com.example.backend.<package>.<ClassUnderTest>*" \
  -DtargetTests="com.example.backend.<package>.<TouchedTests>" \
  -Dmutators=STRONGER,NON_VOID_METHOD_CALLS,CONSTRUCTOR_CALLS,EXPERIMENTAL_NAKED_RECEIVER,EXPERIMENTAL_MEMBER_VARIABLE
```

A PIT run takes tens of minutes, far past any shell's foreground window, so launch it **detached** with the same log-and-sentinel shape as the baseline gate:

```bash
setsid nohup bash -c './mvnw org.pitest:pitest-maven:mutationCoverage -DtargetClasses="..." -DtargetTests="..." -Dmutators=STRONGER,NON_VOID_METHOD_CALLS,CONSTRUCTOR_CALLS,EXPERIMENTAL_NAKED_RECEIVER,EXPERIMENTAL_MEMBER_VARIABLE > "${TMPDIR:-/tmp}/pit.log" 2>&1; echo "GATE_EXIT=$?" >> "${TMPDIR:-/tmp}/pit.log"' </dev/null >/dev/null 2>&1 &
```

Then hand the wait to a **monitor**: your runtime's scheduled wake that checks the log for `GATE_EXIT` on an interval and resumes you once it appears. End the turn after arming it. The run finishes no sooner for a turn held open on a sleep loop or on repeated reads of the log; it only spends the turn. Until the sentinel lands, leave this worktree's `target/` alone (no compile, test, or `clean`), because PIT is reading that bytecode and a rebuild voids the run.

`target/pit-reports/mutations.xml` carries the per-mutant status. `SURVIVED` means a test ran the line without asserting on the behavior, so strengthen the assertion; `NO_COVERAGE` means no test reached the line, so add the missing case. Narrowing `targetClasses`, dropping mutators, or asserting on a duplicated implementation constant moves the score without making the test load-bearing.

The tests are done when every mutant is KILLED, or a survivor carries a justification that names the test asserting the mutated behavior and says why that test still passes with the mutant alive — the mutation is masked by something the code does anyway, as when a domain record coerces the caller's null back to List.of(). A justification with no such test to name has found an unasserted line, not an equivalent mutant: a removed call to a void audit or log method reads as equivalent because there is no return value to trace, while in fact no test may assert the record at all. The surviving mutant plus the test that pins it belong in the change summary.

## Architecture constraints

Preserve these boundaries:

- Spring Security owns authentication.
- Authentication state is stored in the HTTP session.
- Spring Session persists sessions in Redis.
- Login, CSRF and health endpoints, the Epic JWKS (`GET /api/auth/epic/jwks.json`, ADR 0013, D14), and Epic Login's three browser routes (`GET /api/auth/epic/launch`, `/authorize` and `/callback`, ADR 0013) are public — the Epic ones while Epic Login is on, and `404` while it is off; everything else on the application chain is deny-by-default (ADR 0010).
- **Every protected handler declares its Permission with method security** — `@PreAuthorize("hasAuthority('<permission>')")` on the handler method, nowhere else — and the application chain repeats the same Permission as a URL rule, as a backstop. Its final rule denies whatever no earlier rule named, so a route left out of `SecurityConfig` is refused rather than open, and a handler added without a declaration fails `ArchitectureTest.every_protected_handler_declares_a_permission`. Self-service handlers (`AuthController`, `SelfController`, `SessionController`) and the public `EpicJwksController` and `EpicLaunchController` need no Permission and are listed as such in both places. A new operation therefore needs three things that agree: the handler's declaration, the chain's rule, and the `security` requirement in `docs/openapi.yaml` — `AuthorizationContractTests` proves the three against each other for every documented operation. The app-wide `ApiExceptionHandler` hands a method-security refusal back to the chain (it must never become a `500`), which answers, logs and audits it as the generic refusal.
- **Authority is DERIVED from Group membership, not stored.** There is no role column and no administrative role: every active SCIM User receives baseline `ROLE_USER` (self-service only), and its Permissions are the union of the Roles the role mapping assigns to the Groups it directly belongs to. The Admin group is the Superuser Group and grants every Permission through that Role, not through an authority of its own; `ROLE_ADMIN` does not exist.
- **Authority is derived once, at login.** A session carries the authorities it was issued with, so adding a User to a mapped Group grants its Role's Permissions at its next login and never mid-session. That is the specified behaviour rather than a limitation: recomputing per request would make an authority change take effect at an unpredictable moment and would put a database read on every authenticated request. `ScimEndToEndIntegrationTests` pins both directions of it.
- The Admin group is resolved by its reservation marker (`scim_resources.reserved_name`), never by its `displayName`. A protection or a derivation that depended on an attribute a connector may change would be no protection at all — which is also why that column is not updatable, and why the Bootstrap Admin's lockout exemption reads the same marker.
- A credential never reaches a web adapter. The application layer hands out projections (`IdentitySummary`, `ScimUserResource`) that have no field a password hash could be written into, so exposure is prevented structurally rather than by review. `ArchitectureTest` holds the boundary, so a future handler cannot reach around the projection by taking the aggregate.
- The login path and the administration path do not share an application service. `LoginIdentityService` serves authentication (`UserDetails` and the derived authorities); `IdentityAdministrationService` serves an administrator and, of the two, is the only one that mutates an identity. Do not add identity writes to the former; the login path's own write, the failure run and lockout, belongs to `FailureCounter`.
- **The login surface lives in `auth`; the directory lives in `scim`; the dependency runs one way.** `auth.application` reaches SCIM Users and Groups through `scim.domain` ports, and nothing in `scim` mentions `auth` — `ArchitectureTest.no_cyclic_dependencies` slices on `com.example.backend.(*)..`, so closing that loop fails the build. Anything needing a User's id from a userName goes through `ScimUserRepository`, not through the login surface's own service. `LockoutPolicy` lives in `scim.domain` for the same reason: the state it governs does.
- Authenticating submitted credentials goes through `LoginService`, which records the attempt against the identity as part of doing it. A web adapter never calls `AuthenticationManager` itself: an entry point that did would authenticate with no lockout, and nothing would fail. See `/docs/adr/0001-count-login-attempts-on-the-login-path.md`.
- Deactivation and unlocking are separate capabilities and neither performs the other — see **Unlock** in `/CONTEXT.md` and the rules in `/docs/domain-rules.md`. A User may be inactive, locked, both, or neither, and restoring one says nothing about the other. Deactivation is the directory's (SCIM) or a scheduled job's; the administration adapter exposes no write to any directory-owned field — `userName`, `displayName`, `active`, Group membership — and addresses Users by stable id. `AdminAccountControllerTests.noHandlerWritesADirectoryOwnedAttribute` holds that.
- A login-state or administrative write uses a narrow port operation (`updateLoginState`, `requirePasswordChange`), never a full-row write. The login path writes the User's row on every rejected attempt from a value it read at the start, so a full-row write would revert whatever an administrator or a connector changed in between — a password included — and would advance the SCIM version for a change no client can see. `ScimUserRepository#updateLoginState` records both reasons.
- Every per-User **Session revocation** goes through `SessionRevocationService`, which defers to the commit, ends the sessions, and audits and logs under its cause (see `/docs/domain-rules.md`). A new trigger calls that module with a `SessionRevocationCause`, and the directory reaches it through the `ScimUserSessions` port. Only the module calls the `AccountSessions` port. That port names one capability ("this account's sessions end"), and the `..infrastructure.session..` adapter owns everything Spring Session about it. `spring.session.data.redis.repository-type: indexed` is load-bearing: the default repository cannot be searched by principal, has no bean for that adapter, and startup fails rather than accepting a revocation it cannot enforce. It is set in `src/main/resources/session.yaml`, which the main and test `application.yaml` both import so they cannot disagree — change it there, not in either of them.
- Every read and write of the signed-in session's own attributes — the owner's stable id under the principal index, the role-mapping hash, and the Epic tokens with their lifetime-bounded idle clamp — goes through `auth.domain.SignedInSession`. Callers ask it questions (`owner()`, `issuedUnder`, `epicTokens`, `boundByLifetime`) rather than reading the attribute map, so a missing or malformed index identifies nobody everywhere, never a `500`. The module is plain domain code over `SignedInSession.Attributes`, which each adapter layer implements by delegation: `HttpSessionAttributes` in `auth.controller` and in `auth.config` (one adapter layer does not depend on another), and the read-only `SpringSessionAttributes` in `auth.infrastructure.session`. A new reader adapts its session the same way rather than calling `getAttribute`.
- Runtime credentials and environment-specific settings remain external configuration.

`src/test/java/arch/ArchitectureTest.java` is the executable form of the structural boundaries: the module graph, onion layering, package placement, naming, constructor injection, JPA mapping, and package-cycle freedom. The module graph is an allowlist, `MODULE_DEPENDENCIES`: a new top-level package under `com.example.backend` fails the build until it has an entry there, and a new dependency between modules fails until it is added to the depending module's set — so declare the edge deliberately, in that map, rather than working around the rule. The onion model treats `..config..` as an inbound adapter: configuration may wire an application seam, but application and domain code never depend on configuration. Read it before reshaping packages or adding a layer.

For domain terminology and architectural decisions, follow `/docs/agents/domain.md`. Record durable architectural choices as ADRs rather than expanding this file.

## Security-sensitive changes

Treat authentication, authorization rules, logout, session invalidation, cookie attributes, and credential handling as security-sensitive. Cover changed behavior with tests and keep production secrets out of tracked files.

The SPA depends on several of these at runtime: the session-bound CSRF synchronizer token from `GET /api/auth/csrf`, the `401` versus `403` split, the session window, and the CSP. `frontend/AGENTS.md`'s "Backend contract" section states what it relies on — read it before changing any of the four, and update it in the same change. The token never travels in a cookie: `CookieCsrfTokenRepository` and `csrf.spa()` are banned by the `be-csrf-cookie-token` Semgrep rule, and `/docs/adr/0009-csrf-synchronizer-token.md` records why.

## API contract

Before completing changes to controller routes, request or response bodies, status codes, authentication requirements, or validation constraints, update `docs/openapi.yaml`. Verify every affected operation and schema against the implementation.

The baseline gate verifies it too: the tests in `src/test/java/com/example/backend/contract/` hold every response their fixtures receive against the document, fail for any documented status no fixture produces, and compare the mapped routes with the documented operations. A route or status you add therefore needs both its documentation and a fixture that produces it. `docs/api-contract-check.md` says what is checked and how to read a failure.

# AGENTS.md — backend

Spring Boot 4 on Java 25, built through `./mvnw`. A JDK 25 must be on `PATH`:
the wrapper only launches Maven. Run every command below from `backend/`.

## Verification

### Baseline gate

`./scripts/verify.sh` is the baseline gate: a backend change is complete only
when it exits zero. It runs the build, the tests, the ArchUnit rules and the
Semgrep scan, and **the script is the source of truth for that list**. Run it
through the root's `scripts/gate.sh` with `-C backend`. Add or update a focused
regression test for every behaviour change.

Two halves have their own loops:

- **ArchUnit**: the rules are `src/test/java/arch/ArchitectureTest.java`
  (`./mvnw -Dtest=ArchitectureTest test`). The `*RulesTests` beside it show each
  rule reporting a planted violation; after changing a rule, run the package,
  `./mvnw -Dtest='arch/**' test`.
- **Semgrep**: `./scripts/semgrep.sh` scans this app only and fails on any
  finding — the rules in `semgrep/rules/`, each tested against `semgrep/tests/`
  first, plus the `p/*` registry packs, which track upstream, so a pack gaining a
  rule can turn the gate red with no commit here. `docs/TESTING_GUIDE.md`
  ("Semgrep scan") has the configuration and why `.semgrepignore` is
  authoritative.

A red gate is a defect in the change: move the class, adjust the design, or fix
the flagged code. A Semgrep false positive takes `// nosemgrep: RULE_ID` plus a
reason. Edit a rule, the ruleset list or `.semgrepignore` only when the user
asks for the architecture or scan policy itself to change, and say so.

A change to Redis-backed session persistence needs an integration check against
Redis: the controller tests use servlet mocks.

### Test suite policy

`README.md` ("Test layers") describes the layers; `scripts/test-gates.sh` holds
the lists.

- The Spring Boot service started by `BackendApplication` on SQL Server and Redis
  is the only path tests certify.
- Iterate with `./scripts/test-gates.sh fast` after every edit,
  `subsystem <package>` for the boundary you touched, and `broad` before a push.
  The baseline gate stays the completion criterion.
- A class joins the fast gate's explicit list only when it boots no application
  (no `@SpringBootTest`), starts no container and reads nothing from the process
  environment; an in-memory `ApplicationContextRunner` with injected values
  qualifies. The fast gate fails on an entry with no source file, so a deleted or
  renamed class leaves the list in the same change. Subsystem gates select by
  package and need no edit.
- The Surefire forks drop every `.env.example` key (`excludedEnvironmentVariables`
  in `pom.xml`): add a new runtime variable there when you add it to
  `.env.example`. Epic is faked on loopback (`FakeEpic`); a test that reaches a
  real host is a defect.
- A broken test is corrected from source authority or removed with `git rm`;
  `@Disabled` and skip markers stay out of the tree.
- A suite-shaping change (a deletion, a gate edit, a measured runtime) records
  its numbers, from tool output, in its pull request; `README.md` stays free of
  counts and timings.

### Full-context tests

Spring caches a `@SpringBootTest` context and reuses it for every class with an
identical configuration; differing from all of them costs a boot. **Read
`docs/TESTING_GUIDE.md` ("Full-context tests") before writing or changing
one.** Done when the gate summary's `Spring contexts started` count rises only
for a configuration the behaviour genuinely needs.

### Conditional gate: mutation testing

Mutation testing checks that a test is **load-bearing**: it fails when the
behaviour it names breaks. **Trigger**: writing or changing a unit test — scoped
to the touched test class and the production class it covers, never the module,
with exactly this mutator set:

```bash
./mvnw org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses="com.example.backend.<package>.<ClassUnderTest>*" \
  -DtargetTests="com.example.backend.<package>.<TouchedTests>" \
  -Dmutators=STRONGER,NON_VOID_METHOD_CALLS,CONSTRUCTOR_CALLS,EXPERIMENTAL_NAKED_RECEIVER,EXPERIMENTAL_MEMBER_VARIABLE
```

A run takes tens of minutes: **read `docs/TESTING_GUIDE.md` ("Mutation testing
(PIT)") before launching one**.

Done when every mutant is KILLED, or a survivor's justification names the test
asserting the mutated behaviour and says why it still passes — the mutation is
masked by something the code does anyway, as when a domain record coerces a null
back to `List.of()`. A justification with no test to name has found an
unasserted line, not an equivalent mutant: a removed call to a void audit or log
method looks equivalent only because nothing asserts the record. Survivors and
the tests that pin them go in the change summary.

### The FHIRcast shim

`fhircast-shim/` is the local Epic stand-in's DCR shim, Medplum seed and the
writer of the managed `.env` block `make fhircast-up` keeps — plain Node, no
dependencies, run by compose on the pinned Node image and never built by Maven.
The shim fronts Medplum's FHIRcast hub, which through it accepts a Hub Token
or, as Epic's does, the launch's SMART access token as `Bearer`, and denies a
subscription on its open socket (`hub.mode` `denied`) once the SMART access
token it was made with expires or is revoked; a test-only control expires one
at once. `docs/configuration/epic-login.md` ("Local Epic stand-in") has every
endpoint it answers. Semgrep scans it; `make integration-test` runs its unit
tests. Iterate with `node --test fhircast-shim/test/*.test.mjs`.

## Architecture constraints

**Read `docs/ARCHITECTURE.md` before changing any of these** — adding a
protected operation, a login-state write, a way to end a session, a
security-chain filter, or a reader of the session's attributes. It has each
rule's mechanics, the ArchUnit rule that holds it, and the reason.

- Spring Security owns authentication; its state lives in the HTTP session,
  which Spring Session persists in Redis.
- Public: login, CSRF, health, the SPA's files, the Epic JWKS and Epic Login's
  three browser routes (`404` while Epic Login is off). Everything else on the
  application chain is deny-by-default (ADR 0015).
- Every protected handler declares its Permission — or `hasRole('SUPERUSER')`
  for connector administration and setting a Group's Permissions — with
  `@PreAuthorize`, and the chain repeats it as a URL rule. A new operation needs
  three that agree: the declaration, the chain's rule, and its `security` in
  `docs/openapi.yaml`.
- Authority is derived from Group membership once, at login — never stored,
  never recomputed per request. A direct member of the Admin group is the
  Superuser (`ROLE_SUPERUSER`).
- The Admin group and the Bootstrap Admin are recognised by the reservation
  marker, never by a name a connector can change.
- A credential never reaches a web adapter: the application layer hands out
  projections with no field a password hash could go in.
- The login path and the administration path share no application service.
  Only `LoginStateService` writes the login state; the directory decides none of
  it.
- `auth` reaches the directory through `scim.domain` ports; nothing in `scim`
  mentions `auth`.
- Submitted credentials are authenticated only through `LoginService`, never by
  a web adapter calling `AuthenticationManager`.
- Deactivation and unlocking are separate capabilities, and the administration
  adapter writes no directory-owned field.
- A login-state or administrative write is a narrow port operation, never a
  full-row write.
- Ending a User's sessions goes only through `SessionRevocationService`; ending
  the caller's own session only through `SessionEnding`.
- A security-chain filter that reads the signed-in session is a named web-layer
  bean, placed by `@Qualifier`, with its container registration disabled.
- The signed-in session's attributes are read and written only through
  `auth.domain.SignedInSession`.
- Runtime credentials and environment-specific settings stay external
  configuration.

The ArchUnit module graph is an allowlist, `MODULE_DEPENDENCIES`: a new
top-level package or a new edge between modules fails the build until declared
there. Record a durable architectural choice as an ADR
(`/docs/agents/domain.md`), not in this file.

## Security-sensitive changes

Authentication, authorization rules, logout, session invalidation, cookie
attributes and credential handling are security-sensitive: cover the changed
behaviour with tests.

The SPA relies on four of them at runtime — the session-bound CSRF token from
`GET /api/auth/csrf`, the `401` versus `403` split, the session window and the
CSP. **Read `frontend/docs/BACKEND_CONTRACT.md` before changing any, and update
it in the same change.** The CSRF token never travels in a cookie: the
`be-csrf-cookie-token` Semgrep rule bans `CookieCsrfTokenRepository` and
`csrf.spa()` (ADR 0009).

## API contract

A change to a route, a request or response body, a status, an authentication
requirement or a validation constraint updates `docs/openapi.yaml` in the same
change. The tests in `src/test/java/com/example/backend/contract/` hold every
response against the document, fail on a documented status no fixture produces,
and compare mapped routes with documented operations — so a new route or status
needs both its documentation and a fixture that produces it
(`docs/api-contract-check.md`).

The SPA mirrors the Permission, token-Permission, `AuditOperation` and
`lockCause` enums and each called operation's `security`; `VOCABULARIES` in
`/scripts/spa-contract/check.mjs` lists exactly what the root's contract check
compares.

`docs/openapi.yaml` is larger than one read holds: search it for the path or
schema you are changing.

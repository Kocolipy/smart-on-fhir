---
type: Testing Guide
title: Backend testing guide
description: How the Semgrep scan is configured, how to write a full-context test that joins a cached Spring context, how an in-memory fake is held to its real adapter, and how to run and read a scoped PIT mutation run.
tags: [testing, semgrep, pit, spring-boot-test, contract-tests]
---

# Backend testing guide

## Semgrep scan

`./scripts/semgrep.sh` scans **this app only** and exits non-zero on any finding, with two halves: the checked-in rules in `semgrep/rules/` (offline, each carrying the reason this service cares) and the `p/*` registry packs, whose contents track upstream. Before the scan it tests the local rules against `semgrep/tests/`: each fixture is named after the rule file it tests and marks the lines a rule must report (`ruleid:`) and must leave alone (`ok:`), so a rule that stops firing fails the gate. A new rule needs its fixture there.

The script owns both config lists; `.semgrepignore` owns the skipped paths, and is authoritative only because the script pins `--project-root .` — both files' header comments say why. The frontend scans itself separately (`npm run test:security`).

## Full-context tests

A `@SpringBootTest` class runs in a context Spring caches and reuses for every class with an identical configuration; each context boots once and gets its own migrated SQL Server database and Redis database. Under the gate scripts every test JVM puts its databases on one SQL Server the script started; under a bare `./mvnw` or an IDE, each JVM starts a server of its own (`ContainerTestConfiguration` holds the mechanism). Joining an existing configuration costs a new class nothing; differing from all of them costs a boot. Write a new class to join:

- Start from `@SpringBootTest` + `@Import(ContainerTestConfiguration.class)`, the configuration most classes share, and add only what the behaviour under test needs. Each extra profile, property, imported configuration, `@MockitoBean` or `@DynamicPropertySource` makes a context of its own; a property set to its default value is the usual needless one.
- Declare a test configuration as a top-level class and import it, as `InMemorySessionRegistryConfiguration` and `DormancyTestClockConfiguration` are: the cache keys on the class, so a nested copy shares with nobody. Import `InMemorySessionRegistryConfiguration` only when the test reads `InMemoryAccountSessions`.
- A class that needs Epic Login on but signs no one in through Epic extends `EpicLoginOnContext` rather than declaring its own `@DynamicPropertySource`: the cache keys on the declaring method, so a copy per class boots a context per class. A suite that signs in through a fake Epic keeps its own (`EpicTestEnvironment` says why).
- The context's pools connect as the deployment's logins — the app login, and the retention login for the retention pool — and Flyway migrates as the owner login, so the context's `JdbcTemplate` has exactly the service's privileges. A test that must change the schema, delete an audit event or read another login's sessions (`sys.dm_exec_sessions`) speaks as that principal through the `DatabaseLogins` bean (`owner()`, `retention()`, `superuser()`), never by switching role.
- Read a `UNIQUEIDENTIFIER` column back through `UuidColumnRows`: an untyped `getObject` returns the id's upper-case text, which never equals a `UUID`.
- Write for a database other classes in the context have already used: mint unique names (a SCIM `userName` or Group `displayName` collides across classes) with `ScimTestRows.unique`, read back only the rows the test made, remove them afterwards, and assert on those rows or on deltas rather than whole-table counts. Register each User or Group the class creates with a `CreatedScimRows` field — its `createUser` and `createGroup` (through the application services), `post` and `create` (as a connector over SCIM HTTP) and `track` (for any other create's answer) do so as they create — and call its `deleteAll` from `@AfterEach`: it deletes exactly the registered ids, never "every row that is not reserved", which would take the rows of every other class sharing the database, as PIT's single-JVM run does. The MockMvc requests themselves are shared too: `SessionLogin` and `SessionCsrf` for the SPA's login and CSRF handshake (`ServerSession` against a `RANDOM_PORT` server), `ScimHttp` for a connector's SCIM request, `TestJson` for the bodies.
- A class that destroys shared state — deletes or reshapes a seeded fixture, the Admin group or the Bootstrap Admin — declares `@DirtiesContext(classMode = AFTER_CLASS)`, so the eviction states the reason; a class that moves a fixture and puts it back before its test ends destroys nothing, and shares the context — as `GroupPermissionPropagationIntegrationTests` shares `DevFixtures.Context` with `DevelopmentFixturesIntegrationTests`. Isolation that rests on a coincidentally different property disappears when a cleanup removes it. A case that runs inside a transaction rolled back after it destroys nothing, so it needs no eviction and joins the cached context. `@Transactional` does that where the class declares its own cases; a subclass whose cases are declared on an abstract contract registers `RollsBackEachCase`, as the SQL Server User store contracts do, because Spring reads `@Transactional` off the class declaring the case.

The suite runs in two JVMs (`forkCount` in `pom.xml`); pass `-DforkCount=1` to debug in one. The gate scripts start the one SQL Server those JVMs share (`scripts/test-sql-server.sh`) and remove it when they exit; a leftover from a killed run carries the label `smart-on-fhir.test-sql-server`.

## In-memory fakes

A unit test that runs against an in-memory fake of a port says something about production only while the fake does what the real adapter does. So **an in-memory fake used beside a real adapter shares an abstract contract with it, run against both**: an abstract `<Port>Contract` declares the cases and asks each subclass for the port, and two subclasses run them — `InMemory<Port>ContractTests` over the fake, which joins the fast gate, and `SqlServer<Port>ContractTests` over the adapter, beside the adapter's other tests and registering `RollsBackEachCase` (or cleaning up as the owning module's integration tests do). `ScimUserRepositoryContract`, `InMemoryScimUserRepositoryContractTests` and `SqlServerScimUserRepositoryContractTests` are the pattern.

- Cover what the unit tests rely on: lookups, uniqueness and conflicts, ordering, deletion, and — for a lock — exclusivity.
- A uniqueness or conflict case runs against the real adapter, never only the fake. A fake that throws where the table refuses still cannot model what the refusal does to the rest of the transaction — on SQL Server it can no longer commit — so the SQL Server half asserts that against the adapter alone, as `SqlServerScimConnectorTokenRepositoryContractTests` does, and the contract says so.
- A difference the contract exposes is fixed in the fake. A difference that is the adapter's defect is reported, not encoded in either half; a difference the fake keeps on purpose is named in the contract and left out of its cases.

## Mutation testing (PIT)

The trigger and the completion criterion are in `backend/AGENTS.md`; this is how a run is made and read.

Target the touched test class and the production class it covers, with the **expanded mutator set** below and nothing else. It is a superset of PIT's `DEFAULTS` (`-Dmutators` replaces the list, so `STRONGER` carries the defaults), and the extra operators mutate lines `DEFAULTS` leaves alone — a discarded return value such as `request.changeSessionId()` yields no default mutant, so session-fixation rotation could go untested under a 100% score. One run is therefore final; line coverage below 100% beside a 100% score points at a line no operator reached:

```bash
./mvnw org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses="com.example.backend.<package>.<ClassUnderTest>*" \
  -DtargetTests="com.example.backend.<package>.<TouchedTests>" \
  -Dmutators=STRONGER,NON_VOID_METHOD_CALLS,CONSTRUCTOR_CALLS,EXPERIMENTAL_NAKED_RECEIVER,EXPERIMENTAL_MEMBER_VARIABLE
```

A PIT run takes tens of minutes, far past any shell's foreground window, so launch it **detached** through the root `scripts/gate.sh`, as the baseline gate is, from the repo root:

```bash
scripts/gate.sh run "${TMPDIR:-/tmp}/pit.log" -C backend ./mvnw org.pitest:pitest-maven:mutationCoverage -DtargetClasses="..." -DtargetTests="..." -Dmutators=STRONGER,NON_VOID_METHOD_CALLS,CONSTRUCTOR_CALLS,EXPERIMENTAL_NAKED_RECEIVER,EXPERIMENTAL_MEMBER_VARIABLE
```

Then hand the wait to a **monitor**: your runtime's scheduled wake that checks the log for `GATE_EXIT` on an interval and resumes you once it appears; then `scripts/gate.sh read "${TMPDIR:-/tmp}/pit.log"` gives the result. End the turn after arming it. The run finishes no sooner for a turn held open on a sleep loop or on repeated reads of the log; it only spends the turn. Until the sentinel lands, leave this worktree's `target/` alone (no compile, test, or `clean`), because PIT is reading that bytecode and a rebuild voids the run.

`target/pit-reports/mutations.xml` carries the per-mutant status. `SURVIVED` means a test ran the line without asserting on the behavior, so strengthen the assertion; `NO_COVERAGE` means no test reached the line, so add the missing case. Narrowing `targetClasses`, dropping mutators, or asserting on a duplicated implementation constant moves the score without making the test load-bearing.

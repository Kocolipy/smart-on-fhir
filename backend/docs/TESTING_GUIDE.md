---
type: Testing Guide
title: Backend testing guide
description: How the Semgrep scan is configured, how to write a full-context test that joins a cached Spring context, and how to run and read a scoped PIT mutation run.
tags: [testing, semgrep, pit, spring-boot-test]
---

# Backend testing guide

## Semgrep scan

`./scripts/semgrep.sh` scans **this app only** and exits non-zero on any finding. It scans with two halves: the checked-in local rules in `semgrep/rules/` (offline and deterministic, each rule carrying the reason this service cares about it) and the `p/*` registry packs for generic Java and OWASP coverage. Before the scan it runs the local rules' tests, `semgrep scan --test` over the fixtures in `semgrep/tests/`: each fixture is named after the rule file it tests and marks every line a rule must report (`ruleid:`) and must leave alone (`ok:`), so a rule that stops reporting a planted line fails the gate. `.semgrepignore` keeps those fixtures out of the scan itself, since their planted lines are findings by design. The pack _list_ is fixed in the script, but the packs' _contents_ resolve from the registry at run time and track upstream, so a pack gaining a rule can turn this gate red with no commit here. The script owns both config lists; `.semgrepignore` owns the skipped paths, and the script's `--project-root .` is what makes that file authoritative — Semgrep resolves the project root from git (the monorepo root), and without the pin the built-in default skip list stays in force and silently drops every file under `src/test/java`. The frontend scans itself separately via `npm run test:security` — nothing scans the monorepo as a whole.

## Full-context tests

A `@SpringBootTest` class runs in a context Spring caches and reuses for every class with an identical configuration; each context boots once and gets its own migrated SQL Server database and Redis database on containers the JVM shares (`ContainerTestConfiguration` holds the mechanism). Joining an existing configuration costs a new class nothing; differing from all of them costs a boot. Write a new class to join:

- Start from `@SpringBootTest` + `@Import(ContainerTestConfiguration.class)`, the configuration most classes share, and add only what the behaviour under test needs. Each extra profile, property, imported configuration, `@MockitoBean` or `@DynamicPropertySource` makes a context of its own; a property set to its default value is the usual needless one.
- Declare a test configuration as a top-level class and import it, as `InMemorySessionRegistryConfiguration` and `DormancyTestClockConfiguration` are: the cache keys on the class, so a nested copy shares with nobody. Import `InMemorySessionRegistryConfiguration` only when the test reads `InMemoryAccountSessions`.
- The context's pools connect as the deployment's logins — the app login, and the retention login for the retention pool — and Flyway migrates as the owner login, so the context's `JdbcTemplate` has exactly the service's privileges. A test that must change the schema, delete an audit event or read another login's sessions (`sys.dm_exec_sessions`) speaks as that principal through the `DatabaseLogins` bean (`owner()`, `retention()`, `superuser()`), never by switching role.
- Read a `UNIQUEIDENTIFIER` column back through `UuidColumnRows`: an untyped `getObject` returns the id's upper-case text, which never equals a `UUID`.
- Write for a database other classes in the context have already used: mint unique names (a SCIM `userName` or Group `displayName` collides across classes), read back only the rows the test made, remove them afterwards, and assert on those rows or on deltas rather than whole-table counts.
- A class that destroys shared state — deletes or reshapes a seeded fixture, the Admin group or the Bootstrap Admin — declares `@DirtiesContext(classMode = AFTER_CLASS)`, as `RolePropagationIntegrationTests` does, so the eviction states the reason. Isolation that rests on a coincidentally different property disappears when a cleanup removes it.

The suite runs in two JVMs (`forkCount` in `pom.xml`); pass `-DforkCount=1` to debug in one.

## Mutation testing (PIT)

The trigger and the completion criterion are in `backend/AGENTS.md`; this is how a run is made and read.

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

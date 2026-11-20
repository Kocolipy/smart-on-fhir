---
type: Agent Guide
title: Flaky tests
description: Confirming that a test is a flake, and the ledger of confirmed flakes in both apps.
tags: [agents, testing]
---

# Flaky tests

A **flake** is a test that both passes and fails on one unchanged tree. This
file is the ledger of every confirmed flake in both apps, and the evidence that
admits one. A suspected flake stays out until confirmed: an entry sends the next
agent rerunning past what may be a real failure.

## Confirming a flake

The evidence is **incidental**: runs you made anyway, for the work in front of
you. Spend runs on that work only; a failure with no pass beside it is a real
failure, handled as the owning app's `AGENTS.md` says.

A test is a flake when your runs hold all of these:

- a failing run and a passing run of the test, in either order, each in a
  command that ran it;
- no edit between the two runs to the test or to code it exercises — when that
  code is code your working tree changes, the failure is your change's to fix,
  never ledgered;
- the failure line verbatim, from the failing run's log.

## Recording

Add one entry per test under **Ledger**, ordered by path:

```markdown
### `<app>/<path to test file>` — `<test name>`

- **Command**: the command of the failing run.
- **Failure**: the failure line, verbatim.
- **Evidence**: `<YYYY-MM-DD>`, `<short commit>`, the failing and the passing command.
- **Suspected cause**: what the failure points to, or `unknown`.
```

A test already in the ledger gains an **Evidence** line per observation, plus
its **Failure** line when that differs; one test keeps one entry.

A gate red only on ledgered flakes completes on its next green run; report the
entries beside the gate summary. A change that fixes a flake removes its entry
in the same change, so the ledger lists live flakes only.

## Ledger

### `frontend/test/e2e/accounts-admin.spec.ts` — `pages a connector's active tokens five at a time, and a revoked one leaves the list`

- **Command**: `make integration-test`
- **Failure**: `Error: expect(locator).toBeVisible() failed` (waiting for `getByText('Showing 6 to 7 of 7 tokens')`, `accounts-admin.spec.ts:505`)
- **Evidence**: `2026-11-20`, `b7b79cb3` with an uncommitted working tree, failed in `make integration-test`, passed in the same command run again with only FHIRcast code, its specs and docs edited between, none of which an Admin session reaches.
- **Suspected cause**: unknown; the pager did not show the second page within 5 s of `Next`.

### `frontend/test/e2e/dormancy.spec.ts` — `a dormant account is locked for dormancy until the Helpdesk unlocks it`

- **Command**: `COMPOSE_PROJECT_NAME=e2e325me make integration-test`
- **Failure**: `Error: locator.fill: Test timeout of 120000ms exceeded.` (waiting for `getByLabel('Username')`, `dormancy.spec.ts:56`)
- **Evidence**: `2026-11-20`, `f93e4750`, failed in `COMPOSE_PROJECT_NAME=e2e325me make integration-test` on fresh volumes, passed in the same command run again on that tree.
- **Suspected cause**: unknown; the sign-in page did not render its Username field within the test timeout on a cold stack.

### `frontend/test/e2e/home.spec.ts` — `sends a signed-in visit to login on to Home`

- **Command**: `make integration-test`
- **Failure**: `Error: expect(locator).toBeVisible() failed` (waiting for `getByRole('heading', { name: 'Profile' })`, `home.spec.ts:44`)
- **Evidence**: `2026-11-20`, `b7b79cb3` with an uncommitted working tree, failed in `make integration-test`, passed in the same command run again with no edit between.
- **Suspected cause**: unknown; the failure screenshot is a blank page, the SPA not yet rendered within 5 s. Not Vite's dependency optimizer running again mid-run: every import under `src/` is static, so a cold dev server's first page load discovers and optimizes them all.

### `frontend/test/e2e/idle-sign-out.spec.ts` — `warns before the idle limit, stays on Escape, and signs out at the limit`

- **Command**: `make integration-test`
- **Failure**: `Test timeout of 90000ms exceeded.` (then, in its cleanup, `Error: apiRequestContext.get: Target page, context or browser has been closed` at `scim.helpers.ts:198`)
- **Evidence**: `2026-11-20`, `b7b79cb3` with an uncommitted working tree, failed in `make integration-test`, passed in the same command run again with only `fhircast-registration.spec.ts` edited between, which it does not exercise.
- **Suspected cause**: unknown; the report names no step it waited on.

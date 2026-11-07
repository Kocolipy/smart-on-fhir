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

### `frontend/test/e2e/fhircast-card.spec.ts` — `an event published once the first lease has ended still reaches the card`

- **Command**: `make integration-test`
- **Failure**: `Error: expect(received).toEqual(expected) // deep equality` — `Expected: ["Connected"]`, `Received: undefined`, at `fhircast-card.spec.ts:228`.
- **Evidence**: `2026-11-07`, `9b0f7a73` (plus an uncommitted change that removes Settings from the sidebar and leaves this test untouched); `make integration-test` failed, then the next `make integration-test` passed.
- **Suspected cause**: `undefined` means `window.fhircastStatuses`, which `recordStatuses` sets, was missing by the end of the lease wait (about 2 min). That points to the page's document being replaced (a reload or navigation) during the wait, not to a status other than Connected.

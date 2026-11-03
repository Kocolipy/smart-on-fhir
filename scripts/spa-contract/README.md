---
type: Contract
title: SPA–API contract check
description: What make verify-contract holds the SPA to — the closed sets it decodes strictly and every request declaration's Permission or Superuser requirement — against backend/docs/openapi.yaml, and when it refuses to pass.
tags: [contract, openapi, spa, verification]
---

# SPA–API contract check

`make verify-contract`, from the repo root, runs `../verify-contract.sh`, which
runs this check. `make verify` ends with it. It belongs to neither app because it
reads both, which neither app may do (root `AGENTS.md`, "Paths").

The SPA mirrors part of the API's vocabulary by hand. This check holds that copy
to `backend/docs/openapi.yaml`. The backend's contract tests hold the document to
the running service, so the SPA is held to the backend by transitivity.

## What it holds

- **The closed sets the SPA decodes strictly**: the Permissions, the token
  Permissions, the audit operations and the lock causes, each against its enums in
  the document. `VOCABULARIES` in `check.mjs` is the one list of which set is held
  to which schema, and whether in both directions (`equal`) or only the document's
  values (`covers`). A new closed set joins it there.
- **Every exported request declaration under `frontend/src/`**: the Permission it
  names, every Permission for a declaration requiring several, or `superuser`,
  against its operation's `security`.

A value either side gains or loses fails the check, naming the mismatch.

## Running it

It needs `npm ci` in `frontend/` and the pinned Node. It runs its own tests
(`check.test.mjs`) first, then the check (`main.mjs` loads the SPA's modules and
the document, and `check.mjs` compares them). It refuses to pass when it finds no
declaration, or when an enum or export it expects is missing, so an empty or
renamed input cannot pass as agreement.

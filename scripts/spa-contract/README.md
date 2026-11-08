---
type: Contract
title: SPA–API contract check
description: What make verify-contract holds the SPA to — the closed sets it decodes strictly and every request declaration's Permission or Superuser requirement — against backend/docs/openapi.yaml, how it finds the declarations, and when it refuses to pass.
tags: [contract, openapi, spa, verification]
---

# SPA–API contract check

`make verify-contract`, from the repo root, runs `../verify-contract.sh`, which
runs this check. `make verify` ends with it. It belongs to neither app because it
reads both, which neither app may do (root `AGENTS.md`, "Paths").

The SPA mirrors part of the API's vocabulary by hand. This check holds that copy
to `backend/docs/openapi.yaml`. The backend's contract tests hold the document to
the running service, so the SPA is held to the backend by transitivity.

## The vocabulary chain

Each closed set the API exposes is defined once, as a Java enum, and passes
through the document to the SPA. Nothing is generated: each link is a copy, held
set-equal to the one before it. The document's enums are named under
`#/components/schemas/`.

| Closed set             | Java (backend)                               | `openapi.yaml` enum                                                                     | SPA                                     |
| ---------------------- | -------------------------------------------- | --------------------------------------------------------------------------------------- | --------------------------------------- |
| Session Permissions    | every `Permission` that is not `tokenOnly()` | `UserResponse/properties/permissions/items`                                             | `PERMISSIONS` (`src/auth/api.ts`)       |
| Delegatable Permission | `Permission.delegatable()`                   | `DelegatablePermission`                                                                 | covered by `PERMISSIONS`                |
| Token Permissions      | `Permission.tokenGrantable()`                | `ConnectorTokenPermissions/items`, `ConnectorTokenSummary/properties/permissions/items` | `TOKEN_PERMISSIONS` (`accounts-api.ts`) |
| Audit operations       | `AuditOperation`, by `name()`                | `AuditOperation`                                                                        | `AUDIT_OPERATIONS` (`audit-api.ts`)     |
| Lock causes            | `LockCause`, by `name()`                     | `AccountSummary/properties/lockCause` (its `null` is no member)                         | `LOCK_CAUSES` (`accounts-api.ts`)       |

- **Java ↔ document**: the backend's `VocabularyContractTests`
  (`backend/docs/api-contract-check.md`) compares each Java set with each enum
  beside it in both directions, so a member either side gains or loses fails the
  backend's baseline gate — even one no response yet carries. Every Permission
  lands in a compared set: a session's, a token's, or both.
- **Document ↔ SPA**: this check. `VOCABULARIES` in `check.mjs` is the one list
  of which SPA set is held to which enum, and whether in both directions
  (`equal`) or only to hold every documented value (`covers`, for a set the SPA
  also decodes another response with: `PERMISSIONS` against the delegatable
  enum). A new closed set joins both lists.

## The request declarations

Every exported request declaration under `frontend/src/` is held to the
operation it calls: the Permission it names, every Permission for a declaration
requiring several, or `superuser`, against that operation's `security`.

They are found from the source text. `main.mjs` reads the production modules
(`productionSources`: every `.ts`/`.tsx` file under `frontend/src/` that is no
`*.test`, `*.testHelpers` or `*.d` file), and `discover` in `check.mjs` reads
those: every module with an
`export const NAME: ReadDeclaration<…>`, `WriteDeclaration<…>` or
`PagedDeclaration<…>` — however its generic arguments wrap — is evaluated
beside the vocabulary modules, whatever else it exports. A name found that way
whose export does not evaluate to a request declaration (a `path` function and a
Permission, `superuser` or `{ allOf }` requirement) is reported, never skipped.

A value either side gains or loses fails the check, naming the mismatch.

## Running it

It needs `npm ci` in `frontend/` and the pinned Node. It runs its own tests
(`check.test.mjs`) first, then the check (`main.mjs` reads the sources, loads the
modules `discover` names with the frontend's Vite, parses the document, and
`check.mjs` compares them). It refuses to pass when it finds no declaration, or
when an enum or export it expects is missing, so an empty or renamed input
cannot pass as agreement.

---
type: Decision Record
title: "ADR 0015: Groups carry Permissions; the Superuser is one authority"
description: A User's Permissions are the baseline plus its direct Groups' stored Permissions, the Superuser is one authority no Group can carry, connector tokens have their own grantable set, and Unlock cannot escalate.
tags: [authorization, permissions, superuser, groups]
---

# 15. Groups carry Permissions; the Superuser is one authority

Date: 2026-10-20

## Status

Accepted.

## Context

A deployment must be able to give a helpdesk operator Unlock without
directory-write credentials, so administrative power has to be delegatable in
pieces. Delegation is real only if no delegated set adds up to the whole: a
holder that can mint a connector token able to add anyone to the Admin group, or
set the password of a User who holds more, is a Superuser while looking
delegated, and a rule that a token carries only what its minter holds cannot
close that once the minter holds the directory write Permissions itself.

Groups are created in the directory, over SCIM. Access that only configuration
can confer leaves a SCIM-created Group conferring nothing until a redeploy.

## Decision

### Permissions are a closed set, and Groups carry them

A User's Permissions are the **baseline** plus the union of the Permissions of the
Groups the User is a **direct** member of. A Group with no Permissions confers
nothing. There is no Role, no role mapping and no mapping hash.

A Group's Permissions are stored in the database (`group_permission`, cascaded
away with a deleted Group, validated at startup) and set only by a Superuser,
through `PUT /api/admin/groups/{id}/permissions`, never over SCIM. Otherwise
`group:write` would mean "grant anything to anyone". There is no
`permission:assign` or `role:assign` Permission. Only the `local` profile's
development fixtures seed rows.

### The vocabulary has two flags

The closed set of Permissions is in code (`Permission`), with two flags:

- **`delegatable()`** is what a Group may carry: `user:read`, `account:unlock`,
  `group:read`, `audit:read`, `ops:read` and `connector:read`. `account:unlock`
  is the helpdesk Permission: Unlock of a locked User, which cannot escalate
  (below). It is not token-grantable.
- **`tokenGrantable()`** is what a connector token may carry: `user:read`,
  `user:write`, `group:read` and `group:write`.

`user:read` and `group:read` carry both flags. The write halves are token-only:
no session holds them, a Superuser's included, and a Group stored carrying one
fails startup. `patient:read` is neither: it is the baseline Permission (below).

### `patient:read` is the baseline Permission

Every active User holds `patient:read` at sign-in beside `ROLE_USER`, whatever its
Groups, because reading a patient is the application's basic capability rather
than an administrative one. A confined session holds none. It stays a Permission,
and each operation it guards still declares it.

`ROLE_USER` is the baseline authority, meaning "active and no password change
required".

### The Superuser is one authority that can never be put on a Group

- A User is a Superuser when it is a **direct** member of the Admin group, the Group
  with the `admin-group` reserved marker.
- At sign-in, by password or through Epic, such a session gets `ROLE_SUPERUSER`
  plus every delegatable Permission, expanded in code. There is no Spring
  `RoleHierarchy` and no stored list.
- The Superuser is not a member of the `Permission` enum, so no endpoint can put it
  on a Group.
- `/api/auth/me` reports `superuser: true|false` beside `permissions[]`, which for a
  Superuser lists the full delegatable set. The SPA guards each page and action by
  Permission, or by the Superuser.

The Superuser is **not split** into `user:write` and `group:write` for people. No
deployment could configure it differently, and a split name could be misplaced on a
Group again.

### Superuser-only operations

**Rule (MUST).** Any endpoint that changes Group Permissions, Admin-group state, or
connector existence or credentials is Superuser-only: connector create and delete,
token issue, rotate and revoke, and setting a Group's Permissions; `backend/docs/openapi.yaml`
marks each. `connector:read` stays delegatable.

Each is declared like a Permission so the authorization contract covers it:
`@PreAuthorize("hasRole('SUPERUSER')")` on the handler, a backstop rule in the
chain, and `sessionCookie: [superuser]` in the API document, proved by
`AuthorizationContractTests`. There is no step-up for them.

### A Permission discloses its own projection, and no more

Each read Permission discloses the projection it names. `group:read` reads the
Groups projection — each Group's name, member count, Admin-group marker and
Permissions — and `user:read` the Users projection. Who a Group's members are is
Users-projection data, so the Group member read (`GET
/api/admin/groups/{id}/members`) requires **both**, and a holder of `group:read`
alone learns how many members a Group has and never who they are. An operation
that discloses what two Permissions guard requires both: declared on the handler
as `hasAuthority('group:read') and hasAuthority('user:read')`, repeated by the
chain, and named together in the one `sessionCookie` entry of its `security`.
`AuthorizationContractTests` proves each named Permission required and the set
sufficient. No other operation requires more than one.

### Method security is the authority, and the chain is the backstop

Every protected handler declares its Permission, or the Superuser, with method
security. The application chain is deny-by-default as a whole, with self-service
and public routes listed explicitly. The API document is the authorization
contract: every operation declares its `security`, and a contract test proves each
declaration against the running application.

**Writes on a patient pass the chain to be answered `405`.** After the
`patient:read` backstop, which matches `GET` only, a rule admits **any other
method** on `/api/patient/*` to a session holding `ROLE_USER`. No operation serves
such a method, so the dispatcher answers it `405` with FHIR's `not-supported`
outcome rather than the final rule's `403`, which would tell the caller it was
refused when nothing exists to refuse it. Nothing becomes reachable, and a confined
session is still refused `403`. The `/error` rule is the precedent. The carve-out is
safe only while the patient module has no write: **a future patient write must put
its own Permission rule ahead of this one**, and declare it with method security.

### Connector tokens: one vocabulary, enforced separately

A connector token carries its own list of token-grantable Permissions in place of
an access level. The session chain never routes `/scim/**`, and the SCIM chain
accepts bearer tokens only. A root `/.search` returns only the resource types the
token may read, and discovery needs only a valid token.

Only a Superuser mints tokens, and that gate is the whole escalation rule: there is
no rule limiting a token to what its minter holds, since the write Permissions
are token-only and no minter holds them. A token with a write Permission is Superuser-equivalent. A
read-only token is genuinely limited, which is why the token vocabulary stays
separate. A value outside `tokenGrantable()` is refused as an unknown one is.

### Unlock cannot escalate

There is deliberately no rule refusing a target that holds more than the caller,
so `account:unlock` is delegatable only because it can reach nothing beyond a
Lockout. It guards Unlock alone, and an Unlock acts only on a locked User (ADR
0007, "Every cause"): it restores access the User already had, and confines a
User with a password to replacing it. No administrative action sets the
change-required flag on a User that is not locked, so a helpdesk member cannot
force a change on a Superuser, or end its sessions, by any route.

**Accepted cost.** No one can require a password change of an unlocked User whose
password is suspected compromised. Recovery goes through the connector: the
directory sets a new password, which sets the change-required flag and ends the
User's sessions, or deactivates the User. An Epic-linked User was already handled
that way (ADR 0012, D20).

### Admin group protections

The Admin group cannot be renamed or deleted, and the Bootstrap Admin's membership
in it stays frozen. There is no "last enabled administrator" guard: the frozen
Bootstrap Admin and the Superuser guarantee an account able to administer the
deployment.

**A SCIM write aimed at a protected resource is `400` with `scimType:
mutability`, never `403`.** That covers modifying or deleting the Bootstrap
Admin, renaming or deleting the Admin group, and removing the Bootstrap Admin's
membership. The detail says which kind of resource was protected, but not which
resource or why. The SCIM refusal table (`ScimRefusals`, applied by
`ScimExceptionHandler`) maps `ProtectedResourceException` to it. `mutability` is
RFC 7644's error for an attempt to change something that cannot be changed. A
write gets `403` only as Bearer `insufficient_scope`, a token lacking the
Permission the write needs; the one other SCIM `403`, `unsupportedQuery`, no
write returns (RFC 7644 §3.4.2.2). So a `403` on a write always means the
credential, and a connector that gets one re-checks its token, while a `400
mutability` always means the target — and a connector treating `mutability` as
non-retryable is right, since the refusal never succeeds later.

### Session effects

Permissions are held on the session. Shrinking a Group's Permissions, deleting a
Group that has Permissions, or losing direct membership of a permission-bearing
Group or the Admin group ends the User's sessions after commit (ADR 0002). Growth
applies at the next sign-in.

### Dormancy

Dormancy strips every Group membership (ADR 0007).

### Refusals stay generic

A refusal is `403`. Its audit row and log line record the caller, the operation and
"insufficient permissions", never a Permission or the policy, as the logging
App-Standard requires.

## Explicitly rejected

- **One binary administrator role**, a direct Admin-group member holding every
  administrative power and everyone else `ROLE_USER`: nothing could be delegated.
- **Roles defined in configuration and conferred by mapped Groups**, with a
  `connector:token` Permission: delegation was not real, for the reason in
  Context, and the Roles were a second vocabulary beside the Permissions they
  were made of.
- **Coarse `READ_ONLY` / `READ_WRITE` connector tokens.**
- **A limited-reach "dominance" write rule**, under which a writer could change only
  Users or Groups holding no more than it does.
- **Runtime-editable Roles.**
- **In-app password reset.** If it is ever wanted, it needs its own ADR: only Users
  holding nothing beyond baseline, an app-generated password shown once, the flag
  set, sessions ended.
- **Group Permissions over SCIM.**
- **A `role:assign` Permission** separating membership of permission-bearing Groups
  from other Group writes: a directory sync has to manage every Group.

## App-Standards conscious deviations

| Standard requirement                                                                                                                                                                                                                                                                          | Deviation                                                                                                     | Why                                                                                                                                                                                                                                                                                                                                                                                                                              |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Standalone §2 "Roles must not be created, modified, or deleted through API endpoints"; §4 "Role provisioning is configuration-driven"; SSO "The role definition file is the sole source of truth…"; SSO §3.5 "Role management APIs must be read-only"; RBAC recipe "Immutable Security State" | A Superuser sets a Group's Permissions at runtime                                                             | A SCIM-created Group couldn't otherwise be given access without a redeploy. Escalation is closed: Superuser-only, and the Superuser already holds every delegatable Permission. **Review before a change goes live is lost.** It is compensated by after-the-fact review: before/after audit, the `group:read` view of every Group's Permissions, and ADR 0011's ac-4 deviation. The vocabulary and the URL matrix stay in code. |
| Standalone §3.5 "Users cannot assign themselves or others to roles through the application"                                                                                                                                                                                                   | A `group:write` token can add any User, its minter included, to a permission-bearing Group or the Admin group | Directory sync must manage membership, and only a Superuser mints tokens. A write token is Superuser-equivalent.                                                                                                                                                                                                                                                                                                                 |
| SSO §5 "Changes to the authority mapper configuration affect new logins, not existing sessions"                                                                                                                                                                                               | A shrink ends existing sessions                                                                               | Stricter: a lost privilege must not survive in a live session (ADR 0002).                                                                                                                                                                                                                                                                                                                                                        |
| Standalone Questions Q18 "Authorization is enforced by roles, not individual privileges"                                                                                                                                                                                                      | Guards check Permissions, plus the Superuser role                                                             | Follows the Shared RBAC recipe ("Code checks for fine-grained _Authorities_").                                                                                                                                                                                                                                                                                                                                                   |
| SSO §5 "Role definition changes affect generated authorization reports"                                                                                                                                                                                                                       | No report                                                                                                     | Covered by ADR 0011 ac-4. The Groups view is the manual substitute.                                                                                                                                                                                                                                                                                                                                                              |

## Consequences

- Whoever holds `group:write` on a permission-bearing Group, or on the Admin group,
  decides its membership. A write token can do it for its minter too. That is
  bounded by who may mint a token: only the Superuser.
- IM8 ac-2 (MFA for privileged actions) and ac-4 (access review) are not met, and
  ADR 0011 records why.
- Out of scope, deliberately: per-resource authorization (for example "may unlock
  Users in Group X only"), Group nesting, and any Permission a Group may carry that
  is not delegatable. A Permission applies to every resource of its kind.

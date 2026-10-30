# 15. Groups carry Permissions; a Superuser authority replaces Roles

Date: 2026-10-20

## Status

Accepted, and in force. It wholly replaces ADR 0010 (fine-grained Permissions
granted through a configured role mapping), whose file is removed; what survives
of it is stated below as part of this decision.

## Context

Authority was first binary: a direct member of the Admin group held `ROLE_ADMIN`
and every administrative power, and everyone else held `ROLE_USER`. A deployment
could not give a helpdesk operator Unlock without directory-write credentials,
and connector tokens had their own coarse `READ_ONLY` / `READ_WRITE` vocabulary.

ADR 0010 replaced that with a closed set of fine-grained Permissions and let a
deployment grant them through Roles, defined in configuration and conferred by
mapped Groups. Two things in it did not hold up.

- **Delegation was not real.** `connector:token` together with the directory
  Permissions let a holder mint a connector token that could add anyone to the
  Admin group, or set the password of a User who held more. So anyone holding them
  was effectively a Superuser while looking delegated. The no-escalation rule (a
  token may carry only what its minter holds) did not close it, because a holder of
  the directory write Permissions already held the power the token would carry.
- **Roles were configuration, so a Group made in the directory could not be given
  access.** A SCIM-created Group conferred nothing until a redeploy changed the
  mapping, and the mapping's Roles were a second vocabulary beside the Permissions
  they were made of.

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
connector existence or credentials is Superuser-only. Today that is connector create
and delete, token issue, rotate and revoke, and `PUT
/api/admin/groups/{id}/permissions`. `connector:read` stays delegatable.

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

### Unlock acts only on a locked User

`account:unlock` once also guarded a forced password change, which set another
User's change-required flag and ended every session it held. Its only target
refusals were the caller's own account and the Bootstrap Admin, and there is
deliberately no rule refusing a target that holds more than the caller, so a
helpdesk member could force a change on any Superuser but the Bootstrap Admin,
repeatedly, ending its sessions each time. Unlock had a milder version of the same
reach: on a User serving no lockout it still cleared the failure run.

- **Only an Unlock of a locked User requires a password change administratively.**
  No administrative action sets the change-required flag on a User that is not
  locked.
- **An Unlock of a User serving no lockout is refused and changes nothing**: `409`
  with the generic `request-refused` `ApiError`, the stored state and the failure
  run untouched, nothing revoked, audited and logged as a `LOCKOUT_LIFT` failure
  with reason `NOT_LOCKED`. The self-target refusal is checked first. The Bootstrap
  Admin, which can never be locked, is always refused `NOT_LOCKED` by anyone else.
  The login-state module decides whether a lock stood, on the state it reads under
  the User's lock, so an Unlock racing another lands as one success and one
  refusal (ADR 0007).
- **An Unlock of a locked User** lifts the lock, clears the run, resets the dormancy
  basis and, for a User with a password, sets the change-required flag (ADR 0011's
  ac-6).
- `account:unlock` therefore has an effect only on a locked User, and stays
  delegatable: unlocking restores access the User already had, and confines it to
  replacing its password.

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

- **A limited-reach "dominance" write rule**, under which a writer could change only
  Users or Groups holding no more than it does.
- **Runtime-editable Roles.** Roles are removed instead.
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
  ADR 0011 records why, beside dp-8: the SPA shows no classification labels.
- Out of scope, deliberately: per-resource authorization (for example "may unlock
  Users in Group X only"), Group nesting, and any Permission a Group may carry that
  is not delegatable. A Permission applies to every resource of its kind.
- There was no production instance, so the cut from Roles was clean: no mapping
  import and no session compatibility code.

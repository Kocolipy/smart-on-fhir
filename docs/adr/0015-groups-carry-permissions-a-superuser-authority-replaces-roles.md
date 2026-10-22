# 15. Groups carry Permissions; a Superuser authority replaces Roles

Date: 2026-10-20

## Status

Accepted. It wholly supersedes ADR 0010 (fine-grained Permissions, granted by
Group through a configured role mapping), restating what survives from it below.
The decision is written once, here, although it lands in steps: the Superuser
authority and the Superuser-only connector operations come first (#155), and the
rest follows in later tickets (#156 to #159) while Roles still exist. Until each
lands, ADR 0010's mechanism stands for that part. Refined by the 2026-10-22
addendum below: `account:unlock` is Unlock of a locked User only, and the forced
password change is removed.

## Context

ADR 0010 gave every operation one fine-grained Permission and let a deployment
grant them through Roles, defined in configuration and conferred by mapped Groups.
Two things in it did not hold up.

- **Delegation was not real.** `connector:token` together with the directory
  Permissions lets a holder mint a connector token that can add anyone to the Admin
  group, or set the password of a User who holds more. So anyone holding them was
  effectively a Superuser while looking delegated. The no-escalation rule (a token
  may carry only what its minter holds) did not close it, because a holder of the
  directory write Permissions already holds the power the token would carry.
- **Roles were configuration, so a Group made in the directory could not be given
  access.** A SCIM-created Group conferred nothing until a redeploy changed the
  mapping, and the mapping's Roles were a second vocabulary beside the Permissions
  they were made of.

## Decision

### Roles are removed

A User's Permissions are the **baseline** plus the union of the Permissions of the
Groups the User is a **direct** member of. A Group with no Permissions confers
nothing. There is no Role, no role mapping and no mapping hash.

### Group Permissions are data, set by the Superuser only

A Group's Permissions are stored in the database and set only by a Superuser,
through `PUT /api/admin/groups/{id}/permissions`, never over SCIM. Otherwise
`group:write` would mean "grant anything to anyone". There is no
`permission:assign` or `role:assign` Permission.

### The vocabulary has two flags

The closed set of Permissions stays in code, with two flags:

- **`delegatable()`** is what a Group may carry: `user:read`, `account:unlock`,
  `group:read`, `audit:read`, `ops:read` and `connector:read`. `account:unlock`
  is the helpdesk Permission: Unlock and the forced password change, neither of
  which can escalate. It is not token-grantable.
- **`tokenGrantable()`** is what a connector token may carry: `user:read`,
  `user:write`, `group:read` and `group:write`.

`user:read` and `group:read` carry both flags. The write halves are token-only:
no session holds them, a Superuser's included, and a Role or Group that would give
one to a session fails startup.
`patient:read` is neither: it stays the baseline Permission, which every active User
holds and a confined session holds none of. `connector:write` and `connector:token`
leave the vocabulary.

### The Superuser is one authority that can never be put on a Group

- A User is a Superuser when it is a **direct** member of the Admin group, the Group
  with the `admin-group` reserved marker.
- At sign-in, by password or through Epic, such a session gets `ROLE_SUPERUSER`
  plus every delegatable Permission, expanded in code. There is no Spring
  `RoleHierarchy` and no stored list.
- The Superuser is not a member of the `Permission` enum, so no endpoint can put it
  on a Group.
- `/api/auth/me` reports `superuser: true|false` beside `permissions[]`, which for a
  Superuser lists the full delegatable set.
- The Bootstrap Admin's frozen Admin-group membership stays. Together with the
  Superuser it is the recovery guarantee.

The Superuser is **not split** into `user:write` and `group:write` for people. No
deployment could configure it differently, and a split name could be misplaced on a
Group again.

### Superuser-only operations

**Rule (MUST).** Any endpoint that changes Group Permissions, Admin-group state, or
connector existence or credentials is Superuser-only. Today that is connector create
and delete, token issue, rotate and revoke, and `PUT
/api/admin/groups/{id}/permissions` (#159). `connector:read` stays delegatable.

Each is declared like a Permission so the authorization contract covers it:
`@PreAuthorize("hasRole('SUPERUSER')")` on the handler, a backstop rule in the
chain, and `sessionCookie: [superuser]` in the API document, proved by
`AuthorizationContractTests`.

### The token no-escalation rule is deleted

Only a Superuser mints tokens, so that gate replaces it. A token with a write
Permission is Superuser-equivalent. A read-only token is genuinely limited, which is
why the token vocabulary stays separate. A value outside `tokenGrantable()` is still
refused as an unknown one is.

### Session effects

Shrinking a Group's Permissions, or deleting a Group that has Permissions, ends its
members' sessions after commit (ADR 0002). Growth applies at the next sign-in. There
is no step-up for Superuser-only operations.

### Dormancy

Dormancy strips every Group membership.

### Clean cut

There is no production instance: no mapping import, and no session compatibility
code.

### What survives from ADR 0010

- **One vocabulary for both kinds of caller, enforced separately.** A connector
  token carries its own list of Permissions in place of an access level. The session
  chain never routes `/scim/**`, and the SCIM chain accepts bearer tokens only. A
  root `/.search` returns only the resource types the token may read, and discovery
  needs only a valid token.
- **Method security is the authority, and the chain is the backstop.** Every
  protected method declares its Permission, or the Superuser. The application chain
  is deny-by-default as a whole, with self-service and public routes listed
  explicitly. Unlock and force password change on the caller's own account are
  refused.
- **Admin group protections.** The Admin group cannot be renamed or deleted, and the
  Bootstrap Admin's membership in it stays frozen. The "last enabled administrator"
  guard stays dropped: the frozen Bootstrap Admin and the Superuser guarantee an
  account able to administer the deployment.
- **Refusals stay generic.** A refusal is `403`. Its audit row and log line record
  the caller, the operation and "insufficient permissions", never a Permission, a
  Role or the policy, as the logging App-Standard requires.
- **`ROLE_USER` and the baseline Permission.** `ROLE_ADMIN` stays removed.
  `ROLE_USER` stays as the baseline authority meaning "active and no password change
  required". A basic capability is a baseline Permission: every active User holds it
  at sign-in beside `ROLE_USER`, whatever its Groups, and a confined session holds
  none.
- `/api/auth/me` returns `permissions[]`, and the SPA guards each page and action by
  Permission, or by the Superuser.

#### Addendum carried over (2026-10-18): `patient:read` is a baseline Permission

`patient:read` is the baseline Permission: every active User holds it at sign-in,
whatever its Groups, because reading a patient is the application's basic
capability rather than an administrative one. A confined session does not hold it.

#### Addendum carried over (2026-10-18): writes on a patient pass the chain to be answered `405`

The application chain stays deny-by-default, with one carve-out beside the
self-service and public routes: after the `patient:read` backstop, which matches
`GET` only, a rule admits **any other method** on `/api/patient/*` to a session
holding baseline access (`ROLE_USER`). No operation serves such a method, so the
dispatcher answers it `405` with FHIR's `not-supported` outcome — the resource takes
no write — rather than the final rule's `403`, which would tell the caller it was
refused when in fact nothing exists to refuse it. Nothing becomes reachable: there is
no handler behind the rule, and a confined session is still refused `403`. The
`/error` rule is the precedent for admitting a baseline session to a path that serves
no operation of its own.

The carve-out is safe only while the patient module has no write. **A future patient
write must put its own Permission rule ahead of this one**, and declare it with
method security.

## Explicitly rejected

- **A limited-reach "dominance" write rule**, under which a writer could change only
  Users or Groups holding no more than it does.
- **Runtime-editable Roles.** Roles are removed instead.
- **In-app password reset.** If it is ever wanted, it needs its own ADR: only Users
  holding nothing beyond baseline, an app-generated password shown once, the flag
  set, sessions ended.
- **Group Permissions over SCIM.**

## App-Standards conscious deviations

| Standard requirement                                                                                                                                                                                                                                                                          | Deviation                                                                                                     | Why                                                                                                                                                                                                                                                                                                                                                                                                                              |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Standalone §2 "Roles must not be created, modified, or deleted through API endpoints"; §4 "Role provisioning is configuration-driven"; SSO "The role definition file is the sole source of truth…"; SSO §3.5 "Role management APIs must be read-only"; RBAC recipe "Immutable Security State" | A Superuser sets a Group's Permissions at runtime                                                             | A SCIM-created Group couldn't otherwise be given access without a redeploy. Escalation is closed: Superuser-only, and the Superuser already holds every delegatable Permission. **Review before a change goes live is lost.** It is compensated by after-the-fact review: before/after audit, the `group:read` view of every Group's Permissions, and ADR 0011's ac-4 deviation. The vocabulary and the URL matrix stay in code. |
| Logging §3.4 "Ensure audit trails use integrity controls that prevent tampering or deletion"                                                                                                                                                                                                  | Migration V5 rewrites the old `ROLE_GRANT`, `ROLE_REVOKE` and `DORMANCY_ROLE_REVOCATION` rows                 | One-off. No production instance, so no real trail is altered. The append-only trigger is disabled for the three rewrite statements alone, inside the migration's one transaction.                                                                                                                                                                                                                                                |
| Standalone §3.5 "Users cannot assign themselves or others to roles through the application"                                                                                                                                                                                                   | A `group:write` token can add any User, its minter included, to a permission-bearing Group or the Admin group | Directory sync must manage membership, and only a Superuser mints tokens. A write token is Superuser-equivalent.                                                                                                                                                                                                                                                                                                                 |
| SSO §5 "Changes to the authority mapper configuration affect new logins, not existing sessions"                                                                                                                                                                                               | A shrink ends existing sessions                                                                               | Stricter: a lost privilege must not survive in a live session (ADR 0002).                                                                                                                                                                                                                                                                                                                                                        |
| Standalone Questions Q18 "Authorization is enforced by roles, not individual privileges"                                                                                                                                                                                                      | Guards check Permissions, plus the Superuser role                                                             | Follows the Shared RBAC recipe ("Code checks for fine-grained _Authorities_").                                                                                                                                                                                                                                                                                                                                                   |
| SSO §5 "Role definition changes affect generated authorization reports"                                                                                                                                                                                                                       | No report                                                                                                     | Covered by ADR 0011 ac-4. The Groups view is the manual substitute.                                                                                                                                                                                                                                                                                                                                                              |

## Consequences

- Whoever holds `group:write` on a permission-bearing Group, or on the Admin group,
  decides its membership. A write token can do it for its minter too. This is
  documented rather than split into a `role:assign` Permission, because a directory
  sync has to manage every Group, and it is bounded by who may mint a token: only the
  Superuser.
- The API document remains the authorization contract: every operation declares its
  Permission, or the Superuser, and a contract test proves each declaration against
  the running application.
- IM8 ac-2 (MFA for privileged actions) and ac-4 (access review) are still not met,
  and ADR 0011 records why. A new open entry, dp-8, records that the SPA shows no
  classification labels.
- Out of scope, deliberately: per-resource authorization, Group nesting, and any
  Permission a Group may carry that is not delegatable.
- **Landing order.** This ticket (#155) adds the Superuser and moves connector
  administration behind it while Roles still exist: the Superuser's session is
  additive to whatever the role mapping confers. `account:unlock` then replaced
  `user:write` as the guard on Unlock and the forced password change (#157), and
  `user:write` became token-only. Dormancy and the audit Permissions (#156) came
  next, then Groups carrying Permissions from the database with Roles removed
  (#158): migration V6's `group_permission` table, seeded only by the `local`
  profile's development fixtures, validated at startup, and cascaded away with a
  deleted Group. `PUT /api/admin/groups/{id}/permissions` (#159) then landed: the
  Superuser sets a Group's Permissions from the Groups table, a shrink ends the
  members' sessions after commit, and each change is audited as
  `GROUP_PERMISSIONS_CHANGE`. With it every part of this decision is in force.

## Addendum (2026-10-22): `account:unlock` is Unlock of a locked User only

_Contradicts this ADR's "`account:unlock` is the helpdesk Permission: Unlock and
the forced password change, neither of which can escalate", but worth reopening
because the forced change, though it cannot escalate, let a lower-privileged User
deny a higher-privileged one._

### Context

The forced password change set another User's change-required flag and ended
every session it held. Its only target refusals were the caller's own account
and the Bootstrap Admin, and there is deliberately no rule refusing a target that
holds more than the caller. So a holder of `account:unlock` — a helpdesk Group's
member, nothing near a Superuser — could force a change on any Superuser but the
Bootstrap Admin, and repeat it each time the Superuser recovered, ending its
sessions every time. Unlock had a milder version of the same reach: on a User
serving no lockout it still cleared the failure run, so the Permission had an
effect on a User that was not locked at all.

### Decision

- **The forced password change is removed.** `POST
  /api/admin/accounts/{id}/force-password-change`, its use case, its login-state
  transition and the SPA's control are gone. The path is now an undeclared route,
  refused by deny by default like the counter endpoints before it. No migration
  is needed: no table, column or constraint was the forced change's alone.
- **An Unlock of a User serving no lockout is refused and changes nothing.** It
  answers `409` with the generic `request-refused` `ApiError`; the stored state
  is untouched, its failure run included; nothing is revoked; and the refusal is
  audited and logged as the self-target refusal is — a `LOCKOUT_LIFT` failure
  with the new reason `NOT_LOCKED`. The self-target refusal is checked first, so
  an Admin's own row is refused for being its own whatever its state. The
  Bootstrap Admin, which can never be locked, is always refused `NOT_LOCKED` by
  anyone else. Whether a lock stood is decided by the login-state module on the
  state it reads under the User's lock, so an Unlock racing another lands as one
  success and one refusal.
- **An Unlock of a locked User is unchanged**: it lifts the lock, clears the run,
  resets the dormancy basis and, for a User with a password, sets the
  change-required flag (ADR 0011's ac-6).
- `account:unlock` therefore has an effect only on a locked User, and stays
  delegatable: unlocking a locked User restores access the User already had, and
  confines it to replacing its password.
- **History keeps its vocabulary.** The audit listing still reads every row
  stored before: the `PASSWORD_CHANGE_REQUIRE` operation (Unlock still records
  it), the `CREDENTIALLESS_TARGET` refusal reason and the
  `FORCED_PASSWORD_CHANGE` session-revocation cause are kept for those rows, the
  last two documented as no longer recorded. The runtime-only vocabulary — the
  log operation, the login surface's revocation cause, the exception for a
  credentialless target — is removed.

### Accepted cost

No one can require a password change of an unlocked User whose password is
suspected compromised. Recovery goes through the connector instead: the directory
sets a new password, which also sets the change-required flag and ends the User's
sessions, or deactivates the User, which ends them and refuses every Login until
it is reactivated. An Epic-linked User was already handled that way (ADR 0012,
D20).

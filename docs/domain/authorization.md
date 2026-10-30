---
type: Domain Rules
title: Authorization
description: Permissions, the Superuser and Group Permissions — baseline access, deny by default, authorization refusals, the Admin group, membership of power, and the development fixtures.
tags: [authorization, permissions, groups, superuser]
---

# Authorization

**Permission** — one fine-grained power from a closed set defined in code
(`user:read`, `user:write`, `account:unlock`, `group:read`, `group:write`,
`audit:read`, `connector:read`, `ops:read`, `patient:read`); a new one is a code
change, because it means something only once a protected action declares it.
What a Group confers is its **Group Permissions**, below — data, not
configuration (ADR 0015).

**Delegatable** and **token-grantable** — the two flags the vocabulary carries
(ADR 0015). A delegatable Permission is what a Group may carry: `user:read`,
`account:unlock`, `group:read`, `audit:read`, `ops:read` and `connector:read`. A token-grantable
one is what a connector token may carry: `user:read`, `user:write`, `group:read`
and `group:write` ([connectors](./connectors.md)). `user:read` and `group:read`
carry both; the write halves are token-only — no session holds them, a
Superuser's included, and a Group stored carrying one fails startup; `patient:read` is
neither — it is the baseline Permission below. `account:unlock` is delegatable
only: it is the helpdesk Permission, and over SCIM there is no Unlock. The two vocabularies are enforced separately: a
session never reaches the SCIM chain and a token never reaches the application
chain.

**Superuser** — the one session authority that is not a Permission, so it can
never be put on a Group. A User is a Superuser when it is a _direct_ member of
the [Admin group](./directory.md), by password Login or Epic Login alike. At
sign-in such a session gets `ROLE_SUPERUSER` plus every delegatable Permission,
expanded in code — no Spring `RoleHierarchy`, no stored list — added to
whatever its other Groups carry. `GET /api/auth/me` reports it as `superuser`, beside
`permissions`, which for a Superuser include the full delegatable set. Holding
every Permission does not make a session the Superuser. It is not split into
`user:write` and `group:write` for people, so no deployment configures it
differently.

**Conferred power** — what a User's direct memberships confer, as one answer:
its effective Permissions, the Superuser's expansion included and the baseline
not, and whether it is the Superuser. Both halves are read together, from the
Group Permissions and the Admin group's reservation marker, by sign-in, the
Accounts list's Superuser flag and the audit of every Permission change, so no
two of them can disagree about who is the Superuser.

**Baseline access** — every active User with a session holds self-service:
`GET /api/auth/me`, its own password change, logout and `/api/self` — and nothing
else without a Permission, in the browser or over the API. It is `ROLE_USER`,
granted to every session not [confined](./passwords.md) by the change-required
flag, whatever its Groups carry, so no Group can take self-service away.

**Admin** — a User holding at least one administrative Permission through its
Groups. Apart from the Superuser there is no administrative authority of its own:
what an Admin may do is exactly its Permissions — a helpdesk operator holding
`user:read` and `account:unlock` sees Users and unlocks a locked one, and nothing
more. Its reach is every locked User but itself, a Superuser included; there is no
rule refusing a target that holds more than the caller, because an Unlock has no
effect on a User that is not locked — a lower-privileged User cannot use it to
disturb a higher-privileged one that is working (ADR 0015).

## Deny by default

Every operation of the application chain declares what it needs, and whatever
declares nothing is refused. Four kinds of operation:

- **Public** — `POST /api/auth/login`, `GET /api/auth/csrf`,
  `/actuator/health`, and, while Epic Login is on, its JWKS and three browser
  routes ([Epic Login](./epic-login.md)), reachable with no session.
- **Self-service** — authenticated, no Permission: the four baseline routes
  above. They act only on the session's own User and take no User id from the
  request, so there is no caller-supplied id to check ownership against.
- **Permissions** — every other operation requires one or more Permissions,
  exactly one but for the Group member read, which requires two. Each is
  declared on its handler with method security and repeated by the chain as a
  backstop:
  `user:read` lists Users; `account:unlock` unlocks a locked User;
  `group:read` lists Groups with their Permissions and lists the Permissions a
  Group may carry; `group:read` and `user:read` together read a Group's direct
  members, since who they are is Users-projection data (ADR 0015); `audit:read`
  lists audit events; `connector:read` lists connectors; `ops:read` reaches every
  actuator endpoint but health, on whichever port actuator is served.
  `patient:read` reads a Patient (`GET /api/patient/{id}`, [patient](patient.md)).
- **Superuser only** — connector creation and deletion, issuing, rotating and
  revoking a token, and setting a Group's Permissions
  (`PUT /api/admin/groups/{id}/permissions`), declared as `hasRole('SUPERUSER')`
  on the handler, repeated by
  the chain as a backstop, and as `sessionCookie: [superuser]` in the API
  document. **Rule:** any endpoint that changes Group Permissions, Admin-group
  state, or connector existence or credentials is Superuser-only. Minting a token
  is the gate that matters: a token carrying a write Permission is
  Superuser-equivalent ([connectors](./connectors.md)).

Beside the operations the chain admits two things that are not operations: any
`GET` outside the reserved server paths, which is the SPA index or a file
request, and the `/error` page a refusal is rendered through, at baseline access.

An undeclared route under `/api/`, a method a declared route does not serve, and
a `/scim` path outside `/scim/v2` are refused — `403` for a session. A session on
`/scim/v2/**` never reaches this chain: the SCIM chain answers it, reads no
session, and returns the bare `Bearer` `401` of a request with no credential. The
API document declares each operation's requirement in its `security` field, and a
contract test proves every declaration against the running application.

**Authorization refusal** — the `403` a signed-in caller gets when the operation
does not admit it: a Permission it lacks, the Superuser it is not, a route
nothing declares, or a confined session. Generic on purpose: the response carries the generic `request-refused`
`ApiError` (an `OperationOutcome` under `/api/patient`), and the refusal is audited
(`ACCESS_DENIED`) and logged at `WARN` with the caller, the operation (method and
route template) and one reason (`INSUFFICIENT_PERMISSIONS` /
`insufficient-permissions`), never naming the Permission, a Group or the rule. The
missing Permission, or the Superuser, is recoverable from the operation's
declaration in the API document, which names every Permission it requires. A
missing or stale CSRF token is a `403` too, but it is not an authorization
decision and is neither audited nor reasoned as one.

## Group Permissions

What a Group confers is the set of delegatable Permissions stored for it in the
database (`group_permission`), so a Group created in the directory can be given
access without a configuration change or a redeploy (ADR 0015). The rules:

- **Union, direct only.** A User's Permissions are the baseline plus the union of
  the Group Permissions of the Groups it is a _direct_ member of — a Group's
  members are Users, never Groups, so nesting cannot confer anything — and
  belonging to an extra Group never takes a power away.
- **A Group with none confers nothing.** A Group no row names carries nothing,
  and its membership is no change of anyone's Permissions.
- **Baseline Permissions.** `patient:read` is held by every active User
  whatever its Groups, granted at Login beside `ROLE_USER` rather than through a
  Group. Reading a patient is a basic capability, and a Group every User must
  join would be a second place for the fact that it is a User. No Group carries
  it, and a confined session does not hold it.
- **Taken at Login.** Permissions are resolved once, at Login, and the session
  carries them. A confined session holds none. Nothing about the Group
  Permissions is recorded on the session.
- **Losing Permissions ends the sessions they were issued to; gaining waits for
  Login.** A SCIM write that removes a User from a Group that carries
  Permissions — a `PATCH` `remove` or `replace` of the members, a `PUT` whose
  member list leaves it out — or deletes such a Group ends every session each
  former member holds once the write commits (ADR 0002's ordering), so a removed
  power stops at the User's next request. Deleting a Group is a shrink to empty.
  The rule is decided on the stored membership before and after, so a User
  removed and re-added in one `PATCH` keeps its sessions. Adding a User to a Group
  leaves its live sessions alone: its Permissions apply from its next Login. The
  same holds when the Superuser changes the Group itself: a new set lacking a
  Permission the Group carried ends every direct member's sessions once the change
  commits — each revocation recorded with the reason `GROUP_PERMISSIONS_REDUCED`
  and the Superuser as actor — and a change that rolls back ends none, while a set
  that only grows reaches the members at their next Login. There is no step-up
  re-authentication for it, as for no Superuser-only operation.
- **Deletion is safe.** A Group's rows go with it when it is deleted over SCIM
  (the foreign key cascades), so nothing names a deleted Group and the next
  startup has nothing to refuse.
- **Validated at startup, fail-fast.** Startup reads every stored row and
  refuses — naming each Group and value in one message — a name the vocabulary
  does not have and one no Group may carry (anything not delegatable). Removing a
  Permission from code therefore needs a migration that deletes its rows.
- **Set by the Superuser alone, never over SCIM.** Otherwise `group:write` would
  mean "grant anything to anyone". `PUT /api/admin/groups/{id}/permissions`
  replaces a Group's set with the complete set the body names — an array of
  delegatable Permission names, an empty one clearing it, a name given twice
  carried once — with no precondition: the last writer wins. It refuses, before
  anything is written, a body that is missing, not an array of names, or naming
  anything that is not delegatable (`400`), a Group that does not exist (`404`),
  and the Admin group (`409`), whose members' power is the Superuser rather than a
  Permission; a caller who is not the Superuser gets the generic `403`. The SPA's
  Groups table offers it to the Superuser alone
  ([administration](./administration.md)). Besides it, only the `local` profile's
  development fixtures write a Group's Permissions, at startup.

**Admin group** — the Group with the `admin-group` reservation marker, and no
other: it is recognised by the marker, never by its id or name. A direct member
of it is the Superuser. It carries no Group Permissions: its members' power is
the Superuser's, expanded in code. Seeding creates it on a fresh database under
the fixed id `00000000-0000-4000-8000-00000000a001`; an Admin group seeded
earlier under another id keeps that id and is no less the Admin group. Startup
seeds it again whenever it is missing. Over SCIM it cannot be renamed or deleted, and the
Bootstrap Admin's membership of it cannot change; its other members can all be
removed. That frozen membership, together with the Superuser's expansion, is
what guarantees a User holding every delegatable Permission (see
[Recovery guard](./authentication.md)).

**Membership of power** — holding a Group's Permissions is being a direct member
of it, and nothing else confers them. So **`group:write` on a Group that carries
Permissions, or on the Admin group, decides who holds them**: whoever holds it —
over SCIM, a connector token carrying it — decides who holds those Permissions,
the Superuser's included. It is not split into a separate `role:assign`
Permission, because an identity provider sync has to manage every Group. That is
bounded by who may mint a token: only the Superuser, and a write token is
Superuser-equivalent. Every membership change of a Group that confers
Permissions, or of the Admin group — the Group's or the User's deletion
included — is audited as a change of power: a `PERMISSION_GRANT` or
`PERMISSION_REVOKE` event naming the connector, the User and the Group, and
recording the User's effective Permissions before and after — what its direct
memberships confer, the Superuser's expansion included, the baseline not — with
a Superuser marker set when the change grants or removes the Superuser. A
Group's deletion records what the Group carried before it went; a User's
deletion records one event per such Group it was in, each with everything the
User held before it. Beside it is an `INFO` log record classified
`user-administration` / `change` / `success` with `user.target.id`, `group.id`
and `app.authorization.group_permissions`, and a grant of the Superuser is
counted on `authorization.changes{operation="superuser-grant"}`. A membership
change of a Group that confers nothing is none of this. The Superuser setting a
Group's Permissions is audited as `GROUP_PERMISSIONS_CHANGE`, with the Group's own
Permissions before and after, and counted on
`authorization.changes{operation="group-permissions-change"}`
([administration](./administration.md)).

**Groups projection and Permissions listing** — `GET /api/admin/groups`, by a
holder of `group:read`, lists the Groups a page at a time, each with its Group
Permissions sorted by name (none for the Admin group); the SPA's Groups table shows them, and the Admin
group as "Superuser". `GET /api/admin/permissions`, by the same holder, lists
every delegatable Permission: what any Group could carry, and what the
Superuser's editor offers. Both are reads. `GET /api/admin/groups/{id}/members`
reads a Group's direct members a page at a time, and only a session holding
`user:read` beside `group:read` may: `group:read` alone discloses how many members
a Group has, never who ([administration](./administration.md)).

**Development fixtures** — the shipped `authorization.yaml` seeds, with
`APP_DEV_FIXTURES_ENABLED=true`, one Group per development access level, each
with one User in it: Auditors and Helpdesk. The Superuser's User is the
Bootstrap Admin. Their Users get `APP_DEV_FIXTURES_PASSWORD`, never
overwriting one that exists, together with two Users in no Group: `guest`,
holding only baseline access, and `dormant`, put past the dormancy lockout window
at every startup. A third, optional User in no Group, the `epic-clinician`
(`APP_DEV_FIXTURES_EPIC_CLINICIAN`), is the local exception to "a clinician is a
User because SCIM provisioned it" ([Epic Login](epic-login.md)): its `userName` is
the local Epic stand-in's Practitioner id, it holds baseline access only and is
no administrator, and it is seeded only when the variable is set. Like every
fixture it is off unless the fixtures are enabled and refused outside the `local`
profile. What each Group confers is a separate block active under the
`local` profile alone, applied at startup only to a Group that carries nothing
yet; no other profile seeds a Group Permission. The Auditors hold every read —
`user:read`, `group:read`, `ops:read`, `connector:read` and `audit:read` — and
change nothing. The Helpdesk holds the same reads plus `account:unlock`: it also
unlocks a locked User. The same flag has the patient module seed the
dummy Patients ([patient](patient.md)) by a seeder of its own. See
`backend/docs/configuration/group-permissions.md`.

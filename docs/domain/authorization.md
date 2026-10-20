---
type: Domain Rules
title: Authorization
description: Permissions, Roles and the role mapping — baseline access, deny by default, authorization refusals, the Superuser Group, Role assignment, and the development fixtures.
tags: [authorization, permissions, roles]
---

# Authorization

**Permission** and **Role** — a Permission is one fine-grained power from a
closed set defined in code (`user:read`, `user:write`, `group:read`,
`group:write`, `audit:read`, `connector:read`, `connector:write`,
`connector:token`, `ops:read`, `patient:read`); a new one is a
code change, because it means something only once a protected action declares
it. A Role is a named set of them, defined in deployment configuration. Neither
is stored in the database, and nothing at runtime creates or changes one.

**Baseline access** — every active User with a session holds self-service:
`GET /api/auth/me`, its own password change, logout and `/api/self` — and nothing
else without a Permission, in the browser or over the API. It is `ROLE_USER`,
granted to every session not [confined](./passwords.md) by the change-required
flag, whatever Roles it holds or lacks, so a misconfigured mapping can never take
self-service away.

**Admin** — a User holding at least one administrative Permission through its
Roles. There is no administrative authority of its own: what an Admin may do is
exactly its Permissions — a helpdesk operator holding `user:read` and
`user:write` sees and unlocks Users, and nothing more.

## Deny by default

Every operation of the application chain declares what it needs, and whatever
declares nothing is refused. Three kinds of operation:

- **Public** — `POST /api/auth/login`, `GET /api/auth/csrf`,
  `/actuator/health`, and, while Epic Login is on, its JWKS and three browser
  routes ([Epic Login](./epic-login.md)), reachable with no session.
- **Self-service** — authenticated, no Permission: the four baseline routes
  above. They act only on the session's own User and take no User id from the
  request, so there is no caller-supplied id to check ownership against.
- **One Permission** — every other operation requires exactly one, declared on
  its handler with method security and repeated by the chain as a backstop:
  `user:read` lists Users; `user:write` unlocks and forces a password change;
  `group:read` lists Groups and reads the Roles and the role mapping; `audit:read`
  lists audit events; `connector:read` lists connectors, `connector:write` creates
  and deletes them, `connector:token` issues, rotates and revokes their tokens;
  `ops:read` reaches every actuator endpoint but health, on whichever port
  actuator is served.
  `patient:read` reads a Patient (`GET /api/patient/{id}`, [patient](patient.md)).

Beside the operations the chain admits two things that are not operations: any
`GET` outside the reserved server paths, which is the SPA shell or a file
request, and the `/error` page a refusal is rendered through, at baseline access.

An undeclared route under `/api/`, a method a declared route does not serve, and
a `/scim` path outside `/scim/v2` are refused — `403` for a session. A session on
`/scim/v2/**` never reaches this chain: the SCIM chain answers it, reads no
session, and returns the bare `Bearer` `401` of a request with no credential. The
API document declares each operation's requirement in its `security` field, and a
contract test proves every declaration against the running application.

**Authorization refusal** — the `403` a signed-in caller gets when the operation
does not admit it: a Permission it lacks, a route nothing declares, or a confined
session. Generic on purpose: the response carries the generic `request-refused`
`ApiError` (an `OperationOutcome` under `/api/patient`), and the refusal is audited
(`ACCESS_DENIED`) and logged at `WARN` with the caller, the operation (method and
route template) and one reason (`INSUFFICIENT_PERMISSIONS` /
`insufficient-permissions`), never naming the Permission, a Role or the rule. The
missing Permission is recoverable from the operation's declaration in the API
document, since each requires exactly one. A missing or stale CSRF token is a
`403` too, but it is not an authorization decision and is neither audited nor
reasoned as one.

## Role mapping

The read-only `app.authorization` configuration block that defines the Roles and
maps each Role to a Group by the Group's stable id; see `backend/docs/configuration/role-mapping.md`
for its shape. The rules:

- **Union.** A User's Permissions are the union of the Roles of the mapped
  Groups it is a _direct_ member of — nesting is not modelled — so holding an
  extra Role never takes a power away. A User in no mapped Group holds only the
  baseline Permissions and keeps baseline access.
- **Baseline Permissions.** `patient:read` is held by every active User
  whatever its Groups, granted at Login beside `ROLE_USER` rather than through a
  Role. Reading a patient is a basic capability, and a Group every User must join
  would be a second place for the fact that it is a User. A confined session does
  not hold it.
- **Taken at Login.** Permissions are resolved once, at Login, and the session
  carries them, together with the hash of the mapping they were resolved under. A
  confined session holds none.
- **Losing a Role ends the sessions it was issued to; gaining one waits for
  Login.** A SCIM write that removes a User from a mapped Group — a `PATCH`
  `remove` or `replace` of the members, a `PUT` whose member list leaves it out,
  or the Group's `DELETE` — ends every session the User holds once the write
  commits (ADR 0002's ordering), so a removed power stops at the User's next
  request. The rule is decided on the stored membership before and after, so a
  User removed and re-added in one `PATCH` keeps its sessions. Adding a User to a
  mapped Group leaves its live sessions alone: the Role applies from its next
  Login.
- **A changed mapping reaches live sessions at startup.** A deploy that
  changes the mapping changes its hash, and startup ends every authenticated
  session issued under a different hash (or carrying none), whether or not the
  session store survived the deploy. A plain restart, which keeps the hash,
  ends nothing. Startup logs the validated mapping's hash, and the number of
  sessions it ended, as `application-startup` records.
- **Validated at startup, fail-fast.** Startup refuses — naming every problem
  in one message — a Permission name outside the closed set, a Role defined
  twice or without a name, a mapping entry with no Group id, a Group id mapped
  more than once, an entry naming an undefined Role, anything but exactly one
  Superuser Group, a Superuser Group whose Role lacks any Permission, and,
  once the directory is seeded, a mapped Group id that does not resolve to a
  Group or a Superuser Group that is not the reserved Admin group.
- **The hash** is SHA-256 over a canonical rendering of the validated mapping,
  so reordering configuration lists does not change it and any change of meaning
  does.

**Superuser Group** — the one mapped Group whose Role holds every Permission. It
is the [Admin group](./directory.md): seeding creates the Admin group under the
Superuser Group's configured stable id, so a deployment's mapping can name it
before it exists. An Admin group seeded earlier under another id keeps that id,
and startup then refuses the mapping rather than rewriting the id. Over SCIM it
cannot be renamed or deleted, and the Bootstrap Admin's membership of it cannot
change; its other members can all be removed. That frozen membership, together
with startup's refusal of a Superuser Role missing any Permission, is what
guarantees a User holding every Permission (see
[Recovery guard](./authentication.md)).

**Role assignment** — holding a Role is being a direct member of a mapped
Group, and nothing else assigns one. So **`group:write` on a mapped Group is
Role assignment**: whoever holds it — over SCIM, a connector token carrying it —
decides who holds that Role, the Superuser Role's included. It is not split into
a separate `role:assign` Permission, because an identity provider sync has to
manage every Group. Every membership change of a mapped Group is audited as a
change of power: a `ROLE_GRANT` or `ROLE_REVOKE` event naming the connector, the
User, the Group and the Role, beside an `INFO` log record classified
`user-administration` / `change` / `success` with `user.target.id`, `group.id` and
`app.authorization.role`. A membership change of an unmapped Group is none of
this.

**Roles listing** — `GET /api/admin/roles`, by a holder of `group:read`: every
Role the mapping defines, sorted by name, with its Permissions and the Groups
conferring it, each by stable id and current `displayName` (`null` for a mapped
Group since deleted), and the Superuser Group marked. Read-only like the mapping
itself: no endpoint creates, changes or deletes a Role or a mapping entry. The
SPA has no view of it.

**Development fixtures** — the shipped mapping (`authorization.yaml`) is a
development default: Superuser, Account admin, Auditor, Connector admin and
Monitoring, each Role but Superuser conferred by a Group with one User in it.
With `APP_DEV_FIXTURES_ENABLED=true` startup seeds those Groups under the
mapping's ids and their Users with `APP_DEV_FIXTURES_PASSWORD`, never
overwriting one that exists, together with two Users in no Group: `user`, holding
only baseline access, and `dormant`, put past the dormancy lockout window at
every startup. The same flag has the patient module seed the dummy Patients
([patient](patient.md)) by a seeder of its own. Without the fixtures those Groups do not exist, so a
deployment that did not replace the development mapping fails startup instead of
running with it.

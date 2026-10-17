---
type: Domain Rules
title: SCIM directory
description: The SCIM target model — Users, Groups, the Bootstrap Admin and Admin group, edge whitespace, deletion and tombstones, and when a resource version advances.
tags: [scim, directory, users, groups]
---

# SCIM directory

**SCIM service provider** — the role this application plays for identity
provisioning: an external identity provider calls the application's SCIM v2
interface to create and manage Users and Groups. SCIM provisioning performs no
end-user Login; password authentication and its session are a separate
application capability.

**SCIM directory** — the single User-and-Group namespace owned by one application
deployment, not partitioned by tenant. Multiple independently authenticated
[connectors](./connectors.md) holding write Permissions may operate on the same
directory; resource versions and conditional writes are their shared concurrency
seam.

**Connector external identifier** — one connector's `externalId` alias for a User
or Group. A resource has one stable, directory-wide SCIM `id` but may have a
different `externalId` for each connector — RFC 7643 §3.1 makes `externalId` the
provisioning client's, scoped to its provisioning domain, and a connector is that
domain here. Reads and filters expose only the calling connector's alias, so
independent client namespaces cannot collide. When a connector is deleted, all of
its aliases are deleted too and its namespace may be reused by a future connector.

**User** — the one domain identity: Login authenticates against `scim_users`, and
authority is the Permissions the role mapping confers through direct Group
membership. It owns the selected core User profile, stable SCIM id and version,
active state, encoded password, creation metadata, and recent login history. Its
profile round-trips `userName`, the calling connector's `externalId`, `active`,
`name`, `displayName`, `emails`, locale and time-zone attributes, Groups, and SCIM
metadata. The Enterprise User extension and application-specific extensions are
not supported. SCIM may set the password as a write-only provisioning attribute;
the application hashes it immediately and never returns it. SCIM may rename
`userName` under its uniqueness rule while preserving the stable SCIM resource id.
That rule, and Login, compare the normalized form: Unicode NFKC and case folding,
the mapping half of PRECIS UsernameCaseMapped (RFC 8265), which RFC 7644 §5
requires. A password or username change, deactivation, deletion, or removal from a
mapped Group revokes the User's existing sessions (see
[Session revocation](./sessions.md)). Failure runs and lockouts are
application-owned authentication state on the User.

**Edge whitespace** — the keys, every User and Group attribute that is unique or
looked up (`userName`, `displayName` of a User and of a Group, `emails[].value`,
`emails[].type`, and `externalId`), refuse a value beginning or ending with
whitespace: any Unicode `White_Space` character, the no-break and ideographic
spaces as much as the space and the tab. A SCIM create, `PUT` or `PATCH` carrying
one is refused whole with `400 invalidValue` naming the attribute, and nothing is
trimmed, since a trimmed key is not the one the connector holds. Whitespace inside
a key (`a b`) is accepted, and every other attribute keeps the whitespace it was
sent. The same rule reaches authentication. A password Login whose username has
edge whitespace is an unknown account, decided before any lookup: the same bare
`401`, the same subject-less `LOGIN_FAILURE` (`UNKNOWN_ACCOUNT`) and count, and
nothing recorded against the User the trimmed name would find. An Epic `id_token`
whose `iss` or `sub` has edge whitespace is an invalid token (`INVALID_CLAIMS`).
The reason is the engine, not the value: SQL Server ignores trailing spaces when it
compares, so `alice` and `alice ` would collide as keys and match each other in a
lookup; refusing them keeps a key meaning exactly the value the connector holds
(ADR 0013).

**Normalized SCIM storage** — selected User fields use relational columns, while
emails, Groups, memberships, connector aliases, resource versions, connector
tokens, audit events, and tombstones use constrained related tables. JSON resource
blobs are not the source of truth; supported filters and uniqueness are backed by
relational indexes and constraints.

**Bootstrap Admin** — the local recovery User excluded from SCIM write
authority so an administrator can recover the application when external
provisioning is unavailable or has removed every SCIM-managed administrator. It
is visible through the SCIM interface as a read-only User and an immutable member
of the Admin group: clients may discover its current state and authority, but no
SCIM operation may mutate or delete the User or remove that membership. It is the
only User every deployment is seeded with: startup creates it when absent and
never overwrites it, so a rotated recovery password survives a restart. Its
protections are recognised by its reservation marker (see
[Recovery guard](./authentication.md)).

**Group** — a SCIM resource whose membership is the source of elevated
application authorization. Every active User receives baseline access without
membership in a redundant Users group. A Group may contain direct User members
only; Group-valued members and transitive membership are unsupported.

**Admin group** — the server-seeded Group that is the
[Superuser Group](./authorization.md). Its stable resource id carries that
meaning: SCIM may change ordinary membership but may neither rename nor delete
the Group, nor remove the Bootstrap Admin's membership. These protections are the
Superuser Group's alone: every other mapped Group is an ordinary Group over SCIM —
renamable, deletable, its membership writable — so an identity provider can retire
a helpdesk Group without touching this application's configuration.

**SCIM tombstone** — the privacy-minimal record retained after SCIM deletion: the
resource type, stable resource id and deletion time (UTC), and nothing else. Its
table has no column that could hold a profile, credential or membership value, and
the application may insert and read tombstones but never change or remove one. It
holds no hash of a former identifier, by design: historical correlation is by
stable id through the audit stream. Tombstones never participate in uniqueness
checks, as RFC 7644 §3.6 recommends of deleted resources: a former `userName`,
Group `displayName` or connector-scoped `externalId` may be reused by a future
resource. Readable profile and audit detail expire under the audit-retention
policy.

**Deleted User** — a User removed with `DELETE`. Deletion advances the version of
every Group it belonged to, then removes its Group memberships explicitly, in the
same transaction and before the User itself — the schema never removes a membership
by cascade, and refuses to delete a User a membership still names (ADR 0013). It
then removes its other live rows — profile, emails, credential and password history
and connector aliases — revokes its sessions once the deletion commits, and leaves a
SCIM tombstone. Every later operation on its id is `404`. Nothing readable about it
survives outside the audit stream; the tombstone is the only row that does. The
Bootstrap Admin cannot be deleted.

**Deleted SCIM Group** — an ordinary, non-Admin Group removed with `DELETE`.
Deletion advances the version of every former member, then removes its memberships
explicitly, in the same transaction and before the Group itself, exactly as for a
Deleted User; it then makes the Group unavailable through SCIM and leaves a SCIM
tombstone. A deleted mapped Group takes its Role from every member, each
recorded as a `ROLE_REVOKE` and signed out after commit, exactly as removing them
one by one would; the mapping still names its id, but it confers nothing until
the deployment replaces the mapping. The Admin group cannot be deleted.

## Resource version

Every resource whose rendered document a SCIM write changed advances its version
exactly once, and no other resource advances; the version is the resource's strong
ETag and its `meta.version`. `RepresentationChange` is the one place that decides
who moved, inside the statement that stores the change; the SCIM write apply step
carries out everything else a write implies, and advances only the version of a
write that stored nothing but an alias. A User's document lists its Groups by
`displayName`, and a Group's lists its members by their member label — a member's
`displayName`, or its `userName` when it has none — so:

- a User's own write — replacement, `PATCH`, an `active` change, a completed
  password change — advances that User; when it changes the User's label, it
  also advances every Group the User is a direct member of, so an `If-Match`
  holding such a Group's earlier version no longer matches. A `userName` change
  of a User that has a `displayName` leaves its label, and its Groups, alone;
- a Group membership that starts or stops advances the Group and each User whose
  membership changed, and a member removed and re-added in one write advances
  neither;
- a Group rename advances the Group and every member, before and after;
- Group creation advances each initial member, and Group deletion each former
  member; User deletion advances each of its Groups;
- a User's `externalId` alias written alone advances the User alone, and a
  Group's advances the Group alone: the alias is the calling connector's, kept
  beside the resource rather than in it, so neither side renders the other's;
- a Group write that changes neither name, membership nor alias advances
  nothing, not even the Group, so a connector converging on a desired state does
  not invalidate every cached copy on each re-send.

The change-required flag and the dormancy basis are application-owned, not SCIM
attributes, so writing either moves no version.

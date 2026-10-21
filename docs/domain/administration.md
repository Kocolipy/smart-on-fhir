---
type: Domain Rules
title: Administration and audit
description: The Accounts page and its Users and Groups projections, and the SCIM audit trail — what an event may hold, what is recorded, and how it is read.
tags: [administration, accounts, audit]
---

# Administration and audit

## Accounts page

The SPA screen at `/accounts`, an Admin's operational view. Despite the name of the
route and of its endpoint `/api/admin/accounts`, it shows Users: the Users
projection and the Groups projection, both read-only for everything the directory
owns, plus the application-owned operations:

- **Unlock**, offered only while a lockout is in force, and described as the only
  way a lockout ends — one that also requires the User to change its password;
- the **forced password change**, offered only on a credentialed User not already
  flagged;
- connector and token management with one-time plaintext disclosure.

Both User operations address the User by its stable id. It offers neither
operation on the signed-in Admin's own row (comparing the normalized `userName`),
no Unlock on the Bootstrap Admin, and no forced change on it either, so the
backend's [refusals](./authentication.md) are visible before the click. There is
no Deactivate or Activate: `active` is the directory's, and the backend has no
endpoint that would accept a write to any directory-owned field. Each view and
action is shown by its own Permission — the Users projection by `user:read`, its
Unlock and forced change by `account:unlock`, the Groups projection by `group:read`,
connectors by `connector:read` (create, delete and every token action are shown
only to a Superuser) — and the page renders for a User who may see at least one view;
a deep link from anyone else is routed to Home. The page never decides
authorization: the backend refuses each operation to a caller lacking its
Permission regardless.

**Users projection** — what identity administration may know about a User, one
row per User (`GET /api/admin/accounts`): its stable resource id, `userName` and
display name, whether it is the Bootstrap Admin, whether the Admin group confers
administrative authority on it, its `active` flag, whether a credential is set at
all, whether a lockout is in force and its cause (`FAILURES` or `DORMANCY`, so an
operator can tell a forgotten password from an abandoned User before unlocking),
whether a change is required, its last authentication, its creation timestamp and
its direct Groups. The directory-owned fields — identity, `active`, Groups — are
read-only there. Never the password hash, which no projection type has a field
for.

- No field says when a lockout lifts, because none does: what ends it is an
  Admin's Unlock.
- Both refusal mechanisms appear because either alone would mislead — a User
  locked out right now looks healthy if only `active` is shown, and nothing would
  say which need unlocking.
- The administrative flag is derived at read time from Admin-group membership
  rather than stored, so the projection reports the same fact the login path
  derives and there is no column for the two to disagree about.
- Whether a credential exists is reported because "no password was ever set" is
  otherwise indistinguishable from "the password is wrong", and only the first is
  fixed by a SCIM write.
- The Bootstrap Admin's row shows no lockout state at all: it can never be locked,
  so "not locked" would describe a condition that could change.

**Groups projection** — what identity administration may know about a Group, one
row per Group (`GET /api/admin/groups`): its name, its direct member count, and
whether it is the protected Admin group, decided by the reservation marker.
Read-only in its entirety; Groups and membership are the directory's.

## SCIM audit trail

The append-only local history of provisioning and connector token activity, and
of the authentication, lockout, administrative, password-change and scheduled-job
events recorded in the same stream. An event records the actor's stable id — the
Admin or User for an authentication or administrative event, the connector (never
the token) for a SCIM or token-lifecycle one, nobody for a scheduled job — plus
operation, resource type and id, outcome, changed attribute paths, error
classification and timestamp; it never records a password, bearer token, hash,
`userName` or other profile value. The application login may append events but
never update or delete one; only the [audit retention job](./dormancy.md) deletes.

- Every collection query and search is one bulk-read event carrying the number of
  resources returned and the filter's shape (`userName eq ?`), never its values; a
  single-resource read is not recorded.
- A refused create, replacement, patch or deletion is one failure event naming the
  connector, the resource it addressed where it has one, and the reason as the
  `scimType` the connector received (`uniqueness`, `invalidValue`, `mutability`,
  `noTarget`). It is recorded by what the refusal is, whichever part of the
  service refused it — the reading of the request or the directory itself — so the
  same `scimType` is audited alike wherever it arose. A refusal read from the
  request is raised before the resource is looked up, so it names the id the write
  addressed whether or not a resource holds it.
- A refused precondition (a stale or malformed `If-Match`), a body that is not
  SCIM (`invalidSyntax`), a malformed PATCH path (`invalidPath`, `invalidFilter`),
  a `404` for an id naming no resource, a path segment that is not an id at all,
  and any refusal of a read are answered and logged but not recorded.

**Audit listing** — `GET /api/admin/audit-events`, by a holder of `audit:read`:
newest first, paginated, filterable by operation, outcome, actor, resource and time
window. The listing returns the stored events as they are — redaction lives in
what an event can hold, not in the read — and reading the trail is not itself
recorded.

**Audit page** — the SPA screen at `/audit` showing the audit listing, routed for
a holder of `audit:read` alone: read-only, filtered and paged, and never reloaded
on a timer.

---
type: Domain Rules
title: Administration and audit
description: The Accounts page and its Users and Groups projections, and the SCIM audit trail — what an event may hold, what is recorded, and how it is read.
tags: [administration, accounts, audit]
---

# Administration and audit

## Accounts page

The SPA screen at `/accounts`, the operational view for whoever holds
`user:read`, `group:read` or `connector:read`. Despite the name of the route and
of its endpoint `/api/admin/accounts`, it shows Users: the Users
projection and the Groups projection, both read-only for everything the directory
owns, plus the application-owned operations:

- **Unlock**, offered only while a lockout is in force, and described as the only
  way a lockout ends — one that also requires the User to change its password.
  An Unlock the backend refuses because the User is no longer locked (`409`,
  most often another Admin's Unlock landing first) says so and asks for a
  reload;
- **Group Permissions**, set by the Superuser from the Groups projection;
- connector and token management with one-time plaintext disclosure.

Unlock addresses the User by its stable id. It is not offered on the signed-in
Admin's own row — the row whose stable id the session holds, which `GET
/api/auth/me` reports, never one picked out by `userName` — on the Bootstrap
Admin, or on any User that is not locked, so the backend's
[refusals](./authentication.md) are visible before the click. There is no forced
password change: the helpdesk Permission acts only on a locked User (ADR 0015).
There is
no Deactivate or Activate: `active` is the directory's, and the backend has no
endpoint that would accept a write to any directory-owned field. Each view and
action is shown by its own Permission — the Users projection by `user:read`, its
Unlock by `account:unlock`, the Groups projection by `group:read`
(its member read only beside `user:read`, and its "Edit permissions" action only
to a Superuser), connectors by `connector:read`
(create, delete and every token action are shown only to a Superuser) — and the
page renders for a User who may see at least one view;
a deep link from anyone else is routed to Home. The page never decides
authorization: the backend refuses each operation to a caller lacking its
Permission regardless.

**Users projection** — what identity administration may know about a User, one
row per User, read a page at a time (`GET /api/admin/accounts`): its stable resource id, `userName` and
display name, whether it is the Bootstrap Admin, whether the Admin group confers
administrative authority on it, its `active` flag, whether a credential is set at
all, whether a lockout is in force and its cause (`FAILURES` or `DORMANCY`, so an
operator can tell a forgotten password from an abandoned User before unlocking),
whether a change is required, its last authentication, its creation timestamp and
its direct Groups. The directory-owned fields — identity, `active`, Groups — are
read-only there. Never the password hash, which no projection type has a field
for.

It is a page of the directory, not a complete review of it (ADR 0017). The Users
come in normalized `userName` order by default, twenty to a page on the Accounts
page, with the total beside them and Previous and Next controls. A search box
narrows them to the Users whose `userName` or display name contains the text,
ignoring case. Submitting a search reads its first page. A search that matches
nothing says so ("No users match …"), which is distinct from no User being
provisioned at all. The search is matched literally and bound as a statement
parameter, never composed into filter text. Paging follows SCIM's bounds
(`startIndex` one-based, `count` defaulting to 100 and capped at 200,
out-of-range values coerced), as the Group member read's does. Each page's
direct Groups, and so its Superuser flag, come from one membership read for that
page's Users. An Unlock replaces its row on the page shown from the response,
without reading the page again.

The projection is sortable by Directory attributes, through the directory's SCIM
sort with its rules (`/docs/domain/scim-protocol.md`): the whole directory is
ordered before the page is cut, a missing value sorts last ascending and first
descending, and equal keys are ordered by `id`. On the Accounts page the "User"
heading sorts by display name — so a User with none sorts as missing, not by the
`userName` the row shows instead — and the "Active" heading by `active`. Each is
a ghost icon button showing the direction (up ascending, down descending, both
while unsorted), stated to assistive technology by `aria-sort`, and each press
moves ascending, descending, then back to the default order. Changing the sort
reads the first page and keeps the search, and a new search keeps the sort. The
API accepts any sortable User attribute as `sortBy`; an unsortable one, or a
`sortOrder` that is neither direction, is `400` as a malformed `startIndex` is.
Lockout, Password, Password change required and Last sign-in are not sortable:
they are the application's login and credential state, not Directory attributes,
so the directory's query has nothing to order them by.

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
row per Group, read a page at a time (`GET /api/admin/groups`): its name, its direct member count,
whether it is the protected Admin group, decided by the reservation marker, and
the Permissions it carries. Its name and membership are the directory's and
read-only here; its Permissions are the application's. The member count is
counted where the memberships are stored; listing the Groups never reads who is
in them. Its paging, search, sorting and empty results are the Users
projection's, with the Groups in normalized display-name order by default, the
search matching the display name alone, and the "Group" heading sorting by
display name. Members and Permissions have no single sort key and do not sort.
A Group Permissions save replaces its row on the page shown.

**Group member read** — who a Group's direct members are, read a page at a time
(`GET /api/admin/groups/{id}/members?startIndex&count`) on expanding the Group's
row, where the member count is then a disclosure control. A member row shows the
User's stable id, `userName`, display name and `active` flag — the Users
projection's identity and nothing else, never a credential or any login state,
which its type has no field for. Members come in the Users projection's order,
normalized `userName`, with the Group's total beside the page; paging follows
SCIM's bounds (`startIndex` one-based, `count` defaulting to 100 and capped at
200, out-of-range values coerced), and the page asks for twenty at a time with a
Next control while more remain. The read is the directory's own collection query,
the one SCIM `GET /Users` runs, asked without a connector: a filter on
`externalId`, a `$ref` or `meta.location`, which only a connector's query can
give a value, is refused on that path. A Group with no members says so; an
unknown or deleted Group is `404`; a failed read reports to the page's one error
line.

- It requires **both** `group:read` and `user:read`, declared on the handler,
  repeated by the chain and in the API document's one `sessionCookie` entry,
  because member identities are Users-projection data. A session holding
  `group:read` alone sees the plain count and no control, so nothing is
  disclosed to it that it did not see before (ADR 0015).

**Group Permissions** — what a Group confers, set only by the Superuser (ADR 0015):
in the Groups projection a Superuser sees "Edit permissions" on every Group but the
Admin group, which opens a dialog of one checkbox per delegatable Permission, fed
by `GET /api/admin/permissions` and pre-ticked with what the Group carries. Save
sends the complete set (`PUT /api/admin/groups/{id}/permissions`), replacing the
Group's — an empty set clears it, and the last writer wins — and the row shows the
Group as the response states it. Nobody else is offered the action, and nothing
sets a Group's Permissions over SCIM. The session effects — a Permission taken
away ends every direct member's sessions at once, one added reaches them at their
next sign-in — are [authorization's](./authorization.md). Every change is audited:

- an accepted change is one `GROUP_PERMISSIONS_CHANGE` naming the Superuser, the
  Group and the Group's Permissions before and after, logged at `INFO` and counted
  on `authorization.changes{operation="group-permissions-change"}`;
- a refused one — a body that is not a set of delegatable Permissions (`400`), a
  Group that does not exist (`404`), the Admin group (`409`) — is a
  `GROUP_PERMISSIONS_CHANGE` failure naming the Group addressed and one generic
  reason (`INVALID_PERMISSIONS`, `UNKNOWN_RESOURCE`, `PROTECTED_RESOURCE`), never
  a Permission, logged at `WARN`. A caller who is not the Superuser gets the
  generic `403`, recorded as every authorization refusal is (`ACCESS_DENIED`).

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
- A refused administrative operation — an Unlock, a change of a Group's
  Permissions, a connector or token operation — is one failure event naming the
  administrator, what the operation addressed and one closed-set reason, recorded
  by what the refusal is, from one table, whichever use case refused it. Every
  one is recorded, an id that names nothing included (`UNKNOWN_RESOURCE`): a
  User, a Group, a connector or a token. Only a token request of a shape no token
  may have — a lifetime past the ceiling, a Permission no token may carry — is
  answered `400` and not recorded.

**Audit listing** — `GET /api/admin/audit-events`, by a holder of `audit:read`:
newest first with ties broken by event id, filterable by operation, outcome, actor,
resource and time window. It pages as the Users and Groups projections do (ADR
0018): `startIndex` one-based, `count` defaulting to 100 and capped at 200, an
out-of-range value coerced rather than refused, and `count=0` no events but the
true total. It answers `{ totalResults, startIndex, events }`, the total and the
page read in one snapshot; a `startIndex` past the end is `200` with no events. A
filter, `startIndex` or `count` that cannot be read as its type is `400`. The
listing returns the stored events as they are — redaction lives in what an event
can hold, not in the read — and reading the trail is not itself recorded.

**Audit page** — the SPA screen at `/audit` showing the audit listing, routed for
a holder of `audit:read` alone: read-only, filtered, and never reloaded on a timer.
It asks for 50 events at a time and moves through the trail with the pager the
Accounts page uses, which shows once a read has answered: "Events 51–100 of 120",
Previous disabled on the first page, Next disabled once no events lie past the
page shown. Moving keeps the applied filters, and applying filters returns to the
first page. A later page that comes back empty says so and keeps Previous.

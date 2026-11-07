---
type: Decision Record
title: "ADR 0017: Administrative reads are paged by one set of rules"
description: Every paged administrative read pages by SCIM's startIndex and count rules from one paging module; the Users and Groups projections are also searched and sorted by Directory attributes.
tags: [administration, paging, scim, audit]
---

# 17. Administrative reads are paged by one set of rules; the projections are also searched and sorted

Date: 2026-10-27

## Status

Accepted. Covers every paged administrative read: the Users and Groups
projections, a Group's member read, a connector's active-token read and the
audit listing.
`/docs/domain/administration.md` is the behaviour in full; this file holds the
decisions and their reasons.

## Context

A connector provisions as many Users and Groups as the identity provider holds,
so a listing answered whole grows with the directory: one response carrying every
row, and a browser table drawing them. A Groups column answered by inverting
every Group's full membership in memory costs the size of every membership as
well as the number of Users.

The administrative service can run the directory's SCIM collection query without
a connector: the filter, the order, the page and the total, with every literal
bound as a statement parameter. A Group's direct members are read a page at a
time through it.

Once a projection is a page, an order the browser imposes on the rows in hand
sorts twenty rows, not the directory: the first page of a sort by display name
has to be the directory's first twenty display names, which only the query that
selects the page can answer. SCIM's collection query already sorts, for
`/scim/v2/Users`.

The audit listing paged on a model of its own — a zero-based `page` and a `size`,
`400` for an out-of-range value — with a second, hand-built pager in the SPA to
match. Its paging rules could not be SCIM's either, while they lived in `scim`:
`scim` depends on `audit`, which records its events, so the audit listing
reaching them would be a module cycle, and a copy would be a second rule to
drift.

## Decision

**D1. Every paged administrative read pages by one set of rules.**
`GET /api/admin/accounts`, `GET /api/admin/groups`,
`GET /api/admin/groups/{id}/members`,
`GET /api/admin/connectors/{connectorId}/tokens` and `GET /api/admin/audit-events` take
`startIndex` and `count` and answer one page plus `totalResults` and the
`startIndex` it starts at. The rules are SCIM's, through the one `PageRequest`
SCIM itself uses: `startIndex` one-based, `count` defaulting to 100 and capped
at 200, and an out-of-range value coerced, never refused, however far out of
range: a value too large for a 32-bit integer is coerced as any other is. Past
the end is an empty `200`, and `count=0` asks how many there are: no rows, and
the true total. Either is answered from the count alone, with no page read,
so no reader asks SQL Server for no rows. SQL Server 2022 refuses
`FETCH NEXT 0 ROWS` with error 10744 whether the 0 is written into the
statement or bound as a parameter; only a statement recompiled with the value
in hand (`OPTION (RECOMPILE)`, as the audit reader's page statement is) answers
it with no rows. Only a
value that is not an integer at all is `400` — the binder's on the
administrative reads, SCIM's `invalidValue` on SCIM's own. By default
the Users come in normalized `userName` order and the Groups in normalized
display-name order (D5 lets the caller ask for another); a connector's active
tokens always come newest issue first, ties broken by token id. The Accounts
page asks for twenty rows at a time, and five tokens at a time in each connector
section, and the Audit page for 50, through one pager.

**D2. The projections are searched by name.** A `search` parameter narrows the
Users to those whose `userName` or display name contains it, and the Groups to
those whose display name contains it, ignoring case. The search is built as a
filter value (`ScimFilter.nameContains`), as the member read's filter is, and
never as filter-grammar text. Its text is the comparison's literal, bound as a
statement parameter and matched literally: `%`, `_` and a quote are only
characters. The backend strips the search, and a blank one is no search.

**D3. A page's rows are assembled for that page alone.** A page of Users loads
those Users in one read and their direct Groups, and so the Superuser flag, in
one batched membership read for the page's Users. It never reads one membership
per User, and never reads every Group's membership. A page of Groups counts its
Groups' members where they are stored and reads their Permissions in one
statement.

**D4. A row action answers its row.** Unlock and setting a Group's Permissions
answer with the row as it now stands, and the SPA replaces that row on the page
shown without reading the page again. The listings' Permissions are `user:read`
for the Users and `group:read` for the Groups, and their only refusal of their
own is D5's invalid sort.

**D5. The projections are sorted by Directory attributes, through SCIM's sort.**
Each projection takes `sortBy` and `sortOrder` and hands them to the directory's
SCIM sort (`ScimSort`), the one `/scim/v2/Users` uses, with its rules unchanged:
one sortable attribute path of the projection's resource type, `ascending` by
default or `descending`, a missing value last ascending and first descending,
equal keys ordered by `id`, and SQL Server's deviations as
`/docs/domain/scim-protocol.md` records them. The whole directory is sorted
before it is paged, so a sorted page is that order's slice, and the sort
composes with the search. Any attribute SCIM sorts by is accepted, with no
closed set of the projections' own: a second list would be a second rule for
what is sortable. A path SCIM refuses, an order that is neither direction, and
a path only a connector has a value for (`externalId`, a `$ref`,
`meta.location`, which a query asked without a connector cannot evaluate) are
refused `400`, as a malformed `startIndex` is. Without a `sortBy` the order is
D1's default. The Accounts page sorts the Users by the "User" heading (display
name) and the "Active" heading, and the Groups by the "Group" heading, each
toggling ascending, descending, then the default order; changing the sort reads
the first page and keeps the search.

**D6. The audit listing keeps its own order and its typed filters.** The search
and sort of D2 and D5 are the projections' alone. The trail is always newest
first, ties broken by event id, because it is read as a history. Its filters
stay typed values compared with stored columns, so a malformed one is still
`400`. The count and the page are read in one snapshot (ADR 0013).

**D7. The rules live in a module of their own.** The `paging` module,
`paging.domain`, depends on nothing and owns every paging rule. Its
`PageParameter` reads a raw `startIndex` or `count`, an integer of any size, and
is what each administrative read binds its parameters to, so a non-integer is
the binder's `400`; SCIM reads its parameters and its `SearchRequest` members
into the same type. `PageRequest.of` coerces the two into a page, and a
`PageRequest` holds only a page the rules produce: its constructor refuses a
`startIndex` below 1 or a `count` outside 0 to 200. `PageRequest.readsAny`
decides whether a page is read at all, which every reader asks before its page
statement. `audit`, `scim` and `auth` all use these types, so SCIM discovery's
`filter.maxResults` and the ceiling every read enforces stay one constant. RFC
7644 is where the rules come from; SCIM is not where they live.

## Consequences

- **An operator never sees the whole directory at once.** The Users card
  shows twenty Users and the total. Finding one means searching for its name or
  paging to it, and no single screen reviews every User who can log in or every
  Group that confers something. A review that needs the whole directory, such as
  every locked User or every member of the Admin group, takes paging or
  searching, or the directory's own tooling. The search matches names only, not
  state: there is no "locked" or "inactive" filter. Sorting by `active` brings the
  inactive Users together, but nothing sorts by lockout, credential,
  change-required or last sign-in: they are the application's login state, not
  Directory attributes, so the directory's collection query has no column to
  order them by, and a sort by them would be a second query over a second store.
- A page is a stateless slice of a changing store, as SCIM's are. A User
  provisioned or removed, or an event appended, between two page reads can shift
  a row onto the next page or off it. The total is the count when that page was
  read. A sorted page is the same: the `id` tie-break makes one read's order
  total, not two reads'. A move's next `startIndex` follows the last row shown,
  so a page that came back short leaves no gap.
- A read is never refused for being out of range, so a client cannot learn from a
  `400` that its paging was wrong; `startIndex` in the answer says where the
  coerced page starts.
- A sort by display name puts every User without one last ascending and first
  descending, as SCIM does, rather than falling back to the `userName` the
  "User" heading shows for them.
- A row action's answer replaces its row only on the page shown. A row whose
  name no longer matches the search after the action stays on the page until
  the next read.
- The pager shows only once a read has answered. While a move's read is pending,
  and after it fails, the page offers no Previous or Next. On the Audit page,
  Refresh, or any filter change (Apply, Reset, or a change in the Operation or
  Outcome control), is the way on from a failed move, and a failed Refresh keeps
  the pager and the page it counts from.

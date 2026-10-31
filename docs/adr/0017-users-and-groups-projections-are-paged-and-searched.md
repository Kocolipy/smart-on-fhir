# 17. The Users and Groups projections are paged, searched and sorted, not listed whole

Date: 2026-10-27

## Status

Accepted. Replaces the complete listing the Users and Groups projections had
since the Accounts page first shipped. `/docs/domain/administration.md` is the
behaviour in full; this file holds the decision and its reasons.
[ADR 0018](./0018-the-audit-listing-joins-the-projections-paging-model.md) brings
the audit listing onto D1's paging.

## Context

The Accounts page's Users projection read every User in the directory, and was
described as "a complete review of who can log in rather than a page of a
directory". The Groups projection read every Group. Both answered a JSON array
of every row, and the SPA drew every row at once.

That holds for a directory of tens of identities, but a connector provisions as
many Users and Groups as the identity provider holds. Each listing grew with the
directory: one response carrying every row, and a browser table drawing them.
The Users listing also answered its Groups column by reading every Group's full
membership and inverting it in memory, so its cost followed the size of every
membership as well as the number of Users.

#213 already gave the administrative service a way to run the directory's SCIM
collection query without a connector: the filter, the order, the page and the
total, with every literal bound as a statement parameter. A Group's direct
members are read a page at a time through it.

Once a projection is a page, an order the browser imposes on the rows in hand
sorts twenty rows, not the directory: the first page of a sort by display name
has to be the directory's first twenty display names, which only the query that
selects the page can answer. SCIM's collection query already sorts, for
`/scim/v2/Users`.

## Decision

**D1. Both projections are read a page at a time.** `GET /api/admin/accounts`
and `GET /api/admin/groups` take `startIndex` and `count` and answer one page
plus `totalResults`: by default the Users in normalized `userName` order, the
Groups in normalized display-name order (D5 lets the caller ask for another).
The paging bounds are SCIM's, through the same
`PageRequest` the member read and SCIM itself use: `startIndex` one-based,
`count` defaulting to 100 and capped at 200, and out-of-range values coerced,
never refused. All three administrative reads therefore handle a bad value the
same way. The SPA asks for twenty rows at a time.

**D2. Both are searched by name.** A `search` parameter narrows the Users to
those whose `userName` or display name contains it, and the Groups to those whose
display name contains it, ignoring case. The search is built as a filter value
(`ScimFilter.nameContains`), as the member read's filter is, and never as
filter-grammar text. Its text is the comparison's literal, bound as a statement
parameter and matched literally: `%`, `_` and a quote are only characters. The
backend strips the search, and a blank one is no search.

**D3. A page's rows are assembled for that page alone.** A page of Users loads
those Users in one read and their direct Groups, and so the Superuser flag, in
one batched membership read for the page's Users. It never reads one membership
per User, and never reads every Group's membership. A page of Groups counts its
Groups' members where they are stored and reads their Permissions in one
statement.

**D4. Nothing else moves.** Each row carries every field it carried before.
Unlock and setting a Group's Permissions still answer with the row as it now
stands, and the SPA replaces that row on the page shown without reading the page
again. Permissions and audit are unchanged: `user:read` for the Users listing,
`group:read` for the Groups listing. So are the refusals, but for D5's invalid
sort, which is the `400` a malformed `startIndex` already gets.

**D5. Both are sorted by Directory attributes, through SCIM's sort.** Each
listing takes `sortBy` and `sortOrder` and hands them to the directory's SCIM
sort (`ScimSort`), the one `/scim/v2/Users` uses, with its rules unchanged: one
sortable attribute path of the projection's resource type, `ascending` by
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

## Consequences

- **An operator no longer sees the whole directory at once.** The Users card
  shows twenty Users and the total. Finding one means searching for its name or
  paging to it, and no single screen reviews every User who can log in or every
  Group that confers something. A review that needs the whole directory, such as
  every locked User or every member of the Admin group, is no longer one glance.
  It takes paging or searching, or the directory's own tooling. The search
  matches names only, not state: there is no "locked" or "inactive" filter.
  Sorting by `active` brings the inactive Users together, but nothing sorts by
  lockout, credential, change-required or last sign-in: they are the
  application's login state, not Directory attributes, so the directory's
  collection query has no column to order them by, and a sort by them would be
  a second query over a second store.
- The response shape of both listings changed from an array to a page object, so
  any client reading them as an array breaks. The SPA and the E2E suite were
  updated together. `backend/docs/openapi.yaml` describes both shapes, and the
  contract tests hold the running service to it.
- A page is a stateless slice of a changing directory, as SCIM's are. A User
  provisioned or removed between two page reads can shift a row onto the next
  page or off it. The total is the count when that page was read. A sorted page
  is the same: the `id` tie-break makes one read's order total, not two reads'.
- A sort by display name puts every User without one last ascending and first
  descending, as SCIM does, rather than falling back to the `userName` the
  "User" heading shows for them.
- A row action's answer replaces its row only on the page shown. A row whose
  name no longer matches the search after the action stays on the page until
  the next read.

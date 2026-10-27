# 17. The Users and Groups projections are paged and searched, not listed whole

Date: 2026-10-27

## Status

Accepted. Replaces the complete listing the Users and Groups projections had
since the Accounts page first shipped. `/docs/domain/administration.md` is the
behaviour in full; this file holds the decision and its reasons.

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
collection query without a connector: the filter, the default order, the page
and the total, with every literal bound as a statement parameter. A Group's
direct members are read a page at a time through it.

## Decision

**D1. Both projections are read a page at a time.** `GET /api/admin/accounts`
and `GET /api/admin/groups` take `startIndex` and `count` and answer one page
plus `totalResults`: the Users in normalized `userName` order, the Groups in
normalized display-name order. The paging bounds are SCIM's, through the same
`ScimPageRequest` the member read and SCIM itself use: `startIndex` one-based,
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
again. Permissions, refusals and audit are unchanged: `user:read` for the Users
listing, `group:read` for the Groups listing.

## Consequences

- **An operator no longer sees the whole directory at once.** The Users card
  shows twenty Users and the total. Finding one means searching for its name or
  paging to it, and no single screen reviews every User who can log in or every
  Group that confers something. A review that needs the whole directory, such as
  every locked User or every member of the Admin group, is no longer one glance.
  It takes paging or searching, or the directory's own tooling. The search
  matches names only, not state: there is no "locked" or "inactive" filter.
- The response shape of both listings changed from an array to a page object, so
  any client reading them as an array breaks. The SPA and the E2E suite were
  updated together. `backend/docs/openapi.yaml` describes both shapes, and the
  contract tests hold the running service to it.
- A page is a stateless slice of a changing directory, as SCIM's are. A User
  provisioned or removed between two page reads can shift a row onto the next
  page or off it. The total is the count when that page was read.
- A row action's answer replaces its row only on the page shown. A row whose
  name no longer matches the search after the action stays on the page until
  the next read.

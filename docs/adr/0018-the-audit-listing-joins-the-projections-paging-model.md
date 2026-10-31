# 18. The audit listing joins the projections' paging model

Date: 2026-10-31

## Status

Accepted. Extends [ADR 0017](./0017-users-and-groups-projections-are-paged-and-searched.md)'s
D1 to `GET /api/admin/audit-events`. `/docs/domain/administration.md` is the
behaviour in full; this file holds the decision and its reasons.

## Context

The Users and Groups projections and a Group's member read page by `startIndex`
and `count`, answer `totalResults`, and coerce an out-of-range value. The audit
listing was the one paged administrative read left on another model: a zero-based
`page` and a `size`, an answer of `totalElements` and `totalPages`, and a `400`
for a negative page or a size outside 1–200. Its SPA page carried a second,
hand-built pager to match.

The paging rules lived in `scim.domain`, and `scim` depends on `audit`, which
records its events. The audit listing could not reach them without a module
cycle, and a copy would be a second rule to drift.

## Decision

**D1. The audit listing pages as the projections do.** It takes `startIndex` and
`count` and answers `{ totalResults, startIndex, events }`, by the one set of
rules: `startIndex` one-based, `count` defaulting to 100 and capped at 200, past
the end an empty `200`. The Audit page still asks for 50 events at a time, and
moves through the trail with the pager the Accounts page uses.

**D2. Coercion replaces refusal.** An out-of-range `startIndex` or `count` is
coerced, as on every other paged read, where the listing used to answer `400`.
Only a value that is not an integer at all is still `400`, the binder's, as it is
on the projections. `count=0` asks how many there are: no events, and the true
total.

**D3. Audit keeps its own order and its typed filters.** ADR 0017's search and
sort do not come with the paging. The trail is always newest first, ties broken by
event id, because it is read as a history. Its filters stay typed values compared
with stored columns, so a malformed one is still `400`. The count and the page are
still read in one snapshot (ADR 0013).

**D4. The rules move to a module of their own.** `ScimPageRequest` becomes
`paging.domain.PageRequest`, in a new `paging` module that depends on nothing, and
`audit`, `scim` and `auth` all use that one type. It is the same record with the
same constants rather than a wrapper, so SCIM discovery's `filter.maxResults` and
the ceiling every read enforces stay one constant. RFC 7644 is still where the
rules come from; SCIM is no longer where they live.

## Consequences

- A client of the listing reading `page`, `size`, `totalElements` or `totalPages`
  breaks, and one sending `page` or `size` has them ignored like any unknown
  parameter. The SPA and the E2E suite changed with it, and the SPA refuses a page
  still carrying the old fields rather than decoding it.
- A caller sending no `count` now gets 100 events rather than 50.
- A request the listing used to refuse now answers `200`, so a client can no
  longer learn from a `400` that its paging was out of range. `startIndex` in the
  answer says where the coerced page starts.
- The Audit page's pager now shows only once a read has answered, as the Accounts
  page's does. While a move's read is pending, and after it fails, the page offers
  no Previous or Next: Refresh or Apply filters is the way on from a failed move.
  A failed Refresh keeps the pager and the page it counts from.
- A move's next `startIndex` follows the last event shown, so a page that came back
  short leaves no gap. As ADR 0017 says of the projections, a page is a stateless
  slice: an event appended between two page reads shifts the later page by one.

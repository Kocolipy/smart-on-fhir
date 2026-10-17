---
type: Domain Rules
title: SCIM protocol profile
description: The part of SCIM 2.0 the service implements — discovery, filtering and sorting with SQL Server's deviations, conditional writes, request bounds, and the release gate.
tags: [scim, protocol, filtering, etag]
---

# SCIM protocol profile

The **practical SCIM protocol profile**: discovery at `/ServiceProviderConfig`,
`/ResourceTypes`, and `/Schemas` for any valid connector token, whatever its
Permissions, followed by Permission-checked User and Group CRUD, PATCH, filtering,
sorting, pagination, conditional writes with ETags, and standard SCIM errors.
`ServiceProviderConfig` advertises `patch`, `filter`, `sort`, `etag` and
`changePassword` as supported, and Bulk as `supported: false`, permanently.
Acceptance is defined by the RFC contracts, not by the behaviour of Microsoft Entra
ID, Okta, or another vendor.

**Advertise only what is implemented.** Discovery advertises a capability only once
it is implemented, and a request for one that is not is refused rather than
ignored — an ignored `filter` is indistinguishable from a match, which is the one
failure a connector cannot detect. This binds anything added later.

**Users without a password** — a User may be created without `password`; its
`active` value remains authoritative, but password Login returns the same bare
`401` as any rejected credentials until a later SCIM write sets one.

## Collections, filtering and sorting

Collection requests default `count` to 100 and clamp it to 200, while returning
`totalResults`, one-based `startIndex`, and `itemsPerPage`. Filtering implements
the complete RFC 7644 grammar over supported attributes, including comparison,
presence, boolean, grouping, and value-path expressions; unsupported paths fail
predictably rather than being silently misread. "Complete" means full syntax and
semantics over the attributes this service advertises: it makes no unadvertised
attribute queryable, and a filter on `password` is `400 invalidFilter`. Sorting
takes one attribute, puts missing values last ascending and first descending, and
breaks ties by `id`. The same query may be sent as a `SearchRequest` body to
`/Users/.search`, `/Groups/.search`, or the base `/.search`, which spans both types
and treats an attribute one type lacks as having no value there.

**SQL Server deviations** — documented in `backend/docs/openapi.yaml` and recorded
in ADR 0013 ("Observable changes" and its 2026-10-15 addendum):

- `eq`, `ne`, `gt`, `ge`, `lt` and `le` on free text (`name.*`, `displayName`,
  `locale`, `timezone`, `preferredLanguage` and the like) ignore trailing spaces in
  the stored and the compared value, because SQL Server pads the comparison, while
  `co`, `sw` and `ew` keep them significant, because a `LIKE` against the bound
  pattern is not padded;
- a whitespace-only free-text value counts as absent to `pr` and sorts as missing;
- ties broken by `id` follow SQL Server's `uniqueidentifier` order rather than the
  ids' text order;
- case-insensitive comparison uses SQL Server's `LOWER`, which leaves some
  upper-case letters outside ASCII, Latin-1, Latin Extended-A and -B, modern Greek
  and Cyrillic unchanged (the Kelvin, Ohm and Angstrom signs, capital sharp s,
  Georgian Mtavruli, Cherokee and supplementary-plane scripts among them),
  lower-cases `İ` to `i`, and has no final-sigma rule (spike V4 lists every code
  point).

## Writes

**Conditional writes** — `PUT`, `PATCH`, and `DELETE` of an existing resource
accept an optional `If-Match`, as RFC 7644 allows: without one the write is applied
unconditionally, last writer wins; when sent it must be exactly one strong ETag,
checked after authorization and existence: a wildcard, list or malformed one is
`400 invalidValue`, and a stale version is `412`. ETags are strong although RFC
7644's examples show weak ones, because HTTP `If-Match` compares strongly and this
profile promises exact lost-update protection; a weak `W/` tag therefore never
matches. Concurrent writers holding the same ETag are serialized on the resource,
so exactly one succeeds; concurrent unconditional writers are serialized too, so
both succeed and neither is half-applied. Unconditional writes are counted per
connector so an operator can see which integrations run without lost-update
protection. What advances a version is under
[Resource version](./directory.md).

**`externalId`** is read-write on Users and Groups and writes only the calling
connector's alias: a `PUT` sets it to the submitted value or, when omitted, removes
it, and `PATCH` `add`/`replace`/`remove` set or clear it. Another connector's alias
for the same resource is never read or written.

**Passwords** — a password set through `PUT` or `PATCH` is held to the
[password policy](./passwords.md), so one matching any of the User's three most
recent passwords is refused.

## Bounds, not rate limits

The per-request safety bounds — request body, filter length, depth and node
count, PATCH operations, page size (`ScimRequestLimits`, `ScimFilterParser`,
`ScimPageRequest` hold the numbers), and the Login and password-change field
lengths — fail with the closest standard error (`413`, `400 invalidFilter`,
`400 invalidValue`) and never partially mutate a resource. An over-length Login
or change-password field is a `400` from request validation, before
authentication runs: it is not counted toward the failure run, not audited, and
indistinguishable from any other malformed body, so it adds no enumeration
signal. The password fields' bound is the password policy's own maximum, so no
password the policy accepted is ever refused for its length.

The application adds no rate limiter, SCIM or otherwise: throttling
`/scim/v2/**`, Login and the self-service change is the deployment edge's job,
specified in `infra/README.md` ("Edge throttling").

## SCIM release gate

`APP_SCIM_ENABLED` (on by default). Closed, the whole `/scim/v2` namespace answers
`404` with a SCIM error body, discovery included and ahead of authentication, so a
valid token, an expired one and none at all get the same answer and the gate
cannot be probed for the interface behind it. `404` rather than `403` or `503`,
because either of those would disclose a surface that is not being offered. The
backend README ("SCIM release gate") is the configuration reference.

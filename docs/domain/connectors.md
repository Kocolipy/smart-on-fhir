---
type: Domain Rules
title: Connectors and their tokens
description: SCIM connector tokens — format, Token Permissions, who may mint one, lifetime and rotation, credential refusals — and what deleting a connector does.
tags: [scim, connectors, tokens, credentials]
---

# Connectors and their tokens

**Only the Superuser administers connectors** (ADR 0015). Creating and deleting a
connector, and issuing, rotating and revoking a token, are
[Superuser](./authorization.md)-only: any other caller, a User holding every
Permission included, gets the generic `403` that names no Permission or policy.
`connector:read` still lists connectors and their tokens' state.

**SCIM connector token** — a high-entropy opaque bearer credential restricted to
the SCIM interface. The value is a non-secret lookup handle, a dot, and at least
256 bits of `SecureRandom` material; only a SHA-256 digest of the **complete**
value is stored, compared in constant time. An unstretched hash is right only
because the value is full-entropy random material rather than a password, and is
no precedent for hashing a password that way.

## Token Permissions

A token carries **Token Permissions** (ADR 0010, ADR 0015): a non-empty set of
the token-grantable Permissions, `user:read`, `user:write`, `group:read` and
`group:write`, and nothing else — every other Permission guards the application
chain, which no token reaches, and is refused on a token with `400`, as are an
empty list and a name that is no Permission.

- Users reads (`GET`, and `POST /Users/.search`) need `user:read`; Users
  `POST`/`PUT`/`PATCH`/`DELETE` need `user:write`; Groups likewise with
  `group:read` / `group:write`. Write does not imply read.
- A base `/.search` needs at least one read Permission and returns only the
  resource types the token may read — a `user:read`-only token gets Users and no
  Groups, as if the directory held none.
- Discovery (`ServiceProviderConfig`, `Schemas`, `ResourceTypes`) needs a valid
  token and no Permission, and `/Me` is `501` to any valid token. A path no
  endpoint serves needs only a valid token and is a `404`. So a stored token
  holding no Permission still authenticates and reaches discovery alone.

Each Users and Groups handler, and the base `/.search`, declares its Permission
with method security, as an application handler does (ADR 0010); the SCIM chain's
filter repeats the rule from the request's method and path as a backstop, so a
request line it misreads cannot open an operation the handler holds. A refusal is
`403` with `insufficient_scope` and no body, logged at `WARN` and audited as
`ACCESS_DENIED` naming the connector, the operation and the generic reason — never
the missing Permission.

**Who mints, and what a token is worth** — only a Superuser mints, so that gate
replaces the earlier rule that a token may carry only what its minter holds: there
is no further rule on what a Superuser may grant beyond the vocabulary. A token
with a write Permission is Superuser-equivalent, because `group:write` on a Group
that carries Permissions decides who holds them ([membership of
power](./authorization.md)) and `user:write` sets a User's
password. Neither write is a session's: the helpdesk's Unlock needs
`account:unlock`, which no token may carry. A read-only token is genuinely limited, which is why the token
vocabulary stays separate from the session's. A successful issue or rotation is
audited with the Permissions granted.

## Lifecycle

The Superuser may mint, overlap, rotate, and revoke tokens, and any holder of
`connector:read` may inspect their state; plaintext is disclosed once, on the issue
and rotation responses alone, under `Cache-Control: no-store`.

- **Lifetime.** A token expires at most 365 days after issue — RFC 7644 §7.4
  requires bearer tokens to have a limited lifetime the service provider can
  determine — and 365 days is both the default and the hard maximum: a shorter
  lifetime may be chosen, a longer one is refused rather than silently clamped.
- **Rotation** mints a replacement carrying the old token's Permissions, or the
  ones it is given, with a fresh full lifetime and brings the old token's expiry
  **forward** to the end of an overlap window of at most 14 days, never past the
  expiry the old token already had.
- **A token is rotated once**: only a live token with no replacement may be, so
  rotating one that is revoked, has expired or was already rotated is refused
  `409` — nothing minted, nothing written — and audited as a failed
  `CONNECTOR_TOKEN_ROTATE` with `TOKEN_REVOKED`, `TOKEN_ALREADY_ROTATED` or
  `TOKEN_EXPIRED` (read in that order). The token's row is locked before that is
  decided, so of two concurrent rotations of one token exactly one mints a live
  replacement and the other is refused as already rotated; a revocation takes the
  same lock and stays idempotent.
- Every token event names the token it concerns by its stable id beside the
  connector — a rotation the rotated token and its replacement — and never its
  value.
- An operation addressing a connector or token that names nothing — never
  existed, or was deleted, a token whose connector was deleted among them — is
  refused `404`, nothing written, and audited as that operation's failure
  (`CONNECTOR_DELETE`, `CONNECTOR_TOKEN_ISSUE`, `CONNECTOR_TOKEN_ROTATE` or
  `CONNECTOR_TOKEN_REVOKE`) with `UNKNOWN_RESOURCE`, naming the connector and
  the token addressed. A token request of a shape no token may have — a lifetime
  past the ceiling, a Permission no token may carry — is refused `400` and not
  audited: it addresses nothing to record a refusal against.

## Credential refusals

Revocation and expiry are immediate and indistinguishable to the connector. The
only credential refusals the interface makes are a bare `Bearer` challenge when no
credential was presented, `invalid_token` for a malformed, unknown, expired or
revoked one or one whose connector is deleted, and `insufficient_scope` for a valid
token lacking the request's Permission. The token is accepted from the
`Authorization` header — the one method RFC 6750 §2.1 requires — and from nowhere
else: a query string, a form body and a cookie are not rejected but never
consulted, because URLs are routinely logged. A token cannot reach `/api/**`: the
application chain does not read bearer credentials.

## Deleted SCIM connector

A connector removed by an Admin. Deletion revokes every token it holds and deletes
every one of its `externalId` aliases in a single transaction, and is not a row
removal: the connector record survives with a deletion timestamp so an audit event
that names it still resolves for as long as the trail is retained. Users and
Groups are untouched — an alias is a connector's name for a resource, not the
resource. A deleted connector stops being listed and stops authenticating at once,
and its `externalId` namespace becomes available again. Deleting one twice is
refused, because an Admin repeating a delete is likelier to have the wrong id than
to want a second no-op.

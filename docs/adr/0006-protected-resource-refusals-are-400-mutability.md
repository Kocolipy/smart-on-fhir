# 6. Protected-resource refusals are `400 mutability`, not `403`

Date: 2026-10-01

## Status

Accepted. Records a departure from the original SCIM design that spans Group CRUD
and the protected recovery resources, `DELETE`, and the conformance fixtures.

## Context

Two resources exist to keep a deployment recoverable: the Bootstrap Admin, and
the Admin group's Bootstrap membership. The original design said SCIM writes
against them return `403` with no `scimType`.

Group CRUD implemented the refusal as `400` with `scimType: mutability`, and
`DELETE` extended it. `ScimGroupProvisioningIntegrationTests` and
`backend/docs/openapi.yaml` pin that response, and the OpenAPI contract check
holds the document to the implementation.

## Decision

A SCIM write aimed at a reserved resource returns `400` with
`scimType: mutability`. This covers modifying or deleting the Bootstrap Admin,
renaming or deleting the Admin group, and removing the Bootstrap Admin's
membership. The detail says which kind of resource was protected, but not which
resource or why. `ScimExceptionHandler` maps `ProtectedResourceException` to it.

`mutability` is RFC 7644's error for an attempt to change something that cannot
be changed, and that is what happened. A `403` on a write is about the
credential. A connector that got one from a valid token would re-check the
Permissions its token carries, which is the wrong investigation. Here the refusal
concerns the target.

## Consequences

- A SCIM write gets `403` only as Bearer `insufficient_scope`: a token lacking the
  Permission the write needs (ADR 0010). The one other SCIM `403` is
  `unsupportedQuery`, a query capability the service does not offer, which RFC
  7644 §3.4.2.2 prescribes and which no write returns. So a `403` on a write means
  the credential, and a `400 mutability` always means the target.
- Connectors that treat `mutability` as non-retryable stop retrying, which is
  correct: the refusal never succeeds later.

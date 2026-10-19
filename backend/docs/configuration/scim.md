---
type: Configuration Reference
title: SCIM release gate
description: APP_SCIM_ENABLED — what turning the SCIM interface off does, and where its default lives.
tags: [scim, configuration]
---

# SCIM release gate

| Variable           | Default | Meaning                                   |
| ------------------ | ------- | ----------------------------------------- |
| `APP_SCIM_ENABLED` | `true`  | Whether this deployment serves `/scim/v2` |

**On by default.** Every path needs a connector token: the discovery documents
(`ServiceProviderConfig`, `ResourceTypes`, `Schemas`) take any valid one, and the
`Users` and `Groups` resource endpoints the token's Permissions (ADR 0010). Set
`APP_SCIM_ENABLED=false` to turn the interface off: the whole `/scim/v2`
namespace then answers `404` — discovery included, and ahead of
authentication, so a valid connector token gets the same answer as none at all.

The default belongs to `ScimSecurityConfig`, not `application.yaml`.

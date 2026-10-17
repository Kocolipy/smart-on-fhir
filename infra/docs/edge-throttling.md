---
type: Deployment Reference
title: Edge throttling
description: Which surfaces the deployment edge must rate-limit, keyed on what, and why per account rather than per source.
tags: [waf, throttling, security]
---

# Edge throttling (required)

The application has **no request-rate limiter**. Throttling is the deployment
edge's job, and a deployment that exposes the service without it is
misconfigured. In this stack the edge is the ALB: port 8080 admits only the ALB's
security group, so an AWS WAF web ACL associated with the ALB sees every request.

**`infrastructure.yaml` provisions no web ACL.** Attach one before the service
takes traffic.

## What must be throttled

| Surface                      | Path                             | Rate-based key                                   |
| ---------------------------- | -------------------------------- | ------------------------------------------------ |
| SCIM                         | `/scim/v2/` and everything below | the `Authorization` header (one connector token) |
| Login                        | `POST /api/auth/login`           | count-all, scoped down to the path               |
| Self-service password change | `POST /api/auth/change-password` | the `JSESSIONID` cookie (one session)            |
| Epic Login launch            | `GET /api/auth/epic/launch`      | count-all, scoped down to the path               |
| Epic Login callback          | `GET /api/auth/epic/callback`    | count-all, scoped down to the path               |

Also give `/scim/v2/` a count-all ceiling scoped down to the path, so random
bearer values cannot dodge the per-token rule by presenting a different key on
every request. Set the thresholds from the service's own telemetry (see
[Operational telemetry](operational-telemetry.md)) after a week of normal traffic.

## Why per account, and not per source

**Per-account throttling is the primary deterrent.** A brute-force or exhaustion
attempt is aimed at one credential, so the limit belongs on the credential:

- **SCIM:** each connector token is its own key. One misbehaving or stolen token
  is bounded without throttling every other connector.
- **Self-service change:** each session is its own key. A wrong current password
  also lengthens the User's failure run, so guessing there ends in the same
  lockout as Login.
- **Login:** AWS WAF rate-based rules key on headers, cookies, query arguments,
  path, method, IP and labels, but **not on the request body**, so the edge
  cannot count attempts per `userName`. The application does that half. A User's
  failure run locks it after 3 consecutive rejections, and only an Admin's
  Unlock lifts the lock. The Bootstrap Admin is the exception: its failures are
  counted and audited, but it never locks. The edge's Login rule therefore caps
  total cost. Every attempt pays an Argon2id verification, so unthrottled Login
  is a CPU-exhaustion lever. An edge that can key on a JSON body field may add a
  per-`userName` limit on top.
- **Epic Login** (ADR 0012, D19): the launch and the callback are browser
  navigations from Epic, made before there is any session or credential of ours
  to key on, so the edge's rule caps their total cost. The callback matters most:
  every callback makes an outbound token call to Epic, so an unthrottled callback
  would let anyone use this server to flood Epic. A breach is answered `429` with
  `Retry-After`, as for every other surface. The limits are edge configuration,
  not part of the application.

**Per-source (per-IP) throttling is not required and not assumed.** It is left
to the edge's own policy. Behind a corporate proxy or NAT gateway, many
legitimate Users share one address. A per-IP limit tight enough to deter
guessing would lock them out together, and one loose enough not to would deter
nothing. A coarse per-IP flood guard is fine as an addition, but nothing here
relies on it.

## Keep the keys out of WAF logs

The two key fields are credentials. If WAF logging is enabled, add `authorization`
and `cookie` to the logging configuration's `RedactedFields` (as `SingleHeader`).
Otherwise every logged request writes a bearer token or a session cookie to the
log destination.

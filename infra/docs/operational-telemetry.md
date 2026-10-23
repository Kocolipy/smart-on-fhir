---
type: Deployment Reference
title: Operational telemetry
description: The Prometheus scrape — who may read it, moving it off the internet, scraping with a session, and the alert rules.
tags: [prometheus, metrics, alerts]
---

# Operational telemetry

The service publishes Prometheus metrics at `/actuator/prometheus`: request
traffic, latency histograms and error class for every SCIM endpoint and for
Login, plus saturation gauges for the database pool (`hikaricp_connections_*`),
Tomcat's request threads (`tomcat_threads_*`), Redis commands (`lettuce_*`) and
the scheduled jobs (`app_job_*`). Requests are tagged by route template, method,
status and status class (`outcome`), SCIM resource type, SCIM `scimType` and
connector id, and never by `userName`, `externalId`, resource id, filter text or
token value.

## Who can read it

- **A session holding `ops:read` only.** Every actuator path except
  `/actuator/health` needs the `ops:read` Permission — put the Prometheus
  scraper's account in a Group carrying it and nothing else. A session without it
  gets `403`, a caller with no session `401`.
- **Never a connector token.** The SCIM bearer chain covers `/scim/v2/**` only, so
  `Authorization: Bearer <connector token>` on `/actuator/prometheus` is not a
  credential there and gets `401`.
- `/actuator/health` stays public: it is the ALB's health check.

## Keep it off the internet

In this stack the ALB forwards every path to port 8080, so the scrape is reachable
from the internet. It is behind `ops:read` there, but the metrics surface belongs on the
internal network. Move actuator to its own port, which only the VPC can reach:

```bash
# in the service's environment on the EC2 instance
MANAGEMENT_SERVER_PORT=9090
```

With that set, port 8080 stops serving `/actuator/**`, and port 9090 serves it
**still behind `ops:read`**. The port adds a network boundary; it does not
replace the access rule. Two things change with it:

- **The ALB health check moves too.** Set the target group's `HealthCheckPort` to
  `9090`, keep `HealthCheckPath: /actuator/health`, and allow 9090 from the ALB
  security group. Otherwise every target reports unhealthy.
- **Scrapers must be inside the VPC.** Allow 9090 in the EC2 security group from
  the monitoring host's security group only, never from `0.0.0.0/0`. Do not add a
  listener rule that forwards to 9090.

`infrastructure.yaml` does not apply any of this yet. Change it, and review it
against the live stack, before you rely on the internal port.

## Scraping

A scrape presents the session cookie of an account holding `ops:read` and
nothing more (`POST /api/auth/login` as that account,
then send the returned cookie). Sessions end after 15 minutes idle and 8 hours
absolute, so a long-running Prometheus has to log in again. No machine
credential for metrics exists yet.

With the internal port set, the login still goes to the application port, because
`/api/auth/login` is served only there. The same session cookie is then accepted on
port 9090. The session lives in Redis, and the management port reads it through the
application's own session filter (`ManagementSessionConfiguration`).

## Alerts

The alert rules are code: `backend/ops/prometheus/alerts.yaml`. Load them with
`rule_files:` in the Prometheus that scrapes the service. Each rule carries its
own runbook text:

| Alert                                         | Fires on                                                                         | Usually means                                                                                                                                        |
| --------------------------------------------- | -------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ScimAuthenticationFailuresSustained`         | sustained SCIM `401`                                                             | a connector's token expired or was revoked, or probing                                                                                               |
| `LoginAuthenticationFailuresSustained`        | sustained Login `401`                                                            | a guessing campaign spread across accounts                                                                                                           |
| `ScimPreconditionFailuresSustained`           | sustained SCIM `412`                                                             | writers colliding on a stale `If-Match` (writes without `If-Match` apply unconditionally; see the `scim:unconditional_writes:rate1h` recording rule) |
| `ScimUniquenessConflictsSustained`            | sustained SCIM `409`                                                             | a connector re-creating identities it believes are missing                                                                                           |
| `DormancyJobFailed` / `DormancyJobNotRunning` | the dormancy job throws, or has not succeeded for 26 h                           | dormant accounts are not being locked, nor their Roles revoked                                                                                       |
| `EpicJwksFetchFailing`                        | `epic.outbound{call="jwks"}` errors persisting 5 min                             | Epic's `id_token` keys are unreachable (timeouts or Epic `5xx`): launches land at the unavailable notice; password Login is unaffected               |
| `EpicEndpointUnavailable`                     | `epic.outbound{call="token"\|"discovery"}` errors persisting 5 min               | Epic's token or discovery endpoint is unreachable: launches land at the unavailable notice; password Login is unaffected                             |
| `EpicClientCredentialRefused`                 | any `epic.login.failed_calls{call="token",error_category="cert/auth"}` in 15 min | Epic refused our client credential (`invalid_client`, assertion rejected): a signing key or the registration is wrong                                |
| `EpicLoginRefusalsSurge`                      | `login{method="sso",outcome="refused"}` above 3/min for 10 min                   | a broken integration (`ISS_MISMATCH`), unprovisioned Practitioner IDs, or replayed launches; the `reason` label says which                           |

The thresholds are starting points. Tune them against a week of normal traffic.
The dormancy job publishes its series under `job="dormancy"` from startup.
With Epic Login on, so does the outbound client: `epic_outbound_seconds` (a timer)
and `epic_outbound_errors_total` (calls that got no answer or a `5xx`), each
tagged `call` = `discovery`, `jwks` or `token`, exist at zero from startup, so
`EpicJwksFetchFailing` — `increase(epic_outbound_errors_total{call="jwks"}[5m]) > 0`
held for 5 minutes — sees the first error. The JWKS is fetched only when an
`id_token` names a key the kept JWKS lacks, so the alert fires on real launches
failing, not on a background poll. `EpicEndpointUnavailable` reads the same series
for the two calls every launch makes. The Login meters, `login_total` (every
Login that ended, password and Epic alike, by `method` — `password` or `sso` —
`outcome` and `reason`) and `epic_login_failed_calls_total` (each failed Epic call
that ended a Login, by `call` and `error_category`), exist at zero from startup
whether or not Epic Login is on; `EpicLoginRefusalsSurge` reads
`login_total{method="sso",outcome="refused"}`. With Epic Login off the outbound series do not
exist and the outbound rules match nothing.
`DormancyJobNotRunning` measures from
the last success **or the last restart**, so an instance that restarts more often
than daily masks a stuck job. Alert on restarts separately if that happens.

`OperationalTelemetryIntegrationTests` checks every selector in the rule file
against a real scrape, so renaming a metric or tag fails the backend build instead
of silently disarming an alert.

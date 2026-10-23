---
type: Configuration Reference
title: Operational telemetry
description: The Prometheus scrape and its Permission, the management port, and the scheduled-job and dormancy metrics.
tags: [metrics, prometheus, configuration]
---

# Operational telemetry

| Variable                 | Default   | Meaning                                                |
| ------------------------ | --------- | ------------------------------------------------------ |
| `MANAGEMENT_SERVER_PORT` | the app's | Serve `/actuator/**` on this port instead of the app's |

`/actuator/prometheus` is the metrics scrape, and it requires the `ops:read`
Permission (give a scraper a Group carrying it alone): a session without it gets `403`,
and a connector token gets `401` because the SCIM bearer chain
does not cover `/actuator`. Setting `MANAGEMENT_SERVER_PORT` moves all of actuator
to that port under the same rules: `/actuator/health` stays public and every other
actuator path keeps requiring `ops:read`. The exposure and histogram settings live in `src/main/resources/telemetry.yaml`,
which the test configuration imports too. The tag policy (what a metric may be
labelled with) lives in `ScimRequestObservationConvention`. The alert rules are
`ops/prometheus/alerts.yaml`. Deployment steps, the internal-port setup and the
alert table are in `/infra/docs/operational-telemetry.md` under "Operational telemetry".

A scheduled job reports its runs through `ScheduledJobMetrics`
(`app_job_runs_total{job,outcome}`, `app_job_last_success_seconds{job}`). The audit
retention job is `job="audit-retention"` and the dormancy job `job="dormancy"` (which
the alert rules select on). Each run is also its own trace, so every record
a run emits carries one `trace.id`, and is timed as `app_job_run_seconds{job}`. The
dormancy job also counts what it changed: `app_dormancy_users_locked_total` and
`app_dormancy_users_memberships_revoked_total` (Users whose Group memberships were
revoked; `app_dormancy_users_roles_revoked_total` before #158, see
[Dormancy](dormancy.md)), registered at zero from startup, so a mass lockout or revocation is a
spike on an existing series. Authorization changes are counted on
`authorization_changes_total{operation}` (`AuthorizationChangeMetrics`), also at
zero from startup: `operation="superuser-grant"` counts a User gaining direct
membership of the Admin group, and `operation="group-permissions-change"` counts
the Superuser setting a Group's Permissions.

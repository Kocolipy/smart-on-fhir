---
type: Configuration Reference
title: Logging
description: Log variables, the ECS JSON record shape, trace ids, and the optional rolling log file.
tags: [logging, ecs, configuration]
---

# Logging

| Variable                | Default | Meaning                                                        |
| ----------------------- | ------- | -------------------------------------------------------------- |
| `LOG_FILE`              | (unset) | Also write ECS JSON to this file, rolling; unset means no file |
| `APP_ENVIRONMENT`       | `local` | `service.environment` on every record                          |
| `LOG_STRUCTURED_FORMAT` | `ecs`   | Console format; set empty for Boot's human-readable pattern    |

Records are ECS JSON on stdout. Each one carries `service.name` (`backend`),
`service.version` (the built project version), `service.environment`, and an
`@timestamp` in Singapore time (`2026-10-01T16:52:11.726+08:00`). Only the log is
in that zone: the JVM's default zone is not changed, so SCIM `meta` times and
audit times stay UTC (`...Z`). The scheduled jobs' cron expressions are evaluated
in the same `Asia/Singapore` zone, so a job's schedule and its records agree.

Every record emitted inside a request, or inside a scheduled-job run, carries
`trace.id` and `span.id`. The ids are minted here: tracing is on for correlation
only, nothing is exported and an inbound `traceparent` is ignored
(`src/main/resources/telemetry.yaml`).

With `LOG_FILE` set (deployed: `/var/log/backend/backend.json`, see
`/infra/README.md` under "View Logs") the same records are also written to that
file, one JSON object per line, rolled by date and size with a bounded total;
the rolling limits live in `src/main/resources/logging.yaml`. Stdout keeps
working. Local development and the tests leave it unset and write no file.

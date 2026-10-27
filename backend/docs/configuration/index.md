---
okf_version: "0.2"
---

# Configuration reference

How each area of the backend is configured, one file per area. The behavior the
settings govern is in `/docs/domain/`; this is what an operator sets and sees.

# Identity and sign-in

- [Group Permissions](group-permissions.md) - What a Group confers, stored per Group; deleting a Group; the startup validation of stored values; the local profile's development fixtures.
- [Epic Login](epic-login.md) - The APP_EPIC_* variables, startup validation, signing keys and the JWKS, and a real EHR launch locally against the local Epic stand-in, Medplum.
- [SCIM release gate](scim.md) - APP_SCIM_ENABLED, and what turning the SCIM interface off does.
- [Dormancy](dormancy.md) - The two dormancy windows, how the job is serialized and logged, and its startup run with the development fixtures.

# Storage

- [Database logins](database.md) - The three database logins and their grants, the connection pools, where migrations run, and the local SQL Server setup.
- [Session storage](sessions.md) - Why Spring Session's indexed Redis repository is required, what that means for a deployment's Redis, and where the session cookie is configured.
- [Audit trail](audit.md) - The retention period and schedule, what each run logs, and the pool fail-open audit appends commit on.

# Observability

- [Logging](logging.md) - Log variables, the ECS JSON record shape, trace ids, and the optional rolling log file.
- [Operational telemetry](telemetry.md) - The Prometheus scrape and its Permission, the management port, and the job and dormancy metrics.

---
okf_version: "0.2"
---

# Deployment reference

The reference behind `infra/README.md`'s runbook, one file per concern.

# Before taking traffic

- [Edge throttling](edge-throttling.md) - Which surfaces the deployment edge must rate-limit, keyed on what, and why per account rather than per source.

# Database

What the stack does for IM8 as-8, ck-2/ck-4, dp-3 and lm-16 on RDS for SQL Server (ADR 0013, "Compliance resolutions" and "Standards resolutions").

- [Database engine and cost](database-engine.md) - RDS for SQL Server 2022 Standard Edition — edition, instance classes, cost, collation and time zone.
- [Database credentials](database-credentials.md) - Every credential in Secrets Manager, which process reads which secret, and file ownership on the instance.
- [Boot, database bootstrap and migrate step](database-boot.md) - What UserData runs at boot, the idempotent bootstrap, and the migrate step that keeps the owner login out of the service.
- [Database encryption](database-encryption.md) - The customer-managed KMS key and TLS to the database, and how the smoke proves both.
- [Database monitoring](database-monitoring.md) - Enhanced Monitoring, Performance Insights, and the deadlock and blocked-session alarms.

# Operations

- [Post-deploy smoke](post-deploy-smoke.md) - What smoke.sh checks on a running stack, and the bootstrap hygiene that keeps secrets out of logs.
- [Operational telemetry](operational-telemetry.md) - The Prometheus scrape, who may read it, keeping it off the internet, and the alert rules.
- [Parameters reference](parameters.md) - Every CloudFormation parameter, how the Epic parameters reach the service, and the Epic outbound timeouts.
- [Epic signing-key promotion](epic-signing-keys.md) - Publishing, promoting and removing the Epic signing keys, and withdrawing a compromised one.

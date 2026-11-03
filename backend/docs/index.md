---
okf_version: "0.2"
---

# Backend reference

The backend's reference docs. The rules an agent must hold are in `../AGENTS.md`; the decisions are in `/docs/adr/`.

# Architecture and observability

- [Backend architecture](ARCHITECTURE.md) - Each structural boundary the backend keeps, with the ArchUnit test that holds it and why.
- [Backend log records](LOG_RECORDS.md) - Every operation's event actions, the shapes records are built in, what each ERROR attaches, and every record with its fields.

# Contracts

- [API document](openapi.yaml) - The API's authority on routes, statuses, bodies and each operation's security.
- [API contract check](api-contract-check.md) - How the test suite holds openapi.yaml to the running service in both directions.

# Testing and configuration

- [Backend testing guide](TESTING_GUIDE.md) - The Semgrep scan, full-context tests, fakes held to their adapters, and scoped PIT runs.
- [Configuration reference](configuration/index.md) - How each area of the backend is configured, one file per area.

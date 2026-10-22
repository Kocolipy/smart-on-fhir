---
type: Runbook
title: Post-deploy smoke
description: What smoke.sh checks on a running stack, step by step, and the bootstrap hygiene that keeps secrets out of logs.
tags: [smoke, verification, secrets]
---

# Post-deploy smoke

`./smoke.sh <stack> [region]` checks a running stack and exits non-zero on the
first failure. `deploy.sh` runs it after deploying the JAR; run it yourself after
any later deploy.

`./smoke.sh --leaks-only <stack> [region]` runs steps 0 and 6 alone, step 6 over the sources
that exist before the service does — the rendered UserData, the `cfn-init`
metadata and logs, the cloud-init logs, the env files and the database
bootstrap's journal — and needs no JAR. `deploy.sh` runs it when you decline the
JAR prompt, so every deploy scans the boot.

0. **Ownership:** `/opt/backend`, every file root runs, sources or reads there
   and the three systemd units are owned by root and not writable by
   `springboot`, and `springboot` owns nothing under `/opt/backend`
   ([File ownership on the instance](database-credentials.md#file-ownership-on-the-instance)). The JAR
   is checked when it is there.
1. **Health:** `/actuator/health` through the ALB answers `200`. **JVM trust:**
   the service's JVM keeps the JDK's default trust store, and that JDK fetches
   the identity provider's discovery document and JWKS over it
   ([TLS to the database](database-encryption.md#tls-to-the-database)).
2. **TLS:** see [TLS to the database](database-encryption.md#tls-to-the-database).
3. **SCIM:** as the Bootstrap Admin, it mints a one-day token on a throwaway
   connector, then over SCIM creates a User, finds it in the list, finds **no
   development fixture account** — none of the userNames
   `backend/src/main/resources/authorization.yaml` gives its fixtures
   (`app.dev-fixtures`), read from that file so that a fixture
   added there is checked too — deletes the User and confirms it is gone,
   then deletes the connector (revoking the token) and signs out. On a fresh
   stack the first sign-in must replace the seeded password: the smoke generates
   a new one, stores it in `AppBootstrapSecretArn` as `AWSPENDING`, changes it,
   then promotes it to `AWSCURRENT`. **After the first smoke the secret holds the
   Bootstrap Admin's current password** — read it from there. If the promotion
   fails after the change, the message names the pending version to promote by
   hand.
4. **Alarms:** both exist, notify `AlarmTopicArn` (it warns when nobody is
   subscribed yet), and read counters the instance offers.
5. **Owner and master logins:** the running service's environment holds no part
   of the owner or the master secret.
6. **Secret leaks:** every database password (the master's included), the
   trust store's password, the Bootstrap Admin's current and previous passwords
   and the Epic credentials are searched for in the **rendered UserData** (read
   back from EC2), the instance's **`cfn-init` metadata**, the instance's
   `/var/log/cloud-init-output.log`, `/var/log/cloud-init.log`,
   `/var/log/cfn-init.log` and `/var/log/cfn-init-cmd.log`, `/opt/backend/.env`,
   `migrate.env` and `db-bootstrap.env`, the bootstrap's, the migrate step's and
   the service's journals, and the service's log file. A match fails the deploy,
   naming the secret and the file, never printing the value.

**Bootstrap hygiene.** UserData runs with `set -euo pipefail` and never with
`set -x`, and holds no secret to begin with. `deploy.sh`, `db-bootstrap.sh`,
`smoke.sh`, and on the instance `db-bootstrap.sh`, `start.sh` and `migrate.sh`,
keep each secret in a shell variable: it reaches a process on a pipe, through a
process substitution or in that one process's environment — never as a
command-line argument, never in a file (the smoke's cookie jar, in a private
temporary directory removed on exit, is the one exception) — and is never
printed. Never run them with `bash -x`.

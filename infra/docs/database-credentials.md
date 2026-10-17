---
type: Deployment Reference
title: Database credentials
description: Every credential in Secrets Manager, which process reads which secret, and file ownership on the instance.
tags: [secrets-manager, credentials, security]
---

# Database credentials

**Every database credential is in AWS Secrets Manager.** None is a
CloudFormation parameter, none is in UserData or in a file on the instance, and
`deploy.sh` prompts for none.

| Secret (stack output)   | Holds                                        | Created by                                           | Read by                                                    |
| ----------------------- | -------------------------------------------- | ---------------------------------------------------- | ---------------------------------------------------------- |
| `DBMasterSecretArn`     | the RDS master user (`DBMasterUsername`)     | RDS (`ManageMasterUserPassword`)                     | the instance's database bootstrap only; `smoke.sh`         |
| `DBOwnerSecretArn`      | the owner login (`DBOwnerLogin`)             | CloudFormation, generated                            | the bootstrap, the migrate step; `smoke.sh`                |
| `DBAppSecretArn`        | the app login (`DBAppLogin`)                 | CloudFormation, generated                            | the bootstrap, the service; `smoke.sh`                     |
| `DBRetentionSecretArn`  | the retention login (`DBRetentionLogin`)     | CloudFormation, generated                            | the bootstrap, the service; `smoke.sh`                     |
| `DBTrustStoreSecretArn` | the driver-scoped trust store's password     | CloudFormation, generated                            | the boot (builds the store), the service, the migrate step |
| `AppBootstrapSecretArn` | the Bootstrap Admin (`AppBootstrapUsername`) | CloudFormation, generated                            | the service; `smoke.sh` replaces the first password        |
| `EpicClientSecretArn`   | the Epic client id and signing keys (JSON)   | CloudFormation, from the `AppEpicClient*` parameters | the service                                                |

Each generated password is 32 characters and excludes `'`, `"`, `\` and `;`.
Each secret is encrypted under the stack's [database key](database-encryption.md#database-key). There
is no rotation yet (#73).

**The instance role reads these seven secrets and nothing else** —
`GetSecretValue` on the seven ARNs, and `kms:Decrypt` on the database key through
Secrets Manager only. Within the instance the separation is **per process**:
each secret is read by one root-owned script into the environment of the one
process that needs it.

- `/opt/backend/db-bootstrap.sh` (root) reads the master secret and the three
  login secrets;
- `/opt/backend/migrate.sh` reads the owner secret and the trust-store password;
- `/opt/backend/start.sh` reads the app, retention, trust-store, Bootstrap Admin
  and Epic secrets — never the owner's or the master's.

## File ownership on the instance

**`/opt/backend` is `root:root 0755`, and the service user `springboot` owns
nothing in it.** Root runs or sources files there — `db-bootstrap.sh` (run by
`backend-db-bootstrap.service` as root), `db-bootstrap.env` (its
`EnvironmentFile`), `secret.sh` (sourced by `db-bootstrap.sh`, UserData and
`smoke.sh`'s on-instance checks), `start.sh` and `migrate.sh` — and whoever owns
a directory can rename or replace any file in it, whatever the file's own mode.
A `springboot`-owned directory would let the service user swap a file root runs,
take root, and read the RDS master secret with it.

| Path                       | Owner and mode         | Read by                                              |
| -------------------------- | ---------------------- | ---------------------------------------------------- |
| `/opt/backend/`            | `root:root 0755`       | everyone (the units' `WorkingDirectory`)             |
| `db-bootstrap.sh`          | `root:root 0700`       | root (the bootstrap unit)                            |
| `db-bootstrap.env`, `.sql` | `root:root 0600`       | root                                                 |
| `secret.sh`                | `root:root 0644`       | root; `migrate.sh` as `springboot`                   |
| `start.sh`, `migrate.sh`   | `root:root 0755`       | the service and migrate units, as `springboot`       |
| `.env`, `migrate.env`      | `root:root 0600`       | systemd, as root, before it starts a unit's process  |
| `backend.jar`              | `root:root 0644`       | the service and the migrate step, as `springboot`    |
| `rds-ca-bundle.pem`        | `root:root 0644`       | `sqlcmd` (root); the trust store is built from it    |
| `rds-truststore.p12`       | `root:springboot 0640` | the JDBC driver, in the service and the migrate step |

`springboot` writes nothing under `/opt/backend`: its only writable path is the
log directory, `/var/log/backend` (`springboot:springboot 0750`). The env files
are root's because systemd reads every `EnvironmentFile` as root before it drops
to the unit's `User=`, so the service user never opens them — and `smoke.sh`,
which reads `.env` as root, reads only what root wrote. `deploy.sh` installs the
JAR as `root:root 0644`. `smoke.sh` checks all of it (`check_file_ownership`):
every path above and the three unit files are owned by root and not writable by
`springboot`, and `springboot` owns nothing under `/opt/backend`.

**The master secret is now readable by the instance role.** The previous stack
kept it on the operator's machine and ran the bootstrap there, through an SSH
tunnel; this stack runs the bootstrap in UserData, as #71 specifies, so the
instance must read it. The role cannot tell processes apart, so code running as
any user on the instance — the service's JVM included, if it were compromised —
could fetch the master secret through the instance's credentials. Two things
bound that: the service's own process is never given it (`smoke.sh` checks its
`/proc/<pid>/environ`), and the master user's reach beyond what the owner secret
— which the role already had to read for the migrate step — gives is small: the
owner is `db_owner` of the only application database, and the master user adds
the server-level rights to create logins and databases. Blocking the service
user from the instance metadata service would close it; that is follow-up work.

**The service resolves its credentials at every start.** UserData writes
`/opt/backend/start.sh` (root-owned) and the systemd unit runs it. It reads the
secrets through the instance role, puts each value into the JVM's environment
and `exec`s Java: no value is ever written to disk, passed as a command-line
argument (each reaches `jq` on a pipe) or traced. `/opt/backend/.env` holds the
secrets' ARNs, never their values. A secret that cannot be read, or lacks its
password, stops the start before the JVM runs, and systemd retries.

**The service connects as the app and retention logins, never the owner.**
**The application's own secrets** are held the same way, so UserData carries no
secret at all: the Bootstrap Admin's first password (generated), and the Epic
client id and signing keys. The Epic values still enter through `NoEcho`
CloudFormation parameters, which the stack writes into `EpicClientSecretArn`;
they may contain neither `"` nor `\`. Redis has no password: the stack's
single-node `CacheCluster` cannot carry an AUTH token (only a replication group
can), so the old `RedisPassword` parameter, which CloudFormation rejected, is
gone; Redis is reachable only from the instance's security group.

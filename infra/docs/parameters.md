---
type: Deployment Reference
title: Parameters reference
description: Every CloudFormation parameter, how the Epic parameters reach the service, and the Epic outbound timeouts.
tags: [cloudformation, parameters, epic]
---

# Parameters Reference

| Parameter                  | Description                                     | Required | Default                 |
| -------------------------- | ----------------------------------------------- | -------- | ----------------------- |
| VpcId                      | Existing VPC ID                                 | Yes      | -                       |
| PublicSubnet1Id            | Public subnet 1                                 | Yes      | -                       |
| PublicSubnet2Id            | Public subnet 2                                 | Yes      | -                       |
| PrivateSubnet1Id           | Private subnet 1                                | Yes      | -                       |
| PrivateSubnet2Id           | Private subnet 2                                | Yes      | -                       |
| CreateKeyPair              | Create key pair                                 | No       | true                    |
| KeyName                    | Key pair name                                   | No       | spring-backend-key      |
| InstanceType               | EC2 type                                        | No       | t3.small                |
| SSHLocation                | CIDR allowed to SSH to the instance             | Yes      | - (your IP as a /32)    |
| DBInstanceClass            | SQL Server SE class (see "Engine, edition…")    | No       | db.t3.xlarge            |
| DBAllocatedStorage         | RDS storage, GB (20–100)                        | No       | 20                      |
| DBName                     | Database the bootstrap creates                  | No       | backend                 |
| DBMasterUsername           | RDS master username (password RDS-managed)      | No       | dbmaster                |
| DBOwnerLogin               | Owner login (`db_owner`, migrations)            | No       | backend_owner           |
| DBAppLogin                 | App login (member of `backend_app`)             | No       | backend_app_login       |
| DBRetentionLogin           | Retention login (`backend_audit_retention`)     | No       | backend_retention_login |
| KeyAdminArn                | Deploying role/user; administers the DB key     | Yes      | - (deploy.sh fills it)  |
| AlarmEmail                 | Email subscribed to the alarm topic             | No       | (empty)                 |
| RedisNodeType              | ElastiCache node type                           | No       | cache.t3.micro          |
| AppBootstrapUsername       | Bootstrap Admin's username (password generated) | No       | admin                   |
| AppEpicEnabled             | Epic Login switch (`APP_EPIC_ENABLED`)          | No       | false                   |
| AppEpicFhirBase            | Epic FHIR base URL, https                       | If Epic  | (empty)                 |
| AppEpicOauthIssuer         | Epic OIDC issuer, https                         | If Epic  | (empty)                 |
| AppEpicClientId            | Epic client id (NoEcho)                         | If Epic  | (empty)                 |
| AppEpicRedirectUri         | Registered callback URL, https                  | If Epic  | (empty)                 |
| AppEpicClientKey           | Active EC P-384 key, one-line PEM (NoEcho)      | If Epic  | (empty)                 |
| AppEpicClientKeyId         | Active key's `kid`                              | If Epic  | (empty)                 |
| AppEpicClientNextKey       | Next key, one-line PEM (NoEcho)                 | No       | (empty)                 |
| AppEpicClientNextKeyId     | Next key's `kid`, set with the next key         | No       | (empty)                 |
| AppEpicConnectTimeout      | Outbound Epic connect timeout                   | No       | (empty) = 2s            |
| AppEpicReadTimeout         | Outbound Epic read timeout                      | No       | (empty) = 5s            |
| AppEpicMfaEvidenceRequired | Require MFA evidence in `amr` (D17)             | No       | false                   |
| AppEnvironment             | `service.environment` on every log record       | No       | production              |
| LogRetentionDays           | Retention of the `/<stack>/backend` log group   | No       | 90                      |

The `AppEpic*` parameters become the `APP_EPIC_*` variables of
`backend/docs/configuration/epic-login.md`; with `AppEpicEnabled=false` none is
read. No default carries a value, and with the switch on the service refuses to
start until every required one is set and well formed. The two keys and the
client id are `NoEcho`, and the stack writes them into `EpicClientSecretArn`,
which `start.sh` reads at every start; they never reach UserData or a file on the
instance (see [Database credentials](database-credentials.md)). The other
`AppEpic*` values are not secret and are written into `/opt/backend/.env`. Give
each key as **one line with no line breaks** — the armour and the base64 run
together, e.g. `awk 'NF {printf "%s", $0}' key.pem` — because the stack splices
it into the secret's JSON, which a line break would break; for the same reason
none of the three may contain `"` or `\`. Never commit a key or a filled-in
parameters file.

## Epic outbound timeouts

`AppEpicConnectTimeout` and `AppEpicReadTimeout` become `APP_EPIC_CONNECT_TIMEOUT`
and `APP_EPIC_READ_TIMEOUT`: the connect and read timeouts of every call the
service makes to Epic — discovery, the JWKS and the token call, all through one
client (ADR 0012, D25). Each is a duration such as `2s` or `1500ms`; left empty
they are 2 and 5 seconds, and a zero or negative value refuses startup. A call
that times out, like one Epic answers `5xx`, sends the clinician to the login
page's "temporarily unavailable" notice rather than the refused one, and is an
`ERROR` record under `error.category` `network` (or `server`). The token call is
never retried, so a read timeout longer than Epic's own processing time is what
keeps a slow Epic from looking unavailable; raise it rather than lowering it if
clinicians see the notice while Epic is merely slow. There is no circuit breaker:
the [edge throttle](edge-throttling.md) on the callback bounds the load on Epic.

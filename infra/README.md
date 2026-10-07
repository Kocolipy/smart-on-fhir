# AWS CloudFormation Deployment Guide

Deploy Spring Boot backend to AWS ap-southeast-1 with ALB, EC2, RDS PostgreSQL, and Redis.

This directory is the repo-root `infra/` folder — a sibling of `backend/` and
`frontend/`, not part of either app. All commands below are run from `infra/`.

## Quick Start (5 Minutes)

```bash
cd infra

# 1. Get your VPC details
./get-vpc-info.sh vpc-YOUR_VPC_ID

# 2. Deploy everything
./deploy.sh
```

The script will prompt for VPC IDs, passwords, and deploy the entire stack.

---

## Architecture

```
Internet → ALB (HTTP:80) → EC2 (8080) → RDS PostgreSQL + Redis
           (public)         (public)     (private subnets)
```

**Components:**

- Application Load Balancer (internet-facing)
- EC2 instance (Amazon Linux 2023 + Java 25)
- RDS PostgreSQL (encrypted, automated backups)
- ElastiCache Redis (session storage)
- Security groups with proper isolation

---

## Edge throttling (required)

The application has **no request-rate limiter**. Throttling is the deployment
edge's job, and a deployment that exposes the service without it is
misconfigured. In this stack the edge is the ALB: port 8080 admits only the ALB's
security group, so an AWS WAF web ACL associated with the ALB sees every request.

**`infrastructure.yaml` provisions no web ACL.** Attach one before the service
takes traffic.

### What must be throttled

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
[Operational telemetry](#operational-telemetry)) after a week of normal traffic.

### Why per account, and not per source

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
- **Epic Login** (ADR 0013, D19): the launch and the callback are browser
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

### Keep the keys out of WAF logs

The two key fields are credentials. If WAF logging is enabled, add `authorization`
and `cookie` to the logging configuration's `RedactedFields` (as `SingleHeader`).
Otherwise every logged request writes a bearer token or a session cookie to the
log destination.

---

## Prerequisites

1. **Existing VPC in ap-southeast-1** with:
   - 2 public subnets (different AZs) for ALB
   - 2 private subnets (different AZs) for RDS/Redis
   - Internet Gateway attached

2. **AWS CLI** configured:

   ```bash
   aws configure
   # Set region: ap-southeast-1
   ```

3. **Application built** — the _integrated_ JAR (SPA + backend), from the repo
   root:

   ```bash
   make package     # == scripts/package.sh
   ```

   `./mvnw clean package` in `backend/` is a **backend-only** build: the
   `with-frontend` profile is off by default, so that JAR serves no SPA.
   `make package` builds `frontend/dist`, activates the profile with an explicit
   `-Dfrontend.dist.dir`, and fails if `BOOT-INF/classes/static/index.html` is
   missing from the artefact. `./deploy.sh` runs this for you when you answer yes
   to the JAR-deployment prompt.

---

## Deployment

### Option 1: Automated (Recommended)

```bash
./deploy.sh
```

The script prompts for the database-backed `ADMIN` seed credentials, the only
User a deployment is seeded with: `AppBootstrapUsername` /
`AppBootstrapPassword`. Existing accounts are never overwritten
on restart

### Option 2: Manual

```bash
# 1. Get VPC info
./get-vpc-info.sh vpc-xxxxx

# 2. Configure parameters
cp parameters.template.json parameters.json
# Edit parameters.json with your VPC IDs and passwords

# 3. Deploy stack
aws cloudformation create-stack \
  --stack-name spring-backend \
  --template-body file://infrastructure.yaml \
  --parameters file://parameters.json \
  --capabilities CAPABILITY_NAMED_IAM \
  --region ap-southeast-1

# 4. Wait for completion (15-20 minutes)
aws cloudformation wait stack-create-complete \
  --stack-name spring-backend \
  --region ap-southeast-1
```

---

## SSH Key Management

### CloudFormation Creates Key (Recommended)

Set in `parameters.json`:

```json
{
  "ParameterKey": "CreateKeyPair",
  "ParameterValue": "true"
}
```

**Retrieve private key after deployment:**

```bash
KEY_PAIR_ID=$(aws cloudformation describe-stacks \
  --stack-name spring-backend \
  --region ap-southeast-1 \
  --query 'Stacks[0].Outputs[?OutputKey==`KeyPairId`].OutputValue' \
  --output text)

aws ssm get-parameter \
  --name /ec2/keypair/${KEY_PAIR_ID} \
  --with-decryption \
  --query Parameter.Value \
  --output text > spring-backend-key.pem

chmod 400 spring-backend-key.pem
```

### Use Existing Key

Set in `parameters.json`:

```json
{
  "ParameterKey": "CreateKeyPair",
  "ParameterValue": "false"
},
{
  "ParameterKey": "KeyName",
  "ParameterValue": "my-existing-key"
}
```

---

## Deploy Application

```bash
# Get EC2 IP
EC2_IP=$(aws cloudformation describe-stacks \
  --stack-name spring-backend \
  --region ap-southeast-1 \
  --query 'Stacks[0].Outputs[?OutputKey==`EC2PublicIP`].OutputValue' \
  --output text)

# Copy JAR (paths assume you're in the infra/ directory)
scp -i spring-backend-key.pem \
  ../backend/target/backend-0.0.1-SNAPSHOT.jar \
  ec2-user@$EC2_IP:/tmp/backend.jar

# Start application
ssh -i spring-backend-key.pem ec2-user@$EC2_IP << 'ENDSSH'
sudo mv /tmp/backend.jar /opt/backend/backend.jar
sudo chown springboot:springboot /opt/backend/backend.jar
sudo systemctl start backend
sudo systemctl enable backend
ENDSSH
```

---

## Test Application

```bash
# Get ALB URL
ALB_URL=$(aws cloudformation describe-stacks \
  --stack-name spring-backend \
  --region ap-southeast-1 \
  --query 'Stacks[0].Outputs[?OutputKey==`LoadBalancerURL`].OutputValue' \
  --output text)

# Test health
curl $ALB_URL/actuator/health

# Every unsafe request, Login included, sends the session's CSRF token in the
# header GET /api/auth/csrf names. Login replaces the token, so fetch a fresh
# one before each unsafe request.
post() {
  local csrf; csrf=$(curl -s -b cookies.txt -c cookies.txt $ALB_URL/api/auth/csrf)
  curl -X POST "$ALB_URL$1" -b cookies.txt -c cookies.txt \
    -H "Content-Type: application/json" \
    -H "$(echo "$csrf" | jq -r .headerName): $(echo "$csrf" | jq -r .token)" \
    -d "$2"
}

# Login as the Bootstrap Admin. Its seeded password must be replaced first:
# until then the session reaches only the password change, which ends every
# session, so log in again with the new password.
post /api/auth/login '{"username":"admin","password":"YOUR_BOOTSTRAP_PASSWORD"}'
post /api/auth/change-password \
  '{"currentPassword":"YOUR_BOOTSTRAP_PASSWORD","newPassword":"A_NEW_PASSWORD"}'
post /api/auth/login '{"username":"admin","password":"A_NEW_PASSWORD"}'

# Test authenticated endpoint
curl $ALB_URL/api/count -b cookies.txt
```

---

## Operational telemetry

The service publishes Prometheus metrics at `/actuator/prometheus`: request
traffic, latency histograms and error class for every SCIM endpoint and for
Login, plus saturation gauges for the database pool (`hikaricp_connections_*`),
Tomcat's request threads (`tomcat_threads_*`), Redis commands (`lettuce_*`) and
the scheduled jobs (`app_job_*`). Requests are tagged by route template, method,
status and status class (`outcome`), SCIM resource type, SCIM `scimType` and
connector id, and never by `userName`, `externalId`, resource id, filter text or
token value.

### Who can read it

- **A session holding `ops:read` only.** Every actuator path except
  `/actuator/health` needs the `ops:read` Permission, which the Monitoring Role
  holds and nothing else — give the Prometheus scraper's account that Role alone. A
  session without it gets `403` (an Account admin's included), a caller with no
  session `401`.
- **Never a connector token.** The SCIM bearer chain covers `/scim/v2/**` only, so
  `Authorization: Bearer <connector token>` on `/actuator/prometheus` is not a
  credential there and gets `401`.
- `/actuator/health` stays public: it is the ALB's health check.

### Keep it off the internet

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

### Scraping

A scrape presents the session cookie of an account holding `ops:read` — the
Monitoring Role's, and nothing more (`POST /api/auth/login` as that account,
then send the returned cookie). Sessions end after 15 minutes idle and 8 hours
absolute, so a long-running Prometheus has to log in again. No machine
credential for metrics exists yet.

With the internal port set, the login still goes to the application port, because
`/api/auth/login` is served only there. The same session cookie is then accepted on
port 9090. The session lives in Redis, and the management port reads it through the
application's own session filter (`ManagementSessionConfiguration`).

### Alerts

The alert rules are code: `backend/ops/prometheus/alerts.yaml`. Load them with
`rule_files:` in the Prometheus that scrapes the service. Each rule carries its
own runbook text:

| Alert                                         | Fires on                                               | Usually means                                                                                                                                        |
| --------------------------------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ScimAuthenticationFailuresSustained`         | sustained SCIM `401`                                   | a connector's token expired or was revoked, or probing                                                                                               |
| `LoginAuthenticationFailuresSustained`        | sustained Login `401`                                  | a guessing campaign spread across accounts                                                                                                           |
| `ScimPreconditionFailuresSustained`           | sustained SCIM `412`                                   | writers colliding on a stale `If-Match` (writes without `If-Match` apply unconditionally; see the `scim:unconditional_writes:rate1h` recording rule) |
| `ScimUniquenessConflictsSustained`            | sustained SCIM `409`                                   | a connector re-creating identities it believes are missing                                                                                           |
| `DormancyJobFailed` / `DormancyJobNotRunning` | the dormancy job throws, or has not succeeded for 26 h | dormant accounts are not being locked, nor their Roles revoked                                                                                       |
| `EpicJwksFetchFailing`                        | `epic.outbound{call="jwks"}` errors persisting 5 min   | Epic's `id_token` keys are unreachable (timeouts or Epic `5xx`): launches land at the unavailable notice; password Login is unaffected               |

The thresholds are starting points. Tune them against a week of normal traffic.
The dormancy job publishes its series under `job="dormancy"` from startup.
With Epic Login on, so does the outbound client: `epic_outbound_seconds` (a timer)
and `epic_outbound_errors_total` (calls that got no answer or a `5xx`), each
tagged `call` = `discovery`, `jwks` or `token`, exist at zero from startup, so
`EpicJwksFetchFailing` — `increase(epic_outbound_errors_total{call="jwks"}[5m]) > 0`
held for 5 minutes — sees the first error. The JWKS is fetched only when an
`id_token` names a key the kept JWKS lacks, so the alert fires on real launches
failing, not on a background poll. With Epic Login off the series do not exist and
the rule matches nothing.
`DormancyJobNotRunning` measures from
the last success **or the last restart**, so an instance that restarts more often
than daily masks a stuck job. Alert on restarts separately if that happens.

`OperationalTelemetryIntegrationTests` checks every selector in the rule file
against a real scrape, so renaming a metric or tag fails the backend build instead
of silently disarming an alert.

---

## Parameters Reference

| Parameter              | Description                                   | Required | Default               |
| ---------------------- | --------------------------------------------- | -------- | --------------------- |
| VpcId                  | Existing VPC ID                               | Yes      | -                     |
| PublicSubnet1Id        | Public subnet 1                               | Yes      | -                     |
| PublicSubnet2Id        | Public subnet 2                               | Yes      | -                     |
| PrivateSubnet1Id       | Private subnet 1                              | Yes      | -                     |
| PrivateSubnet2Id       | Private subnet 2                              | Yes      | -                     |
| CreateKeyPair          | Create key pair                               | No       | true                  |
| KeyName                | Key pair name                                 | No       | spring-backend-key    |
| InstanceType           | EC2 type                                      | No       | t3.small              |
| SSHLocation            | CIDR allowed to SSH to the instance           | No       | 0.0.0.0/0 — narrow it |
| DBInstanceClass        | RDS instance type                             | No       | db.t3.micro           |
| DBAllocatedStorage     | RDS storage, GB (20–100)                      | No       | 20                    |
| DBName                 | PostgreSQL database name                      | No       | backend               |
| DBUsername             | PostgreSQL master username                    | No       | backend               |
| DBPassword             | Database password                             | Yes      | -                     |
| RedisNodeType          | ElastiCache node type                         | No       | cache.t3.micro        |
| RedisPassword          | Redis password                                | No       | (empty)               |
| AppBootstrapUsername   | Bootstrap Admin's username                    | No       | admin                 |
| AppBootstrapPassword   | Bootstrap Admin's password                    | Yes      | -                     |
| AppEpicEnabled         | Epic Login switch (`APP_EPIC_ENABLED`)        | No       | false                 |
| AppEpicFhirBase        | Epic FHIR base URL, https                     | If Epic  | (empty)               |
| AppEpicOauthIssuer     | Epic OIDC issuer, https                       | If Epic  | (empty)               |
| AppEpicClientId        | Epic client id (NoEcho)                       | If Epic  | (empty)               |
| AppEpicRedirectUri     | Registered callback URL, https                | If Epic  | (empty)               |
| AppEpicClientKey       | Active EC P-384 key, one-line PEM (NoEcho)    | If Epic  | (empty)               |
| AppEpicClientKeyId     | Active key's `kid`                            | If Epic  | (empty)               |
| AppEpicClientNextKey   | Next key, one-line PEM (NoEcho)               | No       | (empty)               |
| AppEpicClientNextKeyId | Next key's `kid`, set with the next key       | No       | (empty)               |
| AppEpicConnectTimeout  | Outbound Epic connect timeout                 | No       | (empty) = 2s          |
| AppEpicReadTimeout     | Outbound Epic read timeout                    | No       | (empty) = 5s          |
| AppEnvironment         | `service.environment` on every log record     | No       | production            |
| LogRetentionDays       | Retention of the `/<stack>/backend` log group | No       | 90                    |

The `AppEpic*` parameters become the `APP_EPIC_*` variables of
`backend/README.md`'s "Epic Login" section; with `AppEpicEnabled=false` none is
read. No default carries a value, and with the switch on the service refuses to
start until every required one is set and well formed. The two keys and the
client id are `NoEcho`, but they are still rendered into the instance's
`/opt/backend/.env` (mode `0600`), as the database and Bootstrap Admin passwords
are. Give each key as **one line with no line breaks** — the armour and the base64
run together, e.g.
`awk 'NF {printf "%s", $0}' key.pem` — because the environment file holds one
value per line. Never commit a key or a filled-in parameters file.

### Epic outbound timeouts

`AppEpicConnectTimeout` and `AppEpicReadTimeout` become `APP_EPIC_CONNECT_TIMEOUT`
and `APP_EPIC_READ_TIMEOUT`: the connect and read timeouts of every call the
service makes to Epic — discovery, the JWKS and the token call, all through one
client (ADR 0013, D25). Each is a duration such as `2s` or `1500ms`; left empty
they are 2 and 5 seconds, and a zero or negative value refuses startup. A call
that times out, like one Epic answers `5xx`, sends the clinician to the login
page's "temporarily unavailable" notice rather than the refused one, and is an
`ERROR` record under `error.category` `network` (or `server`). The token call is
never retried, so a read timeout longer than Epic's own processing time is what
keeps a slow Epic from looking unavailable; raise it rather than lowering it if
clinicians see the notice while Epic is merely slow. There is no circuit breaker:
the edge throttle on the callback (above) bounds the load on Epic.

### Signing-key promotion

The service signs Epic client assertions with the **active** key
(`AppEpicClientKey` / `AppEpicClientKeyId`) and publishes it, plus the optional
**next** key (`AppEpicClientNextKey` / `AppEpicClientNextKeyId`), at the public
`GET /api/auth/epic/jwks.json`, where Epic fetches it. The next key is published
but never signs. The service has no expiry or rotation logic of its own: a key is
published for exactly as long as configuration names it, and every step below is
an operator's redeploy (`/docs/epic-smart-login.md`, D14).

A redeploy here means changing the variables the service starts with and
restarting it. The stack's `UserData` writes `/opt/backend/.env` when the
instance is created, so set each value in **both** places: the stack parameters
(so a replacement instance gets them) and `/opt/backend/.env` on the running
instance, followed by `sudo systemctl restart backend`. Each restart's startup
record carries `app.epic.client_key_id` and `app.epic.client_next_key_id` (the
`kid`s, never the keys), so every step leaves a record in the log.

1. **Create the next key around mid-period**, under a new `kid` — a date makes a
   good one, e.g. `epic-2027-04`:

   ```bash
   openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384 -out next.pem
   awk 'NF {printf "%s", $0}' next.pem   # the one-line value for the parameter
   ```

2. **Publish it.** Set `AppEpicClientNextKey` and `AppEpicClientNextKeyId` and
   redeploy. Confirm both `kid`s are served, the active one first:

   ```bash
   curl -s https://<host>/api/auth/epic/jwks.json | jq -r '.keys[].kid'
   ```

   Leave it published long enough for Epic to have fetched the new document
   before the next step, since Epic may cache our JWKS.

3. **Promote it by redeploying with it as the active key**: move the next key's
   values into `AppEpicClientKey` / `AppEpicClientKeyId`, and put the old active
   key into `AppEpicClientNextKey` / `AppEpicClientNextKeyId`, so it stays
   published while assertions Epic may still be checking against it age out.
   From this restart every assertion carries the new `kid`.

4. **Remove the old key from configuration**: clear `AppEpicClientNextKey` and
   `AppEpicClientNextKeyId` (both together — the service refuses to start with
   one set and not the other) and redeploy. The JWKS then lists the active key
   alone. Destroy the old key's PEM.

To withdraw a compromised key, skip the waiting: promote the next key (or a
freshly created one) and remove the compromised key in a single redeploy.

The keys are environment variables for now; the target is an AWS KMS-held key
that never leaves KMS, which is open work (`/docs/adr/0013-epic-login.md`).

---

## Common Tasks

### View Logs

The service writes ECS JSON to stdout (journald) **and** to a rolling file,
`/var/log/backend/backend.json` (`LOG_FILE` in `/opt/backend/.env`; directory
owned by `springboot`, mode `0750`). The file rolls daily or at 50 MB, keeps 14
days and never exceeds 1 GB on disk. The CloudWatch agent on the instance ships
that file into the log group `/<stack name>/backend`, one stream per instance,
kept for `LogRetentionDays`. The agent's configuration is
`/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json`. The service
itself never sends logs over the network.

Every record carries `service.name` (`backend`), `service.version` (the built
version), `service.environment` (`AppEnvironment`), a `trace.id` / `span.id` for
the request or scheduled-job run that emitted it, and an `@timestamp` in
Singapore time (`+08:00`). Search CloudWatch Logs Insights by `trace.id` to read
one request end to end.

```bash
ssh -i spring-backend-key.pem ec2-user@$EC2_IP
sudo journalctl -u backend -f              # stdout
sudo tail -f /var/log/backend/backend.json # the file the agent ships
```

### Restart Application

```bash
ssh -i spring-backend-key.pem ec2-user@$EC2_IP "sudo systemctl restart backend"
```

### Update Application

```bash
# Build the integrated JAR (SPA + backend), from the repo root
(cd .. && make package)

# Confirm the SPA is in the artefact (package.sh already asserts this)
unzip -Z1 ../backend/target/backend-0.0.1-SNAPSHOT.jar BOOT-INF/classes/static/index.html

# Copy and restart (from infra/)
scp -i spring-backend-key.pem \
  ../backend/target/backend-0.0.1-SNAPSHOT.jar \
  ec2-user@$EC2_IP:/tmp/backend.jar

ssh -i spring-backend-key.pem ec2-user@$EC2_IP << 'ENDSSH'
sudo systemctl stop backend
sudo mv /tmp/backend.jar /opt/backend/backend.jar
sudo chown springboot:springboot /opt/backend/backend.jar
sudo systemctl start backend
ENDSSH
```

### Check ALB Target Health

```bash
TG_ARN=$(aws elbv2 describe-target-groups \
  --names spring-backend-TG \
  --region ap-southeast-1 \
  --query 'TargetGroups[0].TargetGroupArn' \
  --output text)

aws elbv2 describe-target-health \
  --target-group-arn $TG_ARN \
  --region ap-southeast-1
```

### Delete Stack

```bash
./cleanup.sh

# Or manually:
aws cloudformation delete-stack \
  --stack-name spring-backend \
  --region ap-southeast-1
```

### Inspect the Stack

```bash
aws cloudformation describe-stack-events --stack-name spring-backend \
  --region ap-southeast-1 --max-items 20

aws cloudformation describe-stacks --stack-name spring-backend \
  --region ap-southeast-1 --query 'Stacks[0].Outputs' --output table
```

---

## Troubleshooting

### Health Check Failing

Check application status:

```bash
ssh -i spring-backend-key.pem ec2-user@$EC2_IP
sudo systemctl status backend
sudo journalctl -u backend -n 100
```

Check target health:

```bash
aws elbv2 describe-target-health --target-group-arn $TG_ARN
```

**Common causes:**

- Application not running: `sudo systemctl start backend`
- Wrong health check path (should be `/actuator/health`)
- Security group blocking ALB → EC2:8080

### Can't Access via ALB

1. Check ALB DNS is correct
2. Verify security groups:
   - ALB SG allows 80 from internet
   - EC2 SG allows 8080 from ALB SG
3. Check target group shows "healthy"

### Database Connection Error

```bash
ssh -i spring-backend-key.pem ec2-user@$EC2_IP

# Check environment
sudo cat /opt/backend/.env | grep DATABASE

# Test connection
sudo dnf install -y postgresql15
psql -h RDS_ENDPOINT -U backend -d backend
```

### Can't Retrieve SSH Key

- Verify `CreateKeyPair: true` in parameters
- Key only retrievable once from SSM - store it safely
- If lost, deploy new stack or use existing key

---

`QUICKSTART.md` beside this file is the condensed, command-only walkthrough of
the same deployment.

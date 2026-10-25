---
type: Configuration Reference
title: Epic Login
description: The APP_EPIC_* variables, startup validation, signing keys and the JWKS, and running a real EHR launch locally against the local Epic stand-in.
tags: [epic, smart-on-fhir, configuration]
---

# Epic Login

Epic SMART on FHIR EHR launch Login (`/docs/adr/0012-epic-login.md`, the sole authority on it).
**Off by default**, and then nothing below is read or required: the app starts
with no Epic variable at all, and every `/api/auth/epic/**` path answers `404`
ahead of the session, CSRF and authorization checks, mirroring
`APP_SCIM_ENABLED=false`.

| Variable                         | Required when enabled | Default | Meaning                                                                   |
| -------------------------------- | --------------------- | ------- | ------------------------------------------------------------------------- |
| `APP_EPIC_ENABLED`               | —                     | `false` | The feature switch                                                        |
| `APP_EPIC_FHIR_BASE`             | yes                   | none    | The one allowed launch `iss`, sent as `aud`, and the prefix of `fhirUser` |
| `APP_EPIC_OAUTH_ISSUER`          | yes                   | none    | OIDC issuer, for discovery and the `id_token` `iss`                       |
| `APP_EPIC_CLIENT_ID`             | yes                   | none    | Epic client id                                                            |
| `APP_EPIC_REDIRECT_URI`          | yes                   | none    | Absolute callback URL as registered (path `/api/auth/epic/callback`)      |
| `APP_EPIC_CLIENT_KEY`            | yes                   | none    | Active signing key: EC P-384 private key, PKCS#8 PEM                      |
| `APP_EPIC_CLIENT_KEY_ID`         | yes                   | none    | The active key's `kid`                                                    |
| `APP_EPIC_CLIENT_NEXT_KEY`       | no                    | none    | Next key, published but never used to sign; set with its `kid`            |
| `APP_EPIC_CLIENT_NEXT_KEY_ID`    | no                    | none    | The next key's `kid`; must differ from the active one                     |
| `APP_EPIC_CONNECT_TIMEOUT`       | no                    | `2s`    | Connect timeout for every outbound Epic call                              |
| `APP_EPIC_READ_TIMEOUT`          | no                    | `5s`    | Read timeout for every outbound Epic call                                 |
| `APP_EPIC_MFA_EVIDENCE_REQUIRED` | no                    | `false` | Require MFA evidence in the `id_token`'s `amr` (D17); see below           |

`APP_EPIC_MFA_EVIDENCE_REQUIRED` stays off until Epic confirms it sends `amr` on
an EHR launch (ADR 0012, "Open items"). Off, the Epic organisation's MFA is an
attestation and every Epic `LOGIN_SUCCESS` records the factor `idp-attested`.
On, a token whose `amr` names no RFC 8176 second factor and not `mfa` is
refused as `INVALID_CLAIMS`, and the factor recorded is the one `amr` named;
ADR 0012 lists the factors accepted.

**No default credentials**: this repository is public, so no URL, client id or
key has a fallback anywhere. With the switch on, startup fails fast when a
required variable is missing or blank, when a URL is not an absolute `https`
URL (only the `dev` profile may use `http`, for the local stand-in), when a key is
not an EC P-384 private key in PKCS#8 PEM, when the next key and its `kid` are
not set together, when the two `kid`s are equal, or when a timeout is not
positive. An empty variable counts as unset. The error names the variable and
the rule, **never the value**, and the bound settings print with both keys
redacted. Startup does not contact Epic: discovery runs on the first launch and
is kept for 24 hours, so a wrong issuer or an unreachable Epic shows on the first
launch — as the login page's "temporarily unavailable" notice when Epic gives no
answer within the timeouts or answers `5xx` — never as a failed deploy (ADR 0012).

Generate a key with `openssl genpkey -algorithm EC -pkeyopt
ec_paramgen_curve:P-384`. The PEM may be given on one line, with or without its
line breaks escaped as `\n`. Never commit a key.

The startup record carries `app.epic.enabled` and, while it is on, the active
and next `kid` (`app.epic.client_key_id`, `app.epic.client_next_key_id`), so a
redeploy that promotes a key leaves a record. It never carries a URL, the
client id or key material.

With the switch on, `GET /api/auth/epic/jwks.json` is public and serves the
active key and then the next key, if one is set, as EC P-384 public keys for
ES384 under their `kid`s, with no private parameter. Epic verifies our client
assertions against it. Client assertions are signed by the active key only,
through `ClientAssertionSigner`. The runbook for promoting a key is
`/infra/docs/epic-signing-keys.md`, "Signing-key promotion".

```bash
curl -i localhost:8080/api/auth/epic/jwks.json
```

### Scopes, the FHIRcast hub and the CSP

Epic Login asks for exactly
`launch openid fhirUser fhircast/Patient-open.read fhircast/Patient-close.read`
(FHIRcast STU3 scope syntax; ADR 0016). Nothing configures it: the registration
at Epic must allow all five, or Epic narrows the grant and the session keeps no
FHIRcast hub. No `offline_access` or `online_access` is asked for, so Epic issues
no refresh token.

The token response's `hub.url` is kept only on host `127.0.0.1` (any port,
`http` or `https`) and `hub.topic` only within `[A-Za-z0-9\-._~]{1,128}`; a
deployment whose Epic names its hub anywhere else gets "FHIRcast unavailable" on
Home, with no log line naming either value. `GET /api/auth/epic/fhircast` then
releases the session's access token to its SPA, and is answered `404` with the
rest of the namespace while the switch is off.

The CSP (`SecurityConfig`) carries the hub's loopback entries in `connect-src`
whatever the switch says: `'self' http://127.0.0.1:* https://127.0.0.1:*
ws://127.0.0.1:* wss://127.0.0.1:*`, and no Epic origin — the browser never
calls Epic. A managed Chrome needs the application's origin allowed for Local
Network Access, through the `LocalNetworkAccessAllowedForUrls` policy, or each
clinician answers the browser's prompt once
(`/frontend/docs/BACKEND_CONTRACT.md`).

## Local Epic stand-in

A real SMART EHR launch, locally and without Epic, and a stand-in for Epic's
FHIRcast hub (Subspace), which runs on the clinician's own machine, so its
`hub.url` is a loopback address; the stand-in is built to look the same. It is
the one local stand-in for Epic, behind the `fhircast` compose profile, so
`make infra-up` and a plain `docker compose up` never start it. From the
repository root:

```bash
make fhircast-up     # SQL Server, Redis and the stand-in; seeds Medplum, sets up backend/.env, prints the launch URL
make dev             # the app, which reads that .env
make fhircast-publish EVENT=Patient-open PATIENT=<id>   # an event on the clinician's topic, as the EHR sends one
make fhircast-down   # stops them all; the volumes, Medplum's database among them, are kept
```

Then open the URL `make fhircast-up` printed and sign in on Medplum's page as
the clinician it names; "Launching from the stand-in" below has what happens
next and what is set up for you.

`make fhircast-publish` plays the EHR's part once the FHIRcast card reads
`Connected`: it publishes `EVENT` (`Patient-open` or `Patient-close`) for the
Patient whose FHIR id is `PATIENT` (`scripts/fhircast-publish.sh`). It takes the
topic from Medplum's token response for the seeded clinician, signed in with the
`fhircast/` scopes, so it is the topic the launch subscribed on, and posts as
Medplum's super admin, as the shim subscribes. `TOPIC=<topic>` publishes on
another, such as the `hubTopic` `GET /api/auth/epic/fhircast` releases to a
session signed in as another clinician.

Two parts, both on `127.0.0.1` alone:

- **Medplum** (`medplum/medplum-server`, self-hosted, with its own Postgres and
  Redis, apart from the app's) is both the SMART EHR launcher and the FHIRcast
  STU3 websocket hub. When a `fhircast/` scope is granted, its token response
  carries `hub.url` (`http://127.0.0.1:8103/fhircast/STU3`) and `hub.topic`.
- **The DCR shim** (`backend/fhircast-shim`, a small Node service) plays the
  hub's authorization role, which Medplum lacks. Medplum names `hub.url` from
  its own base URL, and the hub's metadata must be under `hub.url`, so the shim
  fronts Medplum: Medplum's base URL is the shim's port, and the shim passes
  everything that is not its own through to Medplum, websockets included.

| Port             | What answers                                                                                                |
| ---------------- | ----------------------------------------------------------------------------------------------------------- |
| `127.0.0.1:8103` | Medplum's base URL, through the shim: its OIDC issuer, FHIR base (`/fhir/R4`), hub, and the shim's `/dcr/*` |
| `127.0.0.1:3103` | Medplum's web app: the sign-in page Medplum's authorize sends the browser to                                |

The shim's part, under that base URL:

| Endpoint                                                | Does                                                                                                                                                                                              |
| ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /fhircast/STU3/.well-known/fhircast-configuration` | Medplum's hub metadata, plus `registration_endpoint`, `token_endpoint` and `revocation_endpoint`                                                                                                  |
| `POST /dcr/register`                                    | RFC 7591: with a SMART access token Medplum's introspection calls active as `Bearer`, stores the one public JWK posted in `jwks` and answers a new `client_id`                                    |
| `POST /dcr/token`                                       | the `urn:ietf:params:oauth:grant-type:jwt-bearer` grant: an `assertion` signed by that key, `iss` = `sub` = `client_id`, `aud` this URL, an unexpired `exp` and an unused `jti`, gets a hub token |
| `POST /dcr/revoke`                                      | RFC 7009 revocation of a hub token                                                                                                                                                                |
| `DELETE /dcr/register/{client_id}`                      | RFC 7592 deletion, with the registration's `registration_access_token`; its tokens stop working and it gets no more                                                                               |
| `GET /dcr/test/state`                                   | test-only: the registrations, and the tokens revoked or expired and the channel endpoints whose lease has ended, for E2E assertions                                                               |
| `POST /dcr/test/drop?endpoint={id}`                     | test-only: closes every socket open at the channel endpoint `/ws/fhircast/{id}`, as a network fault would, for E2E's dropped-socket case                                                          |

The hub accepts a hub token on its own paths (`/fhircast/`) through the shim,
which exchanges it for Medplum's super admin token while it is live, and
refuses it once revoked, expired or its registration deleted. A hub token
lasts an hour; `FHIRCAST_HUB_TOKEN_LIFETIME_SECONDS` sets another lifetime,
and `make integration-test` sets 75. The shim also holds each subscription to
a lease, which Medplum names in its confirmation but never enforces on an open
socket: the lease starts with the subscription's first socket, the
confirmation's `hub.lease_seconds` states what is left of it, its sockets are
closed when it runs out, and Medplum denies its channel endpoint from then on.
A lease lasts an hour, as Medplum grants; `FHIRCAST_LEASE_SECONDS` sets another,
and `make integration-test` sets 75. Medplum keeps a FHIRcast topic per Medplum
project, and the shim subscribes as the super admin, so whoever publishes to a
topic the app subscribes to publishes as the super admin too.

`make fhircast-up` runs the one-shot `medplum-init` after every service is
healthy, as `make infra-up` runs `sqlserver-init`. It seeds Medplum
idempotently (`backend/fhircast-shim/src/seed.mjs`): a project for the local
EHR; a clinician, `clinician@example.com`, as a Practitioner with a Medplum
sign-in at the Practitioner id `SEED_CLINICIAN_ID` gives it, and one more for
each Epic Login E2E case that signs in at Medplum, each at the Practitioner id
`SEED_EPIC_LOGIN_CLINICIANS` gives it and the spec names; Tan Wei Ming, one of
the dev profile's patient fixtures, as a Patient carrying his Epic id `eq081-VQEgP8drUUqCWzHfw3`;
a `SmartAppLaunch` for him (see "Launching from the stand-in");
and this app's ClientApplication — `private_key_jwt` against our JWKS
(`http://host.docker.internal:8080/api/auth/epic/jwks.json`, as Medplum's
container reaches the backend), PKCE, and the redirect and launch URIs of
`make dev`. It also gives Medplum an RS256 signing key, generated then and
never written anywhere else: Medplum creates an ES384 one on first start, and
the backend verifies an `id_token` as RS256 only (ADR 0012), so the first run
on a fresh volume restarts Medplum to load it. Medplum takes an id a client
chooses only when that is a UUID, so neither an Epic id nor an `e2e-` name can
be one: the clinicians' fixed ids are UUIDs, and Medplum assigns every other
id, which the seed finds again on every run, so the ids hold from one run to
the next. The ClientApplication's id is the client id, which comes from the
seed, and `make fhircast-up` writes it to `.env` itself.

A Medplum volume seeded before the clinicians' ids were fixed holds each
sign-in under a Practitioner Medplum assigned, and the seed then fails on
Medplum's "User is already a member of this project". Remove the volume and
seed afresh: `make fhircast-down`, then
`docker volume rm backend_medplum-postgres-data`, then `make fhircast-up`. SQL
Server's and Redis's volumes are left as they are. Every sign-in is a
development fixture in `compose.yaml`; the super admin is Medplum's own
published default.

Medplum is plain `http`, so the backend must run in the **`dev` profile**
(beside `local`, which runs the migrations), the only one that accepts `http`
Epic URLs (D21). That profile alone also accepts Medplum's relative `fhirUser`
(`Practitioner/{id}`); outside it, `fhirUser` must be the absolute
`{APP_EPIC_FHIR_BASE}/Practitioner/{id}`. Never use either allowance anywhere
but a developer machine.

### What `make fhircast-up` sets in `.env`

`make fhircast-up` keeps a managed block in `backend/.env`, between
`# >>> fhircast-up (managed) >>>` and `# <<< fhircast-up <<<`: created when
`.env` has none, rewritten in place when it has. It stops first, before any
container starts, if `backend/.env` does not exist: copy `backend/.env.example`
to it, as `make infra-up` also needs. The block holds:

| Variable                          | Value for the stand-in                                                 |
| --------------------------------- | ---------------------------------------------------------------------- |
| `SPRING_PROFILES_ACTIVE`          | `local,dev`                                                            |
| `APP_EPIC_ENABLED`                | `true`                                                                 |
| `APP_EPIC_FHIR_BASE`              | `http://127.0.0.1:8103/fhir/R4`                                        |
| `APP_EPIC_OAUTH_ISSUER`           | `http://127.0.0.1:8103`, without the trailing slash                    |
| `APP_EPIC_CLIENT_ID`              | the ClientApplication's id, from the seed                              |
| `APP_EPIC_REDIRECT_URI`           | `http://localhost:5173/api/auth/epic/callback`                         |
| `APP_EPIC_CLIENT_KEY`             | an EC P-384 key, generated on the first run and never again            |
| `APP_EPIC_CLIENT_KEY_ID`          | a `kid` made with it, `local-` and a timestamp                         |
| `APP_DEV_FIXTURES_ENABLED`        | `true`                                                                 |
| `APP_DEV_FIXTURES_EPIC_CLINICIAN` | the clinician's Practitioner id, `SEED_CLINICIAN_ID` in `compose.yaml` |

Every value but the key is rewritten on each run from `compose.yaml` and the
seed, so the block follows them. The key stays while the block holds one, so a
second run leaves it as it is; remove its two lines from the block (or the
whole block) to have another generated. It is one line, in single quotes, with
its line breaks written as `\n`, which both bash (`make dev` sources `.env`) and
the backend read. It is a private key: `.env` is git-ignored, and it stays there.

Any other line of `.env`, above or below the block, that sets one of these
variables is commented out, with a note that the block sets it now, so the block
alone decides what the backend reads; every other line is left as it was, byte
for byte. `.env.example` sets `SPRING_PROFILES_ACTIVE`, `APP_DEV_FIXTURES_ENABLED`
and `APP_EPIC_ENABLED` itself, so on a copy of it the first run comments those
out. `.env` is read when the backend starts, so a run that changed anything in
the file says to restart the backend; a run that changed nothing does not.
`make fhircast-down` leaves the block where it is. The issuer is Medplum's base
URL without its slash because the backend discovers it at
`{APP_EPIC_OAUTH_ISSUER}/.well-known/openid-configuration` and holds the
document's `issuer` and the `id_token`'s `iss` to it exactly; Medplum is
configured to issue that same string. The FHIR base is the `iss` the launch
names, and Medplum accepts it as the `aud` the backend sends. The redirect URI
goes through the Vite dev server (`make dev`), so the callback's `302 /` lands
on the SPA.

### Launching from the stand-in

Three steps, with nothing pasted, provisioned or created by hand:

1. `make fhircast-up`.
2. `make dev`. `make fhircast-up` does not start the app; if Vite (`:5173`) or
   the backend (`:8080`) does not answer when it finishes, it warns and says so,
   and still succeeds.
3. Open the launch URL it printed,
   `http://localhost:5173/api/auth/epic/launch?iss=http://127.0.0.1:8103/fhir/R4&launch={SmartAppLaunch id}`,
   sign in on Medplum's page as `clinician@example.com` with the password
   `compose.yaml` seeds (`fhircast-stand-in-clinician`), and approve the scopes.

The launch lands on Home (`/`), signed in as the clinician with Tan Wei Ming in
the Patient card, and Home's FHIRcast card connects to Medplum's hub through the
shim and reads `Connected`. What the seed and the dev fixtures did for it:

- **The `SmartAppLaunch`** is the seed's (step 6 of `seed.mjs`). Its `patient`
  references the seeded Patient, and its `patient.identifier.value`
  (`eq081-VQEgP8drUUqCWzHfw3`) is what Medplum's token response names as
  `patient`, ahead of the reference's Medplum id; a launch with no `patient`
  makes the token response name none. Medplum does not consume a launch on use:
  signing in through one a second time, in a fresh browser, lands on Home the
  same way. The seed therefore makes the launch once, finds it by a tag on every
  run after, and the URL never changes, so it can be bookmarked. The tag is what
  tells it from the launches the E2E specs make for the same Patient. A
  Medplum volume removed takes the launch with it, and the next run prints a new URL.
- **The clinician's User** is the dev fixtures' `epic-clinician` (below), which
  `APP_DEV_FIXTURES_EPIC_CLINICIAN` sets from the block: a User whose `userName`
  is the Practitioner id, in no Group, holding the baseline Permissions alone
  (which include `patient:read`, enough for Home and the Patient card). It is no
  administrator, and a member of none of the fixture Groups. Its password is the
  fixtures' (`APP_DEV_FIXTURES_PASSWORD`), which an Epic Login never presents;
  an Epic Login lands on Home (`/`) whether or not a password change is pending
  (ADR 0008).

`app.dev-fixtures.epic-clinician` (`APP_DEV_FIXTURES_EPIC_CLINICIAN`) follows the
rules every development fixture does: nothing is seeded unless the fixtures are
enabled (`APP_DEV_FIXTURES_ENABLED`), startup fails outside the `local` profile
when they are, and with the variable unset or blank no such User is seeded. The
User is created when absent and never overwritten. Its `userName` must equal the
Practitioner id character for character, because that is how Epic Login links a
clinician (`docs/domain/epic-login.md`, "Linking by `userName`"); `compose.yaml`
is the one place the id is chosen, which is why the backend takes it from `.env`
rather than carrying a copy. The other seeded clinicians, made for the E2E
specs, sign in with the same password but have no User: one is provisioned by
the spec that signs it in, or by hand over SCIM. Medplum always names its hub on
`127.0.0.1` with a well-formed topic, so it cannot show a dropped hub; the drop
rules are covered by the backend's tests against FakeEpic.

`make integration-test` runs the same launch as Playwright specs, part of the
E2E gate (`frontend/AGENTS.md`): the `epic` project drives Epic Login through
it, as the seeded clinician of each case, and the `fhircast` project
the seeded clinician's launch, the hub, and the FHIRcast card
(`fhircast-card.spec.ts`, as a clinician seeded for it alone). It generates its
own key, sets every `APP_EPIC_*` value itself, and runs the shim's own unit
tests first. It also unsets `APP_DEV_FIXTURES_EPIC_CLINICIAN` after loading
`.env`, whatever the block says: the specs' backend seeds no Epic clinician, so
`epic-unprovisioned` stays unprovisioned and the `fhircast` project provisions
its own.

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

## Local Epic stand-in

A real SMART EHR launch, locally and without Epic, and a stand-in for Epic's
FHIRcast hub (Subspace), which runs on the clinician's own machine, so its
`hub.url` is a loopback address; the stand-in is built to look the same. It is
the one local stand-in for Epic, behind the `fhircast` compose profile, so
`make infra-up` and a plain `docker compose up` never start it. From the
repository root:

```bash
make fhircast-up     # SQL Server, Redis and the stand-in; seeds Medplum and prints the values below
make fhircast-down   # stops them all; the volumes, Medplum's database among them, are kept
```

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
| `GET /dcr/test/state`                                   | test-only: the registrations and the revoked tokens, for E2E assertions                                                                                                                           |

The hub accepts a hub token on its own paths (`/fhircast/`) through the shim,
which exchanges it for Medplum's super admin token while it is live, and
refuses it once revoked, expired or its registration deleted. A hub token
lasts an hour; `FHIRCAST_HUB_TOKEN_LIFETIME_SECONDS` sets another lifetime,
and `make integration-test` sets 60. Medplum keeps a FHIRcast topic per Medplum
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
the next. `make fhircast-up` prints the ClientApplication's id — the client id,
which comes from the seed — and the clinician's Practitioner id.

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
but a developer machine. Run the backend with these in place of the Epic lines
in `.env`:

| Variable                 | Value for the stand-in                              |
| ------------------------ | --------------------------------------------------- |
| `SPRING_PROFILES_ACTIVE` | `local,dev`                                         |
| `APP_EPIC_ENABLED`       | `true`                                              |
| `APP_EPIC_FHIR_BASE`     | `http://127.0.0.1:8103/fhir/R4`                     |
| `APP_EPIC_OAUTH_ISSUER`  | `http://127.0.0.1:8103`, without the trailing slash |
| `APP_EPIC_CLIENT_ID`     | the client id `make fhircast-up` prints             |
| `APP_EPIC_REDIRECT_URI`  | `http://localhost:5173/api/auth/epic/callback`      |
| `APP_EPIC_CLIENT_KEY`    | a key generated for the purpose (see above)         |
| `APP_EPIC_CLIENT_KEY_ID` | any `kid`, e.g. `local-1`                           |

The issuer is Medplum's base URL without its slash because the backend
discovers it at `{APP_EPIC_OAUTH_ISSUER}/.well-known/openid-configuration` and
holds the document's `issuer` and the `id_token`'s `iss` to it exactly; Medplum
is configured to issue that same string. The FHIR base is the `iss` the launch
names, and Medplum accepts it as the `aud` the backend sends. The redirect URI
goes through the Vite dev server (`make dev`), so the callback's `302 /` lands
on the SPA. Generate the key per machine and keep it out of the repository,
e.g. `export APP_EPIC_CLIENT_KEY="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384)"`.

To launch by hand, provision a User over SCIM whose `userName` is the
clinician's Practitioner id. A connector's write leaves a password change
pending, which confines the User's password Login to `/change-password` but not
its Epic Login: an Epic Login presents no password of ours, so it lands on Home
(`/`) whether or not the change is pending (ADR 0008). Create a `SmartAppLaunch`
in Medplum — its `patient` references the seeded Patient, and its
`patient.identifier.value` (`eq081-VQEgP8drUUqCWzHfw3` for Tan Wei Ming) is what
the token response names as `patient`, ahead of the reference's Medplum id;
with no `patient`, the token response names none — and open
`http://localhost:5173/api/auth/epic/launch?iss=http://127.0.0.1:8103/fhir/R4&launch={SmartAppLaunch id}`.
Sign in on Medplum's page as the clinician, with the password `compose.yaml`
seeds, and grant the scopes; the launch lands on Home (`/`) signed in as that
User, with its Groups' Permissions. The other seeded clinicians sign in with
the same password.

`make integration-test` runs the same launch as Playwright specs, part of the
E2E gate (`frontend/AGENTS.md`): the `epic` project drives Epic Login through
it, as the seeded clinician of each case, and the `fhircast` project
the seeded clinician's launch and the hub. It generates its own key, sets all
of the above itself, and runs the shim's own unit tests first.

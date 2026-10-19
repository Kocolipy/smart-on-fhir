---
type: Configuration Reference
title: Epic Login
description: The APP_EPIC_* variables, startup validation, signing keys and the JWKS, and running a real EHR launch locally against the SMART launcher.
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
URL (only the `dev` profile may use `http`, for a local launcher), when a key is
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

## Local Epic launcher

A real SMART EHR launch, locally and without Epic: the SMART Health IT launcher
(`smartonfhir/smart-launcher-2`) stands in for Epic as the `smart-launcher`
service in `compose.yaml`. It is behind the `epic-launcher` compose profile, so
`make infra-up` and a plain `docker compose up` never start it. From the
repository root:

```bash
make epic-launcher-up     # SQL Server, Redis and the launcher on http://localhost:9009
make epic-launcher-down   # stops all three; `make infra-down` leaves the launcher up
```

The launcher is plain `http`, so the backend must run in the **`dev` profile**
(beside `local`, which runs the migrations),
the only one that accepts `http` Epic URLs (D21). That profile alone also
accepts the launcher's relative `fhirUser` (`Practitioner/{id}`); outside it,
`fhirUser` must be the absolute `{APP_EPIC_FHIR_BASE}/Practitioner/{id}`. Never
use either allowance anywhere but a developer machine. Run the backend with
these in place of the Epic lines in `.env`:

| Variable                 | Value for the launcher                           |
| ------------------------ | ------------------------------------------------ |
| `SPRING_PROFILES_ACTIVE` | `local,dev`                                      |
| `APP_EPIC_ENABLED`       | `true`                                           |
| `APP_EPIC_FHIR_BASE`     | `http://localhost:9009/v/r4/fhir`                |
| `APP_EPIC_OAUTH_ISSUER`  | `http://localhost:9009/v/r4/fhir` (the same URL) |
| `APP_EPIC_CLIENT_ID`     | any value; the launcher accepts any client id    |
| `APP_EPIC_REDIRECT_URI`  | `http://localhost:5173/api/auth/epic/callback`   |
| `APP_EPIC_CLIENT_KEY`    | a key generated for the purpose (see above)      |
| `APP_EPIC_CLIENT_KEY_ID` | any `kid`, e.g. `local-1`                        |

The FHIR base is the `iss` the launcher sends on a provider EHR launch, and its
discovery document names that same URL as the issuer. The redirect URI goes
through the Vite dev server (`make dev`), so the callback's `302 /` lands on
the SPA. Generate the key per machine and keep it out of the repository, e.g.
`export APP_EPIC_CLIENT_KEY="$(openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384)"`.

To launch by hand, provision a User over SCIM whose `userName` is the
Practitioner ID you will launch as. A connector's write leaves a password change
pending, which confines the User's password Login to `/change-password` but not
its Epic Login: an Epic Login presents no password of ours, so it lands on
Home (`/`) whether or not the change is pending (ADR 0008's 2026-10-09
addendum). Then, in the launcher UI at
`http://localhost:9009`:

- **Launch type** `Provider EHR Launch`; pick a patient, and set the provider to
  that Practitioner ID. Turn on "Skip login" and "Skip authorization".
- **Client** `Confidential asymmetric`, with the JWKS URL
  `http://host.docker.internal:8080/api/auth/epic/jwks.json`: the launcher
  verifies our client assertion against it, from inside its container.
- **App launch URL** `http://localhost:5173/api/auth/epic/launch`.

Launching lands on Home (`/`), signed in as that User with its Groups'
Permissions. `make integration-test` runs the same launch as a Playwright
spec, part of the E2E gate (`frontend/AGENTS.md`); it generates its own key and
sets all of the above itself.

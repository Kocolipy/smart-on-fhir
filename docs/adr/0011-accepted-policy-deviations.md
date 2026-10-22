# 11. Accepted policy deviations and deliberate scope limits

Date: 2026-10-05

## Status

Accepted. Records the controls and standard requirements this application knowingly
does not meet, as reviewed against the IM8 application control catalog on 2026-09-25
and against the Standalone User Access Control standard, so a later reviewer sees a
decision rather than an oversight. Four entries are open work, not settled decisions:
as-8, ck-4, dp-3 and dp-8. Epic Login (ADR 0012, 2026-10-06) changed the ac-2, ac-12 and as-8
entries and added ck-4; they were folded into the table on 2026-10-10. On 2026-10-14 the
deployed database's parts of as-8, ck-4 and dp-3 were met (#65, ADR 0013); each entry says
what stays open.

## Context

The application is a SCIM 2.0 service provider with its own password Login, an Epic
Login for clinicians (ADR 0012), and an administration SPA. Its compliance reviews raised controls that are either met
elsewhere (at the deployment edge, by the directory) or deliberately not met. Left
unrecorded, each looks like a gap and invites someone to re-litigate it in code.

## Decision

### IM8 controls not met

| Control                              | Deviation                                       | Rationale                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| ------------------------------------ | ----------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| as-4 Authentication rate-limiting    | No application-layer limiter                    | Throttling is the deployment edge's job for `/scim/v2/**`, Login, change-password and Epic Login's launch and callback (`/infra/docs/edge-throttling.md`, "Edge throttling"). The application keeps only per-request safety bounds — parser, body and page limits — which are not rate limiting.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| as-8 Secrets management              | The Epic signing key is not held in KMS         | **Open for the Epic signing key; met for the deployed database** (#65, ADR 0013). In the deployed stack every database credential is in AWS Secrets Manager — the RDS master user's RDS-managed, the owner, app and retention logins' generated — and so are the Bootstrap Admin's first password and the Epic client credentials (`APP_EPIC_CLIENT_KEY`, `APP_EPIC_CLIENT_NEXT_KEY`; ADR 0012, D7, D14, D16). None is a CloudFormation parameter (the Epic values excepted), in UserData or on disk; the service's start script resolves them at every start, failing fast on a missing one, and the instance role reads only those secrets (`/infra/docs/database-credentials.md`, "Database credentials"). Still open: the Epic signing key's target is an AWS KMS-held key that signs through the KMS API, behind `ClientAssertionSigner`; until then the key reaches the JVM's environment. Local development keeps environment configuration. |
| ck-4 Key storage                     | The Epic signing key is held in the environment | **Open.** Fails for the same key as as-8, and closes with it when the KMS signer exists. The database's keys are met (#65): a customer-managed KMS key with rotation enabled encrypts RDS storage, backups, snapshots, Performance Insights and the secrets, under a key policy limited to RDS, Secrets Manager and the deploy role's administration.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| dp-3 Data in transit encryption      | TLS unspecified beyond "TLS at the edge"        | **Open beyond the database hop.** The database hop is met (#65, ADR 0013): the deployed RDS for SQL Server encrypts every session at TLS 1.2 or higher (`rds.force_ssl = 1`, `rds.tls10` and `rds.tls11` disabled) and refuses a client that cannot do TLS, and the service validates its certificate against a driver-scoped trust store built from the RDS CA bundle (`/infra/docs/database-encryption.md`). Otherwise no minimum version, cipher policy or internal-hop TLS is specified. HSTS is Spring Security's default — sent only on a request the application sees as secure, never with `preload` — and no forwarded-headers strategy is configured, so behind a TLS-terminating edge the edge must send it. Epic Login's own URLs are `https` only (ADR 0012, D21).                                                                                                                                                                                                                                                                |
| ac-2 MFA enforcement                 | Password Login stays single-factor, Superuser-only actions included | Password Login is the deliberate authentication model for non-clinicians and the Bootstrap Admin. The same goes for the actions only a Superuser may take (connectors and their tokens, ADR 0015): there is no step-up, because password Login has no second factor to step up to. A Superuser signed in through Epic is MFA'd by Epic. Epic Login meets ac-2: the Epic organisation enforces MFA at its own sign-in (ADR 0012, D17). Until Epic confirms that an EHR launch's `id_token` carries `amr`, that is an attestation and every Epic `LOGIN_SUCCESS` records `idp-attested`; `APP_EPIC_MFA_EVIDENCE_REQUIRED` turns it into a check.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| ac-4 Access review                   | No periodic privilege re-attestation            | Membership is directory-derived, and Group Permissions are set by a Superuser and audited (ADR 0015). The `group:read` Groups view supports a manual review. There is still no review cycle.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| ac-12 SSO for internal services      | Non-clinicians use the application's own Login  | Clinicians sign in through their organisation: an Epic Login is an OpenID Connect sign-in at the Epic organisation's IdP (ADR 0012, D1, D17). Non-clinicians and the Bootstrap Admin, which can never sign in through Epic (D6), keep password Login, the application's own authentication interface. SCIM provisions identity. A User may hold both (D5).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| dp-8 Data classification labels      | The SPA shows no classification labels anywhere | **Open.** An app-wide deviation: no page of the SPA labels the classification of the data it shows. Labels belong to a separate ticket.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| st-3 Public vulnerability disclosure | No `security.txt` or reporting channel          | Out of scope for this capability.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |

ac-6 (default credentials) is met: every imposed credential — the seeded Bootstrap
Admin, a connector password write, an Unlock, a forced change — sets the
change-required flag, and a password session stays confined until the User
replaces it (ADR 0008). The one waiver is the seeded Bootstrap Admin with the
development fixtures on, which startup refuses outside the `local` profile, so
only a developer's machine can enable them. The Bootstrap Admin's first password
has no fallback: a start without one fails. An Epic Login, which presents no password of ours, is
not confined by the flag (ADR 0008's 2026-10-09 addendum).

### Standalone User Access Control standard

- **No admin-initiated password reset tokens.** The standard asks for paired
  token-issuance and redemption endpoints. A forced password change plus the
  authenticated change-password flow restores access without an administrator ever
  learning or transporting the credential, and with no reset token to issue,
  display, log or replay. The cost: a User who has forgotten their password cannot
  self-recover and needs a connector to set a new one. There is no email recovery.
- **`userName` is not restricted to alphanumerics.** The standard's §5 Input says:
  "User-supplied input exceeding maximum length or with invalid format (e.g.,
  invalid email, non-alphanumeric username) is rejected with `400 Bad Request`."
  SCIM `userName` instead follows RFC 7643, which leaves its format to the service
  provider, and the identity provider's own format: directories provision emails
  and UPNs (`alice@example.org`), and Epic Login matches `userName` exactly to the
  Practitioner ID Epic proved (ADR 0012). An alphanumeric-only rule would refuse
  the identifiers the directory owns. The length limit and the refusals of control
  characters still apply (recorded 2026-10-12, when ADR 0013 was reviewed against
  the standard).
- **`userName` is mutable.** SCIM's core schema makes `userName` read-write and
  directory renames are routine, so rejecting them would break interoperability.
  The standard's concern — renames orphaning state — is answered structurally: the
  stable SCIM `id` is the principal and the key for all application-owned data,
  sessions are indexed by id, and a rename revokes the User's sessions.
- **Email addresses are not unique across Users.** The standard's reason is that
  email is a login and reset identifier; here it is neither, since Login is
  `userName`-only and no email recovery exists. RFC 7643 gives `emails` uniqueness
  `none`. Uniqueness holds only within a User: at most one primary and no duplicate
  `(type, value)` pair.
- **A deleted `userName` is reusable,** as RFC 7644 §3.6 allows. Audit resolves every
  actor and resource by stable id, never by `userName`, so a reused name cannot merge
  two identities in the trail.
- **Deletion is not a soft delete, and the tombstone holds no profile.** Traceability
  lives in the append-only audit stream, which keeps actor, resource id, operation
  and changed paths for the full retention period. Keeping a readable profile after
  a SCIM `DELETE` would retain personal data the deletion is understood to remove.
- **A write token is Superuser-equivalent.** The standard reserves role assignment to
  role administrators. Here the directory is authoritative for membership and a Role
  is conferred by mapped-Group membership, so a token holding `group:write`
  necessarily can confer one (ADR 0010, ADR 0015). Only a Superuser mints a token,
  but a compromised write token is still an escalation path, bounded by its 365-day
  lifetime and 14-day rotation overlap, immediate revocation, session revocation on
  every lost mapped-Group membership, and an audit event for each change.
- **No account-owner notifications.** Deferred, not settled: no channel exists, and
  dormancy lockout, Role revocation and Unlock are recorded only in the audit stream
  (App-Standards finding USR-8).

### Out of scope

- SCIM Bulk, `/Me` self-service (answered `501`), and Enterprise User or custom
  schema extensions.
- Nested, transitive or dynamic Groups.
- Multi-tenancy, or more than one directory per deployment.
- Vendor-specific workarounds for Microsoft Entra ID, Okta or other connectors.
- OAuth authorization-server flows, refresh tokens and JWT access tokens: connector
  tokens are locally issued opaque credentials.
- SCIM-driven end-user Login or session creation.
- Administrator editing of SCIM-owned User, Group or membership data through the
  application's own API: the directory owns it.

## Consequences

- A change that would meet one of these controls is a reversal of this ADR, and
  belongs in a new one rather than in code alone.
- as-8, ck-4 and dp-3 stay open, for what their entries name, until a deployment
  specifies them. A production
  deployment must close all three before it relies on this application, and
  before it relies on Epic Login in particular. dp-8 stays open until the ticket
  that adds classification labels lands.
- A deployment that exposes the service without edge throttling is misconfigured,
  since as-4 is met nowhere else.

# 12. Accepted policy deviations and deliberate scope limits

Date: 2026-10-05

## Status

Accepted. Records the controls and standard requirements this application knowingly
does not meet, as reviewed against the IM8 application control catalog on 2026-09-25
and against the Standalone User Access Control standard, so a later reviewer sees a
decision rather than an oversight. Three entries are open work, not settled decisions:
as-8, ck-4 and dp-3. Epic Login (ADR 0013, 2026-10-06) changed the ac-2, ac-12 and as-8
entries and added ck-4; they were folded into the table on 2026-10-10.

## Context

The application is a SCIM 2.0 service provider with its own password Login, an Epic
Login for clinicians (ADR 0013), and an administration SPA. Its compliance reviews raised controls that are either met
elsewhere (at the deployment edge, by the directory) or deliberately not met. Left
unrecorded, each looks like a gap and invites someone to re-litigate it in code.

## Decision

### IM8 controls not met

| Control                              | Deviation                                        | Rationale                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| ------------------------------------ | ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| as-4 Authentication rate-limiting    | No application-layer limiter                     | Throttling is the deployment edge's job for `/scim/v2/**`, Login, change-password and Epic Login's launch and callback (`/infra/README.md`, "Edge throttling"). The application keeps only per-request safety bounds — parser, body and page limits — which are not rate limiting.                                                                                                                                                                          |
| as-8 Secrets management              | No secret-store requirement                      | **Open.** Secrets stay deployment environment configuration, the Epic signing keys included (`APP_EPIC_CLIENT_KEY`, `APP_EPIC_CLIENT_NEXT_KEY`; ADR 0013, D7, D14, D16), validated at startup and never logged. A store-backed injection path (SSM SecureString or equivalent) with fail-fast on a missing value is not built. For the signing key the target is an AWS KMS-held key that signs through the KMS API, behind `ClientAssertionSigner`.        |
| ck-4 Key storage                     | The Epic signing key is held in the environment  | **Open.** Fails for the same key as as-8, and closes with it when the KMS signer exists.                                                                                                                                                                                                                                                                                                                                                                   |
| dp-3 Data in transit encryption      | TLS unspecified beyond "TLS at the edge"         | **Open.** No minimum version, cipher policy or internal-hop TLS is specified. HSTS is Spring Security's default — sent only on a request the application sees as secure, never with `preload` — and no forwarded-headers strategy is configured, so behind a TLS-terminating edge the edge must send it. Epic Login's own URLs are `https` only (ADR 0013, D21).                                                                                               |
| ac-2 MFA enforcement                 | Password Login stays single-factor               | Password Login is the deliberate authentication model for non-clinicians and the Bootstrap Admin. Epic Login meets ac-2: the Epic organisation enforces MFA at its own sign-in (ADR 0013, D17). Until Epic confirms that an EHR launch's `id_token` carries `amr`, that is an attestation and every Epic `LOGIN_SUCCESS` records `idp-attested`; `APP_EPIC_MFA_EVIDENCE_REQUIRED` turns it into a check.                                                      |
| ac-4 Access review                   | No periodic privilege re-attestation             | Role assignment is directory-derived Group membership (ADR 0010) and every change is audited, but no review cycle exists.                                                                                                                                                                                                                                                                                                                                  |
| ac-12 SSO for internal services      | Non-clinicians use the application's own Login   | Clinicians sign in through their organisation: an Epic Login is an OpenID Connect sign-in at the Epic organisation's IdP (ADR 0013, D1, D17). Non-clinicians and the Bootstrap Admin, which can never sign in through Epic (D6), keep password Login, the application's own authentication interface. SCIM provisions identity. A User may hold both (D5).                                                                                                     |
| st-3 Public vulnerability disclosure | No `security.txt` or reporting channel           | Out of scope for this capability.                                                                                                                                                                                                                                                                                                                                                                                                                          |

ac-6 (default credentials) is met: every imposed credential — the seeded Bootstrap
Admin, a connector password write, an Unlock, a forced change — sets the
change-required flag, and a password session stays confined until the User
replaces it (ADR 0008). An Epic Login, which presents no password of ours, is
not confined by the flag (ADR 0008's 2026-10-09 addendum).

### Standalone User Access Control standard

- **No admin-initiated password reset tokens.** The standard asks for paired
  token-issuance and redemption endpoints. A forced password change plus the
  authenticated change-password flow restores access without an administrator ever
  learning or transporting the credential, and with no reset token to issue,
  display, log or replay. The cost: a User who has forgotten their password cannot
  self-recover and needs a connector to set a new one. There is no email recovery.
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
- **A connector can confer any Role.** The standard reserves role assignment to role
  administrators. Here the directory is authoritative for membership and a Role is
  conferred by mapped-Group membership, so a token holding `group:write` necessarily
  can confer one (ADR 0010). A compromised token is therefore an escalation path,
  bounded by the token's Permissions, its 365-day lifetime and 14-day rotation
  overlap, immediate revocation, session revocation on every lost mapped-Group
  membership, and an audit event for each change.
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
- as-8, ck-4 and dp-3 stay open until a deployment specifies them. A production
  deployment must close all three before it relies on this application, and
  before it relies on Epic Login in particular.
- A deployment that exposes the service without edge throttling is misconfigured,
  since as-4 is met nowhere else.

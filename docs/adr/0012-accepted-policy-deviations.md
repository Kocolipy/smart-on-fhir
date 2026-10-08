# 12. Accepted policy deviations and deliberate scope limits

Date: 2026-10-05

## Status

Accepted. Records the controls and standard requirements this application knowingly
does not meet, as reviewed against the IM8 application control catalog on 2026-09-25
and against the Standalone User Access Control standard, so a later reviewer sees a
decision rather than an oversight. Two entries are open work, not settled decisions:
as-8 and dp-3; the Epic Login addendum below extends as-8 to the Epic signing key and
adds ck-4 (key storage) beside it, open for the same reason.

## Context

The application is a SCIM 2.0 service provider with its own password Login and an
administration SPA. Its compliance reviews raised controls that are either met
elsewhere (at the deployment edge, by the directory) or deliberately not met. Left
unrecorded, each looks like a gap and invites someone to re-litigate it in code.

## Decision

### IM8 controls not met

| Control                              | Deviation                                | Rationale                                                                                                                                                                                                                                                                                                |
| ------------------------------------ | ---------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| as-4 Authentication rate-limiting    | No application-layer limiter             | Throttling is the deployment edge's job for `/scim/v2/**`, Login and change-password (`/infra/README.md`, "Edge throttling"). The application keeps only per-request safety bounds — parser, body and page limits — which are not rate limiting.                                                         |
| as-8 Secrets management              | No secret-store requirement              | **Open.** Secrets stay deployment environment configuration. A store-backed injection path (SSM SecureString or equivalent) with fail-fast on a missing value is not built.                                                                                                                              |
| dp-3 Data in transit encryption      | TLS unspecified beyond "TLS at the edge" | **Open.** No minimum version, cipher policy or internal-hop TLS is specified. HSTS is Spring Security's default — sent only on a request the application sees as secure, never with `preload` — and no forwarded-headers strategy is configured, so behind a TLS-terminating edge the edge must send it. |
| ac-2 MFA enforcement                 | Privileged login stays single-factor     | Password Login is the deliberate authentication model. Epic Login meets ac-2 on its own path: see the addendum below.                                                                                                                                                                                    |
| ac-4 Access review                   | No periodic privilege re-attestation     | Role assignment is directory-derived Group membership (ADR 0010) and every change is audited, but no review cycle exists.                                                                                                                                                                                |
| ac-12 SSO for internal services      | No organisational IdP authentication     | SCIM provisions identity; password Login remains the application's own authentication interface.                                                                                                                                                                                                         |
| st-3 Public vulnerability disclosure | No `security.txt` or reporting channel   | Out of scope for this capability.                                                                                                                                                                                                                                                                        |

ac-6 (default credentials) is met: every imposed credential — the seeded Bootstrap
Admin, a connector password write, an Unlock, a forced change — sets the
change-required flag, and the session stays confined until the User replaces it
(ADR 0008).

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
- as-8 and dp-3 stay open until a deployment specifies them; a production
  deployment must close both before it relies on this application.
- A deployment that exposes the service without edge throttling is misconfigured,
  since as-4 is met nowhere else.

## Addendum: Epic Login (ADR 0013)

ADR 0013 adds a second way to sign in, Epic Login — a SMART on FHIR EHR launch for
clinicians — beside password Login, which is unchanged. It changes the ac-2,
ac-12 and as-8 entries above, and ck-4 (key storage) joins as-8 as open work. The
entries below take precedence over those rows of the table where they differ.

- **ac-2 MFA enforcement — met on the Epic path, still a deviation for password
  Login.** An Epic Login is multi-factor because the Epic organisation enforces MFA
  at its own sign-in (ADR 0013, D17). For now that is an organisational
  attestation, referenced from ADR 0013: Epic has not confirmed that an EHR
  launch's `id_token` carries `acr` or `amr`, so every Epic `LOGIN_SUCCESS` records
  its MFA factor as `idp-attested`. Once Epic confirms the claim,
  `APP_EPIC_MFA_EVIDENCE_REQUIRED` turns the attestation into a check: a token
  without a second factor in `amr` is refused, and the factor recorded is the one
  `amr` names. Password Login stays the single-factor deviation recorded in the
  table, for the non-clinicians who use it and for the Bootstrap Admin, which can
  never sign in through Epic.
- **ac-12 SSO for internal users — met for clinicians, still a deviation for
  everyone else.** Clinicians now sign in through their own organisation: an Epic
  Login is an OpenID Connect sign-in at the Epic organisation's IdP, which proves
  the clinician and enforces its own MFA (ADR 0013, D1, D17). Non-clinicians — and
  the Bootstrap Admin, which can never sign in through Epic (D6) — keep password
  Login, the application's own authentication interface, so for them ac-12 stays
  the deviation recorded in the table. A User may hold both (ADR 0013, D5).
- **as-8 Secrets management — still open, and extended to name the Epic signing
  key.** Epic Login adds a private key: the EC P-384 key that signs the client
  assertion Epic's token endpoint authenticates us by, with an optional next key
  published for rotation (`APP_EPIC_CLIENT_KEY`, `APP_EPIC_CLIENT_NEXT_KEY`; ADR
  0013, D7, D14, D16). Like the application's other secrets it is deployment
  environment configuration for now, validated at startup and never logged (D22).
  The target is an AWS KMS-held key that signs through the KMS API, so the private
  key never leaves KMS; signing sits behind `ClientAssertionSigner`, so that
  change replaces one implementation. **ck-4 Key storage** fails for the same key
  and closes with it. Both stay open until the KMS signer exists, and a production
  deployment of Epic Login must treat them as as-8 below says it must.

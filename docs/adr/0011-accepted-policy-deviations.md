---
type: Decision Record
title: "ADR 0011: Accepted policy deviations and scope limits"
description: The IM8 controls and App-Standards requirements this application knowingly does not meet, the FHIRcast-in-the-browser deviations, the entries still open, and what is out of scope.
tags: [compliance, im8, app-standards, deviations]
---

# 11. Accepted policy deviations and deliberate scope limits

Date: 2026-10-05

## Status

Accepted. Records the controls and standard requirements this application knowingly
does not meet, as reviewed against the IM8 application control catalog on 2026-09-25
and against the Standalone User Access Control standard, so a later reviewer sees a
decision rather than an oversight. Four entries are open work, not settled decisions:
as-8, ck-4, dp-3 and dp-8; each says what stays open.

## Context

The application is a SCIM 2.0 service provider with its own password Login, an Epic
Login for clinicians (ADR 0012), and an administration SPA. Its compliance reviews raised controls that are either met
elsewhere (at the deployment edge, by the directory) or deliberately not met. Left
unrecorded, each looks like a gap and invites someone to re-litigate it in code.

## Decision

### IM8 controls not met

| Control                              | Deviation                                                           | Rationale                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| ------------------------------------ | ------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| as-4 Authentication rate-limiting    | No application-layer limiter                                        | Throttling is the deployment edge's job for `/scim/v2/**`, Login, change-password and Epic Login's launch and callback (`/infra/docs/edge-throttling.md`, "Edge throttling"). The application keeps only per-request safety bounds — parser, body and page limits — which are not rate limiting.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| as-8 Secrets management              | The Epic signing key is not held in KMS                             | **Open for the Epic signing key; met for the deployed database** (ADR 0013). In the deployed stack every database credential is in AWS Secrets Manager — the RDS master user's RDS-managed, the owner, app and retention logins' generated — and so are the Bootstrap Admin's first password and the Epic client credentials (`APP_EPIC_CLIENT_KEY`, `APP_EPIC_CLIENT_NEXT_KEY`; ADR 0012, D7, D14, D16). None is a CloudFormation parameter (the Epic values excepted), in UserData or on disk; the service's start script resolves them at every start, failing fast on a missing one, and the instance role reads only those secrets (`/infra/docs/database-credentials.md`, "Database credentials"). Still open: the Epic signing key's target is an AWS KMS-held key that signs through the KMS API, behind `ClientAssertionSigner`; until then the key reaches the JVM's environment. Local development keeps environment configuration. |
| ck-4 Key storage                     | The Epic signing key is held in the environment                     | **Open.** Fails for the same key as as-8, and closes with it when the KMS signer exists. The database's keys are met (ADR 0013): a customer-managed KMS key with rotation enabled encrypts RDS storage, backups, snapshots, Performance Insights and the secrets, under a key policy limited to RDS, Secrets Manager and the deploy role's administration.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| dp-3 Data in transit encryption      | TLS unspecified beyond "TLS at the edge"                            | **Open beyond the database hop.** The database hop is met (ADR 0013): the deployed RDS for SQL Server encrypts every session at TLS 1.2 or higher (`rds.force_ssl = 1`, `rds.tls10` and `rds.tls11` disabled) and refuses a client that cannot do TLS, and the service validates its certificate against a driver-scoped trust store built from the RDS CA bundle (`/infra/docs/database-encryption.md`). Otherwise no minimum version, cipher policy or internal-hop TLS is specified. HSTS is Spring Security's default — sent only on a request the application sees as secure, never with `preload` — and no forwarded-headers strategy is configured, so behind a TLS-terminating edge the edge must send it. Epic Login's own URLs are `https` only (ADR 0012, D21).                                                                                                                                                                     |
| ac-2 MFA enforcement                 | Password Login stays single-factor, Superuser-only actions included | Password Login is the deliberate authentication model for non-clinicians and the Bootstrap Admin. The same goes for the actions only a Superuser may take (connectors and their tokens, ADR 0015): there is no step-up, because password Login has no second factor to step up to. A Superuser signed in through Epic is MFA'd by Epic. Epic Login meets ac-2: the Epic organisation enforces MFA at its own sign-in (ADR 0012, D17). Until Epic confirms that an EHR launch's `id_token` carries `amr`, that is an attestation and every Epic `LOGIN_SUCCESS` records `idp-attested`; `APP_EPIC_MFA_EVIDENCE_REQUIRED` turns it into a check.                                                                                                                                                                                                                                                                                                 |
| ac-4 Access review                   | No periodic privilege re-attestation                                | Membership is directory-derived, and Group Permissions are set by a Superuser and audited (ADR 0015). The `group:read` Groups view supports a manual review. There is still no review cycle.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| ac-12 SSO for internal services      | Non-clinicians use the application's own Login                      | Clinicians sign in through their organisation: an Epic Login is an OpenID Connect sign-in at the Epic organisation's IdP (ADR 0012, D1, D17). Non-clinicians and the Bootstrap Admin, which can never sign in through Epic (D6), keep password Login, the application's own authentication interface. SCIM provisions identity. A User may hold both (D5).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| dp-8 Data classification labels      | The SPA shows no classification labels anywhere                     | **Open.** An app-wide deviation: no page of the SPA labels the classification of the data it shows. Labels belong to a separate ticket.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| st-3 Public vulnerability disclosure | No `security.txt` or reporting channel                              | Out of scope for this capability.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |

ac-6 (default credentials) is met: every imposed credential — the seeded Bootstrap
Admin's, a connector password write's, an Unlock's — sets the change-required flag,
and a password session stays confined until the User replaces it (ADR 0007, "The change-required flag"). The one waiver is the seeded Bootstrap Admin with the
development fixtures on, which startup refuses outside the `local` profile, so
only a developer's machine can enable them. The Bootstrap Admin's first password
has no fallback: a start without one fails. An Epic Login, which presents no password of ours, is
not confined by the flag (ADR 0007, "The change-required flag").

### FHIRcast in the browser (ADR 0016)

The SPA connects to Epic's local FHIRcast hub itself, because the hub listens on
the clinician's loopback interface, which the server cannot reach. That puts the
SMART access token in the browser, and the Hub Subscription it is presented on.
Each deviation below follows from that; ADR 0016 has the design.

- **The SMART access token in the tab's memory for the connection's life, and
  the Hub Subscription in `sessionStorage`, visible to JS (IM8 as-8, ck-4; SSO
  token-storage rule).** The connection holds the SMART access token in the
  tab's memory from the release until it is torn down: every subscription,
  renewal and unsubscription presents it as `Bearer`. It is never stored. The
  tab's `sessionStorage` keeps the hub URL and topic, the one live Hub
  Subscription's channel endpoint and the expiry of its lease, and the instant
  the SMART access token expires. It keeps no private key, `client_id` or
  other hub credential: the hub issues none.
  - _Rationale:_ the browser makes every hub call, and the hub accepts the
    SMART access token on each. Holding it for the connection's life is what
    renewing and tearing down without the backend take; a reload fetches the
    release, and the token with it, again. What the tab keeps lets a reload
    reopen the stored channel endpoint rather than subscribing at the hub
    again, and tell a token past its expiry, which the backend no longer
    releases, from FHIRcast unavailable. `sessionStorage` is per tab, never sent to a server, and gone with
    the tab. What is kept is decoded on every read, and every URL in it is held
    to `127.0.0.1` again. Every end of the session clears it, and so does a
    password Login in the tab.
  - _Residual risk:_ any script running in the SPA's origin can read the
    token from memory for as long as the connection lasts, not only for one
    request, and what the tab keeps. The CSP (`script-src 'self'`, no inline
    script, no third-party origin) is what keeps that origin the
    application's own. The channel endpoint alone opens a socket only on the
    clinician's own workstation, where the hub listens on loopback. A tab
    Chrome duplicates copies what is kept, and both tabs then share one
    subscription until one moves off it.
- **The loopback CSP: any port, any scheme (IM8 as-9).** `connect-src` admits
  `http`, `https`, `ws` and `wss` on `127.0.0.1:*`.
  - _Rationale:_ Epic chooses the local hub's port and scheme, and the SPA
    cannot know them in advance. The host is pinned: no Epic origin and no other
    host is admitted, and the backend keeps a `hub.url` only on `127.0.0.1`.
  - _Residual risk:_ an injected script could reach any loopback service on the
    clinician's workstation. Script injection is already what the rest of the
    CSP refuses.
- **Plain `http` and `ws` to the loopback hub, which carries the SMART access token (IM8
  dp-3).** The subscription, the unsubscription and the socket may be
  unencrypted.
  - _Rationale:_ loopback traffic never leaves the workstation, and Epic's
    local hub chooses its own scheme.
  - _Residual risk:_ another process on the same workstation, with the
    privilege to watch loopback traffic, could read the token. That process
    already holds the clinician's desktop.
- **Epic-set access-token lifetimes over 30 minutes (SSO standard).** The
  released SMART access token lives as long as Epic's `expires_in` says,
  typically an hour.
  - _Rationale:_ Epic issues it; this application neither mints nor shortens
    it. The backend refuses to release an access token past its expiry.
  - _Residual risk:_ a token copied out of the tab stays valid at Epic, and at
    the hub, until it expires. Nothing revokes it at Epic (ADR 0012, D13).
- **The FHIRcast connection can outlive the application session (IM8 as-11;
  SSO: refresh tokens do not extend the local session lifetime).** The
  connection subscribes again with the SMART access token whenever the lease
  nears its end, until the hub denies it or its expiry passes (ADR 0016), and
  the 8-hour absolute session lifetime is not enforced on it, nor the idle
  timeout tracked by it.
  - _Rationale:_ the hub, not this application, accepts the token and keeps
    the subscription, and a renewal needs nothing of the backend, so nothing on
    the server can stop it. In the tab the connection stops with the session:
    every end of it tears the connection down at the hub (below) and drops the
    token from memory. Renewals are not activity, so they never keep the
    session alive either.
  - _Residual risk:_ a SMART access token copied out of the tab keeps
    subscribing, and so reading the clinician's FHIRcast events, after the
    session has ended — past its absolute lifetime and however long idle —
    until it expires. Only from the clinician's own workstation, where the hub
    listens on loopback.
- **Revocation at the hub is best-effort (IM8 ck-2; SSO: all tokens associated
  with a logged-out session are revoked).** Every end of the session tears down
  what the tab's connections hold at the hub — the live Hub Subscription, and
  any whose socket was never confirmed, unsubscribed with the SMART access
  token, then the socket closed — and clears `sessionStorage`
  (`/docs/domain/epic-login.md`, "Tearing down", has the steps). Each
  unsubscription is given 3 seconds, and one that fails or times out never
  stops the rest, nor the end of the session. The SMART access token is not
  revoked: Epic offers no revocation for it (ADR 0012, D13). Logout's
  `Clear-Site-Data` stays the backstop for what the tab kept.
  - _Rationale:_ the hub, not this application, holds the subscription, and
    only the browser can reach it, so the browser cancels it. It does so after
    the backend has ended the session, never before, so a hub that refuses,
    fails or hangs cannot keep a clinician signed in. The token the
    unsubscription carries is the one the connection already holds.
  - _Residual risk:_ where the hub does not answer in time, or is never
    reached — the tab closed before the session ended — the subscription stands
    until its lease runs out. A reload loses nothing to tear down: it resumes
    the subscription the tab kept, and a tab holds one live subscription at a
    time; a subscription a page before a reload kept that the session ends
    before any connection resumed it is forgotten, and left to its lease. A
    connection still subscribing when the session ends unsubscribes what the
    hub answers it at once, unwatched. Only from the clinician's own
    workstation, where the hub listens on loopback.
- **Hub Subscriptions absent from the application's audit trail (Logging audit
  contract, IM8 lm-4).** The trail records each release of the SMART access token
  (`FHIRCAST_TOKEN_RELEASE`). It does not record the subscriptions made with it.
  - _Rationale:_ the browser makes those calls to the hub, and the backend never
    sees them. The hub is the party that accepts them, and it records them.
  - _Residual risk:_ an investigator reconstructs a clinician's hub activity
    from the hub's records, joined to this trail by time and User, not from this
    trail alone.
- **Browser-side integration calls not logged or monitored (Logging
  outbound-call rule, IM8 lm-16).** The hub calls carry no correlation id,
  latency metric or error log of the application's.
  - _Rationale:_ they are made from the clinician's browser to their own
    workstation. The card shows only generic states, and never logs a hub's or
    Epic's `error_description` or any response body, so nothing sensitive reaches
    the browser console.
  - _Residual risk:_ a hub fault on one workstation is seen only as that
    clinician's "FHIRcast unavailable". It is not seen centrally.

### Standalone User Access Control standard

- **No admin-initiated password reset tokens.** The standard asks for paired
  token-issuance and redemption endpoints. An Unlock of a locked User plus the
  authenticated change-password flow restores access without an administrator ever
  learning or transporting the credential, and with no reset token to issue,
  display, log or replay. The cost: a User who has forgotten their password cannot
  self-recover and needs a connector to set a new one, and a User whose password is
  suspected compromised but who is not locked cannot be made to change it by anyone
  in this application; the connector sets a new password, or deactivates the User,
  in the directory. There is no email recovery.
- **`userName` is not restricted to alphanumerics.** The standard's §5 Input says:
  "User-supplied input exceeding maximum length or with invalid format (e.g.,
  invalid email, non-alphanumeric username) is rejected with `400 Bad Request`."
  SCIM `userName` instead follows RFC 7643, which leaves its format to the service
  provider, and the identity provider's own format: directories provision emails
  and UPNs (`alice@example.org`), and Epic Login matches `userName` exactly to the
  Practitioner ID Epic proved (ADR 0012). An alphanumeric-only rule would refuse
  the identifiers the directory owns. The length limit and the refusals of control
  characters and edge whitespace still apply (ADR 0013).
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
  role administrators. Here the directory is authoritative for membership and a
  Group's membership confers its Permissions, so a token holding `group:write`
  necessarily can confer them (ADR 0015). Only a Superuser mints a token, but a
  compromised write token is still an escalation path, bounded by its 365-day
  lifetime and 14-day rotation overlap, immediate revocation, session revocation on
  every lost membership of a permission-bearing Group, and an audit event for each
  change.
- **No account-owner notifications.** Deferred, not settled: no channel exists, and
  dormancy lockout, Membership revocation and Unlock are recorded only in the audit stream
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

- A change that would meet one of these controls reverses its entry here, and is
  edited into this ADR in the same change, never made in code alone.
- as-8, ck-4 and dp-3 stay open, for what their entries name, until a deployment
  specifies them. A production
  deployment must close all three before it relies on this application, and
  before it relies on Epic Login in particular. dp-8 stays open until the ticket
  that adds classification labels lands.
- A deployment that exposes the service without edge throttling is misconfigured,
  since as-4 is met nowhere else.

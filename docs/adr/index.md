---
okf_version: "0.2"
---

# Architecture decisions

The decisions behind the system, numbered. Each states its decision as it stands
now; git holds its history. `/docs/agents/domain.md` has how an ADR changes, and
`/docs/domain/` the behaviour each decision produces.

# Sign-in and sessions

- [ADR 0001: Counting Login attempts and ending every Login](0001-count-attempts-and-complete-every-login-on-the-login-path.md) - The login path counts each attempt itself rather than through a framework listener, and one module ends every password and Epic Login in one order — decide, establish, the method's work, record.
- [ADR 0002: Revoking sessions after the commit](0002-revoke-sessions-after-commit.md) - Every write that ends a User's sessions revokes them only after its transaction commits, through one after-commit port that runs every queued revocation even when one fails.
- [ADR 0007: Lockout and Unlock](0007-lockout-lifted-only-by-admin-unlock.md) - A Lockout has two causes, consecutive failures and dormancy, never lifts on a timer, and only an administrator's Unlock of a locked User ends it; dormancy also revokes every Group membership.
- [ADR 0008: No password-change grace period](0008-no-password-change-grace-period.md) - An imposed password is bounded by the dormancy lockout rather than a grace deadline, and the change-required flag confines a password Login only, never an Epic Login.
- [ADR 0009: CSRF synchronizer token](0009-csrf-synchronizer-token.md) - CSRF uses a session-bound synchronizer token fetched from GET /api/auth/csrf and held in memory by the SPA, never a cookie.

# Directory and authorization

- [ADR 0006: Protected-resource refusals are 400 mutability](0006-protected-resource-refusals-are-400-mutability.md) - A SCIM write aimed at the Bootstrap Admin, the Admin group or its Bootstrap membership is refused 400 mutability, so a 403 on a write always means the credential.
- [ADR 0015: Groups carry Permissions; a Superuser replaces Roles](0015-groups-carry-permissions-a-superuser-authority-replaces-roles.md) - A User's Permissions are the baseline plus its direct Groups' stored Permissions, the Superuser is one authority no Group can carry, connector tokens have their own grantable set, and Unlock cannot escalate.
- [ADR 0017: Administrative reads are paged by one set of rules](0017-administrative-reads-are-paged-by-one-set-of-rules.md) - Every paged administrative read pages by SCIM's startIndex and count rules from one paging module; the Users and Groups projections are also searched and sorted by Directory attributes.

# Epic and FHIR

- [ADR 0012: Epic Login](0012-epic-login.md) - Signing clinicians in by SMART on FHIR EHR launch — the flow, decisions D1–D29, the Epic tokens kept for the session, client authentication and signing keys, outbound resilience, and its IM8 controls and deviations.
- [ADR 0014: FHIR R5 Patient from the service's own tables](0014-fhir-r5-patient-from-own-tables.md) - Patients are served as base FHIR R5, rendered by hand from the patient module's own tables behind a port, with no FHIR library on the runtime classpath.
- [ADR 0016: SMART access token released for FHIRcast](0016-smart-access-token-released-to-the-spa-for-fhircast.md) - The one Epic token that leaves the backend — the SMART access token released to the SPA so the browser can connect to Epic's loopback FHIRcast hub, and how the tab connects, stays connected and tears down.

# Observability and data

- [ADR 0003: ECS-structured logging with redaction](0003-ecs-structured-logging-with-redaction.md) - ECS JSON records built only through LogEvent's shapes, a closed event vocabulary, classified ERRORs, SQL text and connection details kept out of every record, and the tests and scans that hold redaction.
- [ADR 0004: Audit append failure semantics](0004-audit-append-failure-semantics.md) - An audit append fails closed where the request would succeed and fails open with an alert where it is already refused, decided once from the event's kind, on a pool of its own, with the trail append-only in the database.
- [ADR 0005: Serializing scheduled jobs on lock rows](0005-serialize-scheduled-jobs-on-per-job-lock-rows.md) - Each scheduled job takes its own lock row with UPDLOCK and READPAST inside one run transaction, so no two runs of a job overlap across instances and different jobs never block each other.
- [ADR 0013: SQL Server 2022 replaces PostgreSQL](0013-sql-server-2022-replaces-postgresql.md) - The backend runs on SQL Server 2022 alone — three logins, the collation and seed lock, lock order, the deployed bootstrap, the shared test server, IM8 and App-Standards conditions, and the verified engine behaviour.

# Compliance

- [ADR 0011: Accepted policy deviations and scope limits](0011-accepted-policy-deviations.md) - The IM8 controls and App-Standards requirements this application knowingly does not meet, the FHIRcast-in-the-browser deviations, the entries still open, and what is out of scope.

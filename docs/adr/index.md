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
- [ADR 0007: Lockout, Unlock and the change-required flag](0007-lockout-lifted-only-by-admin-unlock.md) - A Lockout has two causes, consecutive failures and dormancy, never lifts on a timer, and only an administrator's Unlock of a locked User ends it; dormancy also revokes every Group membership; an imposed password has no grace deadline, and its flag confines a password Login only.
- [ADR 0009: CSRF synchronizer token](0009-csrf-synchronizer-token.md) - CSRF uses a session-bound synchronizer token fetched from GET /api/auth/csrf and held in memory by the SPA, never a cookie.

# Directory and authorization

- [ADR 0015: Groups carry Permissions; the Superuser is one authority](0015-groups-carry-permissions-and-the-superuser-is-one-authority.md) - A User's Permissions are the baseline plus its direct Groups' stored Permissions, the Superuser is one authority no Group can carry, connector tokens have their own grantable set, Unlock cannot escalate, and a SCIM write at a protected resource is 400 mutability, never 403.
- [ADR 0017: Administrative reads are paged by one set of rules](0017-administrative-reads-are-paged-by-one-set-of-rules.md) - Every paged administrative read pages by SCIM's startIndex and count rules from one paging module; the Users and Groups projections are also searched and sorted by Directory attributes.

# Epic and FHIR

- [ADR 0012: Epic Login](0012-epic-login.md) - Signing clinicians in by SMART on FHIR EHR launch — the flow, decisions D1–D29, the Epic tokens kept for the session, client authentication and signing keys, outbound resilience, and its IM8 controls and deviations.
- [ADR 0014: FHIR R5 Patient, read from the in-house patient source](0014-fhir-r5-patient-from-the-in-house-patient-source.md) - Patients are served as base FHIR R5, rendered by hand with no FHIR library on the runtime classpath, and read on every request, uncached, from the in-house patient source's FHIR R4 Patient/{id} behind PatientStore; the adapter sends no credential, a WireMock mock stands in for it locally and in E2E, and the tests read its stub files.
- [ADR 0016: SMART access token released for FHIRcast](0016-smart-access-token-released-to-the-spa-for-fhircast.md) - The one Epic token that leaves the backend — the SMART access token released to the SPA so the browser can connect to Epic's loopback FHIRcast hub, and how the tab connects, stays connected, ends with that token and tears down.

# Observability and data

- [ADR 0003: ECS-structured logging with redaction](0003-ecs-structured-logging-with-redaction.md) - ECS JSON records built only through LogEvent's shapes, a closed event vocabulary, classified ERRORs, SQL text and connection details kept out of every record, and the tests and scans that hold redaction.
- [ADR 0004: Audit append failure semantics](0004-audit-append-failure-semantics.md) - An audit append fails closed where the request would succeed and fails open with an alert where it is already refused, decided once from the event's kind, on a pool of its own, with the trail append-only in the database.
- [ADR 0005: Serializing scheduled jobs on lock rows](0005-serialize-scheduled-jobs-on-per-job-lock-rows.md) - Each scheduled job takes its own lock row with UPDLOCK and READPAST inside one run transaction, so no two runs of a job overlap across instances and different jobs never block each other.
- [ADR 0013: SQL Server 2022 is the database engine](0013-sql-server-2022-is-the-database-engine.md) - The backend runs on SQL Server 2022 alone — the database logins, collation and seed lock, lock order, deployed bootstrap, shared test server, IM8 and App-Standards conditions, and verified engine behaviour.

# Compliance

- [ADR 0011: Accepted policy deviations and scope limits](0011-accepted-policy-deviations.md) - The IM8 controls and App-Standards requirements this application knowingly does not meet, the FHIRcast-in-the-browser deviations, the entries still open, and what is out of scope.

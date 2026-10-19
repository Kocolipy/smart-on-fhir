---
okf_version: "0.2"
---

# Domain rules

The behavior behind the terms in `/CONTEXT.md`: what each concept does, the
invariants it keeps, and why, under the glossary's own term names. Where a rule is
also a wire contract, `backend/docs/openapi.yaml` is the authority on statuses and
bodies, and the ADRs in `/docs/adr/` record the decisions.

# Sign-in and sessions

- [Sessions](sessions.md) - Session status in the SPA, how sessions end, sign-in reasons, and every trigger of session revocation.
- [Authentication](authentication.md) - Password Login and its refusals, the failure run, Lockout and Unlock, deactivation, the Bootstrap Admin exemption, and the recovery guard.
- [Passwords](passwords.md) - The change-required flag and confined session, forced and self-service changes, the password policy and history, and hashing.
- [Epic Login](epic-login.md) - Login by EHR launch: matching a clinician to a User, every refusal, the Epic tokens' lifetime, MFA, and what is never recorded.

# Directory

- [SCIM directory](directory.md) - Users, Groups, the Bootstrap Admin and Admin group, edge whitespace, deletion and tombstones, and when a resource version advances.
- [SCIM protocol profile](scim-protocol.md) - Discovery, filtering and sorting with SQL Server's deviations, conditional writes, request bounds, and the release gate.
- [Connectors and their tokens](connectors.md) - Token format and Permissions, no escalation, lifetime and rotation, credential refusals, and connector deletion.

# Authority and operations

- [Authorization](authorization.md) - Permissions, Roles and the role mapping, baseline access, deny by default, refusals, the Superuser Group, and the development fixtures.
- [Dormancy and scheduled jobs](dormancy.md) - The dormancy basis and windows, the dormancy job, the audit retention job, and scheduled job locks.
- [Administration and audit](administration.md) - The Accounts page and its projections, and the SCIM audit trail and how it is read.
- [Request paths](request-paths.md) - Reserved server paths, the SPA shell, and file requests.

# Clinical data

- [Patients](patient.md) - The FHIR R5 Patient's elements, NRIC and name rules, derived age, its Restricted classification, the error contract, logging and the absence of read auditing, the default patient and dev fixtures, and R5 conformance.

# monorepo-base

The language of a session-authenticated web application whose identities are
provisioned by external directories over SCIM 2.0. This file says what each term
means; the rules behind them are in `/docs/domain-rules.md`, under the same names.

## Language

### Request paths

**Reserved server path**:
A request path the backend answers itself (`/api`, `/actuator`, `/scim` and
everything beneath them), never with the SPA shell.

**SPA shell**:
The single `index.html` the single-page application boots from, served for any
client-side route.

**File request**:
A request whose last path segment contains a dot; when missing, it stays a 404
rather than becoming the SPA shell.
_Avoid_: frontend route

### Sessions

**Session status**:
What the SPA knows about the current session: `checking`, `authenticated` or
`guest`.

**Guest**:
A person with no authenticated session, and the session status that names them.
_Avoid_: Visitor, anonymous user

**Expired session**:
A session that was authenticated and that the backend has since ended, for
whatever reason; the SPA returns its holder to login saying so.

**Return destination**:
The protected path a Guest asked for before being sent to sign in, replayed once
they have.

**Idle timeout**:
The length of time without a request after which the backend ends a session.
_Avoid_: inactivity (that is the dormancy job's word)

**Absolute session lifetime**:
The maximum age of a session from its creation, however active it is.

**Idle sign-out**:
The SPA ending an idle session on its own, after warning the User.
_Avoid_: inactivity sign-out

**One session per User**:
The rule that an accepted Login ends every other session the User holds.

**Session revocation**:
Ending the sessions a User already holds, because something about its identity,
credential or authority changed.

**Confined session**:
A session issued while the User's change-required flag is set; it may only change
the password or log out.

### Directory

**SCIM service provider**:
The role this application plays: external identity providers call its SCIM
interface to create and manage Users and Groups.

**SCIM directory**:
The single, untenanted namespace of Users and Groups in one deployment, shared by
every connector.

**Connector**:
One external client of the SCIM interface, registered by an Admin and
authenticated by its own tokens.
_Avoid_: client, integration, IdP (the IdP is what drives a connector)

**SCIM connector token**:
An opaque bearer credential issued to one connector, carrying Token Permissions,
usable only on the SCIM interface.
_Avoid_: API key, scope (no token has one), read-only or read-write token

**Connector external identifier**:
One connector's own `externalId` alias for a User or Group, invisible to every
other connector.

**Deleted SCIM connector**:
A connector an Admin removed: its tokens are revoked and its aliases deleted, but
its record remains so audit events still name it.

**User**:
Any identity in the SCIM directory — active or deactivated, with a session or
without, with a password or without.
_Avoid_: Account, SCIM User (in prose), member (except of a Group)

**Group**:
A set of Users in the SCIM directory with direct members only; a mapped Group
confers its Role.

**Admin group**:
The server-seeded Group that is the Superuser Group, seeded under that Group's
stable id; it can be neither renamed nor deleted. Membership confers nothing of
its own: its Role, Superuser, is what grants every Permission.

**Admin**:
A User holding at least one administrative Permission, through its Roles.
_Avoid_: administrator role, ADMIN account, `ROLE_ADMIN` (no such authority
exists)

**Bootstrap Admin**:
The seeded recovery User: an immutable member of the Admin group, never locked,
and not writable over SCIM — with startup's validation of the Superuser Role,
what guarantees the deployment always has a holder of every Permission.

**Reservation marker**:
The mark seeding writes once on the Bootstrap Admin and the Admin group, by which
their protections are recognised instead of by name.

**SCIM tombstone**:
The privacy-minimal record left when a User or Group is deleted: its type, stable
id and deletion time, and nothing else.

**Deleted User** / **Deleted SCIM Group**:
A User or ordinary Group removed with SCIM `DELETE`, leaving only a tombstone.

**Deactivated User**:
A User whose SCIM `active` is false; it cannot sign in.
_Avoid_: disabled, inactive User

**Reactivation**:
A SCIM write that takes a User's `active` from false to true.

**Resource version**:
The opaque value each User and Group carries, advanced exactly once by every
write that changes its rendered document, and exposed as its strong ETag.
_Avoid_: revision, weak ETag

**Conditional write**:
A SCIM `PUT`, `PATCH` or `DELETE` carrying `If-Match`, applied only while the
resource version still matches; optional, so a write without one is
unconditional.

**Practical SCIM protocol profile**:
The part of SCIM 2.0 this application implements and advertises in discovery;
everything outside it is refused rather than ignored.

**SCIM release gate**:
The deployment switch (`APP_SCIM_ENABLED`) that, when closed, makes the whole
`/scim/v2` namespace answer `404` ahead of authentication.

### Authorization

**Permission**:
One named, fine-grained power a protected action requires, such as
`user:read` or `connector:token`; the set is closed and defined in code, not
configuration.
_Avoid_: scope, right, privilege

**Role**:
A named set of Permissions defined in deployment configuration; a User holds
one by being a direct member of the Group mapped to it.
_Avoid_: role (for `ROLE_USER`, baseline access, which is not a Role)

**Role mapping**:
The read-only deployment configuration that defines the Roles and maps each one
to a Group by stable id; a User's Permissions are the union of the Roles of the
mapped Groups it directly belongs to, taken at Login. Readable, never writable,
at `GET /api/admin/roles` by a holder of `group:read`.

**Mapped Group**:
A Group the role mapping names, and so one that confers a Role on its direct
members.

**Role assignment**:
Membership of a mapped Group, which is the only way a User holds a Role; so
`group:write` on a mapped Group is Role assignment, not a harmless Permission.
_Avoid_: role grant (for anything but the audited `ROLE_GRANT` event)

**Role-change propagation**:
How a change of Role reaches live sessions: losing a mapped Group — by SCIM
`PATCH`, `PUT` or the Group's `DELETE` — ends the User's sessions after commit;
gaining one applies at the next Login; and a session issued under a different
role mapping is ended at startup.

**Superuser Group**:
The one mapped Group whose Role holds every Permission: the Admin group. It
cannot be renamed or deleted and the Bootstrap Admin's membership of it is
frozen; every other mapped Group is writable and deletable.
_Avoid_: enabling

**Superuser Role**:
The Role the Superuser Group confers, required at startup to hold every
Permission.

**Deny by default**:
The rule that every operation of the application chain declares what it needs —
public, self-service or one Permission — and whatever declares nothing is
refused.

**Token Permissions**:
The Permissions a connector token carries, from the same vocabulary a Role
grants but only the four directory ones — `user:read`, `user:write`,
`group:read`, `group:write` — each enforced per resource type on the SCIM
interface. Write does not imply read. A token is issued with at least one; a
stored token holding none would authenticate and reach discovery alone.
_Avoid_: scope, `scim.read`, `scim.write` (they no longer exist)

**No escalation**:
The rule that a token may carry only Permissions its creator holds itself, on
issue and on rotation alike, so `connector:token` mints credentials but never
more power than its holder has; a refused attempt is audited with the
Permissions asked for.

**Baseline access**:
What every active User holds without any Role and without a required
password change: self-service (`/api/auth/me`, change-password, logout,
`/api/self`, `/api/session`), spelled `ROLE_USER` in the session and not a Role,
and the baseline Permissions `counter:read` and `counter:write`.

**Counter**:
Each User's own tally at `/api/count`, the application's sample feature, read
and changed through the baseline Permissions.

**Development fixtures**:
The development Role mapping's Groups and Users, seeded only when
`APP_DEV_FIXTURES_ENABLED` is on.

**Self-service**:
An operation needing a signed-in session and no Permission, acting only on the
session's own User.

**Authorization refusal**:
A `403` for a signed-in caller the operation does not admit — a Permission it
lacks, a route nothing declares, or a session confined to the password change.
Audited and logged with the caller, the operation and one generic reason, never
the Permission, Role or rule.
_Avoid_: forbidden (for a CSRF refusal, which is not an authorization decision)

### Authentication

**Login**:
The one operation that turns submitted credentials _or an EHR launch_ into a
session or a refusal. Its login method, `password` or `sso`, is recorded on the
audit trail's login events and on `session-start`.
_Avoid_: sign-on, authenticate (as a noun for the operation)

**EHR launch**:
Epic opening this application in the clinician's system browser, which signs the
clinician in through the Epic issuer instead of a password — the User whose
`userName` is exactly the Practitioner ID Epic proved. Every EHR launch is a fresh
Login, replacing whatever session the browser held. Recorded as login method `sso`.
_Avoid_: SSO, sign-on

**Epic issuer**:
The one Epic organisation a deployment trusts to sign clinicians in: its FHIR base
(`APP_EPIC_FHIR_BASE`, which every launch must name and every `fhirUser` sits
under) and its OpenID Connect issuer (`APP_EPIC_OAUTH_ISSUER`). Exactly one per
deployment, because a Practitioner ID is unique only within one organisation.
_Avoid_: SSO, sign-on, identity provider (for the organisation)

**Failure run**:
The consecutive failures recorded against one User: rejected Logins, and wrong
current passwords on the self-service password change. It counts password
refusals only: a refused EHR launch never lengthens it, because Epic checked the
credential, not this service.

**Lockout**:
The permanent state a User enters for one of two causes — its failure run
reaching the limit (`FAILURES`), or the dormancy job finding it past the lockout
window (`DORMANCY`); only an Admin's Unlock ends it. A lock keeps the cause it was
first imposed for, and the login refusal is the same bare `401` for either.

**Lock cause**:
Why a User is locked, `FAILURES` or `DORMANCY`, recorded beside the lock and
shown in the Users projection so a forgotten password and an abandoned account
can be told apart before unlocking.

**Unlock**:
An Admin holding `user:write` ending a User's lockout, whatever its cause, and
its failure run, and restarting its dormancy basis; it never changes `active`,
just as deactivation never ends a lockout.

**Bootstrap Admin exemption**:
The Bootstrap Admin's immunity from lockout and from both dormancy steps, so the
deployment always has a way back in.

**Change-required flag**:
The application's mark that a User's current password was imposed by someone
else and must be replaced before it may do anything else.
_Avoid_: password expiry, temporary password

**Forced password change**:
An Admin holding `user:write` setting another User's change-required flag and
ending its sessions, without ever seeing a password.

**Self-service password change**:
A User replacing its own password from its own session.

**Password policy**:
The one set of rules every path that sets a password applies: length, no
`userName` inside it, and no reuse from the password history.
_Avoid_: complexity rules (there are none)

**Password history**:
The User's three most recent passwords, the current one included, kept as
hashes so a new password can be refused for repeating one.

**Recovery guard**:
The rules that keep a deployment recoverable: no Admin may Unlock or
force-change its own User, whatever Permissions it holds (the Bootstrap Admin
included, which replaces its password by the self-service change), nobody may
force the Bootstrap Admin's change, and the Bootstrap Admin can never be locked.
There is no "last enabled administrator" guard: the Bootstrap Admin's frozen
Superuser Group membership is what keeps a holder of every Permission.

### Dormancy

**Dormancy basis**:
The instant a User's dormancy is measured from — its last real Login, completed
password change, reactivation or Unlock, or else its creation.

**Dormant User**:
A User whose dormancy basis is older than one of the dormancy windows: the
lockout window (90 days by default) or the longer role-revocation window (180).
_Avoid_: inactive User (that is a deactivated one)

**Dormancy verdict**:
What the dormancy job owes one User at one instant, decided from its dormancy
basis and "now" alone: not due, Lockout, or Lockout + Role revocation. It owns
the basis choice and both cutoffs, answers not due for the Bootstrap Admin, and
is the only place the job compares a basis with a window.

**Role revocation**:
The dormancy job's second step: removing every mapped-Group membership of a User
past the role-revocation window.

**Dormancy job**:
The scheduled job, daily at 04:00 Singapore time, that locks every unlocked
User past the lockout window with cause `DORMANCY`, and removes the direct
membership of every mapped Group from every User past the role-revocation
window. It never writes `active` and never deletes; the Bootstrap Admin is
exempt from both steps.
_Avoid_: inactivity deactivation, dormant-authority revocation (the two jobs it
replaced)

**Audit retention job**:
The scheduled job, daily at 03:30 by default, that deletes audit events older
than the retention period.

**Scheduled job lock**:
The per-job row a scheduled job run holds, so two runs of one job never overlap
across instances.

### Administration

**Accounts page**:
The administrative screen at `/accounts`, each view shown by its own Permission:
the Users projection (`user:read`) with Unlock and the forced password change
(`user:write`), the Groups projection (`group:read`), and connector management
(`connector:read`; create and delete `connector:write`, tokens
`connector:token`).

**Users projection**:
What administration may know about each User — never a credential.

**Groups projection**:
What administration may know about each Group: its name, member count, and
whether it is the Admin group.

**Roles listing**:
The read of the role mapping (`GET /api/admin/roles`, `group:read`): each Role,
its Permissions, and the Groups conferring it by stable id and `displayName`,
with the Superuser Group marked. API only; the SPA has no view of it.

**SCIM audit trail**:
The append-only history of provisioning, connector, authentication,
administrative and scheduled-job events, holding ids and classifications but no
profile or credential value.
_Avoid_: log (the application log is a different stream)

**Audit listing**:
The read of the audit trail, by a holder of `audit:read`.

**Audit page**:
The SPA screen at `/audit` showing the Audit listing, routed for a holder of
`audit:read` alone. Read-only, filtered and paged, and never reloaded on a
timer.

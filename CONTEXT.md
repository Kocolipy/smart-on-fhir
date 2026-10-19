# smart-on-fhir

The language of a session-authenticated web application whose identities are
provisioned by external directories over SCIM 2.0. This file says what each term
means; the rules behind them are in `/docs/domain/`, under the same names, one file
per area — each section below names its own.

## Language

### Request paths

Rules: [request-paths](docs/domain/request-paths.md).

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

**Error namespace**:
A reserved server path whose every refusal has one error body, whichever layer
refuses: `/api` answers with `ApiError`, `/api/patient` with a FHIR
`OperationOutcome`, `/scim/v2` with the SCIM error document. The most specific
namespace covering a request's path decides.
_Avoid_: error shape by package, error path

### Sessions

Rules: [sessions](docs/domain/sessions.md).

**Session status**:
What the SPA knows about the current session: `checking`, `unchecked`,
`authenticated` or `guest`. `unchecked` is a start-up check that did not
answer: the session is unknown, not ended, and the visitor is no Guest.

**Guest**:
A person with no authenticated session, and the session status that names them.
_Avoid_: Visitor, anonymous user

**Expired session**:
A session that was authenticated and that the backend has since ended, for
whatever reason; the SPA returns its holder to login saying so.

**Return destination**:
The protected path a Guest asked for before being sent to sign in, replayed once
they have.

**Home**:
The signed-in landing page at `/`: where a User arrives after signing in with no
Return destination, and where a successful Epic Login lands. The login page is
`/login`.

**Sign-in reason**:
Why a Guest is at the login page — an Expired session, an Idle sign-out, a
password change, a Lockout that a password change ran into, or an Epic Login
that was refused or found Epic unavailable — and so what the page says. A cold
arrival has none.
_Avoid_: provenance, login notice flag

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

### Directory

Rules: [directory](docs/domain/directory.md),
[scim-protocol](docs/domain/scim-protocol.md),
[connectors](docs/domain/connectors.md).

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

**Member label**:
How a Group's rendered members name each User: its `displayName`, or its
`userName` when it has none.

**Admin group**:
The server-seeded Group that is the Superuser Group; membership confers nothing
of its own beyond the Superuser Role.

**Bootstrap Admin**:
The seeded recovery User: an immutable member of the Admin group, never locked,
and not writable over SCIM.

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

**Edge whitespace**:
Whitespace (any Unicode `White_Space` character) at the start or end of a key —
a unique or looked-up User or Group attribute, a password Login's username, or an
Epic `id_token`'s `iss` or `sub` — which is refused rather than trimmed.
_Avoid_: padding, trailing spaces (the rule covers both edges and every kind of whitespace)

**Practical SCIM protocol profile**:
The part of SCIM 2.0 this application implements and advertises in discovery;
everything outside it is refused rather than ignored.

**SCIM release gate**:
The deployment switch (`APP_SCIM_ENABLED`) that, when closed, makes the whole
`/scim/v2` namespace answer `404` ahead of authentication.

### Authorization

Rules: [authorization](docs/domain/authorization.md).

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
to a Group by stable id.

**Mapped Group**:
A Group the role mapping names, and so one that confers a Role on its direct
members.

**Role assignment**:
Membership of a mapped Group, which is the only way a User holds a Role; so
`group:write` on a mapped Group is Role assignment, not a harmless Permission.
_Avoid_: role grant (for anything but the audited `ROLE_GRANT` event)

**Role-change propagation**:
How a change of Role reaches live sessions: losing one ends them, gaining one
waits for the next Login.

**Superuser Group**:
The one mapped Group whose Role holds every Permission: the Admin group.

**Superuser Role**:
The Role the Superuser Group confers, required at startup to hold every
Permission.

**Deny by default**:
The rule that every operation of the application chain declares what it needs —
public, self-service or one Permission — and whatever declares nothing is
refused.

**Token Permissions**:
The Permissions a connector token carries: the four directory ones, `user:read`,
`user:write`, `group:read` and `group:write`, from the same vocabulary a Role
grants.
_Avoid_: scope, `scim.read`, `scim.write` (they no longer exist)

**No escalation**:
The rule that a token may carry only Permissions its creator holds itself, on
issue and on rotation alike.

**Baseline access**:
What every active User holds without any Role and without a required password
change: self-service, spelled `ROLE_USER` in the session and not a Role, and the
baseline Permission `patient:read`.

**Development fixtures**:
The development Role mapping's Groups and Users, and the dummy Patients, seeded
only when `APP_DEV_FIXTURES_ENABLED` is on.

**Self-service**:
An operation needing a signed-in session and no Permission, acting only on the
session's own User.

**Authorization refusal**:
A `403` for a signed-in caller the operation does not admit — a Permission it
lacks, a route nothing declares, or a confined session — that never names the
Permission, Role or rule.
_Avoid_: forbidden (for a CSRF refusal, which is not an authorization decision)

### Authentication

Rules: [authentication](docs/domain/authentication.md),
[passwords](docs/domain/passwords.md),
[epic-login](docs/domain/epic-login.md).

**Login**:
The one operation that turns submitted credentials _or an EHR launch_ into a
session or a refusal. Its login method, `password` or `sso`, is recorded on the
audit trail's login events and on `session-start`.
_Avoid_: sign-on, authenticate (as a noun for the operation), database login
(a SQL Server principal, see Database)

**EHR launch**:
Epic opening this application in the clinician's system browser, which signs the
clinician in through the Epic issuer instead of a password — the User whose
`userName` is exactly the Practitioner ID Epic proved. Recorded as login method
`sso`.
_Avoid_: SSO, sign-on

**Epic issuer**:
The one Epic organisation a deployment trusts to sign clinicians in, named by its
FHIR base (`APP_EPIC_FHIR_BASE`) and its OpenID Connect issuer
(`APP_EPIC_OAUTH_ISSUER`).
_Avoid_: SSO, sign-on, identity provider (for the organisation)

**Epic tokens**:
The access token, refresh token and `id_token` from a successful EHR launch's
Login, held server-side on the session it signed in, for that session's life.
_Avoid_: SMART token, FHIR token, OAuth token

**MFA factor**:
The second factor an EHR launch's Login was made with, recorded on its
`LOGIN_SUCCESS`: `idp-attested`, or the factor the `id_token`'s `amr` named. A
password Login carries none.
_Avoid_: MFA method, authentication method, acr

**Failure run**:
The consecutive failures recorded against one User: rejected password Logins,
and wrong current passwords on the self-service password change.

**Lockout**:
The permanent state a User enters when its failure run reaches the limit or the
dormancy job finds it dormant; only an Admin's Unlock ends it.

**Lock cause**:
Why a User is locked, `FAILURES` or `DORMANCY`, recorded beside the lock.

**Unlock**:
An Admin ending a User's lockout, whatever its cause, and its failure run; it
never changes `active`, just as deactivation never ends a lockout.

**Bootstrap Admin exemption**:
The Bootstrap Admin's immunity from lockout and from both dormancy steps, so the
deployment always has a way back in.

**Change-required flag**:
The application's mark that a User's current password was imposed by someone
else and must be replaced before a password Login may do anything else.
_Avoid_: password expiry, temporary password

**Confined session**:
A session issued by a password Login while the User's change-required flag is set;
it may only change the password or log out.

**Forced password change**:
An Admin setting another User's change-required flag and ending its sessions,
without ever seeing a password.

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
The rules that keep a deployment recoverable: no Admin may Unlock or force-change
its own User, nobody may force the Bootstrap Admin's change, and the Bootstrap
Admin can never be locked.

### Dormancy

Rules: [dormancy](docs/domain/dormancy.md).

**Dormancy basis**:
The instant a User's dormancy is measured from — its last real Login, completed
password change, reactivation or Unlock, or else its creation.

**Dormant User**:
A User whose dormancy basis is older than one of the dormancy windows: the
lockout window or the longer role-revocation window.
_Avoid_: inactive User (that is a deactivated one)

**Dormancy verdict**:
What the dormancy job owes one User at one instant, decided from its dormancy
basis and "now" alone: not due, Lockout, or Lockout + Role revocation. The only
place the job compares a basis with a window.

**Role revocation**:
The dormancy job's second step: removing every mapped-Group membership of a User
past the role-revocation window.

**Dormancy job**:
The daily scheduled job that locks dormant Users and revokes the Roles of those
dormant for longer. It never writes `active` and never deletes.
_Avoid_: inactivity deactivation, dormant-authority revocation (the two jobs it
replaced)

**Audit retention job**:
The daily scheduled job that deletes audit events older than the retention period.

**Scheduled job lock**:
The per-job row a scheduled job run holds, so two runs of one job never overlap
across instances.

### Patients

Rules: [patient](docs/domain/patient.md). Decided in ADR 0014.

**Patient**:
The person care is about, as this service holds them: one NRIC, an official
name whose text is authoritative, a gender and a date of birth, served as a FHIR
R5 `Patient` resource at `GET /api/patient/{id}`. Age is derived from the date of
birth, never held.
_Avoid_: patient record, demographics, subject (a Patient is no User)

**Default patient**:
The one Patient Home shows until a SMART launch supplies the patient in context:
a development fixture under a fixed id, so it exists only where the development
fixtures are on.

**Patient card**:
Home's card showing the Default patient's name, NRIC, gender, date of birth and
age, or "No patient in context" when there is none.

### Database

Decided in ADR 0013. Every term here names a database principal, never the
sign-in operation: write "database login" or one of the qualified names below, and
keep a bare **Login** for the operation under Authentication.

**Database login**:
One of the three database identities the backend connects as — the owner
login, the app login and the retention login — each holding only its own
privileges, with no role switching on a connection.
_Avoid_: database role (the roles are what a login is a member of), `SET ROLE`,
a bare "login" (that is the sign-in operation)

**Owner login**:
The database login that owns the schema and runs migrations; only the migrate
step holds it, never the running service.
_Avoid_: admin user, master user (that is RDS's own)

**App login**:
The database login every request runs as, a member of `backend_app` only; it may
append audit events but never update or delete one.
_Avoid_: runtime role, service account

**Retention login**:
The database login only the audit retention job uses, a member of
`backend_audit_retention` only, holding `SELECT` and `DELETE` on audit events and
nothing else: the one principal that may delete an aged-out event, and no
principal may update one.
_Avoid_: retention role (the role it belongs to), audit admin

**Migrate step**:
The one-shot run of the service that applies the schema migrations as the owner
login and exits, before the service starts, so the service itself never holds
the owner credential.
_Avoid_: startup migration, Flyway at boot

### Administration

Rules: [administration](docs/domain/administration.md).

**Accounts page**:
The administrative screen at `/accounts`: the Users and Groups projections, Unlock,
the forced password change, and connector management, each view shown by its own
Permission.

**Users projection**:
What administration may know about each User — never a credential.

**Groups projection**:
What administration may know about each Group: its name, member count, and
whether it is the Admin group.

**Roles listing**:
The read of the role mapping (`GET /api/admin/roles`): each Role, its
Permissions, and the Groups conferring it. API only; the SPA has no view of it.

**SCIM audit trail**:
The append-only history of provisioning, connector, authentication,
administrative and scheduled-job events, holding ids and classifications but no
profile or credential value.
_Avoid_: log (the application log is a different stream)

**Audit listing**:
The read of the audit trail, by a holder of `audit:read`.

**Audit page**:
The SPA screen at `/audit` showing the Audit listing, for a holder of
`audit:read`.

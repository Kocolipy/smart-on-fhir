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
A set of Users in the SCIM directory with direct members only; it confers its
Group Permissions on its direct members.

**Member label**:
How a Group's rendered members name each User: its `displayName`, or its
`userName` when it has none.

**Admin group**:
The server-seeded Group, recognised by its `admin-group` reservation marker alone;
a direct member is the Superuser. It carries no Group Permissions.

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
`user:read` or `ops:read`; the set is closed and defined in code, not
configuration. The Superuser is not one.
_Avoid_: scope, right, privilege

**Group Permissions**:
The Delegatable Permissions a Group carries, stored per Group in the database
rather than configured, and held by each of its direct members from its next
sign-in. A Group with none confers nothing. Set only by the Superuser, as a
complete set (`PUT /api/admin/groups/{id}/permissions`), never on the Admin group
and never over SCIM, so `group:write` on a Group that carries Permissions decides
who holds them.
_Avoid_: Role, role mapping, mapped Group, Role assignment; role (for `ROLE_USER`,
baseline access)

**Permission-change propagation**:
How a change of what a User's Groups confer reaches live sessions: losing a
Permission — leaving a Group that carries it, the Group being deleted, or the
Superuser taking it off the Group — ends them after the commit; gaining one waits
for the next Login.
_Avoid_: role-change propagation

**Superuser**:
The session authority, spelled `ROLE_SUPERUSER`, of a User who is a direct member
of the Admin group. It is not a Permission, so no Group can carry it; its session
also holds every delegatable Permission, and `/api/auth/me` reports it as
`superuser`. Only the Superuser administers connectors and their tokens, and sets
a Group's Permissions.
_Avoid_: admin (for a holder of administrative Permissions, who is not a Superuser)

**Conferred power**:
What a User's direct Group memberships confer beyond baseline access, answered as
one: its effective Permissions (its Groups' Group Permissions, plus every
Delegatable Permission for a member of the Admin group) and whether it is the
Superuser. Sign-in, the Accounts list and every Permission change read it from the
same place.
_Avoid_: authority (the Superuser is the session authority, and Spring's
`GrantedAuthority` is any entry a session holds)

**Delegatable Permission**:
A Permission a Group may carry: `user:read`, `account:unlock`, `group:read`,
`audit:read`, `ops:read` and `connector:read`. `account:unlock` is the helpdesk
Permission: Unlock, which has no effect on a User that is not locked.

**Token-grantable Permission**:
A Permission a connector token may carry: `user:read`, `user:write`, `group:read`
and `group:write`. The write halves are token-only: no session holds `user:write`
or `group:write`, a Superuser's included.

**Deny by default**:
The rule that every operation of the application chain declares what it needs —
public, self-service, one Permission or the Superuser — and whatever declares
nothing is refused.

**Token Permissions**:
The Permissions a connector token carries: only Token-grantable Permissions, from
the same vocabulary a Group carries. A token with a write Permission is Superuser-equivalent, which is why only
the Superuser mints one.
_Avoid_: scope, `scim.read`, `scim.write`

**Baseline access**:
What every active User holds whatever its Groups carry, without a required
password change: self-service, spelled `ROLE_USER` in the session, and the
baseline Permission `patient:read`, which no Group carries.

**Development fixtures**:
The development Groups and their Users, the dummy Patients, and — under the
`local` profile alone — the development Groups' Permissions, seeded only when
`APP_DEV_FIXTURES_ENABLED` is on. Turned on outside the `local` profile they
fail startup, because they waive the Bootstrap Admin's first-login change.

**Self-service**:
An operation needing a signed-in session and no Permission, acting only on the
session's own User.

**Authorization refusal**:
A `403` for a signed-in caller the operation does not admit — a Permission it
lacks, a route nothing declares, or a confined session — that never names the
Permission, Group or rule.
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
`userName` is exactly the Practitioner ID Epic proved — and lands on Home with
its Launch context, which seeds the tab's EHR context. Recorded as login method
`sso`.
_Avoid_: SSO, sign-on

**Launch context**:
What an EHR launch's landing carried — the patient and encounter Epic's token
response put in context, each a FHIR id, either possibly absent — which seeds
the EHR context. Passed to the SPA in the landing's fragment, never kept
server-side, and gone from the URL once read: what the tab then holds is the
EHR context.
_Avoid_: SMART context, patient context (for the pair), EHR context (for what
the tab holds after the landing), `launch` (Epic's opaque launch parameter, a
different value)

**EHR context**:
What the EHR currently has open for this browser tab: today a patient and an
encounter, each a FHIR id, either possibly absent, with room for more members
later. Kept by the tab alone, never server-side, and seeded by an EHR launch's
Launch context.
_Avoid_: Launch context (for what the tab holds after the landing), SMART
context, patient context (for the pair), session context

**Epic issuer**:
The one Epic organisation a deployment trusts to sign clinicians in, named by its
FHIR base (`APP_EPIC_FHIR_BASE`) and its OpenID Connect issuer
(`APP_EPIC_OAUTH_ISSUER`).
_Avoid_: SSO, sign-on, identity provider (for the organisation)

**Epic tokens**:
The access token, refresh token and `id_token` from a successful EHR launch's
Login, held server-side on the session it signed in, for that session's life.
The access token alone is released to that session's SPA, as the **SMART access
token**.
_Avoid_: SMART token, FHIR token, OAuth token

**SMART access token**:
The one Epic token released to the SPA: the access token of the session's EHR
launch, fetched from `GET /api/auth/epic/fhircast` with its expiry, granted
scope and the session's FHIRcast Hub, and presented as `Bearer` only to register
at that hub (ADR 0016). The `id_token` and refresh token are never released.
_Avoid_: SMART token, Epic token (for this one alone), FHIRcast token

**FHIRcast Hub**:
Epic's local FHIRcast STU3 hub on the clinician's own workstation, which
announces the chart the clinician opens and closes (`Patient-open`,
`Patient-close`). An EHR launch's token response names it (`hub.url`) and the
clinician's topic (`hub.topic`); the session keeps the pair only when the hub is
on `127.0.0.1` and the topic is well formed, and the SPA connects to it for
the whole session, on every signed-in page, while the tab holds an EHR context.
_Avoid_: FHIRcast server, context hub, Medplum (the local stand-in for it)

**Hub Token**:
The bearer token the FHIRcast Hub issues the SPA by the `jwt-bearer` grant,
after the SPA registered a key of its own with the SMART access token. It
authorizes the Hub Subscription, and is the hub's credential, not one of the
Epic tokens. Revoked, best-effort, when the session ends.
_Avoid_: FHIRcast token, access token (for this one)

**Hub Subscription**:
The SPA's websocket subscription, with the Hub Token, to `Patient-open` and
`Patient-close` on the clinician's topic. Connected once the hub confirms it on
the socket, made anew before its lease or its Hub Token runs out, and when the
hub says its endpoint has gone. It lasts the session, not Home: leaving Home and
coming back neither closes its socket nor subscribes again, and the subscription
is cancelled at the hub (`hub.mode=unsubscribe`) and its socket closed when the
session ends. Once connected, every Event Notification on the socket is acknowledged or
ignored.
_Avoid_: listener, FHIRcast session

**Event Notification**:
A FHIRcast STU3 message the FHIRcast Hub sends on a connected Hub Subscription's
socket: a `timestamp`, an `id`, and an `event` naming `hub.topic`, `hub.event`
and a `context`; only a well-formed `Patient-open` or `Patient-close` on the
clinician's topic is acknowledged, with its `id`, and shown on the FHIRcast card
as the event, its time and the Patient's FHIR id.
_Avoid_: FHIRcast message, context change, push

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
never changes `active`, just as deactivation never ends a lockout. Only while a
lockout is in force: an Unlock of a User that is not locked is refused and
changes nothing.

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
The rules that keep a deployment recoverable: no Admin may Unlock its own User,
and the Bootstrap Admin can never be locked.

### Dormancy

Rules: [dormancy](docs/domain/dormancy.md).

**Dormancy basis**:
The instant a User's dormancy is measured from — its last real Login, completed
password change, reactivation or Unlock, or else its creation.

**Dormant User**:
A User whose dormancy basis is older than one of the dormancy windows: the
lockout window or the longer revocation window.
_Avoid_: inactive User (that is a deactivated one)

**Dormancy verdict**:
What the dormancy job owes one User at one instant, decided from its dormancy
basis and "now" alone: not due, Lockout, or Lockout + Membership revocation. The
only place the job compares a basis with a window.

**Membership revocation**:
The dormancy job's second step: removing every direct Group membership — the
Admin group's included, the Bootstrap Admin's exempt — of a User past the
revocation window, recorded as one `DORMANCY_MEMBERSHIP_REVOCATION`.
_Avoid_: role revocation

**Dormancy job**:
The daily scheduled job that locks dormant Users and revokes every Group
membership of those dormant for longer. It never writes `active` and never deletes.
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

**Patient card**:
Home's card showing the EHR context's patient — name, NRIC, gender, date of
birth and age — or "No patient in context" when the tab holds no EHR context,
its EHR context names no patient, or no Patient is held under its id. With no patient in context it reads nothing.

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
The administrative screen at `/accounts`: the Users and Groups projections, Unlock
of a locked User, setting a Group's Permissions (the Superuser only), and
connector management, each view shown by its own Permission.

**Users projection**:
What administration may know about each User — never a credential.

**Groups projection**:
What administration may know about each Group: its name, member count, whether
it is the Admin group, and its Group Permissions.

**Permissions listing**:
The read of every Delegatable Permission (`GET /api/admin/permissions`), for a
holder of `group:read`: what any Group could carry. API only.
_Avoid_: Roles listing

**SCIM audit trail**:
The append-only history of provisioning, connector, authentication,
administrative and scheduled-job events, holding ids and classifications but no
profile or credential value.
_Avoid_: log (the application log is a different stream)

**Audit listing**:
The read of the audit trail, by a holder of `audit:read`.

**Permission change**:
An audit event of a User gaining or losing direct membership of a Group that
confers Permissions, or of the Admin group: `PERMISSION_GRANT` or
`PERMISSION_REVOKE`, recording the User's effective Permissions before and after
and whether the change granted or removed the Superuser. A Group that confers
nothing records none.
_Avoid_: role change

**Group Permissions change**:
An audit event of the Superuser setting a Group's Permissions:
`GROUP_PERMISSIONS_CHANGE`, naming the Superuser and the Group and recording the
Group's Permissions before and after; a refused one is a failure with a generic
reason and no Permission. Not a Permission change: no membership moved.
_Avoid_: role edit, permission assignment

**Audit page**:
The SPA screen at `/audit` showing the Audit listing, for a holder of
`audit:read`.

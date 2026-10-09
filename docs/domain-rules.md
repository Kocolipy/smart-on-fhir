# Domain rules

The behavior behind the terms in `/CONTEXT.md`: what each concept does, the
invariants it keeps, and why. `/CONTEXT.md` is the glossary and says only what a
term means; this file is where the rules live, under the same term names. Where
a rule is also a wire contract, `backend/docs/openapi.yaml` is the authority on
statuses and bodies, and the ADRs in `/docs/adr/` record the decisions.

## Request paths

**Reserved server path** — a request path the backend answers for itself:
`/api`, `/actuator` and `/scim`, each reserving both the exact path and everything
beneath it. Reserved paths are authenticated by the filter chain and keep their
own error responses. `SpaRoutes.isReservedServerPath` is the only place the list
lives. `/scim` is reserved for a reason worth stating: without it a mistyped SCIM
path would be read as a client-side route and answered with the SPA shell and a
`200`, which a provisioning client would parse as a successful empty response
rather than as an error.

**SPA shell** — `index.html`, the single document the single-page application
boots from. A path with no server-side handler is _forwarded to the shell_ when
it belongs to the client-side router, which is how a deep link such as
`/showcase` survives a page reload.

**File request** — a path whose **last** segment contains a dot
(`/assets/index-a1b2c3.js`, `/favicon.ico`). A missing file request stays a 404;
it is never answered with the SPA shell, or a broken asset URL would return HTML
with a 200. A dot in an earlier segment (`/v1.0/settings`) does not make a path
one.

Avoid "frontend route" as a term: it blurred two different questions — whether a
path may be served without authentication (true of file requests) and whether a
missing path should become the shell (false of them) — which is how two modules
came to implement it twice and disagree.

## Sessions

**Session status** — what the SPA currently knows about the visitor's session:
`checking` before the one start-up check has answered, then `authenticated` or
`guest`. It is a _condition_, not an event, which is why a session ending is not
a fourth member. `resolveSessionRoute` is the only place the status decides what
a route does.

**Guest** — the session status of a person with no session. The status a cold
arrival starts in, and
the one a sign-out returns to.

**Expired session** — a session that _was_ authenticated and which the backend
has since refused with a `401`. The status becomes `guest` either way; what
distinguishes an expired session is its provenance, carried as `sessionExpired`
and passed into the redirect so the login route can say the session ended rather
than greeting a stranger. A `403` is **not** an expired session: a stale CSRF
token is re-fetched and the request retried once, and a `403` that survives that
is an authorization refusal. The session survives both.

**Return destination** — the protected path a visitor asked for before being
redirected to sign in, recorded as `from` in router state and replayed once the
status turns `authenticated`. Owned by the route guards, so no page navigates on
its own behalf after signing in.

**Idle timeout** and **absolute session lifetime** — the two independent bounds
on every authenticated session, an Admin's included: 15 minutes without a request
(`SESSION_TIMEOUT`) and 8 hours from creation (`APP_SESSION_ABSOLUTE_LIFETIME`).
Whichever is reached first ends the session, and the SPA cannot tell which; both
present as an expired session.

**Idle sign-out** — the SPA's own end of an idle session, timed by the
`idleTimeoutSeconds` the backend reports rather than a constant of the SPA's.
Only user input counts as activity, never a request, and it is shared across tabs;
a warning a minute before the limit offers to stay signed in, and staying is a real
request that renews the backend's clock too. At the limit the SPA logs out and
returns to login marked `inactive` — the code's name for this provenance, not the
glossary's. `/frontend/AGENTS.md` ("Backend contract") is the contract.

**One session per User** — an accepted Login ends every other session the User
holds, so signing in from a second browser signs the first out; the first sees an
expired session. It is one of the **session revocation** triggers below.

## Epic Login

A Login by **EHR launch**: Epic opens the application in the clinician's system
browser, Epic's OpenID Connect provider proves who the clinician is, and the
session is then built exactly as password Login builds it. Password Login is
unchanged, and a User may hold both. ADR 0013 records the decisions and the flow;
`backend/docs/openapi.yaml` holds the three browser routes.

**Linking by `userName`** — an Epic clinician is a User because the SCIM
connector provisioned it with `userName` equal to the clinician's Practitioner
FHIR ID, which the directory holds. There is no separate link, no schema change,
and no just-in-time provisioning: an Epic Login never creates or modifies a User,
so nobody Epic can authenticate gets an account the directory did not give them.

**Exact match** — the Practitioner ID comes from the `id_token`'s `fhirUser`,
which must be exactly `{APP_EPIC_FHIR_BASE}/Practitioner/{id}`: the deployment's
own FHIR base, the `Practitioner` type, a non-blank id and nothing after it. The
User is found through the normal normalized-`userName` lookup and then accepted
only when its stored `userName` equals the id character for character. Epic IDs
are case-sensitive and normalization lowercases, so `eabc` never signs in the
User provisioned as `eABC`.

**Refusals** — the login decision refuses an Epic Login, in this order:

| The Practitioner ID names                                             | Refused as         |
| --------------------------------------------------------------------- | ------------------ |
| no User whose stored `userName` equals it exactly, a case variant too | `UNKNOWN_ACCOUNT`  |
| the Bootstrap Admin                                                   | `UNKNOWN_ACCOUNT`  |
| a deactivated User                                                    | `ACCOUNT_DISABLED` |
| a locked User, whatever the cause: a failure run or dormancy          | `ACCOUNT_LOCKED`   |

**The Bootstrap Admin is excluded**, recognised by its reservation marker and
never by its name, so no rename moves the exclusion and no other User acquires
it. It is refused exactly as a name that matches nobody is, so a refusal never
says the account exists. Password Login stays its recovery path: Epic proving a
clinician is never a way into the deployment's last account.

A refusal lands the browser at `/?signin=refused` with no detail, its session
ended first whoever it belonged to, and the login page shows only "Sign-in from
Epic was refused". It is audited once, as a `LOGIN_FAILURE` with login method
`sso`, its reason, and the refused User's stable id — none for
`UNKNOWN_ACCOUNT`, so an ID that matched nobody leaves no trace of itself. The
reason is the audit trail's alone: the operational log says only "Epic sign-in
refused", because the reason tells whether an account exists.

**Epic being unavailable is not a refusal.** When Epic does not answer in time,
or answers with a server error, on any of the calls a Login makes, the launch
lands at `/?signin=unavailable`, its session ended the same way, and the login
page says to try again shortly. It names no User — none was ever resolved — and
is audited as a `LOGIN_FAILURE` with login method `sso` and `EPIC_UNAVAILABLE`,
counting toward no failure run.

**A refusal never lengthens a failure run.** Epic checked the credential, not
this service, so a refused launch is no evidence of guessing — and counting it
would let a clinician whose Epic ID is a case variant of a User's `userName`
lock that User out by launching. Lockout and deactivation still refuse an Epic
Login exactly as they refuse a password one; Epic's word unlocks and reactivates
nobody.

**One organisation per deployment** — a bare Practitioner ID is unique only
within one Epic organisation, so a deployment trusts exactly one **Epic
issuer**: every launch's `iss` must equal `APP_EPIC_FHIR_BASE` exactly, never
normalized, and the `id_token` must come from `APP_EPIC_OAUTH_ISSUER`.
Non-production and production are separate deployments. Because the issuer is
fixed, it is not repeated on each audit event or stored per User.

**Every launch is a fresh Login** — the launch ends whatever session the
browser held, whoever it belonged to, and a refused launch leaves the browser
signed out: a shared workstation is never left signed in as the previous User. A
successful Epic Login is recorded as a password Login is — failure run cleared,
dormancy basis moved, `LOGIN_SUCCESS` with login method `sso`, every other
session of the User revoked — so **one session per User** holds across both
Login paths. The authorities are the ones password Login gives the same User with
the change-required flag clear: an Epic Login is **never a confined session**,
because it presents no password of this service's and the flag marks an imposed
password. A flagged User signed in through Epic holds its Permissions, `GET
/api/auth/me` reports `passwordChangeRequired: false`, and the Login moves the
dormancy basis even while the flag is set. The flag itself is neither read into
the session nor cleared, so the same User's password Login is still confined
(ADR 0008's 2026-10-09 addendum).

**Identity, and the Epic tokens for the session** — the Login decides who signed
in from the `id_token` alone. A successful Epic Login then keeps the **Epic
tokens** — Epic's access token, with its expiry and granted scope, the refresh
token when Epic issues one, and the `id_token` — server-side, on the session it
signed in and under that session's signed-in id only. A refused or unavailable
launch keeps nothing, and a password Login's session holds none. The tokens live
exactly as long as the session: logout, the idle timeout, the absolute session
lifetime, every session revocation and the next launch in that browser each
leave nothing to retrieve, and a session holding them is stored no longer than
its remaining absolute lifetime, however recently it was used. An expired
access token ends nothing; the refresh
token and the `id_token` stay with the session. Only backend code reads them, by
session: no response carries them, `/api/auth/me` included, and the SPA holds no
Epic credential. The patient and encounter context and the rest of the token
response are discarded, and no FHIR API is called yet. Today Epic issues no
refresh token, because the Login asks only for `launch openid fhirUser`
(ADR 0013's 2026-10-09 addendum).

**Input bounds** — what the browser hands a Login is checked before it is held,
sent to Epic or redeemed:

| Input              | Bound                                                                                              | Refused as       |
| ------------------ | -------------------------------------------------------------------------------------------------- | ---------------- |
| `iss` (launch)     | present, and exactly `APP_EPIC_FHIR_BASE` as a string: no case folding, no trailing-slash trimming | `ISS_MISMATCH`   |
| `launch` (launch)  | 1–8192 characters of printable ASCII (`!` to `~`), no whitespace                                   | `INVALID_LAUNCH` |
| `state` (callback) | the pending request's own, compared in constant time                                               | `INVALID_STATE`  |
| `code` (callback)  | 1–8192 characters of printable ASCII, no whitespace                                                | `INVALID_CODE`   |

A refused input is logged by its field and the rule it broke — `missing`,
`length`, `charset` or `mismatch` — and never by its value.

**Single use** — the pending authorization request a launch leaves (its `state`,
nonce and PKCE verifier) belongs to the browser session that made it, and the
first callback takes it, before checking anything. A second callback with the
same `state` — replayed, or racing the first — finds nothing and is refused as
`INVALID_STATE` without Epic being called, so at most one of them signs anyone
in. Epic's authorization codes are single-use besides.

**Protocol refusals** — anything else short of a Login lands at
`/?signin=refused` exactly as an account refusal does, its session ended, and
is audited once with no subject and its reason: Epic answering the callback with
an OAuth error (`IDP_ERROR`), Epic's token endpoint refusing the code
(`TOKEN_EXCHANGE_FAILED`), an `id_token` whose signature does not verify or is
not RS256 (`INVALID_SIGNATURE`), one whose `iss`, `aud`, `azp`, `exp`, `iat`
(30 seconds of clock skew either way) or `nonce` fails (`INVALID_CLAIMS`), and a
`fhirUser` that is not a Practitioner of this deployment's FHIR base
(`INVALID_FHIR_USER`).

**MFA** — an Epic Login is multi-factor because the Epic organisation enforces
MFA at its own sign-in. Until Epic confirms the `id_token` says so, that is an
attestation, and each Epic `LOGIN_SUCCESS` records its factor as `idp-attested`.
Once `APP_EPIC_MFA_EVIDENCE_REQUIRED` is on, the `id_token`'s `amr` must name a
second factor (or `mfa`), a token without one is refused as `INVALID_CLAIMS`,
and the factor recorded is the one `amr` named. Password Login carries no factor.

**Epic signs nobody out** — an Epic session is an ordinary session from the moment
it starts, bounded by the idle timeout and the absolute session lifetime, and
ended by the next launch in that browser. Signing out of Epic does not end it.
Ending it drops the Epic tokens it holds but does not revoke them at Epic: the
access token stays valid there until it expires, and a refresh token, once one
is issued, for its own lifetime. ADR 0013 records both as an accepted risk (D13).

**A suspected compromise is a deactivation** — an Epic-linked User suspected
compromised is deactivated in the directory, which revokes its sessions once the
deactivation commits and refuses every later launch as `ACCOUNT_DISABLED`; the
Epic organisation, whose credential it was, is told. A forced password change does
not apply: an Epic Login presents no password of this service's (D20), so the
change-required flag it sets confines only the User's password Login. It still
ends every session the User holds, an Epic one included.

**Never recorded** — the launch's `launch`, the callback's `code` and `state`, the
nonce, the PKCE verifier, Epic's `id_token`, access token and refresh token, our
client assertion and our signing keys appear in no log record and no audit event
(D22).

## Identity provisioning

### SCIM target model

**SCIM service provider** — the role this application plays for identity
provisioning: an external identity provider calls the application's SCIM v2
interface to create and manage Users and Groups. SCIM provisioning does not
perform an end-user Login; password authentication and its session remain a
separate application capability.

**SCIM directory** — the single User-and-Group namespace owned by one application
deployment. It is not partitioned by tenant. Multiple independently authenticated
connectors holding write Permissions may operate on the same directory; resource
versions and conditional writes provide their shared concurrency seam.

**Connector external identifier** — one connector's `externalId` alias for a User
or Group. A resource has one stable, directory-wide SCIM `id` but may have a
different `externalId` for each connector — RFC 7643 §3.1 makes `externalId` the
provisioning client's, always scoped to its provisioning domain, and a connector
is that domain here. Reads and filters expose only the
calling connector's alias, so independent client namespaces cannot collide. When
a connector is deleted, all of its aliases are deleted too and its namespace may
be reused by a future connector.

**Deleted SCIM connector** — a connector removed by an Admin. Deletion revokes
every token it holds and deletes every one of its `externalId` aliases in a single
transaction, and is not a row removal: the connector record survives with a
deletion timestamp so an audit event that names it still resolves for as long as
the trail is retained. Users and Groups are untouched — an alias is a connector's
name for a resource, not the resource. A deleted connector stops being listed and
stops authenticating at once, and its `externalId` namespace becomes available
again. Deleting one twice is refused, because an Admin repeating a delete is
likelier to have the wrong id than to want a second no-op.

**SCIM connector token** — a high-entropy opaque bearer credential restricted to
the SCIM interface. The value is a non-secret lookup handle, a dot, and at least
256 bits of `SecureRandom` material; only a SHA-256 digest of the **complete**
value is stored, compared in constant time. An unstretched hash is right only
because the value is full-entropy random material rather than a password, and is
no precedent for hashing a password that way. A token carries **Token Permissions**
(ADR 0010): a non-empty set of `user:read`, `user:write`, `group:read` and
`group:write`, and nothing else — every other Permission guards the application
chain, which no token reaches, and is refused on a token with `400`, as are an
empty list and a name that is no Permission. Over SCIM, Users reads (`GET`, and
`POST /Users/.search`) need `user:read` and Users `POST`/`PUT`/`PATCH`/`DELETE`
need `user:write`; Groups likewise with `group:read` / `group:write`. Write does
not imply read. A base `/.search` needs at least one read Permission and returns
only the resource types the token may read — a `user:read`-only token gets Users
and no Groups, as if the directory held none. Discovery (`ServiceProviderConfig`,
`Schemas`, `ResourceTypes`) needs a valid token and no Permission, and `/Me` is
`501` to any valid token. All of it is decided in the SCIM chain's filter from
the request's method and path, before any handler runs, so no handler carries a
check of its own; a path no endpoint serves needs only a valid token and is a
`404`. A refusal is `403` with `insufficient_scope` and no body, logged at `WARN`
and audited as `ACCESS_DENIED` naming the connector, the operation and the
generic reason — never the missing Permission. Issuing requires at least one
Permission; a stored token holding none still authenticates and reaches
discovery alone.

**No escalation** — a token may carry only Permissions the administrator minting
it holds itself, by the Permissions its session was issued with. A request for
anything more is `403` and audited as a failed `CONNECTOR_TOKEN_ISSUE` (or
`_ROTATE`) with `PERMISSION_ESCALATION` and the Permissions requested; the check
runs after the request's own shape and after the connector is found. Rotation is
minting, so it is held to the same rule whether it keeps the old token's
Permissions or is given new ones: a token a Superuser issued cannot be renewed
by a Connector admin lacking any of its Permissions. A successful issue or
rotation is audited with the Permissions granted. Whoever holds `connector:token`
together with `group:write` can therefore issue a credential that assigns Roles,
because `group:write` on a mapped Group is Role assignment.

A holder of `connector:token` may mint, inspect, overlap, rotate, and revoke
tokens; plaintext is disclosed once, on the issue and rotation responses alone,
under `Cache-Control: no-store`. A token expires at most 365 days after issue —
RFC 7644 §7.4 requires bearer tokens to have a limited lifetime the service
provider can determine — and 365 days is both the default and the hard maximum: a shorter lifetime may be
chosen, a longer one is refused rather than silently clamped. Rotation mints a
replacement carrying the old token's Permissions, or the ones it is given, with a
fresh full lifetime and brings the old token's expiry **forward**
to the end of an overlap window of at most 14 days, never past the expiry the old
token already had; a second rotation therefore cannot undo the first one's
shortening. Revocation and expiry are immediate and indistinguishable to the
connector: the only credential refusals the interface makes are a bare `Bearer`
challenge when no credential was presented, `invalid_token` for a malformed,
unknown, expired or revoked one or one whose connector is deleted, and
`insufficient_scope` for a valid token lacking the request's Permission. The
token is accepted from the `Authorization` header — the one method RFC 6750 §2.1
requires — and from nowhere else: a query string, a form body and a cookie are
not rejected but never consulted, because URLs are routinely logged. A token
cannot reach `/api/**`: the application chain does not read bearer credentials.

**User** — the one domain identity: Login authenticates against `scim_users`, and
authority is the Permissions the role mapping confers through direct Group
membership. It owns the selected core User profile, stable SCIM id and version, active state,
encoded password, creation metadata, and recent login history. Its profile
round-trips `userName`, the calling connector's `externalId`, `active`, `name`,
`displayName`, `emails`, locale and time-zone attributes, Groups, and SCIM
metadata. The Enterprise User extension and application-specific extensions are
not supported in the first release. SCIM may set the password as a write-only
provisioning attribute; the application hashes it immediately and never returns
it. SCIM may rename `userName` under its uniqueness rule while preserving the
stable SCIM resource id. That rule, and Login, compare the normalized form:
Unicode NFKC and case folding, the mapping half of PRECIS UsernameCaseMapped
(RFC 8265), which RFC 7644 §5 requires. A password or username change, deactivation, deletion, or removal from a
mapped Group revokes the User's existing sessions so a stale login principal never
survives a security change (see **Session revocation**). Failure
runs and lockouts remain application-owned authentication behavior on the User.

**Normalized SCIM storage** — the PostgreSQL representation of the target model.
Selected User fields use relational columns, while emails, Groups, memberships,
connector aliases, resource versions, connector tokens, audit events, and
tombstones use constrained related tables. JSON resource blobs are not the
source of truth; supported filters and uniqueness are backed by relational
indexes and constraints.

**Bootstrap Admin** — the local recovery User excluded from SCIM write
authority so an administrator can recover the application when external
provisioning is unavailable or has removed every SCIM-managed administrator. It
is visible through the SCIM interface as a read-only User and an immutable member
of the Admin group: clients may discover its current state and authority, but no
SCIM operation may mutate or delete the User or remove that membership. It is the
only User every deployment is seeded with: startup creates it when absent and
never overwrites it, so a rotated recovery password survives a restart.

**Group** — a SCIM resource whose membership is the source of elevated application authorization. Every active User receives
baseline User access without requiring membership in a redundant Users group.
A Group may contain direct User members only; Group-valued members and transitive
membership are unsupported. Users and Groups enter the SCIM interface together;
they are not separate future capabilities.

**Admin group** — the server-seeded Group that is the **Superuser Group**: its
members hold the Superuser Role, and with it every Permission, in addition to
baseline User access. The Group confers authority only through the Role the mapping assigns it. Its stable resource id carries that
meaning: SCIM may change ordinary membership but may neither rename nor delete
the Group, nor remove the Bootstrap Admin's membership. These protections are
the Superuser Group's alone: every other mapped Group is an ordinary Group over
SCIM — renamable, deletable, its membership writable — so an identity provider
can retire a helpdesk Group without touching this application's configuration.

**SCIM tombstone** — the privacy-minimal record retained after SCIM deletion: the
resource type, stable resource id and deletion time (UTC), and nothing else. Its
table has no column that could hold a profile, credential or membership value, and
the application may insert and read tombstones but never change or remove one. It
holds no hash of a former identifier, by design: historical correlation is by
stable id through the audit stream. Tombstones never
participate in uniqueness checks, as RFC 7644 §3.6 recommends of deleted
resources: a former `userName`, Group `displayName` or
connector-scoped `externalId` may be reused by a future resource. Readable profile
and audit detail expire under the configured audit-retention policy.

**Deleted User** — a User removed with `DELETE`. Deletion removes its
live rows — profile, emails, credential and password history, Group memberships
and connector aliases — advances the version of every Group it belonged to,
revokes its sessions once the deletion commits, and leaves a SCIM tombstone. Every
later operation on its id is `404`. It is not merely a deactivated User, and nothing
readable about it survives outside the audit stream; the tombstone is the only
row that does. The Bootstrap Admin never enters this state because it cannot be
deleted.

**Deleted SCIM Group** — an ordinary, non-Admin Group removed with `DELETE`.
Deletion removes its memberships, makes it unavailable through SCIM, and leaves
a SCIM tombstone. A deleted mapped Group takes its Role from every member, each
recorded as a `ROLE_REVOKE` and signed out after commit, exactly as removing them
one by one would; the mapping still names its id, but it confers nothing until
the deployment replaces the mapping. The Admin group never enters this state
because it cannot be deleted.

**Practical SCIM protocol profile** — discovery at `/ServiceProviderConfig`,
`/ResourceTypes`, and `/Schemas` for any valid connector token, whatever its
Permissions, followed by Permission-checked User and Group CRUD, PATCH, filtering, sorting, pagination,
conditional writes with ETags, and standard SCIM errors. A User may be created
without `password`; its `active` value remains authoritative, but password Login
returns the same bare `401` as any rejected credentials until a later SCIM write
sets one. Collection requests default `count` to 100 and clamp it to 200, while
returning `totalResults`, one-based `startIndex`, and `itemsPerPage`. Filtering
implements the complete RFC 7644 grammar over supported attributes, including
comparison, presence, boolean, grouping, and value-path expressions; unsupported
paths fail predictably rather than being silently misread. "Complete" means full
syntax and semantics over the attributes this service advertises: it makes no
unadvertised attribute queryable, and a filter on `password` is
`400 invalidFilter`. Sorting takes one
attribute, puts missing values last ascending and first descending, and breaks ties
by `id`. The same query may be sent as a `SearchRequest` body to `/Users/.search`,
`/Groups/.search`, or the base `/.search`, which spans both types and treats an
attribute one type lacks as having no value there. `PUT`, `PATCH`, and
`DELETE` of an existing resource accept an optional `If-Match`, as RFC 7644
allows: without one the write is applied unconditionally, last writer wins; when
sent it must be exactly one strong ETag, checked after authorization and
existence: a wildcard, list or malformed one is `400 invalidValue`, and a stale
version is `412`. ETags are strong although RFC 7644's examples show weak ones,
because HTTP `If-Match` compares strongly and this profile promises exact
lost-update protection; a weak `W/` tag therefore never matches. Concurrent writers holding the same ETag are serialized on the
resource, so exactly one succeeds; concurrent unconditional writers are serialized
too, so both succeed and neither is half-applied. Unconditional writes are counted
per connector so an operator can see which integrations run without lost-update
protection. `externalId` is read-write on Users and Groups and writes
only the calling connector's alias: a `PUT` sets it to the submitted value or, when
omitted, removes it, and `PATCH` `add`/`replace`/`remove` set or clear it. Another
connector's alias for the same resource is never read or written. A password set
through `PUT` or `PATCH` is refused
when it matches, after normalization, any of the User's three most recent passwords,
the current one included. The first release advertises Bulk as
unsupported rather than implementing a partial `/Bulk` endpoint. Acceptance is
defined by the RFC contracts rather than behavior specific to Microsoft Entra
ID, Okta, or another vendor. The application adds no rate limiter, SCIM or
otherwise: per-request safety bounds are not rate limits, and throttling
`/scim/v2/**`, Login and the self-service change is the deployment edge's job,
specified in `infra/README.md` ("Edge throttling").

The per-request safety bounds — request body, filter length, depth and node
count, PATCH operations, page size (`ScimRequestLimits`, `ScimFilterParser`,
`ScimPageRequest` hold the numbers), and the Login and password-change field
lengths — fail with the closest standard error (`413`, `400 invalidFilter`,
`400 invalidValue`) and never partially mutate a resource. An over-length Login
or change-password field is a `400` from request validation, before
authentication runs: it is not counted toward the failure run, not audited, and
indistinguishable from any other malformed body, so it adds no enumeration
signal. The password fields' bound is the password policy's own maximum, so no
password the policy accepted is ever refused for its length.

Every capability above is implemented, and `ServiceProviderConfig` advertises
`patch`, `filter`, `sort`, `etag` and `changePassword` as supported. Bulk's
`supported: false` is permanent. The rule the profile was reached under still
binds anything added later: discovery advertises a capability only once it is
implemented, and a request for one that is not is refused rather than ignored —
an ignored `filter` is indistinguishable from a match, which is the one failure a
connector cannot detect.

**Resource version** — every resource whose rendered document a SCIM write
changed advances its version exactly once, and no other resource advances; the
version is the resource's strong ETag and its `meta.version`.
`RepresentationChange` is the one place that decides who moved. A User's
document lists its Groups by `displayName`, and a Group's lists its members, so:

- a User's own write — replacement, `PATCH`, an `active` change, a completed
  password change — advances that User alone;
- a Group membership that starts or stops advances the Group and each User whose
  membership changed, and a member removed and re-added in one write advances
  neither;
- a Group rename advances the Group and every member, before and after;
- Group creation advances each initial member, and Group deletion each former
  member; User deletion advances each of its Groups;
- a Group's `externalId` alias written alone advances the Group alone;
- a Group write that changes neither name, membership nor alias advances
  nothing, not even the Group, so a connector converging on a desired state does
  not invalidate every cached copy on each re-send.

**SCIM release gate** — `APP_SCIM_ENABLED` (on by default). Closed, the whole
`/scim/v2` namespace answers `404` with a SCIM error body, discovery included and
ahead of authentication, so a valid token, an expired one and none at all get the
same answer and the gate cannot be probed for the interface behind it. `404`
rather than `403` or `503`, because either of those would disclose a surface that
is not being offered. The backend README ("SCIM release gate") is the
configuration reference.

**SCIM audit trail** — the append-only local history of provisioning and connector
token activity, and of the authentication, lockout, administrative, password-change
and scheduled-job events recorded in the same stream. An event records the actor's
stable id — the Admin or User for an authentication or administrative event, the
connector (never the token) for a SCIM or token-lifecycle one, nobody for a
scheduled job — plus operation, resource type and id, outcome, changed attribute
paths, error classification and timestamp; it never records a password, bearer
token, hash, `userName` or other profile value. Every collection query and search is
one bulk-read event carrying the number of resources returned and the filter's
shape (`userName eq ?`), never its values; a single-resource read is not recorded.
Admins read it through the **audit listing** (`GET /api/admin/audit-events`): newest
first, paginated, filterable by operation, outcome, actor, resource and time window.
The listing returns the stored events as they are — redaction lives in what an event
can hold, not in the read — and reading the trail is not itself recorded. The
Accounts page has no audit view yet; this listing is the read one would be built on.

**Audit retention job** — the second scheduled job, daily at 03:30 by default on
its own scheduled job lock row, deleting every audit event older than the
retention period: 365 days unless deployment configuration says otherwise, and
never less than 90. A configured period below the floor stops startup rather than
shortening the trail, because 90 days is the shortest window in which an
investigation into a User's activity is still possible.

### Access model

**Guest** — a person with no authenticated session. A Guest may use only the
login page; asking for a protected route records the return destination and
sends them there.

**User** — any identity in the SCIM directory, signed in or not. Every active
User with a session receives baseline access — self-service: `GET /api/auth/me`,
its own password change, logout, `/api/self` and `/api/session` — and nothing
else without a Permission, in the browser or over the API. Baseline access is
`ROLE_USER`, granted to every session not confined by
the change-required flag, whatever Roles it holds or lacks, so a misconfigured
mapping can never take self-service away.

**Admin** — a User holding at least one administrative Permission through its
Roles. There is no administrative authority of its own: what an Admin may do is
exactly its Permissions — a helpdesk operator holding `user:read` and
`user:write` sees and unlocks Users, and nothing more.

**Deny by default** — every operation of the application chain declares what it
needs, and whatever declares nothing is refused. Three kinds of operation:

- **Public** — `POST /api/auth/login`, `GET /api/auth/csrf` and
  `/actuator/health`, reachable with no session.
- **Self-service** — authenticated, no Permission: the five baseline routes
  above. They act only on the session's own User and take no User id from the
  request, so there is no caller-supplied id to check ownership against.
- **One Permission** — every other operation requires exactly one, declared on
  its handler with method security and repeated by the chain as a backstop:
  `user:read` lists Users; `user:write` unlocks and forces a password change;
  `group:read` lists Groups and reads the Roles and the role mapping; `audit:read` lists audit events; `connector:read`
  lists connectors, `connector:write` creates and deletes them,
  `connector:token` issues, rotates and revokes their tokens; `ops:read` reaches
  every actuator endpoint but health, on whichever port actuator is served;
  `counter:read` / `counter:write` read and change the counter.

Beside the operations the chain admits two things that are not operations: any
`GET` outside the reserved server paths, which is the SPA shell or a file
request, and the `/error` page a refusal is rendered through, at baseline access.

An undeclared route under `/api/`, a method a declared route does not serve, and
a `/scim` path outside `/scim/v2` are refused — `403` for a session. A session on `/scim/v2/**` never reaches this
chain: the SCIM chain answers it, reads no session, and returns the bare `Bearer`
`401` of a request with no credential. The API document declares each operation's requirement in its
`security` field, and a contract test proves every declaration against the
running application.

**Authorization refusal** — the `403` a signed-in caller gets when the operation
does not admit it: a Permission it lacks, a route nothing declares, or a session
confined by the change-required flag. Generic on purpose: the response is
bodiless, and the refusal is audited (`ACCESS_DENIED`) and logged at `WARN` with
the caller, the operation (method and route template) and one reason
(`INSUFFICIENT_PERMISSIONS` / `insufficient-permissions`), never naming the
Permission, a Role or the rule. The missing Permission is recoverable from the
operation's declaration in the API document, since each requires exactly one. A
missing or stale CSRF token is a `403` too, but it is not an authorization
decision and is neither audited nor reasoned as one.

**Permission** and **Role** — a Permission is one fine-grained power from a
closed set defined in code (`user:read`, `user:write`, `group:read`,
`group:write`, `audit:read`, `connector:read`, `connector:write`,
`connector:token`, `ops:read`, `counter:read`, `counter:write`); a new one is a
code change, because it means something only once a protected action declares
it. A Role is a named set of them, defined in deployment configuration. Neither
is stored in the database, and nothing at runtime creates or changes one.

**Role mapping** — the read-only `app.authorization` configuration block that
defines the Roles and maps each Role to a Group by the Group's stable id; see the
backend README's "Role mapping" for its shape. The rules:

- **Union.** A User's Permissions are the union of the Roles of the mapped
  Groups it is a _direct_ member of — nesting is not modelled — so holding an
  extra Role never takes a power away. A User in no mapped Group holds only the
  baseline Permissions below and keeps baseline access (`ROLE_USER`).
- **Baseline Permissions.** `counter:read` and `counter:write` are held by every
  active User whatever its Groups, granted at Login beside `ROLE_USER` rather
  than through a Role. The counter is a basic capability, and a Group every User
  must join would be a second place for the fact that it is a User. A confined
  session does not hold them.
- **Taken at Login.** Permissions are resolved once, at
  Login, and the session carries them, together with the hash of the mapping
  they were resolved under. A confined session holds none.
- **Losing a Role ends the sessions it was issued to; gaining one waits for
  Login.** A SCIM write that removes a User from a mapped Group — a `PATCH`
  `remove` or `replace` of the members, a `PUT` whose member list leaves it out,
  or the Group's `DELETE` — ends every session the User holds once the write
  commits (ADR 0002's ordering), so a removed power stops at the User's next
  request. The rule is decided on the stored membership before and after, so a
  User removed and re-added in one `PATCH` keeps its sessions. Adding a User to a
  mapped Group leaves its live sessions alone: the Role applies from its next
  Login.
- **A changed mapping reaches live sessions at startup.** A deploy that
  changes the mapping changes its hash, and startup ends every authenticated
  session issued under a different hash (or carrying none), whether or not the
  session store survived the deploy. A plain restart, which keeps the hash,
  ends nothing. Startup logs the validated mapping's hash, and the number of
  sessions it ended, as `application-startup` records.
- **Validated at startup, fail-fast.** Startup refuses — naming every problem
  in one message — a Permission name outside the closed set, a Role defined
  twice or without a name, a mapping entry with no Group id, a Group id mapped
  more than once, an entry naming an undefined Role, anything but exactly one
  **Superuser Group**, a Superuser Group whose Role lacks any Permission, and,
  once the directory is seeded, a mapped Group id that does not resolve to a
  Group or a Superuser Group that is not the reserved Admin group.
- **The hash** is SHA-256 over a canonical rendering of the validated mapping,
  so reordering configuration lists does not change it and any change of meaning
  does.

**Superuser Group** — the one mapped Group whose Role holds every Permission. It
is the Admin group: seeding creates the Admin group under the Superuser Group's
configured stable id, so a deployment's mapping can name it before it exists. An
Admin group seeded earlier under another id keeps that id, and startup then
refuses the mapping rather than rewriting the id. Over SCIM it cannot be renamed
or deleted, and the Bootstrap Admin's membership of it cannot change; its other
members can all be removed. There is no "last enabled administrator" guard: the
frozen Bootstrap Admin, together with startup's refusal of a Superuser Role
missing any Permission, is what guarantees a User holding every Permission.

**Role assignment** — holding a Role is being a direct member of a mapped
Group, and nothing else assigns one. So **`group:write` on a mapped Group is
Role assignment**: whoever holds it — over SCIM, a connector token carrying it —
decides who holds that Role, the Superuser Role's included. It is not split into
a separate `role:assign` Permission, because an identity provider sync has to
manage every Group; it is stated here so nobody mistakes it for a harmless
Permission. Every membership change of a mapped Group is audited as a change of
power: a `ROLE_GRANT` or `ROLE_REVOKE` event naming the connector, the User, the
Group and the Role, beside an `INFO` log record classified `user-administration`
/ `change` / `success` with `user.target.id`, `group.id` and
`app.authorization.role`. A membership change of an unmapped Group is none of
this.

**Roles listing** — `GET /api/admin/roles`, by a holder of `group:read`: every
Role the mapping defines, sorted by name, with its Permissions and the Groups
conferring it, each by stable id and current `displayName` (`null` for a mapped
Group since deleted), and the Superuser Group marked. Read-only like the mapping
itself: no endpoint creates, changes or deletes a Role or a mapping entry. The
SPA has no view of it.

**Development fixtures** — the shipped mapping (`authorization.yaml`) is a
development default: Superuser, Account admin, Auditor, Connector admin and
Monitoring, each Role but Superuser conferred by a Group with one User in it.
With `APP_DEV_FIXTURES_ENABLED=true` startup seeds those Groups under the
mapping's ids and their Users with `APP_DEV_FIXTURES_PASSWORD`, never
overwriting one that exists, together with two Users in no Group: `user`, holding
only baseline access, and `dormant`, put past the dormancy lockout window at
every startup. Without the fixtures those Groups do not exist, so a
deployment that did not replace the development mapping fails startup instead of
running with it.

**Login** — the one operation that turns submitted credentials into an
authentication or a refusal, and where attempts are recorded against the failure
run; the self-service password change records the only other kind, a wrong
current password, through the same counting. It lives in `LoginService`, so an
entry point that authenticates submitted credentials without going through it
has no **lockout** at all; the
`/api/auth/login` endpoint adds only the session, the CSRF token, and the bare
`401`. Why the counting is recorded here rather than driven by Spring Security's
authentication events is
`docs/adr/0001-count-login-attempts-on-the-login-path.md`.

**Failure run** — the consecutive failures recorded against one User, counted on
the User's own row as `failed_login_attempts`. A login the backend accepts ends
the run and returns the count to zero; a login it rejects lengthens it, and so
does a wrong current password on the self-service password change. An unknown
username has no run, because nothing is recorded for a name that names no User.

**Lockout** — the state a User enters for one of two causes, closing it to
logins **permanently**: its failure run reaches the configured limit
(`app.auth.lockout.max-attempts`, default 3) — cause `FAILURES` — or the dormancy
job finds it past the lockout window — cause `DORMANCY` (ADR 0011). There is no
duration, no configuration key expressing one, and no passage of time that lifts
it. The only thing that ends it is an Admin performing Unlock (ADR 0007). The
User's row records `locked_at`, the instant the lock was imposed, and `lock_cause`
beside it, set and cleared together (a CHECK constraint refuses one without the
other), so "is it locked" is a question about the row rather than a comparison
against a clock. A lock keeps the cause it was first imposed for. A locked User is
refused **with its correct password**, and refused the same way as a wrong one,
whatever the cause: a bare `401` with no body, so the response
never reveals that the User exists or that it is locked — a User who cannot get
in learns nothing by waiting, which is intended. Attempts made while it holds
neither count nor deepen it. Imposing it **revokes the User's live sessions**,
after the transaction commits, so a locked User stops acting immediately rather
than when the session it already held expires. Enforcement is Spring Security's,
which checks the User's status before it compares passwords; the counting is the login
path's and the self-service change's. The failure lockout is the per-User half of brute-force deterrence on Login; the
deployment edge throttles the rest, because it cannot see the `userName` in a Login
body (`infra/README.md`, "Edge throttling").

**Bootstrap Admin exemption** — the seeded Admin
(`app.auth.bootstrap-username`) is the deployment's local recovery identity and is
the one principal lockout never applies to — neither cause, and neither dormancy
step. Its failed attempts are counted and
audited as `LOGIN_FAILURE` like anyone's, but no run of them locks it. With no
automatic lift, a lockable recovery User would let an unauthenticated attacker
brick the deployment; the accepted cost is unbounded online guessing against that
single User, answered by the Argon2id verification cost every attempt pays, the
uniform refusal, and the audited failures — not by a lock.

**Deactivated User** — a User whose SCIM `active` attribute is false, set by a
connector's SCIM write; the application itself never writes it. The Accounts page reports it and cannot change
it: `active` is directory-owned. It is refused at login exactly as a locked User
is: a bare `401`, indistinguishable from a wrong password, so the response reveals
nothing. The flag is never merely reported.

There is one flag: SCIM's `active` is the whole of "may authenticate". The
application keeps no enabled flag of its own, because one beside `active` would be
unreachable over the wire.

**Deactivating** and **unlocking** are **two separate capabilities**, and neither
performs the other. Deactivation settles whether a User is permitted at all, and
is the directory's; unlocking settles whether it is being penalised for failed
logins right now, and is an Admin's. So:

- Deactivating a User leaves its failure run and `locked_at` as they stand.
  The run is evidence, and it is most wanted at the moment a User is being
  closed.
- Reactivating a User leaves a lockout it is serving in force. Restoring access
  is not a finding that the failed logins did not happen; the lockout still ends
  only when an Admin unlocks it.
- Unlocking ends a lockout, whatever its cause, and clears the failure run with
  it, and says nothing about the `active` flag. A deactivated User can be
  unlocked and stays deactivated. Unlocking a locked User also restarts its
  **dormancy basis**, so the next dormancy run does not lock it again before it
  has had the chance to sign in, and records the lifted lock's cause on its
  `LOCKOUT_LIFT` event. Unlocking a User that has a password also sets its
  **change-required flag** (below); an Admin cannot unlock themselves.

**Change-required flag** — application-owned state on a User saying its current
password was imposed by somebody else and must be replaced before a password Login
by the User may do anything else; an Epic Login is not confined by it (see
**Confined session**). Stored as `password_change_required_since`: its presence is the
flag, as `locked_at`'s is the lockout, and its value is when the change was last
required. There is no deadline for the change; see **Dormancy basis** and ADR 0008. It is not a SCIM attribute, so setting it does not advance the version.
It is **set** by every connector password write (create, PUT or PATCH carrying a
password), by a **forced password change**, by an **Unlock** of a User that has
a password — the credential that reached the lockout threshold may be the one an
attacker was guessing — by a reactivation of a User that has a password, since a
credential that sat unused across a deactivation is not trusted on return, and by
seeding the Bootstrap Admin, whose first password comes from deployment
configuration. A credentialless User is unlocked or reactivated without it, having
no password to replace. It is **cleared** only by a successful self-service change;
a connector write never clears it.

**Confined session** — a session issued by a password Login while the
change-required flag is set. It holds no Permission and not even baseline access,
a Superuser's included, so it may call only `GET /api/auth/me`, the self-service
change and logout; every other endpoint, `/api/admin/**`, `/api/self` and
`/api/session` included, answers `403`. An Epic Login is never confined: it
presents no password of this service's, so the imposed credential the flag marks
is not what it used, and it receives the authorities the User would hold with the
flag clear (ADR 0008's 2026-10-09 addendum). A User who only ever signs in
through Epic therefore never has to replace the imposed password; that password
still buys only a confined session.

**Forced password change** — an action of a holder of `user:write`, setting the
change-required flag on another User and ending every session it holds. The Admin
never sees, chooses or transports the password. Refused (`403`) on the caller itself
whatever Permissions it holds — the Bootstrap Admin's included, which
replaces its own password through the self-service change — and on the Bootstrap
Admin by anyone; and (`409`) on a credentialless User.

**Self-service password change** — `POST /api/auth/change-password`, for the User
the session belongs to. A wrong current password lengthens the same failure run as
a rejected Login, so it leads to the same lockout; a new password that breaks the
password policy or repeats a recent one is refused by naming the rule, never
echoing either value. Success hashes the new password, clears the flag, advances
the version, records a `PASSWORD_CHANGE` audit event with no password value, and
revokes every session of the User, the submitter's included.

**Password policy** — one policy, `PasswordPolicy`, governs every path that sets
a password: SCIM `password` on `POST`, `PUT` and `PATCH`, and the self-service
change. The password is first normalized by the mapping half of PRECIS's
OpaqueString profile (RFC 8265, which RFC 7644 §5 requires), and that form is
what is validated, compared and hashed. Then:

- 12 to 256 characters, one code point counting as one character. Every printable
  character is accepted, the space and any Unicode code point included; none is
  stripped, substituted or refused for being "special".
- Refused when it equals or contains the `userName`, case-insensitively.
- Refused when it matches any entry of the **password history** — the User's
  three most recent passwords, the current one included. The history is kept as
  Argon2id hashes in `scim_user_password_history`, matched by verifying the
  candidate against each, trimmed to the newest three on every change, and
  deleted with the User. So a connector re-sending a password still in the
  history is refused: a `PUT` that means no credential change omits `password`.
- No composition rules, no expiry, and no hints or knowledge-based recovery
  questions, because forced complexity, forced rotation and hint mechanisms all
  reduce real-world strength.

A violation is `400 invalidValue` over SCIM and a `400` naming the unmet rule on
the application chain, never echoing the submitted value. The policy also calls
for a blocklist of common and known-compromised passwords, deployment-configured
and loaded from a local corpus so that no candidate, nor any hash or prefix of
one, is ever sent to an external service. It is not implemented: no corpus ships
yet, and a rule with nothing to check against would always pass.

Every password, current or historical, is hashed with Argon2id (`m=19456` KiB,
`t=2`, `p=1`) through a `DelegatingPasswordEncoder`, so every stored hash carries its `{argon2id}`
prefix and the scheme can change later without a schema migration. Argon2id has
no input-length ceiling, which is why the maximum is 256 characters rather than
bcrypt's 72-byte truncation point. There is **no pepper**: it would be the only
keyed secret in the system, a rotation and migration burden for no gain over
Argon2id's per-hash salts. Nothing here or in session state has a key to rotate —
hashes are self-describing and unkeyed, and Spring Session keeps state
server-side with no signing key — by design rather than omission; the only
rotatable credentials are connector tokens and the database and Redis passwords
deployment configuration supplies.

**Recovery guard** — what keeps the deployment recoverable. Two rules.
**Self-target refusal:** no Admin may Unlock or force-change themselves (`403`), whatever Permissions it holds — a
Superuser's included — so recovering from a self-inflicted state takes a second
Admin, and nobody may force the Bootstrap Admin's password change at all. And the
Bootstrap Admin can never be locked and is protected from every SCIM write,
deactivation included, so a deployment whose other Admins are all locked is still
recoverable: it signs in and unlocks them. No runtime guard counts the remaining
administrators: the Bootstrap Admin's frozen membership of the **Superuser
Group**, together with startup's validation that the Superuser Role holds every
Permission, is what guarantees a User holding every Permission, so a
connector may remove every other member of the Superuser Group.

The self-target check compares NORMALIZED `userName`s: a session names its
principal by whatever spelling it logged in with, and a raw comparison would let
an Admin whose session carried a differently-cased spelling of their own name act
on themselves past the guard. The Accounts page hides both controls on the
signed-in Admin's own row, comparing the same way, so the refusal is visible before
the click.

The Bootstrap Admin's protections are recognised by the **reservation marker** on the User's own resource
row, not by comparing its name to the configured one. A name comparison could be
moved by a rename, and a second identity could acquire the exemption by taking the
configured name; a marker written once by seeding, in a column no UPDATE reaches,
can do neither. The same marker protects the resource from every SCIM write.

**Users projection** — what identity administration may know about a User, one
row per User on the Accounts page (`GET /api/admin/accounts`): its stable resource
id, `userName` and display name, whether it is the Bootstrap Admin, whether the
**Admin group** confers administrative authority on it, its `active` flag, whether
a credential is set at all, whether a lockout is in force and its cause
(`FAILURES` or `DORMANCY`, so an operator can tell a forgotten password from an
abandoned User before unlocking), whether a change is
required, its last authentication, its creation timestamp and its direct Groups.
The directory-owned fields — identity, `active`, Groups — are read-only there.
Never the password hash, which no projection type has a field for. There is no field for when a lockout lifts,
because none does: the flag is the whole lock state, and what ends it is an Admin's
Unlock. Both refusal mechanisms appear because either alone would mislead — a User
locked out right now looks healthy if only `active` is shown, and nothing would say
which need unlocking.

The administrative flag is DERIVED at read time from Admin-group membership rather
than stored, so the projection reports the same fact the login path derives and there
is no column for the two to disagree about. Whether a credential exists is reported
because "no password was ever set" is otherwise indistinguishable from "the
password is wrong", and only the first is fixed by a SCIM write. The Bootstrap
Admin's row shows no lockout state at all: it can never be locked, so "not locked"
would describe a condition that could change.

**Groups projection** — what identity administration may know about a Group, one
row per Group on the Accounts page (`GET /api/admin/groups`): its name, its direct
member count, and whether it is the protected Admin group, decided by the
reservation marker. Read-only in its entirety; Groups and membership are the
directory's.

**Accounts page** — the SPA screen at `/accounts`, an Admin's operational view.
Despite the name of the route and of its endpoint `/api/admin/accounts`, it shows
Users: the
**Users projection** and the **Groups projection**, both read-only for everything
the directory owns, plus the application-owned operations: **Unlock** (offered only
while a lockout is in force, and described as the only way a lockout ends — one that
also requires the User to change its password), the **forced password change**
(offered only on a credentialed User not already flagged), and connector and token
management with one-time plaintext disclosure. Both User operations address the
User by its stable id. It offers neither operation on the signed-in Admin's own
row, no Unlock on the Bootstrap Admin, and no forced change on it either, so the
backend's refusals are visible before the click. There is no Deactivate or
Activate: `active` is the directory's, and the backend has no endpoint that would
accept a write to any directory-owned field. Each view and action is shown by its
own Permission — the Users projection by `user:read`, its Unlock and forced change
by `user:write`, the Groups projection by `group:read`, connectors by
`connector:read` (create and delete by `connector:write`, tokens by
`connector:token`) — and the page renders for a User who may see at least one view;
a deep link from anyone else is routed to the showcase. The page never decides
authorization: the backend refuses each operation to a caller lacking its
Permission regardless.

**Dormancy basis** — the instant a User's dormancy is measured from: its
`lastAuthenticatedAt` — set by every successful Login whose session is not
confined (a password Login made while no password change is required, and every
Epic Login), by a completed self-service change, by an explicit
reactivation and by an administrator's Unlock of a lock — or, for a User that has
had none of those, its creation time. The fallback is what keeps a User
provisioned without a password from being dormant the moment it exists. A
confined session's Login does not move it, so a credential imposed on a User that
is never replaced still ages into the dormancy lockout. Application-owned
authentication state, like the failure run: not a SCIM attribute, absent from
`/Schemas`, and writing it moves no version.

**Dormant User** — a User whose dormancy basis is further in the past than a
dormancy window. Dormancy is relative to a window, not a stored state: the same
User can be dormant for the lockout (90 days by default,
`APP_DORMANCY_LOCKOUT_WINDOW`) and not yet for role revocation (180 days,
`APP_DORMANCY_ROLE_REVOCATION_WINDOW`). Startup fails on a non-positive window, or
on a role-revocation window not longer than the lockout window. The Bootstrap
Admin is never treated as dormant, by its reservation marker, for the reason it is
exempt from lockout. Avoid "inactive" for this: a deactivated User is one whose
`active` flag is false, which a dormant User may or may not be.

**Dormancy job** — the scheduled job that enforces dormancy (ADR 0011), replacing
the earlier inactivity-deactivation and dormant-authority jobs, daily at 04:00
`Asia/Singapore` on its own scheduled job lock row, in two steps:

- **Role revocation.** A User past the role-revocation window — active or not,
  locked or not — loses its direct membership of every mapped Group. Each affected
  Group's version and the User's advance, the User's sessions are revoked after
  commit, and one actorless `DORMANCY_ROLE_REVOCATION` event names the User and
  the Roles lost. Unmapped memberships confer nothing and are untouched. A
  connector may re-add a membership; while the User stays dormant the next run
  removes it again.
- **Lockout.** An unlocked User past the lockout window — active or not — is
  locked with lock cause `DORMANCY`, its sessions revoked after commit, and an
  actorless `DORMANCY_LOCKOUT` event recorded and logged at `WARN` (inactivity,
  not an attack). A User already locked keeps its lock and its cause. The lock
  stands in for "disable": it never lifts on its own — a conscious deviation from
  the standard's 20-minute automatic lift, which is written for failure lockouts —
  and only an administrator's Unlock ends it.

The job never writes `active`, which stays the directory's, and never deletes:
deprovisioning stays with SCIM `DELETE`. A connector re-asserting `active=true`
resets nothing and lifts no lock. The Bootstrap Admin is exempt from both steps.
Its runs are counted (`app.job.runs{job="dormancy"}`) and so are their changes
(`app.dormancy.users.locked`, `app.dormancy.users.roles.revoked`), so a mass
lockout or revocation shows as a spike. With the development fixtures on
(`APP_DEV_FIXTURES_ENABLED=true`), the job also runs once at startup, so the
backdated `dormant` fixture is locked without waiting for 04:00.

**Scheduled job lock** — how the dormancy job and the audit retention job are
serialized: each run takes its own job's row in `scheduled_job_locks` with
`FOR UPDATE SKIP LOCKED` and holds it for the run's transaction. A second run of the
same job, on any instance, skips; another job holds a different row and never waits. Inside a run, each User is
re-read under its resource lock and decided again, so no User is processed twice.

**Session revocation** — ending the sessions a User is already holding, so its
next request arrives as a Guest and the SPA sends it back to login. Sessions are
found by the User's stable id, never its `userName`, so a rename cannot hide one.
The triggers in force:

- an Admin forcing its password change, and its failure run imposing a lockout,
  from Login or the self-service change;
- a SCIM write that takes `active` from true to false;
- a SCIM write that sets, changes or removes its password;
- a SCIM write that changes its `userName`;
- a SCIM `DELETE` of the User;
- a SCIM write that removes it from a mapped Group — `PATCH`, `PUT` or the
  Group's `DELETE` (an addition takes effect at its next Login, since authority
  is derived only then);
- a startup whose role mapping hash differs from the one the session was issued
  under;
- its own successful Login, which ends every other session it holds — one
  session per User;
- its own successful self-service password change;
- the dormancy job's lockout and role revocation — recorded with no actor,
  because the job is not a principal.

Every trigger defers the revocation until after its transaction commits, so a
write that was refused, stale or rolled back revokes nothing, and one SCIM write
that moves several of those attributes revokes once. A SCIM-triggered revocation is
audited as its own event, with its outcome, after the commit; if the session store
fails, the write stands and the connector receives an error. Nothing else revokes:
an ordinary profile or email change, a reactivation, an alias, a Group rename and
Unlock touch no session. Reactivation gives nothing back (a revoked session is
gone; the User signs in again). A refused forced change revokes nothing, which is
what keeps an Admin who mis-clicks their own row from signing themselves out. A
failure run that stops short of the limit revokes nothing either.

Revocation is possible only because sessions are indexed by principal
(`spring.session.data.redis.repository-type: indexed`, set in
`backend/src/main/resources/session.yaml`). Without that index a
session store can be read by id alone, so the ones belonging to a username cannot
be found; the application refuses to start rather than accept a revocation it cannot
enforce.

It is not a lock. A login already in flight when the deactivation commits can still
mint a session that the revocation did not see, because it read the User as
active. Once the write is committed no further login succeeds, so the gap is one
transaction wide rather than open-ended.

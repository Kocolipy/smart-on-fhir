---
type: Domain Rules
title: Epic Login
description: Login by EHR launch — how an Epic clinician is matched to a User, every refusal, the Epic tokens' lifetime, the Launch context and the EHR context it seeds, the session-long FHIRcast connection, MFA, and what is never recorded.
tags: [authentication, epic, smart-on-fhir, oidc]
---

# Epic Login

A Login by **EHR launch**: Epic opens the application in the clinician's system
browser, Epic's OpenID Connect provider proves who the clinician is, and the
session is then built exactly as [password Login](./authentication.md) builds it.
Password Login is unchanged, and a User may hold both. ADR 0012 records the
decisions and the flow; `backend/docs/openapi.yaml` holds the three browser routes.

**Linking by `userName`** — an Epic clinician is a User because the SCIM
connector provisioned it with `userName` equal to the clinician's Practitioner
FHIR ID. There is no separate link, no schema change, and no just-in-time
provisioning: an Epic Login never creates or modifies a User, so nobody Epic can
authenticate gets an account the directory did not give them.

**The local exception** — under the `local` profile alone, the development
fixtures can seed one clinician directly, as they seed every other fixture User:
`app.dev-fixtures.epic-clinician` (`APP_DEV_FIXTURES_EPIC_CLINICIAN`) names the
`userName`, which is the clinician's Practitioner id, so the local Epic stand-in's
clinician signs in with no SCIM step. That User is in no Group and holds baseline
access only ([authorization](authorization.md), "Development fixtures"). Outside
`local` the fixtures are refused outright, so a clinician is a User only because
SCIM provisioned it. `backend/docs/configuration/epic-login.md` has the setup.

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

A refusal lands the browser on the login page at `/login?signin=refused` with no
detail, its session ended first whoever it belonged to, and the login page shows
only "Sign-in from Epic was refused". It is audited once, as a `LOGIN_FAILURE` with login method
`sso`, its reason, and the refused User's stable id — none for
`UNKNOWN_ACCOUNT`, so an ID that matched nobody leaves no trace of itself. The
reason is the audit trail's alone: the operational log says only "Epic sign-in
refused", because the reason tells whether an account exists.

**Epic being unavailable is not a refusal.** When Epic does not answer in time,
or answers with a server error, on any of the calls a Login makes, the launch
lands at `/login?signin=unavailable`, its session ended the same way, and the
login page says to try again shortly. It names no User — none was ever resolved — and
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
successful Epic Login lands on [Home](./sessions.md), `/`, with its Launch
context (below), and is recorded as a
password Login is — failure run cleared, dormancy basis moved, `LOGIN_SUCCESS`
with login method `sso`, every other session of the User revoked — so one session
per User holds across both Login paths. The authorities are the ones password
Login gives the same User with the change-required flag clear: an Epic Login is
**never a
[confined session](./passwords.md)**, because it presents no password of this
service's and the flag marks an imposed password. A flagged User signed in
through Epic holds its Permissions, `GET /api/auth/me` reports
`passwordChangeRequired: false`, and the Login moves the dormancy basis even while
the flag is set. The flag itself is neither read into the session nor cleared, so
the same User's password Login is still confined (ADR 0008).

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
access token ends nothing; the refresh token and the `id_token` stay with the
session. Only backend code reads them, by session, with one exception, the
FHIRcast release (below): no other response carries any of them, `/api/auth/me`
included. The rest of the token response is discarded, the Launch context and the
FHIRcast Hub aside (below), and no FHIR API is called yet. The Login asks for
`launch openid fhirUser fhircast/Patient-open.read fhircast/Patient-close.read`:
identity, and the FHIRcast subscription scopes. Today Epic issues no refresh
token, because the Login asks for neither `offline_access` nor `online_access`
(ADR 0012, D29; ADR 0016).

**The FHIRcast Hub, and the SMART access token's one way out** — the token
response's `hub.url` and `hub.topic` name the clinician's **FHIRcast Hub**, Epic's
local hub on their own workstation. They are kept on the signed-in session beside
the Epic tokens, for exactly as long, when both pass: `hub.url` an `http` or
`https` URL on host `127.0.0.1`, any port, with no user-info, and `hub.topic`
within `[A-Za-z0-9\-._~]{1,128}`. Either failing drops the pair; the tokens and
the Launch context are kept regardless. `GET /api/auth/epic/fhircast` then
releases, to that session alone, its access token with the expiry, the granted
scope and the hub — never the `id_token` or a refresh token — answered
`Cache-Control: no-store`. It answers `404`, the same each time, for a password
Login's session, a session that kept no hub and an access token past its expiry,
and, like every Epic route, while Epic Login is off. Each release and each
refusal the endpoint makes is one `FHIRCAST_TOKEN_RELEASE` audit event against
the caller, `SUCCESS` or `FAILURE` with `NO_EPIC_TOKENS`, `NO_FHIRCAST_HUB` or
`TOKEN_EXPIRED`; none carries the token.

The token chain from there is the browser's, for the whole session: the
signed-in layout starts the session's one FHIRcast connection while the tab
holds an EHR context (below), on whichever signed-in page the tab is on. The
hub's `.well-known`
metadata names its registration and token endpoints, each held to `127.0.0.1`;
the SPA registers a fresh ES384 key by dynamic client registration with the SMART
access token as `Bearer`; it signs a five-minute `jwt-bearer` assertion with that
key for a **Hub Token**; with the Hub Token it makes the **Hub Subscription** to
`Patient-open` and `Patient-close` on the topic, over a websocket held to
`ws(s)://127.0.0.1`. The FHIRcast card's status is `Connecting`, `Connected`,
`Reconnecting`, `Disconnected`, `FHIRcast unavailable` or `Local hub blocked by
browser`, nothing more. The SMART access token, the Hub Token, the registered
`client_id` and the private key live in the tab's `sessionStorage` and go when
the session ends. ADR 0016 has the decision, and ADR 0011 the deviations it
accepts.

**The connection lasts the session, not Home** — the FHIRcast card on Home is
only a view of the connection, which keeps running while the clinician is on
any other signed-in page. Leaving Home and coming back neither registers,
subscribes nor reconnects again: the card shows the same connection, still
`Connected`, and an event the hub sent meanwhile as its last event. Only the
end of the session stops it (ADR 0016, "The connection's lifetime").

**Staying connected** — once connected, the card stays connected through the
clinician's working session. Before the Hub Token or the subscription's lease
(the confirmation's `hub.lease_seconds`) runs out, the SPA renews: a fresh
`jwt-bearer` assertion (five minutes, a `jti` of its own) signed with the key it
registered, a new Hub Token, and a new Hub Subscription, whose socket replaces
the old one once the hub confirms it. It does not register again. Renewal
starts at 80% of the token's or lease's lifetime, or 60 seconds before it ends,
whichever is sooner, though never sooner than 5 seconds after it was granted:
a lifetime of a minute or less has no time 60 seconds before it ends, and is
not renewed without pause. A lifetime the hub does not state is never renewed
by. A renewal is unseen: the card stays `Connected`.

A socket the hub drops, and a renewal that fails, start reconnecting: the card
shows `Reconnecting`, and the SPA retries five times, waiting 1, 2, 4, 8 and 16
seconds before each. A retry reopens the socket at the same endpoint; when the
hub answers that the endpoint has gone (its `hub.mode` `denied`), or after a
failed renewal, it subscribes anew instead, with a new Hub Token. The first
retry whose socket the hub confirms brings the card back to `Connected`. When
all five fail, the card shows `Disconnected` and a **Retry** button, which
connects again from the start, from the release on. Every end of the session
stops all of it; leaving Home stops nothing. The chain's renewals are not bound
to the session's lifetime: ADR 0011 records that deviation.

**Tearing down** — every end of the session (sign-out, an Idle sign-out,
expiry, a password change that ends it) tears down, at the hub, every
connection the tab registered during it, every Retry's included. Once the backend has ended the session, the SPA, in order:

1. sends `hub.mode=unsubscribe` for the topic, naming the subscription's
   `hub.channel.endpoint`, with the Hub Token as `Bearer`;
2. revokes the Hub Token (RFC 7009) at the hub metadata's
   `revocation_endpoint`, authenticating as the registered client with a
   fresh assertion signed by its key;
3. deletes its dynamic registration (RFC 7592) at the
   `registration_client_uri` the registration answered, with its
   `registration_access_token` as `Bearer`;
4. closes the socket;
5. removes every FHIRcast key from `sessionStorage`: the SMART access token,
   the Hub Token, the `client_id` and the private key.

The keys are cleared twice: once at the moment the session ends, before step
1, and again at step 5. The teardown works from what the module holds in
memory, so the first clear keeps the keys from outliving the session while a
slow hub is answered; the second takes whatever a connection still registering
stored in the meantime.

Steps 2 and 3 are best-effort, and skipped when the hub names no such endpoint
on `127.0.0.1`; a connection with no subscription or no Hub Token skips the
steps that need one. Each step is given 3 seconds, and one that fails, is
refused or goes unanswered never stops the next, nor the sign-out, which has
already happened: the SPA shows the login page without waiting. A connection
still registering when the session ends is torn down as soon as the hub
answers it, and never connects. A logout that may have left the session
standing leaves the connection standing too. Logout's `Clear-Site-Data` stays
the backstop for what the tab kept. Nothing revokes the SMART access token at
Epic (ADR 0012, D13); ADR 0011 records why revocation at the hub is only
best-effort.

**The last event** — while connected, the card shows the last **Event
Notification** the hub sent on the socket: its `hub.event` (`Patient-open` or
`Patient-close`), its `timestamp` in Singapore time, and the Patient's FHIR id —
the `id` of the resource under the `patient` key of its `context` — and nothing
else: no name, no MRN. Each message is held to the FHIRcast STU3 notification
shape: a `timestamp` that reads as a time, a non-empty `id`, and an `event`
carrying `hub.topic`, `hub.event` and a `context` list. Its `hub.event` must be
one of the two subscribed events, in any case, and its `hub.topic` exactly the
session's. A message that fails any of these — the hub's `heartbeat` among
them — is ignored and not acknowledged. A valid one is acknowledged on the same
socket with `{"id": <its id>, "status": "200"}`, then shown, replacing the event
before it. A Patient id outside `[A-Za-z0-9\-.]{1,64}` is omitted while the
event and its time still show. Every value is rendered as text, and nothing a
notification carries is logged. The event is held in the tab's memory alone,
with the connection rather than the card, so it survives leaving Home and
coming back: it stays shown through renewal and while `Reconnecting`, is not
shown once `Disconnected`, and a reload, or a Retry, starts with none.

**The Launch context goes to the browser, never into the session** — the
token response's `patient` and `encounter` are the **Launch context**. Each is
checked against FHIR's id grammar, `[A-Za-z0-9\-.]{1,64}`, the grammar a
[Patient](./patient.md)'s id is held to, and a value outside it, or one that is
not a string, is left out. A successful Epic Login lands at
`/#patient=<id>&encounter=<id>`, carrying whichever passed, or at the bare
marker `/#launch` when neither did; a refused or unavailable launch lands at
`/login?signin=…` with no fragment. Nothing of it is kept server-side — not in
the session, Redis or the Epic tokens — and none of it is logged or audited. A
fragment is never sent to a server, so no access log records it either.

The SPA reads the fragment as it loads, checks the same grammar, strips it from
the address bar, and lets what passed seed the tab's **EHR context** — what the
EHR currently has open for that tab, kept under one key of its
`sessionStorage`:

| Event                                                                               | The tab's EHR context |
| ----------------------------------------------------------------------------------- | --------------------- |
| An Epic landing, the bare `/#launch` included                                       | replaced              |
| A reload                                                                            | kept                  |
| Sign-out, session expiry, an Idle sign-out, a password change that ends the session | cleared               |
| A password Login                                                                    | cleared               |

Nothing else changes it yet. Home reads it as it changes, not only on load: a
tab holding an EHR context, even one naming nothing after the bare marker,
shows the FHIRcast card, and a password session shows none.

Home's Patient card reads `GET /api/patient/{patient}` for the patient in
context, and shows "No patient in context" with none, or when that read is a
`404`. The encounter is held but not used yet. Authorization is unchanged, by
decision: `patient:read` alone lets a User read any id, and the read is not
bound to the EHR context, so editing the fragment changes only which patient
that User views (ADR 0012, D8, D28).

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
`/login?signin=refused` exactly as an account refusal does, its session ended, and
is audited once with no subject and its reason: Epic answering the callback with
an OAuth error (`IDP_ERROR`), Epic's token endpoint refusing the code
(`TOKEN_EXCHANGE_FAILED`), an `id_token` whose signature does not verify or is
not RS256 (`INVALID_SIGNATURE`), one whose `iss`, `aud`, `azp`, `exp`, `iat`
(30 seconds of clock skew either way) or `nonce` fails, or whose `iss` or `sub`
has [edge whitespace](./directory.md) (`INVALID_CLAIMS`), and a `fhirUser` that
is not a Practitioner of this deployment's FHIR base (`INVALID_FHIR_USER`).

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
is issued, for its own lifetime. ADR 0012 records both as an accepted risk (D13).

**A suspected compromise is a deactivation** — an Epic-linked User suspected
compromised is deactivated in the directory, which revokes its sessions once the
deactivation commits and refuses every later launch as `ACCOUNT_DISABLED`; the
Epic organisation, whose credential it was, is told. The change-required flag does
not apply: an Epic Login presents no password of this service's (D20), so the
flag confines only the User's password Login.

**Never recorded** — the launch's `launch`, the callback's `code` and `state`, the
nonce, the PKCE verifier, Epic's `id_token`, access token and refresh token, the
Launch context's patient and encounter, the FHIRcast Hub's topic, the Hub Token,
our client assertion and our signing keys appear in no log record and no audit
event (D22).

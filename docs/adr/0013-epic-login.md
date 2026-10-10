# 13. Epic Login: SMART on FHIR EHR launch sign-in

Date: 2026-10-06

## Status

Accepted. Finalised 2026-10-08, when Epic Login's documentation step retired the
design spec it was built from (`docs/epic-smart-login.md`, removed in that change;
git history keeps it). This ADR is now the sole authority on Epic Login: every
decision D1–D28, the flow, the interfaces, the accepted risk D13, the IM8 policy
position and the App-Standards deviations are recorded here. Its addendum on
ADR-0012 updates the ac-2, ac-12 and as-8 entries there. Its own addendum of
2026-10-09 (#24), at the end, supersedes D8 for Epic's access token, refresh
token and `id_token`, which are now kept server-side for the life of the session
an Epic Login signs in; the passages it changes say so where they stand. A second
addendum of 2026-10-09, after it, extends the outcome module to password Login and
renames the `epic.login` counter to `login`, tagged by login method; the passages
it changes say so too. An addendum of 2026-10-10, last, has one module complete a
Login for both login methods, in one order, so password Login too is recorded a
success only once its session is signed in.

The items under "Open items" below are confirmations from outside the code. Each
is answered by recording it here, and none of them changes a decision.

## Context

Clinicians launch the application from inside Epic (a SMART on FHIR EHR launch) and
are signed in through Epic's OpenID Connect provider. To redeem the authorization
code, the application authenticates to Epic's token endpoint as a registered client.
Epic verifies that client authentication against a JWKS the application publishes,
and fetches it from us. How the application holds and rotates its signing keys
therefore has to suit Epic's verifier and the deployment's secrets handling.

ADR-0012 records "no organisational IdP/SSO" and puts OAuth/JWT flows out of scope.
This ADR refines it for exactly one flow: the SMART on FHIR EHR launch, for
clinicians, with password Login kept unchanged beside it.

The goal: a clinician working in Epic opens this application from Epic and is
signed in without a password. Epic's EHR launch proves who they are; the validated
`id_token`'s `fhirUser` names an Epic Practitioner, whose FHIR ID is the SCIM
`userName` of an already-provisioned User; and from that point on the session is
built exactly as password Login builds it. Any User with a password may still sign
in with it.

Out of scope, deliberately:

- standalone launch, a "Sign in with Epic" button, or patient-facing (MyChart)
  Login;
- using patient or encounter launch context, or any FHIR API call. Storing Epic's
  access token and refresh token was out of scope too, until the 2026-10-09
  addendum kept them for the session; using them, refreshing included, still is;
- just-in-time provisioning: an Epic Login never creates or modifies a User;
- single sign-out with Epic;
- signing-key generation, scheduling or expiry: key rotation is operated outside
  the application;
- several Epic organisations sharing one deployment;
- retiring any password feature.

## Decision

### The flow

```
Browser            Epic (Hyperspace + OAuth)          Backend
   | <-- opens system browser on launch URL ---|              |
   |-- GET /api/auth/epic/launch?iss&launch ------------------>| iss allowlist, D18
   |<-------------------------- 302 /api/auth/epic/authorize   |
   |-- GET /api/auth/epic/authorize -------------------------->|
   |<-------------------------- 302 authorize?…launch,aud,PKCE,state,nonce
   |-- GET authorize ------------>|                            |
   |<-- 302 /api/auth/epic/callback?code&state                 |
   |-- GET /api/auth/epic/callback ---------------------------->| consume pending request, state check
   |                              |<-- POST token (client_assertion, verifier)
   |                              |-- id_token --------------->| verify via jwks_uri
   |                              |-- GET /api/auth/epic/jwks.json (Epic verifies our assertion)
   |<-------------------------------------- 302 / (session established)
```

The authorize hop is shown as its own round trip: the launch redirects the browser
to our `/api/auth/epic/authorize`, which redirects it on to Epic. The design spec's
diagram drew the launch redirecting to Epic directly; the hop is what lets Spring
Security's authorization-request filter build the request.

1. **Launch.** `EpicLaunchController` accepts `iss` only when it equals
   `APP_EPIC_FHIR_BASE` exactly (D10) and a `launch` within D18's bounds, ends any
   session the browser holds, and holds `launch` in a new session for the next
   step only. Otherwise it is refused as `ISS_MISMATCH` or `INVALID_LAUNCH`, with
   a `WARN` naming the field and the rule it broke (`missing`, `length`,
   `charset`, `mismatch`), never the value.
2. **Authorize.** The internal hop `GET /api/auth/epic/authorize` is Spring
   Security's authorization-request filter, with a resolver that adds `launch`
   and `aud` to Spring's own request: `response_type=code`, `client_id`, the
   registered `redirect_uri`, `scope=launch openid fhirUser`, `state`, `nonce`
   and PKCE S256. Discovery on `APP_EPIC_OAUTH_ISSUER` runs on first use, never
   at startup, and a successful read is kept for 24 hours (D26). The `state` and
   `nonce` are Spring Security's own (256 and 768 random bits), and the PKCE
   verifier 128 Base64URL characters (768 bits) with its S256 challenge. No login
   hint is sent: the `launch` value already carries the clinician's Hyperspace
   context. The pending request is kept for the browser's session alone,
   in the Redis that holds the sessions (`PendingAuthorizations`), so the
   callback may land on any node (D27).
3. **Callback.** `GET /api/auth/epic/callback` is `oauth2Login`'s processing URL
   on the existing application chain, with `EpicCallbackFilter` ahead of it. The
   pending request is taken out of the store first, atomically (D27); with none,
   or a `state` that differs (compared in constant time), the callback is
   refused as `INVALID_STATE`, an OAuth `error` from Epic as `IDP_ERROR`, and a
   `code` outside D18's bounds as `INVALID_CODE` — none of them calling Epic.
   Otherwise the code is redeemed once through the one outbound client,
   `epicRestClient`, with the same `redirect_uri`, the verifier and our
   `private_key_jwt` assertion (D7) — exactly one request, never retried (D26).
   A refusal from the token endpoint is `TOKEN_EXCHANGE_FAILED`. The verifier is
   gone with the pending request once the exchange is made.
4. **`id_token`.** RS256 only, against the discovered `jwks_uri` fetched through
   `epicRestClient` by the one `EpicJwkSource`, which keeps the keys and refetches
   them on an unknown `kid` (D26); a signature that fails for any reason is
   `INVALID_SIGNATURE`. Then `iss` exactly, `aud` containing the client id and,
   with several audiences, `azp` equal to it, `exp` and `iat` with a 30-second
   skew from the injected `Clock` (an `iat` more than 30 seconds ahead refused),
   the `nonce` in constant time (`EpicIdTokenChecks`), and the MFA evidence once
   D17's switch is on; any of them failing is `INVALID_CLAIMS`. No user-info call
   is made. The access token, any refresh token and the `id_token` are handed to
   the success handler on the callback's request alone, which keeps them only if
   step 7 signs a session in (the 2026-10-09 addendum); the rest of the token
   response is dropped.
5. **Identity.** `fhirUser` must be `{fhirBase}/Practitioner/{id}` with a
   non-blank `id` and nothing after it (`FhirUserReference`), or the Login is
   refused as `INVALID_FHIR_USER`. The dev profile
   alone also accepts the relative `Practitioner/{id}` under the same rules,
   because the local SMART launcher issues no other form; it is decided where
   D21's `http` allowance is, and outside the dev profile parsing is unchanged.
   The launcher's issuer, by contrast, is absorbed by configuration alone:
   `APP_EPIC_OAUTH_ISSUER` and `APP_EPIC_FHIR_BASE` are both its `/v/r4/fhir`.
6. **Login decision.** `LoginService.logInFromEpic` accepts the User whose stored
   `userName` equals the id exactly and records the success exactly as password
   Login does, in one transaction, its `LOGIN_SUCCESS` carrying the MFA factor
   (D17). It refuses, in this order: no exact match (a
   case variant included) or the Bootstrap Admin as `UNKNOWN_ACCOUNT`, a
   deactivated User as `ACCOUNT_DISABLED`, and a User locked for any cause as
   `ACCOUNT_LOCKED` (`EpicLoginFailureReason`).
7. **Session.** `SessionEstablishment`, shared with password Login, rotates the
   session id, saves the security context, sets the principal index and the
   role-mapping hash, drops the pre-login CSRF token and logs `session-start`
   (method `sso`). Then, on the signed-in session alone, the success handler
   keeps Epic's tokens (the 2026-10-09 addendum). The answer is `302 /`; the
   SPA's `/api/auth/me` → `authenticated` → `/showcase` path follows.
8. **Any refusal or OAuth error** at any step lands at `/?signin=refused`, and
   Epic being unreachable at `/?signin=unavailable` (D23) — neither with any
   detail, and both with the browser's session ended first (D24).

How a Login ended is recorded once, by one module, `LoginOutcomeService` (named
`EpicLoginOutcomeService` until the second 2026-10-09 addendum, which also has it
record password Login's endings), which takes the ending — signed in, refused
(reason, refused User, field and rule, failed call) or unavailable (failed call) —
and writes its audit record, log line and counts; where the browser goes next is a
function of the ending alone. An account refusal is handed to it by the login
decision, as password Login's refusal is, so no caller can refuse without the
record: a `LOGIN_FAILURE` under method `sso` with its reason and the refused User's
stable id — none for `UNKNOWN_ACCOUNT` — through `LoginAttemptService.recordRefusal`
(the audit port itself since the 2026-10-10 addendum),
which counts toward no failure run (D12), a `WARN` saying only "Epic sign-in
refused", and `login` (`method=sso`, `outcome=refused`, `reason`). The success
handler then only redirects. A success is handed to it by the success handler, once
the session is signed in: the fail-closed `LOGIN_SUCCESS` is written in the
decision's transaction, and the `user-authentication` record and the `login`
success count only after it commits. The redirect is the one effect the module cannot
have, the application layer knowing no servlet, so both handlers land the
browser through one mapping of the outcome, `EpicLoginLanding` (since the
2026-10-10 addendum, the one `EpicLanding` both handlers redirect through, after
`LoginCompletion` has recorded the ending and ended the session). Every other failure — of
the launch, the authorize hop, the callback, the token exchange, the `id_token` or
its `fhirUser` — goes to `EpicLoginFailureHandler`, the one place it is told apart
(flow step 8, D23, D24), and the outcome it names is recorded by the same module. A
refusal there is `/?signin=refused`, its session ended, a `LOGIN_FAILURE` under
method `sso` with its exact refusal reason (the table under "Audit" below) and no
subject (no User was looked up), counted toward no failure run (D12), `login`
(`method=sso`, `outcome=refused`, `reason`), and one `WARN` saying only "Epic sign-in refused",
with the field and rule of a refused input. The reason is
read from what failed — a typed refusal of our own, the failed Epic call, the
`id_token` decoder's exception — never from a message, which can quote what Epic
sent. Epic being unavailable is the other outcome: `/?signin=unavailable`, its
session ended, a `LOGIN_FAILURE` under method `sso` with `EPIC_UNAVAILABLE` and no
subject, `login` (`method=sso`, `outcome=unavailable`), and one `ERROR` naming the call and
its error category (the table under "Log" below) — the outbound interceptor's for a
call that got no answer, the ending's for a `5xx`. An Epic call that answered with
something unusable (`cert/auth`, `data`) is refused under the call's reason — the
token call's as `TOKEN_EXCHANGE_FAILED`, the JWKS's as `INVALID_SIGNATURE`,
discovery's as `IDP_ERROR` — beside its `ERROR`. Every ending's records carry
`session.hash` — for the session the Login ran in, or for a success the session
it signed in — and the accepted one the MFA factor (ADR 0003, addendum
2026-10-09).

The login filter is configured to neither rotate the session nor save a security
context of its own, and the authorized client is held on the callback's request
alone (`EpicTokenHandOff`), never in a session. The success handler, a web
adapter, establishes the session from our own Login instead. That is what keeps
everything Epic sent out of the pre-login session, and it means an Epic session
is built by exactly the code that builds a password one. Only after that, and
only for an accepted Login, does the success handler keep Epic's access token,
refresh token and `id_token` on the signed-in session (the 2026-10-09 addendum,
superseding D8 for those three).

### Network paths

| From    | To      | Path                                                                                                            | Purpose                                          |
| ------- | ------- | --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| Backend | Epic    | `{issuer}/.well-known/openid-configuration`, `jwks_uri`, `token_endpoint` (HTTPS out, through `epicRestClient`) | Discovery, `id_token` key fetch, code redemption |
| Epic    | Backend | `/api/auth/epic/jwks.json` (HTTPS in, public)                                                                   | Verifies our client assertions                   |
| Browser | Backend | `/api/auth/epic/launch`, `/authorize`, `/callback`                                                              | Launch, the authorize hop, and the callback      |
| Browser | Epic    | `authorization_endpoint`                                                                                        | Clinician authorization                          |

### Routes and configuration

Every route is public, under the reserved `/api` path, and answers `404` while
`APP_EPIC_ENABLED` is off, mirroring `APP_SCIM_ENABLED`. `backend/docs/openapi.yaml`
documents each.

| Route                          | Purpose                                                                                                                        |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------ |
| `GET /api/auth/epic/launch`    | Launch URL registered with Epic.                                                                                               |
| `GET /api/auth/epic/authorize` | Internal hop into Spring's authorization-request resolver.                                                                     |
| `GET /api/auth/epic/callback`  | Redirect URI registered with Epic. The one fixed path whose absolute URL is `APP_EPIC_REDIRECT_URI`.                           |
| `GET /api/auth/epic/jwks.json` | Our public JWKS, holding the active key plus the next key if configured. Never a private parameter. Must be reachable by Epic. |

Configuration is environment variables only, with **no default credentials** (the
remote is public); `backend/README.md`, "Epic Login", is the variable reference
and `/infra/README.md` the deployment's. Startup fails fast when the switch is on
and anything required is missing or malformed, including a URL that is not
`https` outside the dev profile (D21), and it does **not** contact Epic: discovery
runs on first use (D26).

| Variable                                                   | Required when enabled    | Meaning                                                                                                 |
| ---------------------------------------------------------- | ------------------------ | ------------------------------------------------------------------------------------------------------- |
| `APP_EPIC_ENABLED`                                         | no (default `false`)     | Feature switch.                                                                                         |
| `APP_EPIC_FHIR_BASE`                                       | yes                      | The one allowlisted `iss`, sent as `aud`, and the prefix of `fhirUser`. A single value (D4).            |
| `APP_EPIC_OAUTH_ISSUER`                                    | yes                      | OIDC issuer used for discovery and for `id_token` `iss`.                                                |
| `APP_EPIC_CLIENT_ID`                                       | yes                      | Epic client id.                                                                                         |
| `APP_EPIC_REDIRECT_URI`                                    | yes                      | Absolute callback URL as registered.                                                                    |
| `APP_EPIC_CLIENT_KEY` / `APP_EPIC_CLIENT_KEY_ID`           | yes                      | Active EC P-384 private key (PEM) and its `kid`.                                                        |
| `APP_EPIC_CLIENT_NEXT_KEY` / `APP_EPIC_CLIENT_NEXT_KEY_ID` | no                       | Next key. Published only, never used to sign. The two must be set together, and the `kid`s must differ. |
| `APP_EPIC_CONNECT_TIMEOUT` / `APP_EPIC_READ_TIMEOUT`       | no (default `2s` / `5s`) | Outbound timeouts for every Epic call (D25).                                                            |
| `APP_EPIC_MFA_EVIDENCE_REQUIRED`                           | no (default `false`)     | Require MFA evidence in the `id_token`'s `amr` (D17).                                                   |

The SPA needs no new `VITE_` variable. Its login page keeps the password form
unchanged and adds the neutral "Sign-in from Epic was refused" notice for
`?signin=refused`, "Sign-in from Epic is temporarily unavailable. Try again
shortly." for `?signin=unavailable`, and the line "Clinicians: open this
application from Epic." `/frontend/AGENTS.md`, "Backend contract", is the runtime
contract.

### Decisions

Every decision D1–D28 is recorded in this ADR. D7, D14 and D16 are under "Client
authentication and signing keys", D25 and D26 under "Outbound calls and
resilience", and D13 under "Accepted risk"; the rest follow here.

- **D1: EHR launch only.** Epic opens the clinician's system browser on our
  launch URL, not an iframe. There is no standalone launch, no "Sign in with
  Epic" button and no patient-facing Login.
- **D2: linked by `userName`.** A User is linked to Epic by `userName` =
  Practitioner FHIR ID, sent by the SCIM connector, whose IdP holds that
  attribute. No schema change, and no just-in-time provisioning.
- **D3: an exact, case-sensitive match.** The User is found through the normal
  `NormalizedUserName` lookup and then accepted only if its stored `userName`
  equals the Practitioner ID character for character. Normalization lowercases,
  and Epic IDs are case-sensitive.
- **D4: one Epic organisation per deployment.** A bare FHIR ID is unique only
  within one organisation. Non-production and production are separate
  deployments, so the issuer is fixed per deployment and is neither repeated on
  each audit event nor stored per User.
- **D5: password Login is kept** for every User with a password. Clinicians are
  expected to use Epic only, and non-clinicians passwords. One User may hold
  both, and needs no extra rule.
- **D6: the Bootstrap Admin never signs in through Epic,** recognised by its
  reservation marker, never its name, so a rename cannot move the exclusion.
  It is refused as `UNKNOWN_ACCOUNT`, exactly as a name that matches nobody is,
  so the refusal does not say the account exists. Password Login stays its
  recovery path.
- **D8: identity only.** The patient and encounter context and the access token
  are discarded, and nothing from Epic is stored. _Superseded for the access
  token, the refresh token and the `id_token` by the 2026-10-09 addendum_: those
  three are kept server-side for the life of the session the Login signs in. The
  patient and encounter context are still discarded.
- **D9: every launch is a fresh Login.** A session already in the browser is
  replaced, whoever it belongs to.
- **D10: `iss` is required** on the launch URL and must exactly equal
  `APP_EPIC_FHIR_BASE`, compared as a string and never normalized: no case
  folding and no trailing-slash trimming, so with a base ending `…/R4` both
  `…/R4/` and `…/r4` are refused. One deployment trusts one FHIR base (D4),
  and a normalizing comparison would have to decide which variants Epic means
  the same server by, which only Epic can. A missing or different `iss` is
  refused as `ISS_MISMATCH`.
- **D11: a successful Epic Login lands on `/showcase`,** the same default as
  password Login with no return destination.
- **D12: Dormancy and Lockout apply to Epic Login exactly as to password
  Login,** and a successful Epic Login moves the dormancy basis. A refused launch
  does **not** lengthen a failure run: Epic checked the credential, not us, so a
  refusal is no evidence of guessing. Counting it would also let anyone whose
  Epic ID is a case variant of a User's `userName` lock that User out by
  launching. `LoginAttemptService.recordRefusal` (the audit port itself, called by
  `LoginOutcomeService`, since the 2026-10-10 addendum) therefore records the
  `LOGIN_FAILURE` and neither reads nor writes the User's login state, and the
  record names no changed path.
- **D15: a login method on every Login record.** `LOGIN_SUCCESS`,
  `LOGIN_FAILURE` and the operational `session-start` carry `password` or `sso`
  (the audit trail's `login_method` column, the log's `app.login.method`), and the
  Audit page shows it. While D4 holds, `sso` means Epic.
- **D17: Epic Login is multi-factor, by the Epic organisation's attestation
  until Epic confirms the claim.** The Epic organisation enforces MFA at its own
  sign-in, which meets ac-2 on the Epic path (ADR-0012 addendum); its written
  confirmation is an open item (below), and is referenced here once
  received. Until Epic confirms that an EHR launch's `id_token` carries `amr`,
  every Epic `LOGIN_SUCCESS` records its MFA factor as `idp-attested` (the audit
  trail's `mfa_factor` column, closed by `AuditMfaFactor`). The switch is
  `APP_EPIC_MFA_EVIDENCE_REQUIRED`, off by default and with no other default to
  protect, documented beside its siblings in `backend/.env.example`. On, the
  `id_token` must carry MFA evidence and a token without it is refused as
  `INVALID_CLAIMS`; the factor recorded is then taken from `amr`
  (`EpicMfaEvidence`). Evidence is an RFC 8176 second factor — `otp`, `hwk`,
  `swk`, `sms`, `tel`, `sc`, `fpt`, `face`, `iris`, `retina` or `vbm`, the
  first listed being the one recorded — or `mfa`, recorded as itself when no
  factor is named. Epic's own sign-in always takes the password, so a second
  factor beside it is what makes the sign-in multi-factor; `pwd`, `pin` or `kba`
  alone is not evidence. `acr` is not read: its values are each deployment's
  own, none says MFA everywhere, and it names no factor to record — a token
  carrying only `acr` is refused with the switch on. Password Login stays the
  ADR-0012 ac-2 deviation.
- **D18: input bounds.** `launch` and `code` are 1–8192 characters of printable
  ASCII with no whitespace (`U+0021`–`U+007E`), checked before either is held,
  sent or redeemed (`EpicInputBounds`). Both are opaque, so nothing about them
  is checked beyond the bounds; the bounds keep an oversized or binary value out
  of the session store, the outbound call and any log. A value outside them is
  refused as `INVALID_LAUNCH` or `INVALID_CODE`, and the log names the field and
  the rule broken — `missing` (absent or empty), `length` or `charset` — never
  the value. `iss` is not bounded so: it is compared exactly (D10), and its one
  rule beyond `missing` is `mismatch`.
- **D19: the launch and the callback are throttled at the edge.**
  `/api/auth/epic/launch` and `/api/auth/epic/callback` are on the network edge's
  throttling list (`/infra/README.md`, "Edge throttling"), which answers a breach
  with `429` and `Retry-After`. The limits are edge configuration, not part of the
  application. Every callback makes an outbound call to Epic, so an unthrottled
  callback could be used to flood Epic from our server.
- **D20: a suspected compromise is handled by deactivation.** A suspected
  compromise of an Epic-linked User is handled by deactivating the User in the
  directory: that ends their sessions after the deactivation commits (a session
  revocation trigger, `/docs/domain-rules.md`) and refuses every later Epic Login
  as `ACCOUNT_DISABLED`. It is also reported to the Epic organisation, whose
  credential it was. Forced password change does not apply: an Epic Login
  presents no password of ours. For the same reason the change-required flag
  does not confine an Epic Login: a flagged User signed in through Epic holds
  the authorities it would hold unflagged, and the Login moves its dormancy
  basis, while the flag stays set for its password Login (ADR 0008's 2026-10-09
  addendum, #23).
- **D21: `https` only, outside the dev profile.** `APP_EPIC_OAUTH_ISSUER`,
  `APP_EPIC_FHIR_BASE` and `APP_EPIC_REDIRECT_URI` must be absolute `https` URLs,
  and startup refuses any other scheme (`EpicLoginProperties`). Certificate and
  hostname checks are never disabled. Only the dev profile may use `http`, for
  the local Docker launcher, and that profile alone also accepts the launcher's
  relative `fhirUser` (flow step 5).
- **D22: never logged or audited.** The authorization code, `launch`, `state`,
  `nonce`, the PKCE verifier, the `id_token`, the access token, the refresh token
  (the 2026-10-09 addendum), the client assertion, and the private key material
  of the active and next signing keys.
  A startup validation error names the variable, never its value. The first line
  of defence is that no code path hands any of them to a log or audit call; the
  second is the ADR-0003 redaction at the logging boundary, which knows these
  names (see "Masking" below).
- **D23: refused and unavailable are distinct outcomes.** A refusal — bad input,
  a failed check, no acceptable User — is `302 /?signin=refused`. Epic being
  unreachable — a connect or read timeout, or a `5xx`, from discovery, the JWKS
  or the token endpoint — is `302 /?signin=unavailable`, and the login page
  tells the clinician to try again shortly rather than that they were refused.
  Neither carries any further detail. Every Epic call that fails is an
  `EpicOutboundException` carrying its call and its error category
  (`network` for no answer, `server` for a `5xx`, `cert/auth` for our own
  credential refused, `data` for an unusable answer); only the first two are
  unavailable. The other two remain refusals, and are `ERROR`s needing follow-up,
  since a key or a registration is likely wrong. A dropped connection counts as
  no answer, exactly as a timeout does: both leave Epic unreachable.
- **D24: a refused or unavailable launch invalidates any session already in the
  browser** before redirecting, whoever it belongs to. This follows D9, and
  leaves no previous User signed in on a shared workstation.
- **D27: the pending authorization request is single-use.** Its `state`, nonce
  and PKCE verifier are removed from the store atomically on the first callback,
  before anything about the callback is validated, so a replayed or concurrent
  callback finds none and is refused as `INVALID_STATE` without calling Epic;
  Epic's codes are single-use besides. "Atomically" has to hold across nodes
  and concurrent requests, which the session attribute Spring Security keeps it
  in by default does not: Spring Session loads each request its own copy of the
  session and writes it back when the request ends, so two concurrent callbacks
  would each find the attribute. The request is therefore held under its own
  Redis key, `epic:pending-authorization:{sessionId}`, behind the
  `PendingAuthorizations` port (`PendingAuthorizationsAdapter`), with the
  session's idle bound as its expiry, and taken with `GETDEL`, one command that
  reads and removes it. It is stored as JSON — strings only, no Java
  serialization. Keyed by the session id, it stays session-scoped: no other
  session can take it. `state` and the `nonce` are compared in constant time
  (`MessageDigest.isEqual`); the nonce is bound for the rest of the callback's
  own request only (a `ScopedValue`), and is gone with it.
- **D28: data classification is the data owner's.** Until the data owner
  classifies the application's data (an open item, below), the controls assume
  User PII and no clinical data, which D8 guarantees: nothing clinical Epic sends
  is kept. The tokens the 2026-10-09 addendum keeps are credentials, not clinical
  data, and today's scope (`launch openid fhirUser`) reads none; a ticket that
  adds FHIR scopes makes them a key to clinical data, and revisits this.

### Audit

- `LOGIN_SUCCESS` and `LOGIN_FAILURE` carry the login method (`password` or
  `sso`), as the operational `session-start` does (D15); the Audit page shows it.
- An Epic `LOGIN_SUCCESS` also carries the MFA factor (D17): from `amr`, or
  `idp-attested`.
- An Epic `LOGIN_FAILURE` carries exactly one reason from this closed list
  (`EpicLoginFailureReason`). The reasons are audit-only: the operational log
  says only "Epic sign-in refused" (the deviation "the account reasons are
  audit-only", below).

  | Reason                                                    | When                                                                           |
  | --------------------------------------------------------- | ------------------------------------------------------------------------------ |
  | `INVALID_LAUNCH`                                          | `launch` missing or outside D18, or the authorize hop with no launch pending   |
  | `ISS_MISMATCH`                                            | `iss` missing or not exactly `APP_EPIC_FHIR_BASE`                              |
  | `INVALID_STATE`                                           | no pending request, or `state` mismatch                                        |
  | `INVALID_CODE`                                            | `code` missing or outside D18                                                  |
  | `IDP_ERROR`                                               | Epic returned an OAuth `error` to the callback, or an unusable discovery       |
  | `TOKEN_EXCHANGE_FAILED`                                   | the token endpoint refused the exchange (`invalid_grant`, `invalid_client`, …) |
  | `EPIC_UNAVAILABLE`                                        | timeout or `5xx` from discovery, JWKS or token (D23)                           |
  | `INVALID_SIGNATURE`                                       | bad signature, a disallowed algorithm, or a `kid` still unknown after D26      |
  | `INVALID_CLAIMS`                                          | `iss`, `aud`, `azp`, `exp`, `iat`, `nonce` or MFA evidence fails               |
  | `INVALID_FHIR_USER`                                       | `fhirUser` absent or not `{fhirBase}/Practitioner/{id}`                        |
  | `UNKNOWN_ACCOUNT` / `ACCOUNT_DISABLED` / `ACCOUNT_LOCKED` | the login decision, flow step 6                                                |

- Retention follows the existing audit retention policy (at least 90 days). A
  User is correlated with Epic by `userName` = Practitioner ID, under the one
  issuer D4 fixes.
- No Practitioner ID or `fhirUser` appears in the log or the audit trail beyond
  the User's stable id. An unknown ID is not recorded.
- None of the D22 values is ever logged or audited.

### Log

Epic logging uses the ECS structured logging of ADR-0003: the fluent SLF4J API
through `LogEvent`'s shapes, `event.action=user-authentication`, the method as a
key-value field, and `trace.id`. ADR-0003's addendum "Epic Login's outbound calls"
records the operations, shapes and fields.

| Event                                                                         | Level   |
| ----------------------------------------------------------------------------- | ------- |
| Epic Login succeeded, `session-start`                                         | `INFO`  |
| Refusal: bad input (field and rule only, flow step 1), state, claims, account | `WARN`  |
| A JWKS refetch for an unknown `kid` (D26)                                     | `WARN`  |
| Epic unreachable, a JWKS fetch failed after its retries, a token-endpoint 5xx | `ERROR` |

- **Outbound calls.** `EpicOutboundInterceptor` logs each call to Epic (D25).
- **Error categories.** Every `ERROR` an Epic call ends in carries one of these
  as `error.category`, and says whether a person must follow it up:

  | Failure                                         | `error.category` | Follow-up                                     |
  | ----------------------------------------------- | ---------------- | --------------------------------------------- |
  | Connect or read timeout, or a dropped call      | `network`        | no                                            |
  | Epic `5xx`                                      | `server`         | no                                            |
  | `invalid_client`, or Epic rejects our assertion | `cert/auth`      | **yes**: likely a key or registration problem |
  | Malformed Epic response                         | `data`           | yes                                           |

- **Startup.** One `INFO` line, `application-startup`, gives `app.epic.enabled`
  and, when enabled, the active and next `kid` (`app.epic.client_key_id`,
  `app.epic.client_next_key_id`). It never includes key material, URLs or the
  issuer, and each redeploy that promotes a key therefore leaves a record (D14).
- **Masking.** The D22 names join the ADR-0003 redaction at the logging boundary,
  as a second line of defence behind never logging the values: Semgrep's
  `be-log-sensitive-value`, which refuses a value whose name says it is a secret
  being passed to a logging call, also matches `code`, `state`, `nonce`,
  `launch`, the PKCE verifier, `id_token`, `access_token`, `refresh_token` (all
  three by the rule's original "token" family), `client_assertion`,
  and the signing-key variables `APP_EPIC_CLIENT_KEY` and
  `APP_EPIC_CLIENT_NEXT_KEY` (`clientKey`, `clientNextKey`, `privateKey`, a
  `pem`). The keys' `kid`s (`APP_EPIC_CLIENT_KEY_ID`,
  `APP_EPIC_CLIENT_NEXT_KEY_ID`) are deliberately left out: a `kid` is a public
  identifier Epic reads from our JWKS, and the startup line logs both by design
  (D14). ADR-0003's addendum "D22's names" records the change, and
  `EpicLoginRedactionIntegrationTests` holds the emitted log and the audit trail
  to carrying no D22 value on the happy path, every refusal and every
  unavailable path.

### Metrics

Micrometer, on the existing Prometheus registry:

- `login` counter, tagged `method` (`password` or `sso`), `outcome` (`success`,
  `refused` or, for `sso` only, `unavailable`) and `reason` (the audit reason
  above; `none` for a success, so every series has the same tag keys) —
  `LoginMetrics`, behind the `LoginCounts` port `LoginOutcomeService` records
  through, registered whether or not Epic Login is on. Epic Login's series are
  `method=sso`. It was `epic.login`, without the `method` tag, until the second
  2026-10-09 addendum;
- `epic.login.failed_calls` counter, tagged `call` and `error_category`: each Epic
  call whose failure ended a Login, so a refused credential (`cert/auth`), which no
  outbound status tells apart, can be alerted on — `EpicCallMetrics`, behind the
  `EpicCallCounts` port;
- `epic.outbound` timer and `epic.outbound.errors` counter, tagged `call`
  (`discovery`, `jwks` or `token`) — `EpicOutboundInterceptor`;
- alert rules (`/infra/README.md`, "Alerts"): `EpicJwksFetchFailing` and
  `EpicEndpointUnavailable` when `epic.outbound.errors` persists for 5 minutes
  for the JWKS, or for the token or discovery endpoint; `EpicClientCredentialRefused`
  on any `cert/auth` failure of the token call, the one that presents our
  credential; and `EpicLoginRefusalsSurge` when refusals
  (`login{method="sso",outcome="refused"}`) stay above 3 a minute for 10 minutes
  (IM8 lm-16).

### Outbound calls and resilience

- **D25: one outbound client.** Discovery, the JWKS fetch and the token call all
  go through one `epicRestClient` (`EpicRestClientConfig`), built from Spring
  Boot's auto-configured `RestClient.Builder` — so every call is observed as
  every client of the service's is (`http.client.requests`, a client span) — on
  the JDK client with redirects off. The connect and read timeouts are
  `APP_EPIC_CONNECT_TIMEOUT` and `APP_EPIC_READ_TIMEOUT`, 2 and 5 seconds by
  default. There is no circuit breaker: login volume is low and the edge
  throttle (D19) bounds the load. Its one interceptor, `EpicOutboundInterceptor`,
  logs "Epic outbound call started" and "completed" at `INFO` with the call's
  name (`app.epic.call`: `discovery`, `jwks` or `token`, named by the call site as
  a request attribute), the method, `url.full` with no query, fragment or user
  info, the status and `event.duration_ms`; a call that got no answer is
  "Epic outbound call failed" at `ERROR`, `network`, with the failure's stack
  under its type name alone. Bodies and headers are never read. It meters every
  call on the `epic.outbound` timer and every call that got no answer or a `5xx`
  on the `epic.outbound.errors` counter — a name of its own, as Prometheus allows
  one type per metric name — both tagged `call`. It also writes the call's W3C
  `traceparent`: the deployment installs no propagator, so an inbound
  `traceparent` is ignored (ADR 0003), and the outbound header is set here
  rather than by turning propagation on for every request.
- **D26: retries.** The token call is never retried: the code is single-use and
  the clinician can relaunch, and neither the client nor the JDK resends a
  `POST`, a read timeout included. Discovery runs on first use and a successful
  read is kept for 24 hours by the injected `Clock`; a failed one is kept for no
  time, is `unavailable`, and is retried on the next launch. Epic's keys are kept
  the same 24 hours, or until discovery names another `jwks_uri`. An `id_token`
  whose `kid` the kept keys lack is never accepted from them: the JWKS is
  refetched up to 3 times, after 1, 2 and 4 seconds (`EpicRetryPause`, a seam so
  tests see the waits without spending them), each refetch a `WARN` with its
  attempt number. Still unknown, the token is refused — no key matches, the
  decoder's `invalid_id_token`, audited as `INVALID_SIGNATURE` — with one
  `ERROR` (`data`, follow-up), and discovery is read again at once in case Epic
  moved its keys; a failure of that read is not the Login's. A JWKS fetch that
  fails is not one of those refetches: it is Epic unavailable, at once.

### Client authentication and signing keys

- **D7: `private_key_jwt`, ES384.** Client authentication to Epic's token endpoint
  is a client assertion JWT signed ES384 with an EC P-384 key. There is no client
  secret. The assertion's `iss` and `sub` are the client id, its `aud` is the token
  endpoint, it carries a fresh `jti`, and it expires four minutes after it is
  signed. Epic's ceiling is five minutes, and the spare minute absorbs clock skew.
  The assertion is never logged or audited (D22).
- **D14: an active key and an optional next key, rotated by the operator.** The
  application signs with the active key and publishes the active key plus the
  optional next key, public halves only, at the public
  `GET /api/auth/epic/jwks.json`. The next key never signs. Rotation happens
  outside the application. A second key is created around mid-period and
  published as the next key, then the operator promotes it to active by
  redeploying. A key stays published until configuration removes it. The
  application has no expiry or period logic. The runbook is
  `/infra/README.md`, "Signing-key promotion". Every startup record carries both
  `kid`s, never key material, so each promotion leaves a record.
- **D16: the key is an environment variable for now, and KMS is the target.** The
  active key is `APP_EPIC_CLIENT_KEY` / `APP_EPIC_CLIENT_KEY_ID`, and the next key
  is `APP_EPIC_CLIENT_NEXT_KEY` / `APP_EPIC_CLIENT_NEXT_KEY_ID`. Each is a PKCS#8
  PEM, validated at startup. The target is an AWS KMS-held key that signs through
  the KMS API, so the private key never leaves KMS. Signing sits behind the
  `ClientAssertionSigner` interface, which hands back only the finished assertion.
  The KMS change therefore replaces the one environment-key implementation
  (`EnvironmentKeyClientAssertionSigner`) and nothing that uses it. For the same
  reason, the token call adds the assertion to its request itself and never gives
  a library signer a private JWK. as-8 and ck-4 stay open in ADR-0012 until then.

### Accepted risk (D13)

- **Our session outlives the clinician's Hyperspace session.** Epic sends no
  sign-out, so signing out of Epic leaves our session running. It is bounded by
  the 15-minute idle timeout and the 8-hour absolute session lifetime, and the
  next launch in that browser replaces it (D9). Single sign-out with Epic is out
  of scope.
- **Epic's tokens stay valid at Epic until they expire.** As first decided, the
  token response's access token was dropped the moment the `id_token` was read
  (D8), and revoking a token nobody held would have protected nothing. Since the
  2026-10-09 addendum the access token, and a refresh token whenever Epic issues
  one, are **kept** for the life of the session, and the risk now applies to
  kept tokens: ending the session drops them from the store, but nothing revokes
  them at Epic. The access token stays valid there until its `expires_in` runs
  out; a refresh token stays valid for its own, longer lifetime, which makes it
  the more valuable of the two. Neither is logged, audited or sent to the
  browser, so after the session ends only someone who had already copied one out
  of the session store could present it (App-Standards SSO §5, a deviation
  below). Revocation at session end is for the ticket that first asks Epic for a
  refresh token (`offline_access` / `online_access`, #10), once Epic's
  revocation support is confirmed (an open item, below).

### Policy position (IM8 spec-compliance, 2026-10-06)

The IM8 spec-compliance audit of the Epic Login design gave 17 PASS, 9 AUTO-FIX,
3 FAIL, 1 DEFERRED and 94 N/A (all 88 ARC controls are N/A: the application has no
agentic or LLM component). Every FAIL and AUTO-FIX was resolved with the owner, as
follows.

| Control                      | Was      | Resolution                                                                                                                                                 |
| ---------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| as-8 Secrets management      | FAIL     | **Accepted deviation, open** (D16). The key is an environment variable for now; the target is AWS KMS. ADR-0012's as-8 entry is extended to name this key. |
| ck-4 Key storage             | FAIL     | Same as as-8 (D16). A KMS-held key closes both.                                                                                                            |
| ac-2 MFA                     | FAIL     | **Met on the Epic path** by the Epic organisation's MFA (D17). Password Login stays the ADR-0012 deviation.                                                |
| as-1 Input validation        | AUTO-FIX | D18.                                                                                                                                                       |
| as-4 Rate-limiting           | AUTO-FIX | D19.                                                                                                                                                       |
| as-15 Change on compromise   | AUTO-FIX | D20.                                                                                                                                                       |
| dp-3 TLS in transit          | AUTO-FIX | D21. The wider dp-3 entry in ADR-0012 stays open.                                                                                                          |
| lm-16 Key signals            | AUTO-FIX | The metrics above.                                                                                                                                         |
| lm-19 Log sanitisation       | AUTO-FIX | D22, the masking above, and `EpicLoginRedactionIntegrationTests`.                                                                                          |
| ac-12 SSO for internal users | AUTO-FIX | The ADR-0012 addendum: clinicians sign in through their organisation via Epic; password Login for non-clinicians stays a deviation.                        |
| ac-4 Access review           | AUTO-FIX | The existing ADR-0012 deviation is unchanged by Epic Login.                                                                                                |
| pm-6 System documentation    | AUTO-FIX | The sequence diagram and network paths above.                                                                                                              |
| as-10 HSTS                   | DEFERRED | Unchanged. Tracked under ADR-0012's dp-3 entry.                                                                                                            |

### App-Standards position (spec-standards-check, 2026-10-06)

Checked against `Appfw-User-Standards/User_SSO` (standard and questions) and
`Appfw-Logging-Standards` (standard and questions). The findings were resolved with
the owner. Most became the decisions above; these remain **conscious deviations**,
each with its rationale in the section that follows the table.

| Standard requirement                                                                                | Deviation                                                                   |
| --------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| SSO §3.2: callback failures `400`, token failures `401`, IdP unavailable `502`                      | `302 /?signin=refused` or `/?signin=unavailable` (D23)                      |
| SSO §4: rate limiting at the application layer, 10–60 per registration per minute, reset on success | Edge throttling only, limits in edge configuration (D19)                    |
| SSO §4: a registry of redeemed authorization codes                                                  | None; single-use pending request (D27) plus Epic's single-use codes         |
| SSO §4: consumed `state` tracked in a registry against reuse across sessions                        | Session-scoped, consumed on first callback (D27)                            |
| SSO §4: startup fails if metadata fetch fails                                                       | Discovery runs on first use (D26)                                           |
| SSO §3.2: failures complete in consistent time                                                      | Refusal timing is not equalised; values are compared in constant time (D27) |
| SSO §4: a local record is created for each SSO identity at login                                    | No JIT provisioning; SCIM creates Users ahead of login (D2)                 |
| SSO §4: SSO users have no local credentials                                                         | One User may hold a password and Epic Login (D5)                            |
| SSO §2: entry point `/oauth2/authorization/{registrationId}`                                        | `/api/auth/epic/launch` and `/callback`                                     |
| SSO §4: verify MFA evidence in `acr` / `amr`                                                        | Organisational attestation until Epic confirms the claim (D17)              |
| SSO §3.3: success audit includes registration ID and issuer                                         | Implied by D4                                                               |
| SSO §5: tokens of an invalidated session are revoked                                                | Epic's kept tokens are dropped, not revoked (D13)                           |
| Logging §2.2: no reason that reveals whether the account exists                                     | `UNKNOWN_ACCOUNT` / `ACCOUNT_DISABLED` / `ACCOUNT_LOCKED` in the audit only |

### Deviation: discovery on first use, not at startup

App-Standards SSO §4 says startup fails if the provider's metadata cannot be
fetched. Here discovery runs on first use instead (D26): password
Login must not depend on Epic being reachable when the application deploys, and
a deployment whose Epic is down must still start and serve every password User.
The cost is that a wrong `APP_EPIC_OAUTH_ISSUER` is found by the first launch
rather than by the deploy; it shows as `unavailable` (no answer) or `refused`
(an `ERROR` under `data`, needing follow-up), never as a dead end.

### Deviation: redirects, not status codes

App-Standards SSO §3.2 answers a callback failure `400`, a token failure `401` and
an unavailable IdP `502`. Here every outcome is a `302`: `/?signin=refused` or
`/?signin=unavailable` (D23), or `/` signed in. The callback is a navigation in the
clinician's system browser, so a bare status code leaves them on a dead end; one
answer per outcome also gives a prober nothing to enumerate, and the audit reason
keeps the detail.

### Deviation: rate limiting at the edge only

SSO §4 rate-limits at the application layer, 10–60 attempts per registration per
minute, reset on success. Here the launch and the callback are throttled at the
network edge alone, with the limits in edge configuration (D19), as every other
public authentication route already is (ADR-0012, as-4). The edge already
throttles the public routes, and keeps a flood off the JVM — and off Epic, since
every callback this service accepts calls Epic's token endpoint. A deployment
without edge throttling is misconfigured.

### Deviation: no registry of redeemed codes

SSO §4 keeps a registry of redeemed authorization codes. None is kept here: the
pending request is single-use (D27), so a replayed callback finds none and is
refused before any token call, and Epic's codes are single-use at Epic. A code
carried into another launch's callback passes our checks and is refused by Epic,
as `TOKEN_EXCHANGE_FAILED`.

### Deviation: session-scoped `state`

SSO §4 tracks consumed `state` values in a registry, against reuse across
sessions. Here a `state` is valid only in the session that minted it — it is held
under that session's id and taken on the first callback (D27) — so reuse in
another session finds nothing to match, and reuse in the same session finds it
already taken. A registry would add a store of every `state` ever used to protect
against a reuse that already cannot succeed.

### Deviation: refusal timing is not equalised

SSO §3.2 asks that failures complete in consistent time. Refusals here are not
padded to a common duration: those after the Epic round-trip are dominated by
network time, and the browser sees the same answer whatever failed. What a timing
difference could leak is closed where it matters — `state` and the nonce are
compared in constant time (D27).

### Deviation: no just-in-time provisioning

SSO §4 creates a local record for each SSO identity at its first login. Here an
Epic Login never creates or modifies a User: SCIM provisions every User ahead of
any Login (D2), and an Epic Login finding no User is refused as
`UNKNOWN_ACCOUNT`. The directory is the joiner and leaver authority, so nobody Epic
can authenticate gets an account the directory did not give them, and a leaver the
directory deactivated cannot be recreated by a launch.

### Deviation: one User may hold a password and Epic Login

SSO §4 gives an SSO user no local credential. Here a User may hold both (D5):
forbidding it needs a provisioning rule SCIM cannot express — the directory sends a
`userName`, and the same attribute is what links the User to Epic. Clinicians are
expected to use Epic and non-clinicians passwords. Each path keeps its own
controls: a password refusal lengthens the failure run, an Epic refusal never does
(D12), and lockout, deactivation and dormancy refuse both alike.

### Deviation: the `/api/auth/epic/*` routes

SSO §2 names `/oauth2/authorization/{registrationId}` as the entry point. Epic
Login's routes are `/api/auth/epic/launch`, `/authorize` and `/callback` instead:
the SMART EHR launch is Epic-initiated and carries `iss` and `launch`, which no
generic entry point accepts, and every route stays under the reserved `/api` path
the SPA never serves. OAuth's parameter names and formats are unchanged.

### Deviation: MFA by attestation

SSO §4 verifies MFA evidence in `acr` or `amr`. Epic may not send either on an EHR
launch, so until Epic confirms that it does, the Epic organisation's MFA is an
attestation and the factor recorded is `idp-attested` (D17). The check is built
and off: `APP_EPIC_MFA_EVIDENCE_REQUIRED` enforces it once the claim is
confirmed, with no code change.

### Deviation: no registration ID or issuer on the success audit

SSO §3.3 puts the registration ID and the issuer on each success's audit record.
Here neither is recorded: a deployment trusts exactly one Epic organisation (D4),
so both are fixed by the deployment's configuration and the login method `sso`
already says which one it was. Repeating a constant on every event would record
nothing an investigation cannot read from the deployment.

### Deviation: Epic's tokens are not revoked

SSO §5 revokes the tokens of an invalidated session. Epic's tokens are not
revoked (D13). As first decided, the access token was dropped as soon as the
`id_token` had been read, so ending our session left nothing that could present
it. Since the 2026-10-09 addendum the access token and any refresh token are kept
for the session, and ending the session — every way it ends — removes them from
the store with it, but makes no call to Epic: the access token expires there on
its own, and a refresh token lives out its own lifetime. Today Epic issues no
refresh token, because the authorize request asks for neither `offline_access`
nor `online_access`; the ticket that asks for one also decides revocation.

### Deviation: the account reasons are audit-only

Logging standard §2.2 asks that no reason reveal whether the account exists.
`UNKNOWN_ACCOUNT`, `ACCOUNT_DISABLED` and `ACCOUNT_LOCKED` do reveal it, and they
are kept anyway, **in the audit trail only**. An investigation
needs them, the SSO standard asks for specific failure types, and password Login
records the same reasons. The browser sees one answer for all of them,
`/?signin=refused`, and the operational log says only "Epic sign-in refused",
with no reason and no user field. The audit trail, read by an administrator,
holds the detail. The `login` counter carries the reason as a tag; it counts
events and names no account. Password Login now keeps them the same way (the second
2026-10-09 addendum).

### Open items

Confirmations from outside the code. Each is recorded here once received; none
changes a decision.

- The Epic team confirms that the launch opens the system browser and sends `iss`
  with `launch` (D1, D10).
- The directory's IdP confirms it can supply each clinician's Practitioner FHIR
  ID per Epic environment (D2).
- The Epic organisation's written confirmation that its sign-in enforces MFA
  (D17).
- The Epic team confirms whether the `id_token` carries `acr` or `amr` for an EHR
  launch, and which values mean MFA (D17). A yes turns
  `APP_EPIC_MFA_EVIDENCE_REQUIRED` on.
- The data owner classifies the application's data (D28).
- The Epic team confirms whether Epic exposes a token revocation endpoint
  (RFC 7009) for this registration, so the ticket that first requests a refresh
  token can revoke kept tokens when the session ends (D13, the 2026-10-09
  addendum).

The Epic sandbox registration — a non-production app on fhir.epic.com (Clinicians
audience, R4, `openid fhirUser`, our public JWKS URL, the launch URL and redirect
URI), its practitioner provisioned by FHIR ID — is configuration only, and its
values belong in `/infra/README.md` once registered.

## Consequences

- An Epic session is indistinguishable from a password one after sign-in: the same
  authorities (Spring Security's own `FACTOR_PASSWORD` aside, which records a
  credential the Epic path never presents), the same session bounds, the same
  one-session-per-User rule. Only the login method on the records tells them
  apart — and, for a User whose change-required flag is set, the confinement,
  which only a password session carries (D20).
- The callback is served by a security filter, not a controller, so the API
  contract check lists it as filter-served rather than finding it in the handler
  mapping.
- **as-8 (secrets management) and ck-4 (key storage) stay open** in ADR-0012
  until the KMS signer exists (the policy position above). The signing keys are
  deployment environment configuration, like the application's other secrets,
  and a KMS-held key closes both. ADR-0012's as-8 entry names this key (its
  addendum).
- The public JWKS is derived from the private keys at startup, so the
  configuration holds nothing but the PEMs and `kid`s. The KMS signer will need
  its public keys from KMS instead.
- A key leak is contained by promotion: a redeploy with a fresh active key and
  the leaked key removed takes it out of the JWKS at once.
- A key Epic withdraws from its own JWKS can still verify an `id_token` for up
  to 24 hours, from the keys this service kept. Epic's rotation adds a key before
  it signs with it, which the unknown-`kid` refetch absorbs; a withdrawal for
  compromise is an incident, and a restart drops what was kept.
- An `id_token` signed by a key Epic has not yet published holds its callback
  for up to 7 seconds of waits plus three fetches. Login volume is low, and the
  alternative — refusing a clinician whose Epic just rotated — is worse.
- `epic.outbound.errors{call="jwks"}` persisting is the signal that Epic's keys
  are unreachable; `/infra/README.md` carries the alert rule.
- A signed-out Epic clinician's session lives on until its idle bound, its
  absolute lifetime or the next launch (D13); a shared workstation relies on the
  next launch or the idle bound to end it. The Epic tokens it holds (the
  2026-10-09 addendum) live exactly as long.
- An Epic session is told apart from a password one by one more thing since the
  2026-10-09 addendum: the Epic tokens it holds, which only the backend can read.

## Addendum (2026-10-09): Epic's tokens are kept for the session (#24)

**Supersedes D8 for the access token, the refresh token and the `id_token`.**
D8 discarded everything from Epic's token response. Backend features need to
act at Epic on behalf of the signed-in clinician, so a successful Epic Login now
keeps Epic's tokens server-side, bound to the session it signs in, as the
**Epic tokens** (`/CONTEXT.md`). The patient and encounter launch context are
still discarded, and no FHIR API is called yet.

**What is kept** (`EpicTokenSet`): the access token's value; its expiry, Epic's
`expires_in` made an absolute instant on the injected `Clock`; the `scope` it was
granted; the refresh token, when Epic issues one; and the raw `id_token`. Epic
issues a refresh token only to a registration allowed one that asks for
`offline_access` or `online_access`, and the authorize request asks only for
`launch openid fhirUser` (flow step 2), so today the refresh token is always
absent and the access token reads no FHIR resource. Adding the scopes, and
refreshing, are a later ticket's (#10). An expired access token reports so by
the injected clock (`EpicTokenSet.isExpired`), and expiry ends nothing: the
refresh token and the `id_token` stay with the session.

**Only on a successful Epic Login, and only under the signed-in id.** The login
filter's authorized-client repository was `NOTHING_KEPT`; it is now
`EpicTokenHandOff.REPOSITORY`, which holds the authorized client on the callback
request alone. The filter still neither rotates the session nor saves a security
context. `EpicLoginSuccessHandler`, once the login decision has accepted the User
and `SessionEstablishment.establish` has rotated and signed in the session, takes
the client back (`EpicTokenCapture`) and writes the tokens to the signed-in
session as the attribute `app.epic.tokens` (`EpicTokens.SESSION_ATTRIBUTE`). A
refused or unavailable launch never takes them, and they end with its request;
the pre-login session id holds nothing.

**Mechanism: a session attribute, not a key of its own.** A Spring Session
attribute inherits everything a session's end already does — id rotation at sign
in, the idle timeout, the absolute session lifetime, logout, every session
revocation trigger in `/docs/domain-rules.md` (one session per User, a forced
change, a lockout, deactivation, a SCIM password or `userName` change or
`DELETE`, losing a mapped Group, a role-mapping change at startup, the dormancy
job), and D9 and D24's invalidation by the next launch — with no cleanup code to
forget on a future path. A separate Redis key,
as `PendingAuthorizations` keeps the pending request, was rejected: it would need
an explicit delete on every one of those paths. D27's reason for its own key,
atomic single use across concurrent requests, does not arise for tokens written
once at sign-in and only read after. The attribute is Java-serialized like every
other session attribute; the type holds only strings, an instant and a sorted set
of strings.

**Retrieval** is the `EpicTokens` port, `forSession(sessionId)` →
`Optional<EpicTokenSet>`, whose adapter (`EpicTokensAdapter`) reads the session
through Spring Session's repository without touching its last-access time. It
answers empty for a password Login's session, an unknown or ended session, and
no id. The absolute session lifetime is the one end the repository does not
know: it is enforced on a session's next request, so the adapter applies the
same policy on the same clock, and hands nothing out once the lifetime is over
even if no request has ended the session yet.

**Storage is bounded by the remaining absolute lifetime.** The store expires a
session its idle bound after its last request, and every request renews that,
so near the lifetime's end the idle bound alone would keep a session stored up
to that bound past it. For a session holding the tokens, the idle bound is
therefore cut to what remains of the lifetime whenever that is the shorter
(`AbsoluteSessionLifetimePolicy.idleBoundAt`: whole seconds rounded down, never
under one): when the success handler writes them, and again on every later
request, in `AbsoluteSessionLifetimeFilter`, before the request's renewal is
saved, so a renewal cannot undo it. Far from the end — at sign-in, normally — the
cut does not bite and the idle timeout behaves as for any session; a session
without Epic tokens is never touched. The store's expiry of a token-holding
session therefore never lands past its absolute lifetime. One residue is Spring
Session's: the indexed repository keeps an expired session's Redis key five
minutes past its expiry so it can still read the session when Redis reports the
expiry, so the bytes can remain that long past the lifetime's end, during which
the repository, and so the adapter, answers nothing for the session. That grace
is the same for a session that idles out; removing it would mean replacing the
repository's expiry handling, and it was left as it is.
`EpicTokensStorageLifetimeIntegrationTests` holds both bounds against real Redis.

**Never leaves the backend.** No endpoint returns any of the three tokens —
`/api/auth/me` and `docs/openapi.yaml` are unchanged — and the SPA keeps no
SMART credential. `ArchitectureTest.epic_tokens_never_reach_a_rest_controller`
holds that no REST controller depends on `EpicTokenSet` or `EpicTokens`; the
one web adapter that writes them, the success handler, answers only with a
redirect.

**D22 covers all three.** The refresh token joins D22's names, and the
`id_token`, which carries the clinician's identity claims, is treated as the
credentials are. `EpicTokenSet.toString()` names each token by its presence
alone, and its null checks name the argument, never a value. Semgrep's
`be-log-sensitive-value` already refuses `refresh_token` and `refreshToken` by
its original family of names (they contain `token`), so the rule is unchanged.
`EpicLoginRedactionIntegrationTests` now has Epic issue a refresh token on every
path, searches for it beside the other D22 values, and searches every answer the
callback gave the browser as well as the log and the audit trail;
`EpicTokensIntegrationTests` holds the callback's answer and `/api/auth/me` to
carrying none of the three.

**Not encrypted at rest by the application — an accepted risk, for now.** The
access and refresh tokens are bearer credentials at rest in Redis, the refresh
token the longer-lived and more valuable. They sit beside what each session
already holds there — its security context and authorities, under the session
id that is this service's own bearer credential — behind the same controls:
the cache is reachable only from the application's security group, and needs
the AUTH token with TLS in transit when a Redis password is configured. The
deployed single-node cluster has no at-rest encryption (`/infra/infrastructure.yaml`,
`RedisCluster`).
Application-level encryption would need a key held in the same environment as
the Redis credentials (the position D16 records for the signing key), and would
protect only against a compromise of Redis alone; today's tokens grant
`launch openid fhirUser` and no refresh token exists. The ticket that adds FHIR
scopes or a refresh token (#10) revisits this — envelope encryption of the
tokens under a KMS key, or an encrypted replication group — together with
revocation (D13).

**D13 applies to kept tokens.** Ending a session drops its tokens from the store
but does not revoke them at Epic: the access token stays valid until it expires,
and a refresh token, once one is issued, for its own lifetime. The accepted risk
and the SSO §5 deviation above are updated to say so, and Epic's revocation
support is an open item.

## Addendum (2026-10-09): one outcome module and one `login` counter for both login methods

Architecture review 2026-10-08, "Password Login path". The password and Epic
refusal pipelines had diverged: Epic Login's met Logging §2.2 (the account reasons
audit-only) and had a meter, while password Login's refusal `WARN` carried the
exception type (`LockedException`, `DisabledException`, …), which tells its reader
the account exists and its state, left any session the browser held alive, and was
counted nowhere. This addendum makes the compliant behaviour the only one. Epic
Login's behaviour is unchanged apart from the meter's name.

**One module.** `EpicLoginOutcomeService`, `EpicLoginOutcome` and `EpicLoginCounts`
are now `LoginOutcomeService`, `LoginOutcome` and `LoginCounts`, and record a
password Login's endings beside Epic Login's. `LoginOutcome` is signed in (`SignedIn`,
with the login method, and an MFA factor for `sso` only), refused (`Refused`: a
password Login's `PasswordRefused` or an Epic Login's `EpicRefused`, the former
`Refused`), or unavailable (Epic only). The accepted password Login the web adapter
establishes a session from, formerly `LoginService.LoginOutcome`, is
`LoginService.AcceptedLogin`. `LoginService` records a password refusal through the
module, as the login decision records an Epic account refusal, so no caller can
refuse a Login without the record. Where the browser goes next stays each method's
web adapter's: password Login's bare `401` (`AuthController`), Epic Login's
redirect (`EpicLoginLanding`).

**A refused password Login** is recorded as:

- a `LOGIN_FAILURE` under method `password` with its reason — `BAD_CREDENTIALS`,
  `UNKNOWN_ACCOUNT`, `ACCOUNT_LOCKED`, `ACCOUNT_DISABLED` or `OTHER` — naming the
  User when the name matched one, and counted toward its failure run, both as
  before (it is a guess at our credential, unlike an Epic refusal, D12);
- one `WARN`, "Login refused", with `app.login.method=password` and `session.hash`
  for the session the Login ran in, and no reason, no user field and never the
  submitted username — mirroring "Epic sign-in refused", without its field and
  rule, which password Login has none of;
- `login` (`method=password`, `outcome=refused`, `reason`).

An accepted password Login's `user-authentication` record is unchanged and now also
counts `login` (`method=password`, `outcome=success`, `reason=none`); it names no
session, as before, because the session it signs in is not yet rotated when it is
written. (Superseded by the 2026-10-10 addendum: both are now written after the
session is signed in, and the record names it.)

**A refused password Login ends the browser's session** — the password analogue of
D24. `AuthController` invalidates whatever session the request carried, whoever it
belonged to, and clears the security context before the bare `401`, so a shared
browser is never left signed in as the previous User. A refused Login that arrived
without a session creates none. The CSRF token was bound to the ended session
(ADR 0009, which has an addendum of the same date), so the SPA discards it on the
`401` and a retry fetches the next session's (`/frontend/AGENTS.md`, "Backend
contract").

**One `login` counter.** `epic.login` is replaced by `login`, tagged `method`,
`outcome` and `reason`, every series of both methods registered at zero at
startup with the same tag keys (the "Metrics" section above). Its adapter,
`LoginMetrics`, is in `com.example.backend.auth.config` and registered by
`LoginMetricsConfig` unconditionally: password Login exists whether or not Epic
Login is on, and the onion layering lets a configuration adapter implement an
application port. `epic.login.failed_calls` stays Epic's, behind its own port
(`EpicCallCounts`, adapter `EpicCallMetrics` in the Epic configuration).
`EpicLoginRefusalsSurge` now reads `login_total{method="sso",outcome="refused"}`.
A dashboard or rule reading `epic_login_total` must move to
`login_total{method="sso"}`.

**Uniform refusal timing.** Separately from the module, a locked or deactivated
User's password refusal now costs the same one Argon2id verification as a wrong
password (ADR 0007's amendment of the same date). This addendum does not change
"Deviation: refusal timing is not equalised", which is about Epic Login.

## Addendum (2026-10-10): one module completes a Login, for both login methods

Architecture review 2026-10-10, B1. The second 2026-10-09 addendum put each Login
ending's records in one place, but the steps that end a Login were still split
across five modules, and the two login methods ran them in a different order:

- **Password Login** recorded its success inside the login decision
  (`LoginService.logIn`), and `AuthController` established the session afterwards.
  A Login whose session step then failed was still logged "Login accepted" and
  counted `login` `outcome=success`, and that record could name no session.
- **Epic Login** decided, established, and only then recorded the success,
  naming the signed-in session — the order the first 2026-10-09 addendum chose.

**One module, one order.** `LoginCompletion`, a web adapter in
`com.example.backend.auth.controller`, now runs every Login's ending, for both
methods:

1. **Decide** — `LoginService.logIn` or `logInFromEpic`, handed the id of the
   session the browser holds. Both now return a `LoginService.LoginDecision`
   (formerly `EpicLoginDecision`): an accepted Login carrying its `SignedIn`, or a
   refusal. A password refusal is no longer thrown; it is a `PasswordRefused`
   decision, still recorded by the decision and still counted toward the failure
   run there (ADR 0001), so no caller can refuse a Login without the record or
   skip the count. An accepted Login's fail-closed `LOGIN_SUCCESS`, the cleared
   failure run and the after-commit revocation of the User's other sessions stay
   in the decision's transaction (ADR 0004, ADR 0002).
2. **Establish** — `SessionEstablishment`, from the accepted Login as a whole, now
   package-private and called only by `LoginCompletion`, so no adapter can sign a session in without the rest of the
   Login around it.
3. The login method's own work on the signed-in session: Epic Login keeps Epic's
   tokens there (the first 2026-10-09 addendum), under the rotated id alone.
4. **Record** the ending once, through `LoginOutcomeService`: the
   `user-authentication` record and the `login` success count, naming the
   signed-in session by `session.hash`. A Login whose session step or method work
   fails records no success ending.
5. **On a refusal, end the browser's session** and clear the security context
   (D24, and its password analogue from the second 2026-10-09 addendum).

An Epic Login that fails before any decision is ended by the same module
(`LoginCompletion.endUndecided`): its outcome recorded once, naming the session it
ran in, then that session ended.

**What changes on the wire and in the records.** Only the accepted password
Login's ending: its `user-authentication` record and `login` success count are
written after the session is signed in, and the record now carries `session.hash`
of the signed-in session, as Epic Login's always has (ADR 0003, addendum of this
date). Every refusal record, `WARN` text, counter and tag, the `401`, the
redirects, `session-start`, the CSRF token drop and Epic's token keeping are
unchanged.

**The adapters only shape the answer.** `AuthController.login` answers the
signed-in account or the bare `401` — every refusal leaves through one
`LoginRefusedException` and one handler, so a locked account's answer is a wrong
password's byte for byte. The Epic handlers redirect to `/`, `/?signin=refused`
or `/?signin=unavailable`, relative to the context path as before.

**Deleted.** `EpicLoginLanding` and `EpicSignInRedirect`: the session ending they
did is `LoginCompletion`'s, and what remains of the redirect — the three landings,
relative to the context path — is one package-private `EpicLanding` both Epic
handlers end in.
`LoginAttemptService.recordRefusal`, a one-line pass-through: `LoginOutcomeService`
calls `AuditTrail.recordLoginRefusal` itself, which still neither reads nor writes
the User's login state (D12).

This extends the 2026-10-09 rule "a success is recorded after sign-in" to password
Login. ADR 0001's decision holds — the counting is still inside `LoginService` —
though its passage naming `AuthController` and `SessionEstablishment` as the
caller now reads `LoginCompletion` for both.

# 12. Epic Login: SMART on FHIR EHR launch sign-in

Date: 2026-10-06

## Status

Accepted. This ADR is the sole authority on Epic Login: the flow, every decision
D1–D29, the accepted risk D13, its IM8 controls and its App-Standards
deviations. Two things are other ADRs': ADR 0016 decides the SMART access
token's release to the SPA for FHIRcast and the FHIRcast hub kept beside the
tokens, and ADR 0001 how a Login ends, for both methods. ADR 0011 records the
ac-2, ac-12 and as-8 positions this changes.

## Context

Clinicians launch the application from inside Epic (a SMART on FHIR EHR launch) and
are signed in through Epic's OpenID Connect provider. To redeem the authorization
code, the application authenticates to Epic's token endpoint as a registered client.
Epic verifies that client authentication against a JWKS the application publishes,
and fetches it from us. How the application holds and rotates its signing keys
therefore has to suit Epic's verifier and the deployment's secrets handling.

ADR 0011 puts OAuth flows out of scope. This ADR refines it for exactly one flow:
the SMART on FHIR EHR launch, for clinicians, with password Login kept unchanged
beside it.

The goal: a clinician working in Epic opens this application from Epic and is
signed in without a password. Epic's EHR launch proves who they are; the validated
`id_token`'s `fhirUser` names an Epic Practitioner, whose FHIR ID is the SCIM
`userName` of an already-provisioned User; and from that point on the session is
built exactly as password Login builds it. Any User with a password may still sign
in with it.

Out of scope, deliberately:

- standalone launch, a "Sign in with Epic" button, or patient-facing (MyChart)
  Login;
- using the Launch context beyond showing its patient on Home (D8) — the
  encounter is held but unused — or any FHIR API call. Epic's tokens are kept
  for the session (D29), but using them, refreshing included, is out of scope,
  beyond releasing the access token to the SPA for FHIRcast (ADR 0016);
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
   |<------------ 302 /#patient=…&encounter=… (session established, D8)
```

`/docs/domain/epic-login.md` is the behaviour in full: who is matched, every
refusal and the reason it is audited under, the landings, the input bounds, the
Epic tokens' lifetime, the Launch context, MFA and what is never recorded. This
file holds the design that produces it and the reason for each decision.

The authorize hop is its own round trip so that Spring Security's
authorization-request filter builds the request.

1. **Launch.** `EpicLaunchController` checks `iss` (D10) and `launch`
   (`EpicInputBounds`, D18), ends any session the browser holds (D9), and holds
   `launch` in a new session for the next step only.
2. **Authorize.** The internal hop `GET /api/auth/epic/authorize` is Spring
   Security's authorization-request filter, with a resolver that adds `launch`
   and `aud` to Spring's own request: `response_type=code`, `client_id`, the
   registered `redirect_uri`, the scopes (the FHIRcast ones are ADR 0016's),
   `state`, `nonce` and PKCE S256. The `state` and `nonce` are Spring Security's
   own (256 and 768 random bits), and the PKCE verifier 128 Base64URL characters
   (768 bits). No login hint is sent: the `launch` value already carries the
   clinician's Hyperspace context. Discovery on `APP_EPIC_OAUTH_ISSUER` runs on
   first use (D26). The pending request is kept in the Redis that holds the
   sessions (`PendingAuthorizations`, D27), so the callback may land on any node.
3. **Callback.** `GET /api/auth/epic/callback` is `oauth2Login`'s processing URL
   on the existing application chain, with `EpicCallbackFilter` ahead of it,
   which takes the pending request first (D27) and checks the callback's own
   inputs before Epic is called. The code is then redeemed through the one
   outbound client, `epicRestClient`, with the verifier and our
   `private_key_jwt` assertion (D7): exactly one request, never retried (D26).
4. **`id_token`.** RS256 only, against the discovered `jwks_uri`, through the one
   `EpicJwkSource`, which keeps the keys and refetches them on an unknown `kid`
   (D26). `EpicIdTokenChecks` checks the claims on the injected `Clock`, the
   nonce in constant time, and `EpicMfaEvidence` the MFA evidence once D17's
   switch is on. No user-info call is made. The tokens reach the success handler
   on the callback's request alone (D29); the Launch context reaches it on the
   request's own identity (`EpicIdentity`, D8), and the FHIRcast hub the same
   way (ADR 0016). The rest of the token response is dropped.
5. **Identity.** `FhirUserReference` parses `fhirUser`. The `local` profile
   alone also accepts the relative `Practitioner/{id}`, because the local Epic
   stand-in, Medplum, issues no other form; it is decided where D21's `http`
   allowance is, and outside the `local` profile parsing is unchanged. The
   stand-in's issuer, by contrast, is absorbed by configuration alone:
   `APP_EPIC_OAUTH_ISSUER` is its base URL and `APP_EPIC_FHIR_BASE` its
   `/fhir/R4`.
6. **Login decision.** `LoginService.logInFromEpic` matches the User (D2, D3)
   and records the success as password Login does, in one transaction. Lock and
   deactivation are decided once, under the User's lock, by the login-state
   module, in the order password Login and the password change use too, so a
   User both locked and deactivated is refused as locked by every route.
7. **Session.** `LoginCompletion` signs the session in through
   `SessionEstablishment`, exactly as for password Login (ADR 0001), with
   `session-start` under method `sso`. The success handler then keeps Epic's
   tokens on the signed-in session (D29) and redirects to Home with the Launch
   context in the fragment (D8, D11).
8. **Any refusal or OAuth error** lands on the login page, refused or
   unavailable (D23), the browser's session ended first (D24).

How a Login ends is ADR 0001's: `LoginCompletion` runs the decision, the session,
the method's own work and the recording in one order. `LoginService` writes the
decision's own audit record, and `LoginOutcomeService` each ending's log line and
counts, and an Epic Login's failure record. A refusal before any decision — at
the launch, the authorize hop, the callback, the token exchange, the `id_token`
or its `fhirUser` — reaches `EpicLoginFailureHandler`, the one place it is told
apart, and ends through `LoginCompletion.endUndecided`. Its reason is read from
what failed: a typed refusal of our own, the failed Epic call, or the
`id_token` decoder's exception, never a message, which can quote what Epic sent.
An Epic call that answered with something unusable (`cert/auth`, `data`) is
refused under the call's reason, beside its `ERROR`: the token call's as
`TOKEN_EXCHANGE_FAILED`, the JWKS's as `INVALID_SIGNATURE`, and discovery's as
`IDP_ERROR`. Every ending's records carry `session.hash` (ADR 0003, "Login"),
and both Epic handlers redirect through one package-private `EpicLanding`.

The login filter neither rotates the session nor saves a security context of its
own, and the authorized client is held on the callback's request alone
(`EpicTokenHandOff`), never in a session. The session is established from our own
Login instead. That keeps everything Epic sent out of the pre-login session, and
it means an Epic session is built by exactly the code that builds a password one.

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
remote is public): `backend/docs/configuration/epic-login.md` is the variable
reference and `/infra/docs/parameters.md` the deployment's. Startup fails fast when
the switch is on and anything required is missing or malformed, including a URL
that is not `https` outside the `local` profile (D21), and it does **not** contact
Epic: discovery runs on first use (D26). The SPA's side — the login page's two
notices and its "Clinicians: open this application from Epic." line — is
`/frontend/docs/BACKEND_CONTRACT.md`'s.

### Decisions

D7, D14 and D16 are under "Client authentication and signing keys", D25 and D26
under "Outbound calls and resilience", D29 under "Epic's tokens", and D13 under
"Accepted risk". The rest follow here.

- **D1: EHR launch only.** Epic opens the clinician's system browser on our
  launch URL, not an iframe. There is no standalone launch, no "Sign in with
  Epic" button and no patient-facing Login.
- **D2: linked by `userName`.** A User is linked to Epic by `userName` =
  Practitioner FHIR ID, sent by the SCIM connector, whose IdP holds that
  attribute. No schema change, and no just-in-time provisioning.
- **D3: an exact, case-sensitive match** (`/docs/domain/epic-login.md`, "Exact match"), after
  the normal `NormalizedUserName` lookup: normalization lowercases, and Epic IDs
  are case-sensitive.
- **D4: one Epic organisation per deployment.** A bare FHIR ID is unique only
  within one organisation. Non-production and production are separate
  deployments, so the issuer is fixed per deployment and is neither repeated on
  each audit event nor stored per User.
- **D5: password Login is kept** for every User with a password. Clinicians are
  expected to use Epic only, and non-clinicians passwords. One User may hold
  both, and needs no extra rule.
- **D6: the Bootstrap Admin never signs in through Epic,** recognised by its
  reservation marker, never its name, and refused as `UNKNOWN_ACCOUNT`, as a name
  that matches nobody is (`/docs/domain/epic-login.md`, "The Bootstrap Admin is excluded"). Password
  Login stays its recovery path, so Epic proving a clinician is never a way into
  the deployment's last account.
- **D8: the Launch context is passed to the SPA, never kept server-side.** The
  token response's `patient` and `encounter` leave in the success landing's
  fragment and nowhere else, and the SPA seeds the tab's **EHR context** from it
  (`/docs/domain/epic-login.md`, "The Launch context goes to the browser, never into the
  session", has the landings, the grammar and the EHR context's lifetime). The
  grammar is the one `PatientId` holds, mirrored in `auth.epic.LaunchContext`
  because `auth` may not reach `patient`. The context reaches the success handler
  on the callback request's own identity (`EpicIdentity`, which the login filter
  saves nowhere). While the tab holds an EHR context the session's FHIRcast
  connection runs: ADR 0016, ["The connection's
  lifetime"](0016-smart-access-token-released-to-the-spa-for-fhircast.md#the-connections-lifetime).

  Authorization is unchanged, deliberately: `patient:read` alone still lets a
  User read any id, and `GET /api/patient/{id}` is not bound to the Launch
  context. Editing the fragment changes only which patient that User views,
  which the same User could already read by id. Binding the read to the launch
  would mean keeping the context server-side, which this decision avoids.
- **D9: every launch is a fresh Login.** A session already in the browser is
  replaced, whoever it belongs to.
- **D10: `iss` is required** on the launch URL and must exactly equal
  `APP_EPIC_FHIR_BASE`, compared as a string and never normalized: no case
  folding and no trailing-slash trimming, so with a base ending `…/R4` both
  `…/R4/` and `…/r4` are refused. One deployment trusts one FHIR base (D4),
  and a normalizing comparison would have to decide which variants Epic means
  the same server by, which only Epic can. A missing or different `iss` is
  refused as `ISS_MISMATCH`.
- **D11: a successful Epic Login lands on Home (`/`),** the same default as
  password Login with no return destination.
- **D12: Dormancy and Lockout apply to Epic Login exactly as to password
  Login, and a refused launch lengthens no failure run** (`/docs/domain/epic-login.md`, "A
  refusal never lengthens a failure run", has why). `LoginOutcomeService`
  therefore appends the `LOGIN_FAILURE` as an `Audited.LoginRefusal`, which
  neither reads nor writes the User's login state, and the record names no
  changed path.
- **D15: a login method on every Login record.** `LOGIN_SUCCESS`,
  `LOGIN_FAILURE` and the operational `session-start` carry `password` or `sso`
  (the audit trail's `login_method` column, the log's `app.login.method`), and the
  Audit page shows it. While D4 holds, `sso` means Epic.
- **D17: Epic Login is multi-factor, by the Epic organisation's attestation
  until Epic confirms the claim.** The Epic organisation enforces MFA at its own
  sign-in, which meets ac-2 on the Epic path (ADR 0011, ac-2); its written
  confirmation is an open item (below). Until Epic confirms that an EHR launch's
  `id_token` carries `amr`, every Epic `LOGIN_SUCCESS` records its MFA factor as
  `idp-attested` (the audit trail's `mfa_factor` column, closed by
  `AuditMfaFactor`). The switch is `APP_EPIC_MFA_EVIDENCE_REQUIRED`, off by
  default and with no other default to protect, documented beside its siblings
  in `backend/.env.example`. On, `EpicMfaEvidence` requires the evidence
  `/docs/domain/epic-login.md` lists ("MFA"). Epic's own sign-in always takes the password, so
  a second factor beside it is what makes the sign-in multi-factor, and `pwd`,
  `pin` or `kba` alone is not evidence. `acr` is not read: its values are each
  deployment's own, none says MFA everywhere, and it names no factor to record.
  Password Login stays the ADR-0011 ac-2 deviation.
- **D18: input bounds** on `launch` and `code`, checked before either is held,
  sent or redeemed (`EpicInputBounds`; the bounds are `/docs/domain/epic-login.md`'s,
  "Input bounds"). Both are opaque, so nothing about them is checked beyond the
  bounds; the bounds keep an oversized or binary value out of the session
  store, the outbound call and any log. `iss` is not bounded so: it is compared
  exactly (D10).
- **D19: the launch and the callback are throttled at the edge.**
  `/api/auth/epic/launch` and `/api/auth/epic/callback` are on the network edge's
  throttling list (`/infra/docs/edge-throttling.md`, "Edge throttling"), which answers a breach
  with `429` and `Retry-After`. The limits are edge configuration, not part of the
  application. Every callback makes an outbound call to Epic, so an unthrottled
  callback could be used to flood Epic from our server.
- **D20: a suspected compromise is handled by deactivation** (`/docs/domain/epic-login.md`,
  "A suspected compromise is a deactivation"), and reported to the Epic
  organisation, whose credential it was. Requiring a password change does not
  apply: an Epic Login presents no password of ours, which is also why the
  change-required flag does not confine an Epic Login (ADR 0008, #23).
- **D21: `https` only, outside the `local` profile.** `APP_EPIC_OAUTH_ISSUER`,
  `APP_EPIC_FHIR_BASE` and `APP_EPIC_REDIRECT_URI` must be absolute `https` URLs,
  and startup refuses any other scheme (`EpicLoginProperties`). Certificate and
  hostname checks are never disabled. Only the `local` profile may use `http`, for
  the local Epic stand-in, and that profile alone also accepts the stand-in's
  relative `fhirUser` (flow step 5).
- **D22: never logged or audited**: every value `/docs/domain/epic-login.md` lists under
  "Never recorded". A startup validation error names the variable, never its
  value. The first line of defence is that no code path hands any of them to a
  log or audit call. The second is the ADR 0003 redaction scan, which knows
  these names (ADR 0003, "Redaction gates").
  `EpicLoginRedactionIntegrationTests` searches every Login path's log and audit
  trail for them, the Launch context's patient and encounter included.
- **D23: refused and unavailable are distinct outcomes,** `302
  /login?signin=refused` and `302 /login?signin=unavailable`, so the login page
  can tell the clinician to try again shortly rather than that they were
  refused; neither carries any further detail. Every Epic call that fails is an
  `EpicOutboundException` carrying its call and its error category (`network`
  for no answer, a timeout or a dropped connection, `server` for a `5xx`,
  `cert/auth` for our own credential refused, `data` for an unusable answer);
  only the first two are unavailable. The other two remain refusals, and are
  `ERROR`s needing follow-up, since a key or a registration is likely wrong.
- **D24: a refused or unavailable launch invalidates any session already in the
  browser** before redirecting, whoever it belongs to. This follows D9, and
  leaves no previous User signed in on a shared workstation.
- **D27: the pending authorization request is single-use** (`/docs/domain/epic-login.md`,
  "Single use"): it is removed from the store atomically on the first callback,
  before anything about the callback is validated. "Atomically" has to hold across nodes
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
  classifies the application's data (an open item, below), Epic Login's controls
  assume User PII and nothing clinical kept from what Epic sends. **The
  assumption holds server-side; it does not hold for the browser, nor for the
  access token.**
  - _Server-side it holds._ D8 keeps nothing clinical Epic sends: the
    Launch context reaches no session, Redis key, Epic token set, log record or
    audit event (D22). The tokens D29 keeps are credentials, not clinical data,
    and today's scope reads no FHIR resource.
  - _The access token is a credential to Restricted data._ With the FHIRcast
    scopes (ADR 0016) a holder of the access token can register at the
    clinician's hub and learn which patient the clinician opens and closes. The
    `Patient-open` and `Patient-close` events carry the patient's identity in
    clinical context, so **the patient-context event data is classified
    Restricted, Sensitive High**, and the access token is handled as a
    credential to it: released only to the session it was issued for, only over
    ADR 0016's one audited, `no-store` endpoint, and never logged.
  - _In the browser it does not._ The patient id names the patient the clinician
    opened in Epic — clinical context — so it is handled as the Patient it names
    is (Restricted, `/docs/domain/patient.md`, "Data classification"). It is an
    EHR's opaque FHIR id, never an NRIC or a name, and how the Launch context
    travels and is kept bounds its exposure: a fragment no server receives,
    stripped from the address bar, and that tab's `sessionStorage` alone (D8).
    It reveals nothing the User could not already read: `patient:read` reads any
    id (D8).

  A ticket that adds FHIR resource scopes makes the tokens a key to clinical
  data, and a ticket that keeps the Launch context server-side makes it held
  clinical context; either revisits this. The data owner's classification
  supersedes all of it when it lands.

### Audit

- `LOGIN_SUCCESS` and `LOGIN_FAILURE` carry the login method (D15), and an Epic
  `LOGIN_SUCCESS` its MFA factor (D17).
- An Epic `LOGIN_FAILURE` carries exactly one reason from the closed
  `EpicLoginFailureReason`. `/docs/domain/epic-login.md` says when each is recorded:
  "Refusals", "Epic being unavailable is not a refusal", "Input bounds" and
  "Protocol refusals". The reasons are audit-only (the deviation "the account
  reasons are audit-only", below).
- Retention follows the existing audit retention policy (at least 90 days). A
  User is correlated with Epic by `userName` = Practitioner ID, under the one
  issuer D4 fixes, and no Practitioner ID or `fhirUser` is recorded beyond the
  User's stable id.

### Log

Epic logging uses the ECS structured logging of ADR-0003: the fluent SLF4J API
through `LogEvent`'s shapes, `event.action=user-authentication`, the method as a
key-value field, and `trace.id`. ADR 0003 ("Login" and "Epic Login's outbound
calls") records the operations, shapes and fields.

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
- **Masking.** `be-log-sensitive-value` knows every D22 name, and leaves out the
  keys' `kid`s, which are public. `EpicLoginRedactionIntegrationTests` checks the
  log, the audit trail and the browser's answers for every D22 value, on the happy
  path, every refusal and every unavailable path (ADR 0003, "Redaction gates").

### Metrics

Micrometer, on the existing Prometheus registry:

- `login` counter, tagged `method` (`password` or `sso`), `outcome` (`success`,
  `refused` or, for `sso` only, `unavailable`) and `reason` (the audit reason
  above; `none` for a success, so every series has the same tag keys) —
  `LoginMetrics`, behind the `LoginCounts` port `LoginOutcomeService` records
  through, registered whether or not Epic Login is on (ADR 0001);
- `epic.login.failed_calls` counter, tagged `call` and `error_category`: each Epic
  call whose failure ended a Login, so a refused credential (`cert/auth`), which no
  outbound status tells apart, can be alerted on — `EpicCallMetrics`, behind the
  `EpicCallCounts` port;
- `epic.outbound` timer and `epic.outbound.errors` counter, tagged `call`
  (`discovery`, `jwks` or `token`) — `EpicOutboundInterceptor`;
- alert rules (`/infra/docs/operational-telemetry.md`, "Alerts"): `EpicJwksFetchFailing` and
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
  logs each call by the name its call site gives it (`app.epic.call`:
  `discovery`, `jwks` or `token`), reading neither body nor header (ADR 0003,
  "Epic Login's outbound calls").
  It meters every call on the `epic.outbound` timer and every call that got no
  answer or a `5xx` on the `epic.outbound.errors` counter — a name of its own, as
  Prometheus allows one type per metric name — both tagged `call`. It also
  writes the call's W3C `traceparent`: the deployment installs no propagator, so
  an inbound `traceparent` is ignored (ADR 0003), and the outbound header is set
  here rather than by turning propagation on for every request.
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
  `/infra/docs/epic-signing-keys.md`, "Signing-key promotion". Every startup record carries both
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
  a library signer a private JWK. as-8 and ck-4 stay open in ADR-0011 until then.

### Epic's tokens (D29)

**D29: Epic's tokens are kept for the session.** Backend
features need to act at Epic on behalf of the signed-in clinician, so a successful
Epic Login keeps Epic's tokens server-side, bound to the session it signs in, as
the **Epic tokens** (`/CONTEXT.md`). The Launch context is not among them: it is
passed to the SPA in the landing's fragment and kept nowhere server-side (D8),
and no FHIR API is called yet.

- **What is kept** (`EpicTokenSet`) is what `/docs/domain/epic-login.md` lists ("Identity,
  and the Epic tokens for the session"), the access token's `expires_in` made an
  absolute instant on the injected `Clock` (`EpicTokenSet.isExpired`). Epic
  issues a refresh token only when the registration allows one and the request
  asks for `offline_access` or `online_access`, which it does not, so today the
  refresh token is always absent and the access token reads no FHIR resource.
  Adding FHIR resource scopes, and refreshing, belong to a later ticket.

- **Only on a successful Epic Login, and only under the signed-in id.** The login
  filter's authorized-client repository is `EpicTokenHandOff.REPOSITORY`, which
  holds the authorized client on the callback request alone. Once
  `LoginCompletion` has signed the session in, `EpicLoginSuccessHandler` takes the
  client back (`EpicTokenCapture`). It writes the tokens to the signed-in session
  as the attribute `app.epic.tokens` (`SignedInSession.EPIC_TOKENS_ATTRIBUTE`,
  through `SignedInSession.keepEpicTokens`). A refused or
  unavailable launch never takes them, and they end with its request. The
  pre-login session id holds nothing.
- **A session attribute, not a key of its own.** A Spring Session attribute ends
  with the session, whichever way the session ends, with no cleanup code to forget
  on a future path. That covers:
  - id rotation at sign-in;
  - the idle timeout and the absolute session lifetime;
  - logout;
  - every session revocation trigger in `/docs/domain/sessions.md`;
  - D9 and D24's invalidation by the next launch.

  A separate Redis key, as `PendingAuthorizations` keeps the pending request, was
  rejected: it would need an explicit delete on every one of those paths. D27's
  reason for its own key, atomic single use across concurrent requests, does not
  arise for tokens written once at sign-in and only read after. The attribute is
  Java-serialized like every other session attribute. The type holds only
  strings, an instant and a sorted set of strings.

- **Retrieval** is the `EpicTokens` port, `forSession(sessionId)` →
  `Optional<EpicTokenSet>`. Its adapter (`EpicTokensAdapter`) finds the session
  through Spring Session's repository without touching its last-access time, and
  asks it for its tokens (`SignedInSession.epicTokens`). It answers empty for a
  password Login's session, an unknown or ended session, and no id. The
  repository does not know the absolute session lifetime, which is enforced on a
  session's next request. So the same policy is applied on the same clock, and
  nothing is handed out once the lifetime is over, even if no request has ended
  the session yet.

  Its production caller is the FHIRcast release (`FhircastAccessService`, ADR
  0016), which reads the tokens and the hub kept beside them
  (`fhircastHubForSession`) through it rather than through the session
  attribute.

- **Storage is bounded by the remaining absolute lifetime.** The store expires a
  session its idle bound after its last request, and every request renews that.
  Near the lifetime's end, the idle bound alone would keep a session stored past
  it. For a session holding the tokens, the idle bound is cut to what remains of
  the lifetime whenever that is shorter (`AbsoluteSessionLifetimePolicy.idleBoundAt`:
  whole seconds rounded down, never under one). The cut is written once, in
  `SignedInSession.boundByLifetime`, and applied when the success handler writes
  the tokens, and again on every later request in `AbsoluteSessionLifetimeFilter`,
  before the request's renewal is saved. A session without Epic tokens is never
  touched. One residue is Spring Session's: the
  indexed repository keeps an expired session's Redis key five minutes past its
  expiry, during which the adapter answers nothing. That grace is the same for a
  session that idles out, and it was left as it is.
  `EpicTokensStorageLifetimeIntegrationTests` holds both bounds against real Redis.
- **Never leaves the backend, the access token's FHIRcast release aside.** No
  endpoint returns the refresh token or the `id_token`. The access token alone
  is released, to its own session, by `GET /api/auth/epic/fhircast` for the
  SPA's FHIRcast connection — ADR 0016 replaces this decision for it.
  `ArchitectureTest.epic_tokens_never_reach_a_rest_controller` holds that no REST
  controller, that one included, depends on `EpicTokenSet` or `EpicTokens`, and
  `the_released_access_token_reaches_one_rest_controller` holds the released
  projection to that one controller. The one web adapter that
  writes them, the success handler, answers only with a redirect. D22 covers all
  three, the `id_token` included, since it carries the clinician's identity
  claims. `EpicTokenSet.toString()` names each token by its presence alone.
  `EpicTokensIntegrationTests` holds the callback's answer and `/api/auth/me` to
  carrying none of them.
- **Not encrypted at rest by the application: an accepted risk, for now.** The
  access and refresh tokens are bearer credentials at rest in Redis. They sit
  beside what each session already holds there, behind the same controls:
  - the cache is reachable only from the application's security group.

  The deployed single-node cluster has no AUTH token and no at-rest encryption
  (`/infra/infrastructure.yaml`, `RedisCluster`;
  `/infra/docs/database-credentials.md`). Application-level encryption
  would need a key held in the same environment as the Redis credentials (D16's
  position for the signing key), and would protect only against a compromise of
  Redis alone. Adding the FHIRcast scopes (ADR 0016) left it an accepted risk:
  the token reads no FHIR resource, no refresh token exists, and a token copied
  out of Redis lets its holder register at the hub only from the clinician's own
  workstation, since the hub listens on loopback alone. The ticket that adds a
  FHIR resource scope or a refresh token revisits it. The options then are
  envelope encryption under a KMS key, or an encrypted replication group.

### Accepted risk (D13)

- **Our session outlives the clinician's Hyperspace session.** Epic sends no
  sign-out, so signing out of Epic leaves our session running. It is bounded by
  the 15-minute idle timeout and the 8-hour absolute session lifetime, and the
  next launch in that browser replaces it (D9). Single sign-out with Epic is out
  of scope.
- **Epic's tokens stay valid at Epic until they expire.** The tokens D29 keeps
  are dropped from the store when the session ends, every way it ends, but
  nothing revokes them at Epic. The access token stays valid there until its
  `expires_in` runs out. A refresh token stays valid for its own, longer lifetime,
  which makes it the more valuable of the two. Neither is logged or audited, and
  only the access token reaches the browser, held in the tab's memory for as long
  as registering at the FHIRcast hub takes (ADR 0016) — so after the session ends
  only someone who had already copied one out could present it. Epic sets the
  access token's lifetime (ADR 0011 records lifetimes over 30 minutes). Today
  Epic issues no refresh token, because the authorize request asks for neither
  `offline_access` nor `online_access`. Revocation at session end belongs to
  the ticket that first asks for one, once Epic's revocation support is
  confirmed (an open item, below).

### IM8 controls

Epic Login meets these by the decisions above:

| Control                      | Met by                                                                                                                    |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| ac-2 MFA                     | D17, on the Epic path. Password Login stays ADR 0011's ac-2 deviation.                                                    |
| ac-12 SSO for internal users | The Epic path: clinicians sign in through their organisation. Password Login for non-clinicians stays ADR 0011's ac-12 deviation. |
| as-1 Input validation        | D18.                                                                                                                      |
| as-4 Rate-limiting           | D19, at the edge (ADR 0011, as-4).                                                                                        |
| as-15 Change on compromise   | D20.                                                                                                                      |
| dp-3 TLS in transit          | D21, for Epic Login's own URLs. ADR 0011's wider dp-3 entry stays open.                                                   |
| lm-16 Key signals            | "Metrics".                                                                                                                |
| lm-19 Log sanitisation       | D22, "Log"'s masking, and `EpicLoginRedactionIntegrationTests`.                                                           |
| pm-6 System documentation    | The flow's sequence diagram and "Network paths".                                                                          |

Open, and recorded in ADR 0011: **as-8** (secrets management) and **ck-4** (key
storage) for the signing key, which is an environment variable until a KMS-held
key closes both (D16); and **as-10** (HSTS), under its dp-3 entry. Epic Login
leaves ADR 0011's ac-4 deviation as it was. No ARC control applies: the
application has no agentic or LLM component.

### App-Standards deviations

These requirements of `Appfw-User-Standards/User_SSO` and `Appfw-Logging-Standards`
are **conscious deviations**; the decisions above meet the rest:

| Standard requirement                                                           | Deviation                                                        | Why                                                                                                                                                                                            |
| ------------------------------------------------------------------------------ | ---------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SSO §3.2: callback failures `400`, token failures `401`, IdP unavailable `502` | `302 /login?signin=refused` or `/login?signin=unavailable` (D23) | The callback is a navigation in the clinician's browser, where a bare status is a dead end; one answer per outcome gives a prober nothing to enumerate, and the audit reason keeps the detail. |
| SSO §4: application-layer rate limiting, 10–60 per registration per minute     | Edge throttling only (D19)                                       | The edge throttles every public authentication route already (ADR 0011, as-4) and keeps a flood off the JVM and off Epic's token endpoint.                                                     |
| SSO §4: a registry of redeemed authorization codes                             | None (D27)                                                       | The pending request is single-use, so a replayed callback is refused before any token call; Epic's codes are single-use at Epic.                                                               |
| SSO §4: consumed `state` tracked against reuse across sessions                 | Session-scoped, consumed on first callback (D27)                 | Reuse in another session finds nothing to match; reuse in the same one finds it taken.                                                                                                         |
| SSO §4: startup fails if metadata fetch fails                                  | Discovery on first use (D26)                                     | Password Login must not depend on Epic being reachable at deploy. A wrong issuer shows on the first launch, as `unavailable` or a `data` `ERROR`.                                              |
| SSO §3.2: failures complete in consistent time                                 | Not equalised                                                    | Post-round-trip refusals are dominated by network time and the browser sees one answer; `state` and the nonce are compared in constant time (D27).                                             |
| SSO §4: a local record created for each SSO identity at login                  | No JIT provisioning (D2)                                         | The directory is the joiner and leaver authority; a launch cannot recreate a leaver.                                                                                                           |
| SSO §4: SSO users have no local credentials                                    | One User may hold both (D5)                                      | SCIM cannot express the rule. Each path keeps its controls: an Epic refusal never lengthens a failure run (D12), and lockout, deactivation and dormancy refuse both.                           |
| SSO §2: entry point `/oauth2/authorization/{registrationId}`                   | `/api/auth/epic/launch` and `/callback`                          | The EHR launch is Epic-initiated and carries `iss` and `launch`, which no generic entry point accepts; the routes stay under the reserved `/api`.                                              |
| SSO §4: verify MFA evidence in `acr` / `amr`                                   | Organisational attestation (D17)                                 | Epic may not send either on an EHR launch; the check is built and off until Epic confirms.                                                                                                     |
| SSO §3.3: success audit includes registration ID and issuer                    | Implied by D4                                                    | One Epic organisation per deployment, so both are constants of its configuration.                                                                                                              |
| SSO §5: tokens of an invalidated session are revoked                           | Dropped, not revoked (D13)                                       | The risk, and the ticket that settles it, are under D13.                                                                                                                                       |
| Logging §2.2: no reason that reveals whether the account exists                | Account reasons in the audit only                                | Below.                                                                                                                                                                                         |

### Deviation: the account reasons are audit-only

Logging standard §2.2 asks that no reason reveal whether the account exists.
`UNKNOWN_ACCOUNT`, `ACCOUNT_DISABLED` and `ACCOUNT_LOCKED` do reveal it, and they
are kept anyway, **in the audit trail only**. An investigation
needs them, the SSO standard asks for specific failure types, and password Login
records the same reasons. The browser sees one answer for all of them,
`/login?signin=refused`, and the operational log says only "Epic sign-in refused",
with no reason and no user field. The audit trail, read by an administrator,
holds the detail. The `login` counter carries the reason as a tag; it counts
events and names no account. Password Login keeps them the same way (ADR 0001).

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
  token can revoke kept tokens when the session ends (D13, D29).

The Epic sandbox registration — a non-production app on fhir.epic.com (Clinicians
audience, R4, `launch openid fhirUser` and the FHIRcast scopes of ADR 0016, our public
JWKS URL, the launch URL and redirect
URI), its practitioner provisioned by FHIR ID — is configuration only, and its
values belong in `/infra/docs/parameters.md` once registered.

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
- **as-8 (secrets management) and ck-4 (key storage) stay open** in ADR-0011
  until the KMS signer exists ("IM8 controls", above). The signing keys are
  deployment environment configuration, like the application's other secrets,
  and a KMS-held key closes both. ADR 0011's as-8 and ck-4 entries name this
  key.
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
  are unreachable; `/infra/docs/operational-telemetry.md` carries the alert rule.
- A signed-out Epic clinician's session lives on until its idle bound, its
  absolute lifetime or the next launch (D13). A shared workstation relies on the
  next launch or the idle bound to end it. The Epic tokens it holds (D29) live
  exactly as long.
- Beyond the login method, an Epic session differs from a password one only in
  the Epic tokens it holds, which only the backend can read (D29) — the access
  token aside, which its own SPA tab is released for FHIRcast (ADR 0016) — and
  in the FHIRcast card Home shows it.

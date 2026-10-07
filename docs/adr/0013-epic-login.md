# 13. Epic Login: SMART on FHIR EHR launch sign-in

Date: 2026-10-06

## Status

Proposed, and incomplete by design. Started in Epic Login step 4 (signing keys and
JWKS) with the three decisions that step implements: D7, D14 and D16. The
tracer-bullet step (a provisioned clinician signs in from Epic) added the flow and
the decisions it implements: D1–D11 and D15. The account-refusal step added D12
and D24, and the one section 11 App-Standards deviation it implements: the
account reasons kept in the audit only. The spec, `/docs/epic-smart-login.md`,
holds every decision (D1–D28); this ADR gains the rest, the accepted risk D13, the
section 9 policy position and the other section 11 deviations, plus an addendum on
ADR-0012, in the documentation step (spec section 7, step 9). Until then the spec
is the authority for anything not recorded here.

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

## Decision

### The flow

```
Browser            Epic (Hyperspace + OAuth)          Backend
   | <-- opens system browser on launch URL ---|              |
   |-- GET /api/auth/epic/launch?iss&launch ------------------>| iss allowlist
   |<-------------------------- 302 /api/auth/epic/authorize   |
   |-- GET /api/auth/epic/authorize -------------------------->|
   |<-------------------------- 302 authorize?…launch,aud,PKCE,state,nonce
   |-- GET authorize ------------>|                            |
   |<-- 302 /api/auth/epic/callback?code&state                 |
   |-- GET /api/auth/epic/callback ---------------------------->| pending request, state
   |                              |<-- POST token (client_assertion, verifier)
   |                              |-- id_token --------------->| verify via jwks_uri
   |                              |-- GET /api/auth/epic/jwks.json (Epic verifies our assertion)
   |<-------------------------------------- 302 / (session established)
```

1. **Launch.** `EpicLaunchController` accepts `iss` only when it equals
   `APP_EPIC_FHIR_BASE` exactly and a `launch` is present, ends any session the
   browser holds, and holds `launch` in a new session for the next step only.
2. **Authorize.** The internal hop `GET /api/auth/epic/authorize` is Spring
   Security's authorization-request filter, with a resolver that adds `launch`
   and `aud` to Spring's own request: `response_type=code`, `client_id`, the
   registered `redirect_uri`, `scope=launch openid fhirUser`, `state`, `nonce`
   and PKCE S256. Discovery on `APP_EPIC_OAUTH_ISSUER` runs on use, never at
   startup. The pending request is kept in the HTTP session, in Redis, so the
   callback may land on any node.
3. **Callback.** `GET /api/auth/epic/callback` is `oauth2Login`'s processing URL
   on the existing application chain. The pending request is taken out of the
   session, `state` checked, and the code redeemed once through the one outbound
   client, `epicRestClient`, with the verifier and our `private_key_jwt`
   assertion (D7).
4. **`id_token`.** RS256 only, against the discovered `jwks_uri` fetched through
   `epicRestClient`; `iss`, `aud`/`azp`, `exp` and `iat` with a 30-second skew
   from the injected `Clock`, and `nonce`. No user-info call is made.
5. **Identity.** `fhirUser` must be `{fhirBase}/Practitioner/{id}` with a
   non-blank `id` and nothing after it (`FhirUserReference`). The dev profile
   alone also accepts the relative `Practitioner/{id}` under the same rules,
   because the local SMART launcher issues no other form; it is decided where
   D21's `http` allowance is, and outside the dev profile parsing is unchanged.
6. **Login decision.** `LoginService.logInFromEpic` accepts the User whose stored
   `userName` equals the id exactly and records the success exactly as password
   Login does, in one transaction. It refuses, in this order: no exact match (a
   case variant included) or the Bootstrap Admin as `UNKNOWN_ACCOUNT`, a
   deactivated User as `ACCOUNT_DISABLED`, and a User locked for any cause as
   `ACCOUNT_LOCKED` (`EpicLoginFailureReason`).
7. **Session.** `SessionEstablishment`, shared with password Login, rotates the
   session id, saves the security context, sets the principal index and the
   role-mapping hash, drops the pre-login CSRF token and logs `session-start`
   (method `sso`). The answer is `302 /`; the SPA's `/api/auth/me` →
   `authenticated` → `/showcase` path follows.

Any refusal or OAuth error lands at `/?signin=refused`, with no detail, its
session ended (D24). An account refusal is recorded once, by the login decision,
as password Login's refusal is, so no caller can refuse without the record: a
`LOGIN_FAILURE` under method `sso` with its reason and the refused User's stable
id — none for `UNKNOWN_ACCOUNT` — through `LoginAttemptService.recordRefusal`,
which counts toward no failure run (D12), and a `WARN` saying only "Epic sign-in
refused". The success handler then counts it on `epic.login`
(`outcome=refused`, `reason`) and redirects. The rest of the refusal treatment —
the protocol reasons, refused versus unavailable, the outbound resilience —
follows in later steps; the spec is its authority until then.

The login filter is configured to neither rotate the session nor save a security
context of its own, and the authorized client is saved nowhere. The success
handler, a web adapter, establishes the session from our own Login instead. That
is what keeps Epic's `id_token` and access token out of the session store (D8),
and it means an Epic session is built by exactly the code that builds a password
one.

### Decisions

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
- **D5: password Login is kept** for every User with a password. One User may
  hold both, and needs no extra rule.
- **D6: the Bootstrap Admin never signs in through Epic,** recognised by its
  reservation marker, never its name, so a rename cannot move the exclusion.
  It is refused as `UNKNOWN_ACCOUNT`, exactly as a name that matches nobody is,
  so the refusal does not say the account exists. Password Login stays its
  recovery path.
- **D8: identity only.** The patient and encounter context and the access token
  are discarded, and nothing from Epic is stored.
- **D9: every launch is a fresh Login.** A session already in the browser is
  replaced, whoever it belongs to.
- **D10: `iss` is required** on the launch URL and must exactly equal
  `APP_EPIC_FHIR_BASE`; a missing or different `iss` is refused.
- **D11: a successful Epic Login lands on `/showcase`,** the same default as
  password Login with no return destination.
- **D12: Dormancy and Lockout apply to Epic Login exactly as to password
  Login,** and a successful Epic Login moves the dormancy basis. A refused launch
  does **not** lengthen a failure run: Epic checked the credential, not us, so a
  refusal is no evidence of guessing. Counting it would also let anyone whose
  Epic ID is a case variant of a User's `userName` lock that User out by
  launching. `LoginAttemptService.recordRefusal` therefore records the
  `LOGIN_FAILURE` and neither reads nor writes the User's login state, and the
  record names no changed path.
- **D15: a login method on every Login record.** `LOGIN_SUCCESS`,
  `LOGIN_FAILURE` and the operational `session-start` carry `password` or `sso`
  (the audit trail's `login_method` column, the log's `app.login.method`), and the
  Audit page shows it. While D4 holds, `sso` means Epic.
- **D24: a refused launch invalidates any session already in the browser**
  before redirecting, whoever it belongs to. This follows D9, and leaves no
  previous User signed in on a shared workstation.

### Deviation: the account reasons are audit-only

Logging standard §2.2 asks that no reason reveal whether the account exists.
`UNKNOWN_ACCOUNT`, `ACCOUNT_DISABLED` and `ACCOUNT_LOCKED` do reveal it, and they
are kept anyway (spec section 11), **in the audit trail only**. An investigation
needs them, the SSO standard asks for specific failure types, and password Login
records the same reasons. The browser sees one answer for all of them,
`/?signin=refused`, and the operational log says only "Epic sign-in refused",
with no reason and no user field. The audit trail, read by an administrator,
holds the detail. The `epic.login` counter carries the reason as a tag; it counts
events and names no account.

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
  a library signer a private JWK.

## Consequences

- An Epic session is indistinguishable from a password one after sign-in: the same
  authorities (Spring Security's own `FACTOR_PASSWORD` aside, which records a
  credential the Epic path never presents), the same session bounds, the same
  one-session-per-User rule. Only the login method on the records tells them
  apart.
- The callback is served by a security filter, not a controller, so the API
  contract check lists it as filter-served rather than finding it in the handler
  mapping.

- **as-8 (secrets management) and ck-4 (key storage) stay open** in ADR-0012
  until the KMS signer exists (spec section 9). The signing keys are deployment
  environment configuration, like the application's other secrets, and a KMS-held
  key closes both. ADR-0012's as-8 entry gains this key in the step 9 addendum.
- The public JWKS is derived from the private keys at startup, so the
  configuration holds nothing but the PEMs and `kid`s. The KMS signer will need
  its public keys from KMS instead.
- A key leak is contained by promotion: a redeploy with a fresh active key and
  the leaked key removed takes it out of the JWKS at once.

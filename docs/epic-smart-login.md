# Spec: Epic SMART on FHIR EHR launch Login

Status: Draft. Owner: kocolipy. Refines ADR-0012 (it records "no organisational IdP/SSO" and lists OAuth/JWT flows as out of scope). This change will be recorded as ADR 0013.

## 1. Goal

A clinician working in Epic opens this application from Epic and is signed in without a password. Epic's SMART on FHIR OAuth 2.0 **EHR launch** proves who they are. The validated `id_token`'s `fhirUser` names an Epic Practitioner, whose FHIR ID is the SCIM `userName` of an already-provisioned User. From that point on, the session is built exactly as password Login builds it today.

**Password Login is retained unchanged.** Any User with a password may still open the login page and sign in with it.

## 2. Non-goals

- Standalone launch, a "Sign in with Epic" button, or patient-facing (MyChart) Login.
- Using patient or encounter launch context, storing Epic's access token, refresh tokens, or any FHIR API call.
- Just-in-time provisioning: an Epic Login never creates or modifies a User.
- Single sign-out with Epic.
- Signing-key generation, scheduling or expiry. Key rotation is operated outside the application.
- Several Epic organisations sharing one deployment.
- Retiring any password feature.

## 3. Decisions

| #   | Decision                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| --- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| D1  | EHR launch only. Epic opens the clinician's **system browser** on our launch URL, not an iframe.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| D2  | A User is linked to Epic by **`userName` = Practitioner FHIR ID**, sent by the SCIM connector, whose IdP holds that attribute. No schema change.                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| D3  | The match is **exact and case-sensitive**. A User is found through the normal `NormalizedUserName` lookup, and is then accepted only if its stored `userName` equals the Practitioner ID character for character. Reason: normalization lowercases, but Epic IDs are case-sensitive.                                                                                                                                                                                                                                                                                                                                       |
| D4  | **Exactly one Epic organisation per deployment**, because bare FHIR IDs are unique only within one organisation. Non-production and production are separate deployments. The issuer is therefore fixed per deployment and is not repeated on each audit event or stored per User.                                                                                                                                                                                                                                                                                                                                          |
| D5  | Password Login is kept for every User with a password. Clinicians are expected to use Epic only, and non-clinicians to use passwords. One User holding both is allowed and needs no extra rule.                                                                                                                                                                                                                                                                                                                                                                                                                            |
| D6  | The **Bootstrap Admin can never sign in through Epic**, recognised by its reservation marker. Password Login remains its recovery path.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| D7  | Client authentication to Epic's token endpoint is **`private_key_jwt`**, signed ES384 with an EC P-384 key.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| D8  | Identity only. The patient and encounter context and the access token are discarded, and nothing from Epic is stored.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| D9  | **Every launch is a fresh Login.** A session already in that browser is replaced, whoever it belongs to.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| D10 | **`iss` is required** on the launch URL and must exactly equal the configured FHIR base. A missing or different `iss` is refused.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| D11 | A successful Epic Login lands on `/showcase`, the same default as password Login when no return destination exists.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| D12 | Dormancy and Lockout apply to Epic Login exactly as to password Login. A successful Epic Login moves the dormancy basis. A refused launch does **not** lengthen a failure run, because Epic checked the credential, not us.                                                                                                                                                                                                                                                                                                                                                                                                |
| D13 | **Accepted risk:** our session outlives the clinician's Hyperspace session, since Epic sends no sign-out. It is bounded by the 15-minute idle timeout and the 8-hour absolute session lifetime, and the next launch replaces it (D9). Likewise, Epic's access token stays valid at Epic until it expires: it is never stored, logged or sent to the browser, so nothing in this system can present it, and it is not revoked.                                                                                                                                                                                              |
| D14 | Signing keys: the backend signs with the **active key** and publishes the active key plus an optional **next key** in its public JWKS. Rotation is external. A second key is created around mid-period, and the operator later promotes it to active by redeploying. Keys stay published until configuration removes them. The application has no expiry or period logic.                                                                                                                                                                                                                                                  |
| D15 | `LOGIN_SUCCESS`, `LOGIN_FAILURE` and the operational `session-start` carry a **login method**, `password` or `sso`. While D4 holds, `sso` means Epic.                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| D16 | The active signing key is, **for now**, an environment variable (as-8 / ck-4 stay open in ADR-0012). **The target is an AWS KMS-held key** that signs through the KMS API, so the private key never leaves KMS. Signing sits behind a `ClientAssertionSigner` interface so that change stays local to that one implementation.                                                                                                                                                                                                                                                                                             |
| D17 | **Epic Login is multi-factor:** the Epic organisation enforces MFA at its own sign-in. This satisfies ac-2 on the Epic path. It is an organisational attestation recorded in ADR 0013. If Epic confirms it sends `acr` or `amr` (section 8), the `id_token` must carry MFA evidence and a token without it is refused, and the MFA factor is taken from `amr`; until then the factor is recorded as `idp-attested`. Password Login stays the existing ADR-0012 ac-2 deviation.                                                                                                                                             |
| D18 | Input bounds: `launch` and `code` are 1–8192 characters of printable ASCII with no whitespace, refused otherwise. `iss` is compared as an exact string and never normalized.                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| D19 | `/api/auth/epic/launch` and `/api/auth/epic/callback` join the network edge's throttling list (`infra/README.md`, "Edge throttling"), which answers a breach with `429` and `Retry-After`. The limits are edge configuration, not part of the application. Every callback makes an outbound call to Epic, so an unthrottled callback could be used to flood Epic from our server.                                                                                                                                                                                                                                          |
| D20 | A suspected compromise of an Epic-linked User is handled by deactivating the User in the directory. That ends their sessions after commit and blocks Epic Login. It is also reported to the Epic organisation. Forced password change does not apply.                                                                                                                                                                                                                                                                                                                                                                      |
| D21 | `APP_EPIC_OAUTH_ISSUER`, `APP_EPIC_FHIR_BASE` and `APP_EPIC_REDIRECT_URI` MUST be `https`, and startup refuses any other scheme. Certificate and hostname checks are never disabled. Only the dev profile may use `http`, for the local Docker launcher.                                                                                                                                                                                                                                                                                                                                                                   |
| D22 | Never logged or audited: the authorization code, `launch`, `state`, `nonce`, PKCE verifier, `id_token`, access token, client assertion, and the private key material of the active and next signing keys. A startup validation error names the variable, never its value.                                                                                                                                                                                                                                                                                                                                                  |
| D23 | **Refused and unavailable are distinct outcomes.** A refusal (bad input, a failed check, or no acceptable User) is `302 /?signin=refused`. Epic being unreachable (a connect or read timeout, or a 5xx from discovery, JWKS or the token endpoint) is `302 /?signin=unavailable`. Neither carries any further detail.                                                                                                                                                                                                                                                                                                      |
| D24 | **A refused or unavailable launch invalidates any session already in that browser** before redirecting, whoever it belongs to. This follows D9, and leaves no previous User signed in on a shared workstation.                                                                                                                                                                                                                                                                                                                                                                                                             |
| D25 | **One outbound client.** Discovery, the JWKS fetch and the token call all go through one `epicRestClient`, built from Spring Boot's auto-configured `RestClient.Builder`, so they share tracing, logging and timeouts. The connect timeout is 2 seconds and the read timeout 5 seconds, both configurable. There is no circuit breaker: login volume is low and the edge throttle (D19) bounds the load.                                                                                                                                                                                                                   |
| D26 | **Retries.** The token call is **never retried**: the code is single-use, and the clinician can relaunch. Discovery runs on first use and is cached for 24 hours. Only a successful fetch is cached, and a failure is `unavailable` and is retried on the next launch. On an `id_token` whose `kid` is not in the cached JWKS, the JWKS is refetched up to 3 times, after 1, 2 and 4 seconds. If the `kid` is still unknown the token is refused, and discovery is refetched. An unknown `kid` is never accepted from the cache. Each retry logs at `WARN` with its attempt number, and the final failure logs at `ERROR`. |
| D27 | **Single use.** The pending authorization request (`state`, `nonce`, PKCE verifier) is removed from the session atomically on the first callback, before it is validated, so a replayed or concurrent callback finds none and is refused without calling Epic. `state` and `nonce` are compared in constant time. Authorization codes are single-use at Epic.                                                                                                                                                                                                                                                              |
| D28 | **Data classification** is set by the data owner (section 8). Until then the controls assume User PII and no clinical data, which D8 guarantees.                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |

## 4. Flow

1. **Launch.** Epic opens `GET /api/auth/epic/launch?iss={fhirBase}&launch={opaque}`.
   - The request is refused with `302 /?signin=refused`, logged and audited, if `iss` is absent or not exactly equal to `APP_EPIC_FHIR_BASE`, or if `launch` is outside the D18 bounds. The log names the field (`iss`, `launch`) and the broken rule (`missing`, `length`, `charset`, `mismatch`), never the value.
   - The `launch` value is held in the HTTP session for the next step only.
2. **Authorize.** The backend redirects to Epic's `authorization_endpoint`, found by OIDC discovery on `APP_EPIC_OAUTH_ISSUER` and cached (D26). The request carries:
   - `response_type=code`, `client_id`;
   - `redirect_uri` exactly equal to `APP_EPIC_REDIRECT_URI`;
   - `scope=launch openid fhirUser`, `launch`, `aud={fhirBase}`;
   - `state` and `nonce`, both at least 128 bits of entropy;
   - PKCE `code_challenge` = `Base64URL(SHA256(code_verifier))` with `code_challenge_method=S256`, where the verifier is at least 43 characters with at least 256 bits of entropy.

   No login hint is sent: the `launch` value already carries the clinician's Hyperspace context. The pending request is kept in the HTTP session, which lives in the existing Redis session store, so the callback may land on any node.

3. **Callback.** Epic redirects to `GET /api/auth/epic/callback?code&state`.
   - The pending request is removed from the session first (D27). With none present, the callback is refused without a token call.
   - `state` must equal the pending request's, compared in constant time, and `code` must be within the D18 bounds.
   - The code is redeemed once (D26) at `token_endpoint` with the same `redirect_uri`, the `code_verifier` and a client assertion, `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`. The assertion carries `iss` = `sub` = client_id, `aud` = token endpoint, a unique `jti`, `exp` no more than 5 minutes ahead, and the header `kid` of the active key. The verifier is discarded after the exchange.
4. **`id_token` validation.** RS256 only, against the discovered `jwks_uri`, with the D26 refetch on an unknown `kid`. Then:
   - `iss` = `APP_EPIC_OAUTH_ISSUER`
   - `aud` containing client_id, and when `aud` has more than one value, `azp` present and equal to client_id
   - `exp` not passed and `iat` present, with a 30-second clock skew. An `iat` more than 30 seconds in the future is refused. Time comes from an injected `Clock`.
   - `nonce` = the pending nonce, compared in constant time, then discarded
   - MFA evidence in `acr` / `amr` once D17's condition is met

   The access token and the rest of the token response are dropped.

5. **Identity.** `fhirUser` must be `{fhirBase}/Practitioner/{id}`, with a non-blank `id` and nothing after it. The `id` is the Practitioner ID.
6. **Login decision.** `LoginService.logInFromEpic(practitionerId, retainedSessionId)` decides as follows:
   - No User found, stored `userName` ≠ ID exactly, or the User carries the Bootstrap Admin marker → refuse `UNKNOWN_ACCOUNT`.
   - The User is deactivated → refuse `ACCOUNT_DISABLED`.
   - The User is locked (any cause) → refuse `ACCOUNT_LOCKED`.
   - Otherwise the authorities are those password Login computes for this User (`ROLE_USER`, baseline, Role mapping), and the success is recorded exactly as for password Login (failure run cleared, dormancy basis moved, `LOGIN_SUCCESS` fail-closed, other sessions revoked after commit). This runs in one transaction.
7. **Session.** The same session establishment as password Login runs:
   - rotate the session id;
   - save the security context;
   - set the principal index to the stable id;
   - set the role-mapping hash;
   - drop the pre-login CSRF token;
   - log `session-start` (method `sso`).

   The response is `302 /`. The SPA's existing `/api/auth/me` → `authenticated` → `/showcase` path follows.

8. **Any refusal or OAuth error** at any step is `302 /?signin=refused`, and Epic being unreachable is `302 /?signin=unavailable` (D23). Neither gives the browser any detail. Both invalidate any session in that browser first (D24). The Epic failure handler is the single place that logs and audits them, once: `LOGIN_FAILURE` (method `sso`, with a section 5 reason), and a log entry with the exception type only.

## 5. Interfaces

**Backend routes.** All are public, under the reserved `/api` path, and answer **404 when `APP_EPIC_ENABLED` is off**, mirroring `APP_SCIM_ENABLED`.

| Route                          | Purpose                                                                                                                        |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------ |
| `GET /api/auth/epic/launch`    | Launch URL registered with Epic.                                                                                               |
| `GET /api/auth/epic/authorize` | Internal hop into Spring's authorization-request resolver.                                                                     |
| `GET /api/auth/epic/callback`  | Redirect URI registered with Epic. The one fixed path whose absolute URL is `APP_EPIC_REDIRECT_URI`.                           |
| `GET /api/auth/epic/jwks.json` | Our public JWKS, holding the active key plus the next key if configured. Never a private parameter. Must be reachable by Epic. |

**Configuration.** Environment variables only, with **no default credentials** (the remote is public). Startup fails fast when the switch is on and anything required is missing or malformed, including a URL that isn't `https` outside the dev profile (D21). Startup does **not** contact Epic: discovery runs on first use (D26).

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

**Frontend.**

- `login.tsx` keeps the password form unchanged.
- It adds a neutral "Sign-in from Epic was refused" notice for `?signin=refused`, a "Sign-in from Epic is temporarily unavailable. Try again shortly." notice for `?signin=unavailable`, and a line: "Clinicians: open this application from Epic."
- No new `VITE_` variables.

**Audit.**

- A login-method attribute (`password` or `sso`) goes on `LOGIN_SUCCESS` and `LOGIN_FAILURE`, and on the operational `session-start` event. The Audit page shows it.
- An Epic `LOGIN_SUCCESS` also carries the MFA factor (D17): from `amr`, or `idp-attested`.
- An Epic `LOGIN_FAILURE` carries exactly one reason from this closed list. The reasons are audit-only: the operational log says only "Epic sign-in refused" (see section 11).

  | Reason                                                    | When                                                                           |
  | --------------------------------------------------------- | ------------------------------------------------------------------------------ |
  | `INVALID_LAUNCH`                                          | `launch` missing or outside D18                                                |
  | `ISS_MISMATCH`                                            | `iss` missing or not exactly `APP_EPIC_FHIR_BASE`                              |
  | `INVALID_STATE`                                           | no pending request, or `state` mismatch                                        |
  | `INVALID_CODE`                                            | `code` missing or outside D18                                                  |
  | `IDP_ERROR`                                               | Epic returned an OAuth `error` to the callback                                 |
  | `TOKEN_EXCHANGE_FAILED`                                   | the token endpoint refused the exchange (`invalid_grant`, `invalid_client`, …) |
  | `EPIC_UNAVAILABLE`                                        | timeout or 5xx from discovery, JWKS or token (D23)                             |
  | `INVALID_SIGNATURE`                                       | bad signature, a disallowed algorithm, or a `kid` still unknown after D26      |
  | `INVALID_CLAIMS`                                          | `iss`, `aud`, `azp`, `exp`, `iat`, `nonce` or MFA evidence fails               |
  | `INVALID_FHIR_USER`                                       | `fhirUser` absent or not `{fhirBase}/Practitioner/{id}`                        |
  | `UNKNOWN_ACCOUNT` / `ACCOUNT_DISABLED` / `ACCOUNT_LOCKED` | flow step 6                                                                    |

- Retention follows the existing audit retention policy (at least 90 days). A User is correlated with Epic by `userName` = Practitioner ID, under the one issuer D4 fixes.
- No Practitioner ID or `fhirUser` appears in the log or audit beyond the User's stable id. An unknown ID is not recorded.
- None of the D22 values is ever logged or audited.

**Log.** Epic logging uses the existing ECS structured logging (ADR-0003): the fluent SLF4J API, `event.action=user-authentication`, the method as a key-value field, and `trace.id`.

| Event                                                                         | Level   |
| ----------------------------------------------------------------------------- | ------- |
| Epic Login succeeded, `session-start`                                         | `INFO`  |
| Refusal: bad input (field and rule only, flow step 1), state, claims, account | `WARN`  |
| A JWKS or discovery retry (D26)                                               | `WARN`  |
| Epic unreachable, a JWKS fetch failed after its retries, a token-endpoint 5xx | `ERROR` |

- **Outbound calls.** An interceptor on `epicRestClient` logs "Epic outbound call started" and "Epic outbound call completed" at `INFO`, with `call` (`discovery`, `jwks` or `token`), the HTTP method, the base URL and path with no query, the status and `event.duration_ms`. A failure logs at `ERROR` with the error fields. Bodies and headers are never logged. `traceparent` goes out with the call.
- **Error categories** for `ERROR` events:

  | Failure                                         | `error_category` | Follow-up                                     |
  | ----------------------------------------------- | ---------------- | --------------------------------------------- |
  | Connect or read timeout                         | `network`        | no                                            |
  | Epic 5xx                                        | `server`         | no                                            |
  | `invalid_client`, or Epic rejects our assertion | `cert/auth`      | **yes**: likely a key or registration problem |
  | Malformed Epic response                         | `data`           | yes                                           |

- **Startup.** One `INFO` line gives `epic.enabled` and, when enabled, the active and next `kid`. It never includes key material, URLs or the issuer. Each redeploy that promotes a key therefore leaves a record.
- **Masking.** The D22 names (`code`, `state`, `nonce`, `launch`, `code_verifier`, `id_token`, `access_token`, `client_assertion`, and the key variables) join the ADR-0003 redaction at the logging boundary, as a second line behind never logging them.

**Metrics** (Micrometer, the existing Prometheus registry):

- `epic.login` counter, tagged `outcome` (`success`, `refused` or `unavailable`) and `reason` (the audit reason list);
- `epic.outbound` timer and error counter, tagged `call` (`discovery`, `jwks` or `token`);
- an alert rule fires when `epic.outbound{call="jwks"}` errors persist for 5 minutes.

## 6. Code shape

`B/` = `backend/src/main/java/com/example/backend/`.

- **Dependency:** add `spring-boot-starter-oauth2-client` to `backend/pom.xml`.
- **New `B/auth/epic/`:**
  - `EpicLoginProperties`, including the two timeouts
  - `EpicSecurityConfig`: `oauth2Login` on the existing application chain, with:
    - a custom `OAuth2AuthorizationRequestResolver` adding `launch` and `aud`;
    - an authorization-request repository that removes the pending request atomically on first read (D27) and compares `state` in constant time;
    - a `RestClientAuthorizationCodeTokenResponseClient` on `epicRestClient`, with `NimbusJwtClientAuthenticationParametersConverter`;
    - an `id_token` decoder factory that pins RS256, applies the 30-second skew from the injected `Clock`, and adds the `azp` and D17 checks;
    - success and failure handlers. The failure handler invalidates the session (D24), chooses `refused` or `unavailable`, and logs and audits once.
  - `EpicRestClientConfig`: the `epicRestClient` bean (D25), built from `RestClient.Builder` with the timeouts and the outbound logging interceptor
  - `EpicProviderMetadata`: discovery through `epicRestClient`, run on first use and cached for 24 hours, caching only successes (D26)
  - `EpicJwkSource`: the JWKS fetched through `epicRestClient`, with the D26 refetch on an unknown `kid`
  - `EpicLaunchController`
  - `EpicJwksController`
  - `FhirUserReference`
  - `EpicSigningKeys`: the active key plus the optional next key.
  - `ClientAssertionSigner`: the signing interface (D16). The only implementation for now uses the environment-variable key; a KMS implementation replaces it later without touching the rest.
  - `EpicLoginMetrics`
  - `EpicLoginFailureReason`: the closed reason list in section 5
- **Extracted:** the session-establishment block of `AuthController.login` (`B/auth/controller/AuthController.java:101-165`) becomes `B/auth/controller/SessionEstablishment`, shared by both Login paths.
- **Extended:**
  - `LoginService.logInFromEpic`, reusing `LoginIdentityService.authoritiesOf` (`:199`) and `LoginAttemptService.recordSuccess`;
  - a new `LoginAttemptService.recordRefusal`, which audits without counting toward a failure run;
  - the login-method attribute (`password` / `sso`) and the MFA factor through `AuditTrail`;
  - the ADR-0003 redaction, with the D22 names.
- **`SecurityConfig`** (`B/auth/config/SecurityConfig.java:256-325`): `permitAll` for the four routes above. Every existing rule is unchanged.

## 7. Implementation workflow and verification

Each step is one reviewable change. A step is done when its tests pass and the backend baseline gate (`./mvnw clean verify` in `backend/`) is green, plus the frontend gates where touched.

1. **Configuration and switch.** Add the dependency, `EpicLoginProperties` and startup validation, document `.env.example`, and make the switch return 404.
   - _Verify:_ unit tests on validation, including refusal of non-`https` URLs outside the dev profile (D21) and the timeout defaults; a malformed PEM fails startup and its value is absent from the captured output (D22); an integration test that the app starts with no Epic variables and that `/api/auth/epic/**` returns 404 while off; the startup line carries `epic.enabled` and the `kid`s and no key material; `curl -i localhost:8080/api/auth/epic/launch` returns 404.
2. **Extract `SessionEstablishment`** (pure refactor).
   - _Verify:_ the existing tests pass unmodified (`LoginSessionHardeningIntegrationTests`, `CsrfSynchronizerTokenIntegrationTests`, `SessionStartLogIntegrationTests`, `AuthControllerTests`).
3. **Domain:** `FhirUserReference` and `logInFromEpic`, plus the login-method audit attribute and the reason list.
   - _Verify (unit and Testcontainers):_
     - `fhirUser` with the wrong base, the wrong type, a blank id or trailing segments;
     - refusals for an unknown, case-variant (`eabc` vs `eABC`), deactivated, locked or Bootstrap Admin User;
     - a successful Epic Login yields the same authorities as password Login for the same User, moves the dormancy basis, audits `LOGIN_SUCCESS` with method `sso`, and revokes other sessions;
     - password Login audits method `password`;
     - a refusal never increments the failure run.
4. **Signing keys and JWKS.**
   - _Verify:_ the JWKS has no `d` parameter; it lists the active key alone, or the active and next keys when both are configured; client assertions are always signed with the active `kid`; startup fails on a next key without a `kid`, or on duplicate `kid`s; a manual `curl .../jwks.json`.
5. **Launch and authorize redirect.**
   - _Verify (integration):_
     - a missing or wrong `iss` (including a case or trailing-slash variant), or a `launch` that is empty, 8193 characters long, or contains whitespace or non-ASCII, gives `302 /?signin=refused`, is audited with `ISS_MISMATCH` or `INVALID_LAUNCH`, and is logged at `WARN` with the field and rule but not the value;
     - a refused launch invalidates a session already present (D24);
     - a valid launch gives a `302` whose `Location` carries every parameter in flow step 2, with `redirect_uri` exactly `APP_EPIC_REDIRECT_URI` and a `code_challenge` equal to `Base64URL(SHA256(verifier))` for a verifier of at least 43 characters;
     - `state` and `nonce` are unique on every call.
6. **Callback, token exchange and `id_token` validation.**
   - _Verify (integration)_ against an **in-JVM fake Epic**, a JDK `HttpServer` per test that serves discovery, a JWKS, `/authorize` and `/token`. Its `/token` **verifies our client assertion against our JWKS** (claims, `exp`, `jti`, `kid`) and the `redirect_uri` and `code_verifier`, and mints whichever `id_token` the test asks for. Time comes from a fixed `Clock`.
   - Happy path: the right Permissions, a rotated session id, the principal index, the role-mapping hash, refusal of the pre-login CSRF token, and a landing at `/`.
   - Each of these gives `302 /?signin=refused`, an audited `LOGIN_FAILURE` with its section 5 reason, and invalidation of any session in that browser:
     - forged `state`; a reused `state` (the same callback twice); a callback in a fresh session with no pending request, which makes no token call;
     - two concurrent identical callbacks, of which at most one succeeds;
     - the fake `/token` rejecting a wrong `redirect_uri` or `code_verifier`, or a replayed code;
     - a bad signature, an `alg` other than RS256, a `kid` still unknown after the refetches;
     - wrong `iss`, `aud` or `nonce`; several audiences with a wrong or missing `azp`; an expired token; an `iat` 31 seconds in the future;
     - a non-Practitioner `fhirUser`, or an unknown User.
   - Unavailable: a fake that times out or returns 5xx on discovery, JWKS or token gives `302 /?signin=unavailable` with `EPIC_UNAVAILABLE`, an `ERROR` log with the section 5 category, and exactly one token request.
   - JWKS: an unknown `kid` triggers 3 refetches before the refusal, and a rotated key published on the second fetch is accepted.
   - Sessions: a launch while another User's session is present replaces it (D9); one session per User holds across a password Login followed by an Epic Login.
   - Log and metrics checks: after the happy path and every refusal and unavailable path, the captured log and audit output contain none of the D22 values (this extends the existing ADR-0003 redaction tests); outbound calls log start and completion with no query string; the `epic.login` and `epic.outbound` metrics carry the expected tags.
7. **Frontend.** The refused and unavailable notices and the clinician line.
   - _Verify:_ unit tests in `login.test.tsx`; the frontend baseline gate; the existing Playwright specs pass unchanged, because password Login is untouched.
8. **Local end-to-end without Epic.** Add the SMART Health IT launcher (`smartonfhir/smart-launcher-2`) as an opt-in, profiled service in `backend/compose.yaml`; `make infra-up` is unaffected. Point the `APP_EPIC_*` variables at it.
   - _Verify, manually:_ provision a SCIM User whose `userName` is the launcher's Practitioner ID; run a provider EHR launch from the launcher UI and land on `/showcase` with the expected Permissions; launch as an unprovisioned practitioner and get the refused notice; stop the launcher and get the unavailable notice; password Login still works.
   - Then add a Playwright spec over the same path, which is the E2E conditional gate.
   - Confirm here that the launcher's `fhirUser` and issuer formats are absorbed by configuration alone, never by loosening `FhirUserReference`.
9. **Documentation.**
   - Write ADR 0013 (D1–D28, the accepted risk D13, the section 9 policy position, and the section 11 App-Standards deviations), plus an addendum on ADR-0012 updating its ac-2, ac-12 and as-8 entries.
   - Copy the sequence diagram and network paths from section 10 into ADR 0013.
   - In `CONTEXT.md`: Login becomes "credentials _or an EHR launch_"; add **EHR launch** and **Epic issuer** (`_Avoid_: SSO, sign-on`); note that the failure run counts password refusals only.
   - Add an "Epic Login" section to `docs/domain-rules.md`.
   - Add the variables to `infra/`, the key-promotion runbook and the JWKS alert to `infra/README.md`, and the two Epic routes to its "Edge throttling" list (D19).
   - Update `frontend/README.md` and the "Backend contract" section of `frontend/AGENTS.md`.
   - Retire this spec.
   - _Verify:_ `make verify` from the repo root, reading `GATE_EXIT=0` from the log; then a graphify refresh through `graphify-runner`.
10. **Epic sandbox (later, configuration only).** Register a non-production app on fhir.epic.com:
    - Clinicians audience, R4, `openid fhirUser`;
    - JWKS URL = our public `/api/auth/epic/jwks.json`;
    - the launch URL and redirect URI;
    - `APP_EPIC_FHIR_BASE=https://fhir.epic.com/interconnect-fhir-oauth/api/FHIR/R4` and `APP_EPIC_OAUTH_ISSUER=https://fhir.epic.com/interconnect-fhir-oauth/oauth2`.

    Provision the sandbox practitioner (`FHIRTWO`) by its FHIR ID, and launch from the SMART LaunchPad or Hyperdrive. Allow up to an hour for Epic app changes to sync.

## 8. Open items to confirm outside the code

- Your Epic team confirms that the launch opens the system browser and sends `iss` with `launch` (D1, D10).
- Your IdP confirms it can supply each clinician's Practitioner FHIR ID per Epic environment (D2).
- Your Epic organisation's written confirmation that its sign-in enforces MFA (D17), referenced from ADR 0013.
- Your Epic team confirms whether the `id_token` carries `acr` or `amr` for an EHR launch, and which values mean MFA (D17).
- The data owner classifies the application's data (D28).

## 9. Policy position (IM8 spec-compliance, 2026-10-06)

The audit gave 17 PASS, 9 AUTO-FIX, 3 FAIL, 1 DEFERRED and 94 N/A (all 88 ARC controls are N/A: the application has no agentic or LLM component). Every FAIL and AUTO-FIX was resolved with the user, as follows.

| Control                      | Was      | Resolution                                                                                                                                                 |
| ---------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| as-8 Secrets management      | FAIL     | **Accepted deviation, open** (D16). The key is an environment variable for now; the target is AWS KMS. ADR-0012's as-8 entry is extended to name this key. |
| ck-4 Key storage             | FAIL     | Same as as-8 (D16). A KMS-held key closes both.                                                                                                            |
| ac-2 MFA                     | FAIL     | **Met on the Epic path** by the Epic organisation's MFA (D17). Password Login stays the ADR-0012 deviation.                                                |
| as-1 Input validation        | AUTO-FIX | D18.                                                                                                                                                       |
| as-4 Rate-limiting           | AUTO-FIX | D19.                                                                                                                                                       |
| as-15 Change on compromise   | AUTO-FIX | D20.                                                                                                                                                       |
| dp-3 TLS in transit          | AUTO-FIX | D21. The wider dp-3 entry in ADR-0012 stays open.                                                                                                          |
| lm-16 Key signals            | AUTO-FIX | The metrics in section 5.                                                                                                                                  |
| lm-19 Log sanitisation       | AUTO-FIX | D22, plus the redaction tests in step 6.                                                                                                                   |
| ac-12 SSO for internal users | AUTO-FIX | ADR 0013 updates ADR-0012: clinicians sign in through their organisation via Epic; password Login for non-clinicians stays a deviation.                    |
| ac-4 Access review           | AUTO-FIX | The existing ADR-0012 deviation is unchanged by this spec.                                                                                                 |
| pm-6 System documentation    | AUTO-FIX | Section 10, copied into ADR 0013.                                                                                                                          |
| as-10 HSTS                   | DEFERRED | Unchanged. Tracked under ADR-0012's dp-3 entry.                                                                                                            |

## 10. Sequence and network paths

```
Browser            Epic (Hyperspace + OAuth)          Backend
   | <-- opens system browser on launch URL ---|              |
   |-- GET /api/auth/epic/launch?iss&launch ------------------>| iss allowlist, D18
   |<-------------------------- 302 authorize?…launch,aud,PKCE,state,nonce
   |-- GET authorize ------------>|                            |
   |<-- 302 /api/auth/epic/callback?code&state                 |
   |-- GET /api/auth/epic/callback ---------------------------->| consume pending request, state check
   |                              |<-- POST token (client_assertion, verifier)
   |                              |-- id_token --------------->| verify via jwks_uri
   |                              |-- GET /api/auth/epic/jwks.json (Epic verifies our assertion)
   |<-------------------------------------- 302 / (session established)
```

| From    | To      | Path                                                                                                            | Purpose                                          |
| ------- | ------- | --------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| Backend | Epic    | `{issuer}/.well-known/openid-configuration`, `jwks_uri`, `token_endpoint` (HTTPS out, through `epicRestClient`) | Discovery, `id_token` key fetch, code redemption |
| Epic    | Backend | `/api/auth/epic/jwks.json` (HTTPS in, public)                                                                   | Verifies our client assertions                   |
| Browser | Backend | `/api/auth/epic/launch`, `/callback`                                                                            | Launch and callback                              |
| Browser | Epic    | `authorization_endpoint`                                                                                        | Clinician authorization                          |

## 11. App-Standards position (spec-standards-check, 2026-10-06)

Checked against `Appfw-User-Standards/User_SSO` (standard and questions) and `Appfw-Logging-Standards` (standard and questions). The findings were resolved with the user. Most became the decisions above; these remain **conscious deviations**, recorded in ADR 0013.

| Standard requirement                                                                                | Deviation                                                                   | Rationale                                                                                                                                                                                      |
| --------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SSO §3.2: callback failures `400`, token failures `401`, IdP unavailable `502`                      | `302 /?signin=refused` or `/?signin=unavailable` (D23)                      | The callback is a navigation in the clinician's system browser, so a bare status code leaves a dead end. One response per outcome prevents enumeration, and the audit reason keeps the detail. |
| SSO §4: rate limiting at the application layer, 10–60 per registration per minute, reset on success | Edge throttling only, limits in edge configuration (D19)                    | The edge already throttles the public routes, and keeps floods off the JVM.                                                                                                                    |
| SSO §4: a registry of redeemed authorization codes                                                  | None; single-use pending request (D27) plus Epic's single-use codes         | A replayed callback finds no pending request and is refused before any token call.                                                                                                             |
| SSO §4: consumed `state` tracked in a registry against reuse across sessions                        | Session-scoped, consumed on first callback (D27)                            | A `state` is only valid in the session that minted it.                                                                                                                                         |
| SSO §4: startup fails if metadata fetch fails                                                       | Discovery runs on first use (D26)                                           | Password Login must not depend on Epic being reachable at deploy time. A failure shows as `unavailable`.                                                                                       |
| SSO §3.2: failures complete in consistent time                                                      | Refusal timing is not equalised; values are compared in constant time (D27) | Refusals after the Epic round-trip are dominated by network time, and the browser sees one response whatever failed.                                                                           |
| SSO §4: a local record is created for each SSO identity at login                                    | No JIT provisioning; SCIM creates Users ahead of login (D2)                 | The directory is the joiner and leaver authority, and nobody Epic can authenticate gets an account without it.                                                                                 |
| SSO §4: SSO users have no local credentials                                                         | One User may hold a password and Epic Login (D5)                            | Forbidding it needs a provisioning rule SCIM cannot express. Each path keeps its own lockout and dormancy.                                                                                     |
| SSO §2: entry point `/oauth2/authorization/{registrationId}`                                        | `/api/auth/epic/launch` and `/callback`                                     | The SMART EHR launch is Epic-initiated and carries `iss` and `launch`. Routes stay under the reserved `/api`. OAuth parameter names and formats are unchanged.                                 |
| SSO §4: verify MFA evidence in `acr` / `amr`                                                        | Organisational attestation until Epic confirms the claim (D17)              | Epic may not send `acr` / `amr` on an EHR launch. Once it does, the check is enforced.                                                                                                         |
| SSO §3.3: success audit includes registration ID and issuer                                         | Implied by D4                                                               | One issuer per deployment.                                                                                                                                                                     |
| SSO §5: tokens of an invalidated session are revoked                                                | Epic's access token is not revoked (D13)                                    | It is never stored or presented.                                                                                                                                                               |
| Logging §2.2: no reason that reveals whether the account exists                                     | `UNKNOWN_ACCOUNT` / `ACCOUNT_DISABLED` / `ACCOUNT_LOCKED` in the audit only | Investigation needs them, the SSO standard asks for specific failure types, and password Login uses the same reasons. The operational log stays generic.                                       |

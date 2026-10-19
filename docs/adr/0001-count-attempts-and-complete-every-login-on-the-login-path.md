# 1. The login path counts attempts, and one module completes every Login

Date: 2026-09-24

## Status

Accepted. Consolidated from counting attempts on the login path (2026-09-24),
one module completing a Login for both login methods (2026-10-10, architecture
reviews 2026-10-08 and 2026-10-10, B1), and two 2026-10-11 refinements (step 1
decides on the stable id; step 5 ends the session through `SessionEnding`). ADR
0012 stays the authority on Epic Login itself; this ADR decides how a password
Login and an Epic Login are counted and how they end.

## Context

### Where an attempt is counted

A User's **failure run** lengthens on every rejected Login and ends on an accepted
one, and reaching the configured limit imposes a **Lockout** (ADR 0007, see
`/docs/domain/authentication.md`). The one exception is the Bootstrap Admin, whose failures
are counted and audited but never lock it. Something has to notice how each
attempt ended and write it down.

Spring Security already publishes that information as application events —
`AuthenticationSuccessEvent` and `AbstractAuthenticationFailureEvent` — and the
framework's own documented answer is a listener that consumes them. The
alternative is for the code performing the Login to record the attempt itself.

The distinction matters because enforcement is _not_ where recording is:
`LoginIdentityService.loadUserByUsername` reports a locked User to Spring
Security, which refuses it before comparing passwords. So the rule already spans
the domain (`ScimLoginState` decides what counts as locked), the application layer
(the User is reported as locked), and whatever records the attempt.

### How a Login ends

Password Login and Epic Login (ADR 0012) grew their endings separately, and they
had diverged:

- **What a refusal said.** Epic Login kept the account reasons in the audit trail
  only (Logging §2.2); password Login's refusal `WARN` carried the exception type,
  telling its reader the account existed and its state. Password Login also left
  the browser's session alive, and was counted by no meter.
- **When a success counted.** Password Login recorded its success before the
  session was established, so a Login whose session step failed was still logged
  "Login accepted", naming no session.

The steps that end a Login were spread across five modules, so each new path had
to reassemble them in the right order.

## Decision

### Attempts are counted on the login path

The code that checks the password records the attempt explicitly; no listener
subscribes to authentication events. Two paths do so: `LoginService`, on both
outcomes of a password Login, and `PasswordChangeService`, whose wrong current
password lengthens the same failure run. Both record through
`LoginAttemptService`, and both carry out the count and any Lockout it imposes
through the one login-state module, `LoginStateService`, which they call
explicitly (issue #42).

Because that recording is inside the module that authenticates, it cannot be
skipped by a caller: an entry point either goes through `LoginService` and gets
the failure run with it, or it does not authenticate submitted credentials at all.
Both login methods' caller is `LoginCompletion` (below).

### One module records every ending

`LoginOutcomeService` records how a Login ended, by either method. It writes each
ending's audit record, log line and counts. `LoginOutcome` is one of:

- signed in (`SignedIn`, with the login method, and an MFA factor for `sso` only);
- refused (`PasswordRefused` or `EpicRefused`);
- unavailable (Epic only).

A refusal reached by the login decision is recorded by the decision, so no caller
can refuse a Login without the record.

**A refused password Login** is recorded as:

- a `LOGIN_FAILURE` under method `password`, with its reason from the closed set
  `PasswordRefusalReason` (`BAD_CREDENTIALS`, `UNKNOWN_ACCOUNT`, `ACCOUNT_LOCKED`,
  `ACCOUNT_DISABLED` or `OTHER`), the password analogue of
  `EpicLoginFailureReason`. It names the
  User when the name matched one, and counts toward the User's failure run. It is
  a guess at our credential, unlike an Epic refusal (ADR 0012, D12);
- one `WARN`, "Login refused", with `app.login.method=password` and `session.hash`
  for the session the Login ran in. It has no reason, no user field, and never the
  submitted username (ADR 0003);
- `login` (`method=password`, `outcome=refused`, `reason`).

A locked or deactivated User's refusal is therefore logged exactly as a wrong
password's. It also costs the same single Argon2id verification (ADR 0007, its
2026-10-09 amendment).

### One `login` counter

`login` is tagged `method` (`password` or `sso`), `outcome` (`success`, `refused`,
or `unavailable` for `sso` only) and `reason` (`none` for a success). Every series
of both methods is registered at zero at startup with the same tag keys. Its
adapter, `LoginMetrics` (`com.example.backend.auth.config`), is registered
unconditionally by `LoginMetricsConfig` behind the `LoginCounts` port, because
password Login exists whether Epic Login is on or not. `epic.login.failed_calls`
stays Epic's (ADR 0012, "Metrics").

### One module, one order

`LoginCompletion`, a web adapter in `com.example.backend.auth.controller`, runs
every Login's ending, for both methods, in this order:

1. **Decide.** `LoginService.logIn` or `logInFromEpic`, handed the id of the session
   the browser holds, returns a `LoginService.LoginDecision`: an accepted Login
   carrying its `SignedIn`, or a refusal. A password refusal is returned, not
   thrown, and is still recorded and counted toward the failure run inside the
   decision. The following stay in the decision's transaction:
   - an accepted Login's fail-closed `LOGIN_SUCCESS` (ADR 0004);
   - the cleared failure run;
   - the after-commit revocation of the User's other sessions (ADR 0002).

   The User is read once, when it authenticates: `LoginIdentityService` reports
   it as a `LoginPrincipal` carrying its stable id, and everything after works
   from that id, never the name. A User gone by the time the success is recorded
   fails the Login closed inside the transaction.
2. **Establish.** `SessionEstablishment` rotates the session id, saves the security
   context, sets the principal index and the role-mapping hash (through
   `SignedInSession.signIn`, which owns the session's attributes), drops the pre-login
   CSRF token, and logs `session-start`. It is package-private and called only by
   `LoginCompletion`, so no adapter can sign a session in without the rest of the
   Login around it.
3. **The method's own work** on the signed-in session: Epic Login keeps Epic's
   tokens there (ADR 0012, D29), under the rotated id alone.
4. **Record** the success once, through `LoginOutcomeService`: the
   `user-authentication` record and the `login` success count, naming the signed-in
   session by `session.hash`. A Login whose session step or method work fails
   records no success.
5. **On a refusal, end the browser's session**, whoever it belonged to, through
   `SessionEnding` with the reason `LOGIN_SIGNED_NOBODY_IN` — the one module that
   ends the caller's own session, shared with Logout and the password change. The
   answer (a bare `401` or Epic's redirect) expires the session cookie, with no
   `Clear-Site-Data`. This is ADR 0012's D24, extended to password Login, so a
   shared browser is never left signed in as the previous User after a Login that
   signed nobody in. A refused Login that arrived without a session creates none.

An Epic Login that fails before any decision (at the launch, the authorize hop or
the callback, through `EpicLoginFailureHandler`) is ended by
`LoginCompletion.endUndecided`. Its outcome is recorded once, naming the session it
ran in, and then that session is ended.

### The adapters only shape the answer

`AuthController.login` answers either the signed-in account or a bare `401`. Every
refusal leaves through one `LoginRefusedException` and one handler, so a locked
account's answer matches a wrong password's byte for byte. The Epic handlers
redirect through one package-private `EpicLanding` to `/`,
`/login?signin=refused` or `/login?signin=unavailable`, relative to the context
path.

## Consequences

- The counting is visible at the one call site that owns logging in, and is
  assertable without publishing or capturing framework events —
  `LoginLockoutTests` drives the whole Lockout story through `LoginService` with a
  movable clock and no Spring context.
- Authentication performed by a path that bypasses `LoginService` — a token
  exchange, HTTP Basic re-enabled on the filter chain — gets **no** Lockout. A
  listener would have covered those for free. This is the cost we accept, and the
  reason the recording sits behind one module rather than at an endpoint: adding
  such a path means routing it through `LoginService`, as Epic Login's callback is
  (`logInFromEpic`).
- Events remain unpublished by this path, so nothing else can observe Logins by
  subscribing. Auditing is recorded here instead: `LoginAttemptService` appends
  `LOGIN_SUCCESS`, `LoginOutcomeService` appends `LOGIN_FAILURE`, and
  `LoginStateService` appends `LOCKOUT_SET` when a Lockout is imposed.
- A success is counted only once its session exists, and its record names the
  session the User goes on to use. That holds for both methods.
- The password refusal's `WARN` does not reveal whether the account exists or its
  state. An investigation reads the reason from the audit trail or the `login`
  counter's `reason` tag, which counts events and names no account.
- A refused password Login ends the session the browser held, and the CSRF token
  bound to it. The SPA discards the token on the `401`, and a retry fetches the next
  session's (ADR 0009, its 2026-10-09 addendum; `/frontend/AGENTS.md`, "Backend
  contract").
- A third login method needs a `LoginOutcome` variant and a call into
  `LoginCompletion`. The order, the records and the session handling come with it.

## Alternatives considered

**An `ApplicationListener` on Spring Security's authentication events.** Covers
every authentication mechanism automatically, including ones added later, and is
the framework-idiomatic option. Rejected because the Lockout rule would then have
no visible call site at all: reading the login path would not reveal that attempts
are counted, and testing it would mean asserting on a published event or standing
up a context to publish one. Every login method goes through `LoginService`, so
the coverage it buys is hypothetical while the indirection is immediate.

**Counting inside `LoginIdentityService.loadUserByUsername`.** Rejected: that method
is called before the password is compared, so it cannot know whether the attempt
ended in a refusal, and it is deliberately the read-only half of the login
identity: writes belong to the services that own a change.

**Keep the ending's recording in each method's adapter.** Rejected: it is how the
two pipelines diverged. Each fix had to be made twice, and the second copy was the
one that drifted.

**An application service instead of a web adapter for `LoginCompletion`.**
Rejected: establishing and ending a session is servlet work. `LoginCompletion`
depends on no Epic bean, so it exists whether Epic Login is on or not.


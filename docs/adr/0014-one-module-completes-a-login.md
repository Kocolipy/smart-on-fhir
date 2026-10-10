# 14. One module completes a Login, for both login methods

Date: 2026-10-10

## Status

Accepted. First recorded as two addenda on ADR 0013, dated 2026-10-09 (one outcome
module and one `login` counter, from architecture review 2026-10-08, "Password
Login path") and 2026-10-10 (one module completes a Login, from architecture review
2026-10-10, B1). They were moved here on 2026-10-10 because they decide how password
Login and Epic Login end, not how Epic Login works. ADR 0013 stays the authority on
Epic Login itself. This ADR refines the caller named in ADR 0001, and ADR 0003's
login records follow from it.

## Context

Password Login and Epic Login (ADR 0013) grew their endings separately, and they
had diverged:

- **What a refusal said.** Epic Login's refusal met Logging §2.2: the account
  reasons (`UNKNOWN_ACCOUNT`, `ACCOUNT_DISABLED`, `ACCOUNT_LOCKED`) were kept in the
  audit trail only, and the operational log said just "Epic sign-in refused".
  Password Login's refusal `WARN` carried the exception type (`LockedException`,
  `DisabledException`, …). That told its reader the account existed and what state
  it was in. Password Login also left any session the browser held alive, and was
  counted by no meter.
- **When a success counted.** Epic Login decided, established the session, and only
  then recorded the success, naming the signed-in session. Password Login recorded
  its success inside the login decision (`LoginService.logIn`), and `AuthController`
  established the session afterwards. A Login whose session step then failed was
  still logged "Login accepted" and counted a success, and that record could name
  no session.

The steps that end a Login were spread across five modules, so each new path had
to put them back together in the right order.

## Decision

### One module records every ending

`LoginOutcomeService` records how a Login ended, by either method. It writes each
ending's audit record, log line and counts. `LoginOutcome` is one of:

- signed in (`SignedIn`, with the login method, and an MFA factor for `sso` only);
- refused (`PasswordRefused` or `EpicRefused`);
- unavailable (Epic only).

A refusal reached by the login decision is recorded by the decision, so no caller
can refuse a Login without the record.

**A refused password Login** is recorded as:

- a `LOGIN_FAILURE` under method `password`, with its reason (`BAD_CREDENTIALS`,
  `UNKNOWN_ACCOUNT`, `ACCOUNT_LOCKED`, `ACCOUNT_DISABLED` or `OTHER`). It names the
  User when the name matched one, and counts toward the User's failure run (ADR
  0001). It is a guess at our credential, unlike an Epic refusal (ADR 0013, D12);
- one `WARN`, "Login refused", with `app.login.method=password` and `session.hash`
  for the session the Login ran in. It has no reason, no user field, and never the
  submitted username (ADR 0003);
- `login` (`method=password`, `outcome=refused`, `reason`).

A locked or deactivated User's refusal is therefore logged exactly as a wrong
password's. It also costs the same single Argon2id verification (ADR 0007, its
2026-10-09 amendment).

### One `login` counter

`login` replaces `epic.login`. It is tagged `method` (`password` or `sso`),
`outcome` (`success`, `refused`, or `unavailable` for `sso` only) and `reason`
(`none` for a success). Every series of both methods is registered at zero at
startup with the same tag keys. Its adapter, `LoginMetrics`
(`com.example.backend.auth.config`), is registered unconditionally by
`LoginMetricsConfig` behind the `LoginCounts` port, because password Login exists
whether Epic Login is on or not. A dashboard or rule reading `epic_login_total`
must move to `login_total{method="sso"}`. `epic.login.failed_calls` stays Epic's
(ADR 0013, "Metrics").

### One module, one order

`LoginCompletion`, a web adapter in `com.example.backend.auth.controller`, runs
every Login's ending, for both methods, in this order:

1. **Decide.** `LoginService.logIn` or `logInFromEpic`, handed the id of the session
   the browser holds, returns a `LoginService.LoginDecision`: an accepted Login
   carrying its `SignedIn`, or a refusal. A password refusal is returned, not
   thrown, and is still recorded and counted toward the failure run inside the
   decision (ADR 0001). The following stay in the decision's transaction:
   - an accepted Login's fail-closed `LOGIN_SUCCESS` (ADR 0004);
   - the cleared failure run;
   - the after-commit revocation of the User's other sessions (ADR 0002).
2. **Establish.** `SessionEstablishment` rotates the session id, saves the security
   context, sets the principal index and the role-mapping hash, drops the pre-login
   CSRF token, and logs `session-start`. It is package-private and called only by
   `LoginCompletion`, so no adapter can sign a session in without the rest of the
   Login around it.
3. **The method's own work** on the signed-in session: Epic Login keeps Epic's
   tokens there (ADR 0013, D29), under the rotated id alone.
4. **Record** the success once, through `LoginOutcomeService`: the
   `user-authentication` record and the `login` success count, naming the signed-in
   session by `session.hash`. A Login whose session step or method work fails
   records no success.
5. **On a refusal, end the browser's session**, whoever it belonged to, and clear
   the security context. This is ADR 0013's D24, extended to password Login, so a
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
redirect through one package-private `EpicLanding` to `/`, `/?signin=refused` or
`/?signin=unavailable`, relative to the context path.

## Consequences

- A success is counted only once its session exists, and its record names the
  session the User goes on to use. That holds for both methods.
- The password refusal's `WARN` no longer reveals whether the account exists or its
  state. An investigation reads the reason from the audit trail or the `login`
  counter's `reason` tag, which counts events and names no account.
- A refused password Login ends the session the browser held, and the CSRF token
  bound to it. The SPA discards the token on the `401`, and a retry fetches the next
  session's (ADR 0009, its 2026-10-09 addendum; `/frontend/AGENTS.md`, "Backend
  contract").
- A third login method needs a `LoginOutcome` variant and a call into
  `LoginCompletion`. The order, the records and the session handling come with it.
- `LoginAttemptService.recordRefusal`, `EpicLoginLanding` and `EpicSignInRedirect`
  were removed: `LoginOutcomeService` calls `AuditTrail.recordLoginRefusal`
  directly, and `LoginCompletion` does the session ending they did.

## Alternatives considered

**Keep the recording in each method's adapter.** Rejected: it is how the two
pipelines diverged. Each fix had to be made twice, and the second copy was the one
that drifted.

**An application service instead of a web adapter.** Rejected: establishing and
ending a session is servlet work. `LoginCompletion` depends on no Epic bean, so it
exists whether Epic Login is on or not.

# 1. Count login attempts on the login path

Date: 2026-09-24

## Status

Accepted.

Written before the login identity moved onto SCIM Users: `AccountService` and
`AccountAdministrationService` below are today's `LoginIdentityService` and
`IdentityAdministrationService`, and what counts as locked is decided by
`ScimLoginState`. The decision — attempts are counted explicitly by the code that
checks the password, never by a listener — is unchanged. Two paths now do so:
`LoginService`, and `PasswordChangeService`, whose wrong current password
lengthens the same failure run. Both record through `LoginAttemptService`, and
both carry out the count and any lockout it imposes through one `FailureCounter`.

## Context

An account's **failure run** lengthens on every rejected login and ends on an
accepted one, and reaching the configured limit imposes a **lockout** — which has
no duration and ends only when an administrator unlocks the account, and which
revokes the account's live sessions as it is imposed (see `/docs/domain-rules.md`). The one
exception is the Bootstrap Admin, whose failures are counted and audited but never
lock it. Something has to notice how each attempt ended and write it down.

Spring Security already publishes that information as application events —
`AuthenticationSuccessEvent` and `AbstractAuthenticationFailureEvent` — and the
framework's own documented answer is a listener that consumes them. The
alternative is for the code performing the login to record the attempt itself.

The distinction matters because enforcement is *not* where recording is:
`AccountService.loadUserByUsername` reports a locked account to Spring Security,
which refuses it before comparing passwords. So the rule already spans the domain
(`ScimLoginState` decides what counts as locked), the application layer (the account is
reported as locked), and whatever records the attempt.

## Decision

The login path records the attempt explicitly. `LoginService.logIn` authenticates
submitted credentials and calls `LoginAttemptService` on both outcomes; no
listener subscribes to authentication events.

Because that recording is inside the module that authenticates, it cannot be
skipped by a caller: an entry point either goes through `LoginService` and gets
the failure run with it, or it does not authenticate submitted credentials at all.
`AuthController` is one such caller and owns only the session and CSRF work,
which it delegates to `SessionEstablishment`.

## Consequences

- The counting is visible at the one call site that owns logging in, and is
  assertable without publishing or capturing framework events —
  `LoginLockoutTests` drives the whole lockout story through `LoginService` with a
  movable clock and no Spring context.
- Authentication performed by some future path that bypasses `LoginService` — a
  token exchange, an SSO callback, HTTP Basic re-enabled on the filter chain —
  gets **no** lockout. A listener would have covered those for free. This is the
  cost we accept, and the reason the recording sits behind one module rather than
  at an endpoint: adding such a path means routing it through `LoginService`.
- Events remain unpublished by this path, so nothing else can observe logins by
  subscribing. Auditing is recorded here instead: `LoginAttemptService` appends
  `LOGIN_SUCCESS` and `LOGIN_FAILURE`, and `FailureCounter` appends `LOCKOUT_SET`
  when a lockout is imposed.

## Alternatives considered

**An `ApplicationListener` on Spring Security's authentication events.** Covers
every authentication mechanism automatically, including ones added later, and is
the framework-idiomatic option. Rejected because the lockout rule would then have
no visible call site at all: reading the login path would not reveal that attempts
are counted, and testing it would mean asserting on a published event or standing
up a context to publish one. With a single login mechanism, the coverage it buys
is hypothetical while the indirection is immediate.

**Counting inside `AccountService.loadUserByUsername`.** Rejected: that method is
called before the password is compared, so it cannot know whether the attempt
ended in a refusal, and it is deliberately the read-only half of the login
identity: writes belong to the services that own a change.

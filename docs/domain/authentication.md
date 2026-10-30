---
type: Domain Rules
title: Authentication
description: Password Login and its refusals, the failure run, Lockout and Unlock, deactivation, the Bootstrap Admin exemption, and the recovery guard.
tags: [authentication, login, lockout]
---

# Authentication

Epic's login method has its own rules in [Epic Login](./epic-login.md); passwords
and the change-required flag are in [Passwords](./passwords.md).

## Login

**Login** — the one operation that turns submitted credentials into an
authentication or a refusal, and where attempts are recorded against the failure
run; the self-service password change records the only other kind, a wrong
current password, through the same counting. It lives in `LoginService`, so an
entry point that authenticates submitted credentials without going through it
has no lockout at all; the `/api/auth/login` endpoint adds only the session, the
CSRF token, and the bare `401`. Why the counting is recorded here rather than
driven by Spring Security's authentication events is
`docs/adr/0001-count-attempts-and-complete-every-login-on-the-login-path.md`. A
username with [edge whitespace](./directory.md) names no User and is refused as an
unknown one before any lookup, so its failure lengthens no failure run.

**A User with no password has no password Login** — whatever is submitted, a
credentialless User (one provisioned without a password, as a clinician who signs
in only through Epic is) is refused as an unknown name is, and at the same cost:
one Argon2id verification against Spring Security's own dummy hash, which no
submitted string matches. Its refusal is audited as `BAD_CREDENTIALS`,
naming the User, and lengthens no failure run — there is no password to guess,
so a run of attempts never locks out a User who signs in only through Epic.

**A refused Login ends the browser's session** — a refused password Login ends
whatever session the browser held, whoever it belonged to, before its bare `401`,
as a refused EHR launch does: a shared browser is never left signed in as the
previous User after a sign-in that signed nobody in. The CSRF token was bound to
that session, so the SPA discards it, and a retry fetches the next session's. A
refused Login that arrived without a session creates none. It is audited as a
`LOGIN_FAILURE` with login method `password` and its reason, and lengthens the
failure run; the operational log says only "Login refused", because the reason
tells whether an account exists. A locked or deactivated User is refused only
after its submitted password has been compared, exactly as a wrong password is,
so its refusal takes no less work than any other.

**A Login's refusal belongs to the User its one read found** — the authentication
reads the User by name once, and every refusal of that Login, whether the
authentication made it (wrong password, locked, deactivated, no password) or the
decision under the User's lock did, is counted and audited against that User by
stable id, or against nobody (`UNKNOWN_ACCOUNT`) when the read found no User. A
User deleted while its Login is in flight is refused, never answered as a server
fault, and recorded against nobody (`UNKNOWN_ACCOUNT`), on every route — a
password the authentication accepted or one it refused — and in every window:
before the authentication's comparison or after it, up to the moment the User's
lock is taken. Nothing is written or counted for it, so a deletion can never
leave behind a `LOGIN_FAILURE` naming the deleted User, nor a `LOCKOUT_SET` or a
session revocation for a User that no longer exists. Whether the User is gone is
decided by the login-state module, never by a caller: every caller hands it the
User's stable id, and it reads the User under its lock and reports it gone when
it is missing there; the caller then records the outcome its own rules give gone
— a password Login's refusal against nobody here, an
[Epic Login's](./epic-login.md) `ACCOUNT_DISABLED` naming the User. The
submitted name is never looked up again, so a directory rename landing during the
password comparison — one User renamed away, another given its old name — cannot
move the failure, a Lockout it imposes, its session revocation or the
`LOGIN_FAILURE`'s subject onto the other User. Naming the User costs no lookup
and no comparison: the authentication records it as it reads. A fault that
keeps the read from finishing at all — a query timeout, a pool-acquire
timeout — is a server fault, never a refusal: nothing is counted or audited as
a `LOGIN_FAILURE`.

**An accepted Login is a success only once its session is signed in** — by
either login method, the operational log's "Login accepted" and the `login`
success count are written after the session is established, and name it by its
`session.hash`; a Login whose session could not be established is neither logged
nor counted a success. The audit trail's `LOGIN_SUCCESS` is the exception, and
deliberately earlier: it is written with the cleared failure run, in the login
decision's own transaction, so a success the trail cannot record is not a success
(ADR 0004).

## Failure run and Lockout

**Failure run** — the consecutive failures recorded against one User, counted on
the User's own row as `failed_login_attempts`. A login the backend accepts ends
the run and returns the count to zero; a login it rejects lengthens it, and so
does a wrong current password on the self-service password change. An unknown
username has no run, because nothing is recorded for a name that names no User;
nor does a User with no password, by either route, because there is no password
to guess. Both exemptions — no password, and the Bootstrap Admin's below — are the
login-state module's, decided on the User it reads under the User's lock
(`LoginStateService`), never by a caller on the copy it read before.
Concurrent events on one User — failures, a Login, a password change, an Unlock,
the dormancy lock — are serialized on the User's lock, each deciding from the run
as the one before it left it, so none is lost and they end as some serial order of
them would. The run is **capped at the limit**: of N failures racing on a User one
short of it, exactly one imposes the Lockout and is audited as `LOCKOUT_SET`, and
the rest find the User locked and are not counted, so `failed_login_attempts` ends
at the limit rather than at limit + N − 1.

**Lockout** — the state a User enters for one of two causes, closing it to
logins **permanently**: its failure run reaches the configured limit
(`app.auth.lockout.max-attempts`, default 3) — cause `FAILURES` — or the
[dormancy job](./dormancy.md) finds it past the lockout window — cause `DORMANCY`
(ADR 0007). There is no duration, no configuration key expressing one, and no
passage of time that lifts it; only an Unlock ends it. The User's row
records `locked_at`, the instant the lock was imposed, and `lock_cause` beside it,
set and cleared together (a CHECK constraint refuses one without the other), so
"is it locked" is a question about the row rather than a comparison against a
clock. A lock keeps the cause it was first imposed for.

- A locked User is refused **with its correct password**, and refused the same
  way as a wrong one, whatever the cause: a bare `401` with no body, so the
  response never reveals that the User exists or that it is locked — a User who
  cannot get in learns nothing by waiting, which is intended. Attempts made while
  it holds neither count nor deepen it. An attempt refused for the Lockout counts
  nothing even when an Unlock commits before the refusal is recorded: the
  refusal's reason decides that, not the state read when it is counted, so it
  never becomes the first failure of the run the Unlock started. It is still
  recorded as a `LOGIN_FAILURE` with reason `ACCOUNT_LOCKED`, moving nothing.
- A Login never lifts it, not even one racing it. The account check reads the
  User before the password comparison, so a Lockout another attempt imposes — or
  a deactivation the directory commits — while a correct password is being
  compared gets past that check; the accepted Login therefore decides once more
  on the standing read under the User's lock, and is refused there, writing
  nothing: no cleared run, no `LOGIN_SUCCESS`, no session. It is answered and
  recorded as the refusal the check would have made a moment later —
  `ACCOUNT_LOCKED` or `ACCOUNT_DISABLED` — and the same holds for an Epic Login.
  A password Login's refusal there is counted as that refusal is, from the state
  it was decided on, under the same lock and in the same transaction, so nothing
  can commit between the decision and its count.
  A User both locked and deactivated is refused as locked, by every Login and by
  the self-service password change alike: the login-state module decides the
  standing, in that one order (`LoginStateService.StandingRefusal`).
- A password replaced during the comparison refuses the Login too. The same
  decision under the lock confirms that the stored credential is still the one
  the authentication compared the password against — a salted hash differs on
  every write, so a password set again to the same value counts as replaced. If a
  connector or the User's own change elsewhere replaced it, the
  submitted password is by then simply wrong: the Login is refused as
  `BAD_CREDENTIALS`, audited as a `LOGIN_FAILURE` naming the User, and counted on
  the failure run exactly as a rejected password is, a Lockout included at the
  limit. Nothing of the success is written — no cleared run, no moved dormancy
  basis, no `LOGIN_SUCCESS`, no other session revoked, no session issued — so the
  replacement's own revocation of the User's sessions is never undone by a Login
  that was in flight. The standing is decided first: a User locked as well is
  refused as locked. An Epic Login presents no password of this service's, so it
  is never refused by this check.
- Imposing it **revokes the User's live sessions**, after the transaction
  commits, so a locked User stops acting immediately rather than when the session
  it already held expires. What a newly imposed Lockout implies — its audit event
  (`LOCKOUT_SET` for a failure run, `DORMANCY_LOCKOUT` for dormancy) and this
  revocation — is the login-state module's to carry out, for either cause, not
  the caller's whose event imposed it.
- Enforcement is Spring Security's, which checks the User's status before it
  compares passwords; the counting is the login path's and the self-service
  change's.
- The failure lockout is the per-User half of brute-force deterrence on Login;
  the deployment edge throttles the rest, because it cannot see the `userName` in
  a Login body (`infra/docs/edge-throttling.md`, "Edge throttling").

**Bootstrap Admin exemption** — the seeded Admin (`app.auth.bootstrap-username`)
is the deployment's local recovery identity and the one principal lockout never
applies to — neither cause, and neither dormancy step. Its failed attempts are
counted and audited as `LOGIN_FAILURE` like anyone's, but no run of them locks it.
With no automatic lift, a lockable recovery User would let an unauthenticated
attacker brick the deployment; the accepted cost is unbounded online guessing
against that single User, answered by the Argon2id verification cost every
attempt pays, the uniform refusal, and the audited failures — not by a lock.

## Deactivation and Unlock

**Deactivated User** — a User whose SCIM `active` attribute is false, set by a
connector's SCIM write; the application itself never writes it, and the Accounts
page reports it and cannot change it. It is refused at login exactly as a locked
User is: a bare `401`, indistinguishable from a wrong password. There is one
flag: SCIM's `active` is the whole of "may authenticate". The application keeps
no enabled flag of its own, because one beside `active` would be unreachable over
the wire.

**Deactivating** and **unlocking** are **two separate capabilities**, and neither
performs the other. Deactivation settles whether a User is permitted at all, and
is the directory's; unlocking settles whether it is being penalised for failed
logins right now, and belongs to whoever holds `account:unlock`. So:

- Deactivating a User leaves its failure run and `locked_at` as they stand.
  The run is evidence, and it is most wanted at the moment a User is being
  closed.
- Reactivating a User leaves a lockout it is serving in force. Restoring access
  is not a finding that the failed logins did not happen; the lockout still ends
  only when it is Unlocked.
- **Unlock** ends a lockout, whatever its cause, and clears the failure run with
  it, and says nothing about the `active` flag. A deactivated User can be
  unlocked and stays deactivated. Unlocking a locked User also restarts its
  dormancy basis, so the next dormancy run does not lock it again before it
  has had the chance to sign in, and records the lifted lock's cause on its
  `LOCKOUT_LIFT` event. That event's changed paths are what the Unlock changed:
  `failedLoginAttempts` when the run held attempts, and `lockedAt`, `lockCause`
  and `lastAuthenticatedAt` when a lock stood. Unlocking a User that has a
  password also sets its change-required flag. An Unlock of a User serving no
  lockout has no effect: it is refused (`409`), nothing is written — its failure
  run included — and the refusal is a `LOCKOUT_LIFT` failure with reason
  `NOT_LOCKED`. An Unlock of an id no User has is refused (`404`) and recorded
  the same way with `UNKNOWN_RESOURCE`, naming the id it addressed.

The same rule holds for every event that records a login-state transition — a
refusal counted against the run, a Lockout, an Unlock, a required change, a
dormancy lock: its changed paths are exactly the login-state paths that
transition moved, no more and no fewer, as the login-state module reports them. A refused password
Login, or a wrong current password on the self-service change, names
`failedLoginAttempts` when the run lengthened, the Bootstrap Admin's included.
It names no path when the run did not move: a locked User's attempts neither
count nor deepen the lock, and an unknown name, or one with whitespace at an
edge, has no User to count against. The failure that imposes a Lockout names
the run and `lockedAt` and `lockCause`, on its `LOCKOUT_SET` as on its own
refusal. Where one transition is recorded as two events, they split what it
moved: an Unlock's `PASSWORD_CHANGE_REQUIRE` names
`passwordChangeRequiredSince`, and its `LOCKOUT_LIFT` the rest. The credential
is not a login-state path: a completed password change names `password`
because the change was completed, beside `passwordChangeRequiredSince` when it
cleared that flag. Like an accepted Login's `LOGIN_SUCCESS`, which names no
path, it does not name the run it clears or the dormancy basis it moves.

## Recovery guard

What keeps the deployment recoverable. Two rules.

**Self-target refusal:** no User may Unlock themselves (`403`, reason
`SELF_TARGET`), whatever Permissions it holds — a Superuser's included — so
recovering from a self-inflicted state takes a second holder of `account:unlock`.
It is checked before whether a lockout is in force, so the caller's own row is
refused for being its own whatever its state. The check compares stable ids: the
caller is the User whose stable id its signed-in session holds, never the
`userName` the session's principal carries. A connector may rename a User between
its Login and its next request, so a name comparison would let a caller whose own
name had moved act on themselves past the guard, and refuse a caller acting on
whichever User took its former name. Connector administration records its acting
Superuser the same way, and the Accounts page hides Unlock on the caller's own row by the same
comparison, against the stable id `GET /api/auth/me` reports. A session that identifies nobody cannot Unlock (`401`).

**The Bootstrap Admin is never locked** and is protected from every SCIM write,
deactivation included, so a deployment whose other Admins are all locked is still
recoverable: it signs in and unlocks them. No runtime guard counts the remaining
administrators: the Bootstrap Admin's frozen membership of the
[Admin group](./authorization.md), whose direct members are the Superuser, is what
guarantees a User holding every delegatable Permission, so a connector may remove
every other member of the Admin group.

The Bootstrap Admin's protections are recognised by the **reservation marker** on
the User's own resource row, not by comparing its name to the configured one. A
name comparison could be moved by a rename, and a second identity could acquire
the exemption by taking the configured name; a marker written once by seeding, in
a column no UPDATE reaches, can do neither. The same marker protects the resource
from every SCIM write.

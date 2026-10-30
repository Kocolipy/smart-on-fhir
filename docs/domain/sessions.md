---
type: Domain Rules
title: Sessions
description: Session status in the SPA, how sessions end, the sign-in reasons a Guest arrives with, and every trigger of session revocation.
tags: [sessions, spa, revocation]
---

# Sessions

**Session status** — what the SPA currently knows about the visitor's session:
`checking` before the start-up check has answered, then `authenticated` or
`guest` — or `unchecked`, when the check did not answer. It is a _condition_,
not an event, which is why a session ending is not a member of its own.
`resolveSessionRoute` is the only place the status decides what a route does.

**The start-up check** — `GET /api/auth/me`, once on arrival. `200` signs the
visitor in, and **only a `401` makes them a Guest**. Any other answer — a `403`,
a `5xx`, a network error, a CSRF token that could not be fetched — says nothing
about whether a live session stands, so the status becomes `unchecked`: the SPA
neither routes to login nor forgets the CSRF token (no session-end transition
runs), and every route shows one line saying the session could not be checked,
with a way to check again. The retry runs the same check under the same rules.
Treating a hiccup as a Guest would be worse than a wait: the User would sign in
again, and under one session per User that ends every other session they hold.

**Guest** — the session status of a person with no session: where a cold arrival
starts and where a sign-out returns. A Guest may use only the login page,
`/login`; asking for a protected route records the return destination and sends
them there.

**Expired session** — a session that _was_ authenticated and which the backend
has since refused with a `401`. The status becomes `guest` either way; what
distinguishes an expired session is its sign-in reason, `expired`, passed into
the redirect so the login route can say the session ended rather than greeting a
stranger. A `401` expires only the session its request was sent under: one
answered after that session was signed out, or replaced by a fresh sign-in,
ends nothing. A `403` is **not** an expired session: a stale CSRF token is re-fetched
and the request retried once, and a `403` that survives that is an
[authorization refusal](./authorization.md). The session survives both.

**Return destination** — the protected path a visitor asked for before being
redirected to sign in, recorded as `from` in router state and replayed once the
status turns `authenticated`. Owned by the route guards, so no page navigates on
its own behalf after signing in.

**Home** — the signed-in landing page, at `/`, and the default destination: a
sign-in with no return destination lands there, as does a successful Epic Login,
and an authenticated visitor on the login page (`/login`) or on a page it may not
see is sent there. `/` is a protected route like any other, so a Guest's cold
visit records it as the return destination and goes to `/login`. A path with no
route of its own goes to `/`, which is Home for a User and login for a Guest.

**Idle timeout** and **absolute session lifetime** — the two independent bounds
on every authenticated session, a Superuser's included: 15 minutes without a request
(`SESSION_TIMEOUT`) and 8 hours from creation (`APP_SESSION_ABSOLUTE_LIFETIME`).
Whichever is reached first ends the session, and the SPA cannot tell which; both
present as an expired session.

**Idle sign-out** — the SPA's own end of an idle session, timed by the
`idleTimeoutSeconds` the backend reports rather than a constant of the SPA's.
Only user input counts as activity, never a request, and it is shared across tabs;
a warning a minute before the limit offers to stay signed in, and staying is a real
request that renews the backend's clock too. At the limit the SPA logs out and
returns to login with the sign-in reason `inactive` — the code's name for it, not
the glossary's. `/frontend/docs/BACKEND_CONTRACT.md` is the contract.

**Sign-in reason** — why a Guest is at the login page, from a closed set of
six: an expired session, an idle sign-out, the User's own successful password
change, a password change whose wrong current passwords reached the lockout
threshold (`locked`: the account is locked, every session of the User revoked,
and it must be Unlocked before the User signs in again), an Epic refusal and
Epic being unavailable. The first four the SPA records itself when it ends the
session, one at a time, and carries in router state; the Epic two arrive as the
backend's `?signin=refused` or `?signin=unavailable` landing marker. An Epic
marker wins over any router state, since the landing is a fresh navigation. A
password change and a lockout carry no return destination, so the next sign-in
lands on the default; the other two do. A cold arrival and a logout carry none
and the login page says nothing. A refused Login records none either, and its
refusal never says "locked", which would tell a guesser the account exists:
only the User's own session, ended by its own change, is told of the lockout.
`frontend/src/auth/sign-in-reason.ts` is the one owner of the set, the
carriage, the precedence and the copy.

**One session per User** — an accepted Login, by either login method, ends every
other session the User holds, so signing in from a second browser signs the first
out; the first sees an expired session.

## Session revocation

Ending the sessions a User is already holding, so its next request arrives as a
Guest and the SPA sends it back to login. Sessions are found by the User's stable
id, never its `userName`, so a rename cannot hide one. The triggers in force:

- its failure run imposing a lockout, from Login or the self-service change;
- a SCIM write that takes `active` from true to false;
- a SCIM write that sets, changes or removes its password;
- a SCIM write that changes its `userName`;
- a SCIM `DELETE` of the User;
- a SCIM write that removes it from a Group that carries Permissions — `PATCH`,
  `PUT` or the Group's `DELETE` (an addition takes effect at its next Login, since
  authority is derived only then);
- the Superuser taking a Permission off a Group it is a direct member of
  (`PUT /api/admin/groups/{id}/permissions`; a Permission added takes effect at
  its next Login);
- its own successful Login, which ends every other session it holds — one
  session per User;
- its own successful self-service password change;
- the dormancy job's lockout and membership revocation — recorded with no actor,
  because the job is not a principal.

Every one of those triggers but the startup sweep goes through one Session revocation
module, which takes the User, the cause and the actor — the connector for a SCIM
write, the Superuser for a Group's Permissions reduced, nobody otherwise. It defers the revocation
until after its transaction commits, so a write that was refused, stale or rolled
back revokes nothing, and one SCIM write that moves several of those attributes
revokes once. Every revocation that ended at least one session is audited as its
own `USER_SESSIONS_REVOKE` event after the commit, its causes as the reason —
`DEACTIVATED`, `PASSWORD_CHANGED`, `USER_NAME_CHANGED`, `DELETED`, `MEMBERSHIP_REVOKED`,
`GROUP_PERMISSIONS_REDUCED`,
`DORMANCY_LOCKOUT`, `FAILURE_RUN_LOCKOUT` or `REPLACED_BY_LOGIN` — and logged as `session-end` under the same names. One that
ended nothing (the User was signed in nowhere else) records nothing. If the session
store fails, the failure is always audited, with outcome `FAILURE`, and alerted;
the change stands, and the request answers as ADR 0004 sets out: one that would
otherwise succeed — a SCIM write, the dormancy job, a password change, a
Group's Permissions set, an accepted Login — receives an error, while a rejected Login or a
rejected self-service change whose failure run imposed the lockout still answers
with its bare refusal.

Nothing else revokes: an ordinary profile or email change, a reactivation, an
alias, a Group rename and Unlock touch no session. Reactivation gives nothing back
(a revoked session is gone; the User signs in again). A refused Unlock revokes
nothing either, and neither does a failure run that stops short of the limit.

Revocation is possible only because sessions are indexed by principal
(`spring.session.data.redis.repository-type: indexed`, set in
`backend/src/main/resources/session.yaml`). Without that index a session store
can be read by id alone, so the ones belonging to a User cannot be found; the
application refuses to start rather than accept a revocation it cannot enforce.

It is not a lock. A Login whose decision committed before the deactivation, but
whose session is signed in only after the revocation ran, can still keep a session
the revocation did not see. A Login still deciding when the deactivation or a
Lockout commits is refused: its success re-reads the User's standing under the
User's lock ([authentication](./authentication.md)). So once the write is committed
no further login succeeds, and the gap is one transaction wide rather than
open-ended.

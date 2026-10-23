---
type: Domain Rules
title: Passwords
description: The change-required flag and the confined session it issues, the self-service password change, the password policy and history, and how passwords are hashed.
tags: [authentication, passwords]
---

# Passwords

**Change-required flag** — application-owned state on a User saying its current
password was imposed by somebody else and must be replaced before a password Login
by the User may do anything else. Stored as `password_change_required_since`: its
presence is the flag, as `locked_at`'s is the lockout, and its value is when the
change was last required. There is no deadline for the change; see
[Dormancy basis](./dormancy.md) and ADR 0008.

- It is **set** by every connector password write (create, `PUT` or `PATCH`
  carrying a password), by an Unlock of a locked User that
  has a password — the credential that reached the lockout threshold may be the
  one an attacker was guessing — by a reactivation of a User that has a password,
  since a credential that sat unused across a deactivation is not trusted on
  return, and by seeding the Bootstrap Admin, whose first password comes from
  deployment configuration — except with the development fixtures on
  (`APP_DEV_FIXTURES_ENABLED`), which startup refuses outside the `local`
  profile, so they are never on in a deployment. A
  credentialless User is unlocked or reactivated without it, having no password
  to replace.
- It is **cleared** only by a successful self-service change; a connector write
  never clears it.

**Confined session** — a session issued by a password Login while the
change-required flag is set. It holds no Permission and not even baseline access,
a Superuser's included, so it may call only `GET /api/auth/me`, the self-service
change and logout; every other endpoint, `/api/admin/**` and `/api/self`
included, answers `403`. An [Epic Login](./epic-login.md) is never confined: it
presents no password of this service's, so the imposed credential the flag marks
is not what it used, and it receives the authorities the User would hold with the
flag clear (ADR 0008). A User who only ever signs in
through Epic therefore never has to replace the imposed password; that password
still buys only a confined session.

**Only an Unlock requires a change administratively** — an Unlock of a locked
User is the one administrative action that sets the change-required flag, so the
helpdesk Permission cannot disturb a higher-privileged User that is working (ADR
0015). The accepted cost: no one can require a change of an unlocked User whose
password is suspected compromised; recovery goes through the connector instead,
which sets a new password (and with it the flag) or deactivates the User in the
directory.

**Self-service password change** — `POST /api/auth/change-password`, for the User
the session belongs to. A wrong current password lengthens the same failure run as
a rejected Login, so it leads to the same lockout; a new password that breaks the
password policy or repeats a recent one is refused by naming the rule, never
echoing either value. Success hashes the new password, clears the flag, advances
the version, records a `PASSWORD_CHANGE` audit event with no password value, and
revokes every session of the User, the submitter's included. A change sent with no
live session is refused before the current password is compared, so it counts
nothing toward the lockout; its refusal is the generic `401` any request without
a session gets, where the password check's own refusal — the same for a wrong
current password, a lockout and an inactive account — is marked as the check's.

## Password policy

One policy, `PasswordPolicy`, governs every path that sets a password: SCIM
`password` on `POST`, `PUT` and `PATCH`, and the self-service change. The password
is first normalized by the mapping half of PRECIS's OpaqueString profile (RFC 8265,
which RFC 7644 §5 requires), and that form is what is validated, compared and
hashed. Then:

- 12 to 256 characters, one code point counting as one character. Every printable
  character is accepted, the space and any Unicode code point included; none is
  stripped, substituted or refused for being "special".
- Refused when it equals or contains the `userName`, case-insensitively.
- Refused when it matches any entry of the **password history** — the User's
  three most recent passwords, the current one included. The history is kept as
  Argon2id hashes in `scim_user_password_history`, matched by verifying the
  candidate against each, trimmed to the newest three on every change, and
  deleted with the User. So a connector re-sending a password still in the
  history is refused: a `PUT` that means no credential change omits `password`.
- No composition rules, no expiry, and no hints or knowledge-based recovery
  questions, because forced complexity, forced rotation and hint mechanisms all
  reduce real-world strength.

A violation is `400 invalidValue` over SCIM and a `400` naming the unmet rule on
the application chain, never echoing the submitted value. The policy also calls
for a blocklist of common and known-compromised passwords, deployment-configured
and loaded from a local corpus so that no candidate, nor any hash or prefix of
one, is ever sent to an external service. It is not implemented: no corpus ships
yet, and a rule with nothing to check against would always pass.

## Hashing

Every password, current or historical, is hashed with Argon2id (`m=19456` KiB,
`t=2`, `p=1`) through a `DelegatingPasswordEncoder`, so every stored hash carries
its `{argon2id}` prefix and the scheme can change later without a schema
migration. Argon2id has no input-length ceiling, which is why the maximum is 256
characters rather than bcrypt's 72-byte truncation point. There is **no pepper**:
it would be the only keyed secret in the system, a rotation and migration burden
for no gain over Argon2id's per-hash salts. Nothing here or in session state has a
key to rotate — hashes are self-describing and unkeyed, and Spring Session keeps
state server-side with no signing key — by design rather than omission; the only
rotatable credentials are connector tokens and the database and Redis passwords
deployment configuration supplies.

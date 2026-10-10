# 10. Fine-grained Permissions, granted by Group through a configured role mapping

Date: 2026-10-03

## Status

Accepted. It supersedes the original design's
authorization matrix — reachability per authority (`ROLE_USER`, `ROLE_ADMIN`)
declared as path and method rules in deployment configuration — replaces the earlier
method-security plan, and closes App-Standards finding USR-2.

## Context

Authority is binary. A direct member of the Admin group holds `ROLE_ADMIN` and
with it every administrative power: reading the audit stream, managing
connectors, minting connector tokens. Everyone else holds baseline `ROLE_USER`.
A deployment cannot give a helpdesk operator Unlock without also giving them
directory-write credentials, and the Prometheus scraper's account has to be a
full Admin.

Connector tokens have a second, coarser vocabulary (`READ_ONLY` / `READ_WRITE`,
surfaced as `scim.read` / `scim.write`). It cannot express "Groups but not
Users", and nothing stops an Admin minting a token more powerful than the Admin
should hold.

Authorization is decided only by URL rules in the filter chain. There is no
method security, so a route added outside the matched prefixes is protected only
if someone remembers a rule.

The identity provider already decides who belongs to which Group, and the
application already reacts to Admin-group membership changes. Any design that
moved role assignment into the application would add a second source of truth
for access.

## Decision

- **Permissions are a closed set** defined in code: `user:read`, `user:write`,
  `group:read`, `group:write`, `audit:read`, `connector:read`,
  `connector:write`, `connector:token`, `ops:read`, `counter:read`,
  `counter:write`. `user:write` covers Unlock and force password change on the
  session chain as well as Users writes over SCIM.
- **A Role is a named set of Permissions, and a Group confers a Role.** Both live
  in deployment configuration, read-only while the application runs. Startup
  fails on an unknown Permission or Role, a duplicate or unresolvable Group id,
  or anything but exactly one Superuser Group whose Role holds every
  Permission. A User's Permissions are the union over the mapped Groups it is a
  direct member of. Role assignment is persisted as Group membership. Role
  definitions are not stored in the database, which is a conscious deviation
  from the standalone App-Standard: configuration is already the single source
  of truth it asks for.
- **One vocabulary for both kinds of caller, enforced separately.** A connector
  token carries a list of the four directory Permissions in place of its access
  level. The session chain never routes `/scim/**`, and the SCIM chain accepts
  bearer tokens only. A token may carry only Permissions its creator holds (no
  escalation). A root `/.search` returns only the resource types the token may
  read, and discovery needs only a valid token.
- **Method security is the authority, and the chain is the backstop.** Every
  protected method declares its Permission. The application chain is
  deny-by-default as a whole, with self-service and public routes listed
  explicitly. Unlock and force password change on the caller's own account are
  refused.
- **The Admin group becomes the Superuser Group.** It cannot be renamed or
  deleted, and the Bootstrap Admin's membership in it stays frozen. The "last
  enabled administrator" guard is dropped, because the frozen Bootstrap Admin
  plus startup validation guarantee an account holding every Permission.
- **Permissions are held on the session.** Losing a mapped Group revokes the
  User's sessions after commit. Gaining one takes effect at the next sign-in. A
  session records the hash of the mapping it was created under, and sessions
  under a different hash are revoked at startup.
- **Refusals stay generic.** A refusal is `403`. Its audit row and log line
  record the caller, the operation and "insufficient permissions", never a
  Permission, a Role or the policy, as the logging App-Standard requires.
- `/api/auth/me` returns `permissions[]` instead of `roles`, the SPA guards each
  page and action by Permission, and a read-only endpoint (`group:read`) lists
  each Role, its Permissions and its Group.
- `ROLE_ADMIN` is removed. `ROLE_USER` stays as the baseline authority meaning
  "active and no password change required".
- **`counter:read` and `counter:write` are baseline Permissions** (amended while
  implementing this ADR): every active User holds them at sign-in beside
  `ROLE_USER`, whatever its Groups, because the counter is a basic capability
  rather than an administrative one. A confined session holds neither. They stay
  Permissions, and each counter operation still declares its own, so making the
  counter Role-granted later is a one-line change.

## Consequences

- Whoever holds `group:write` on a mapped Group decides who holds that Role. A
  `connector:token` holder who also holds `group:write` can issue a credential
  that does the same. This is documented rather than split into a `role:assign`
  Permission, because a directory sync has to manage every Group.
- A misconfigured mapping fails at startup, not at first use. A mapping change
  needs a redeploy, and it reaches live sessions through the hash check.
- The API document becomes the authorization contract: every operation declares
  its Permission, and a contract test proves each declaration against the
  running application.
- IM8 ac-2 (MFA for privileged actions) and ac-4 (access review) are still not
  met, and this decision does not address them (ADR 0012).
- Out of scope, deliberately: per-resource authorization (for example "may unlock
  Users in Group X only"), editing Roles or the mapping at runtime or through any
  UI, and Group nesting. A Permission applies to every resource of its kind, and
  a mapping changes only by redeploying.

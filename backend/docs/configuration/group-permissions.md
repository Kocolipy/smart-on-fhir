---
type: Configuration Reference
title: Group Permissions
description: What a Group confers — stored per Group, the local profile's development fixtures block, and the startup validation of stored values.
tags: [authorization, groups, permissions, configuration]
---

# Group Permissions

A Group's Permissions are **data**, stored per Group in the `group_permission`
table (migration `V6`), not configuration (ADR 0015). A User's Permissions are
the baseline `patient:read` plus the union of the Permissions of the Groups it is
a **direct** member of, resolved at login; a Group with no row confers nothing.
The Admin group, recognised by its `admin-group` reservation marker, carries no
row: its direct members are the Superuser, expanded in code to every delegatable
Permission. There are no Roles, no role mapping and no `app.authorization`
block, so a Group created over SCIM can be given access without a redeploy.

No SCIM path reads or sets a Group's Permissions. They are set by the Superuser
alone, at runtime: `PUT /api/admin/groups/{id}/permissions` replaces a Group's set
with the complete set its body names (never on the Admin group), and a set that
lacks a Permission the Group carried ends its direct members' sessions after the
commit (`docs/domain/authorization.md`). Migration `V7` grants the application
login the `DELETE` that replacement needs. Besides it, only the development
fixtures below write them, at startup. A holder of `group:read` reads them on
`GET /api/admin/groups`, and every Permission a Group may carry on
`GET /api/admin/permissions`.

## Deleting a Group

`group_permission.group_id` references the Group with `ON DELETE CASCADE`, so a
SCIM `DELETE` of a Group takes its rows with it. Nothing names a deleted Group
afterwards, and the next startup has nothing to refuse. Its former members lose
what it carried, are recorded as `PERMISSION_REVOKE`, and have their sessions
ended after the commit.

## Startup validation

At every startup, after seeding, the application reads every stored row and
refuses to start — naming each Group and value in one message — when one:

- names a Permission the closed vocabulary in code
  (`authorization.domain.Permission`) does not have, or
- names one no Group may carry: anything not delegatable, which is the baseline
  `patient:read` and the token-only `user:write` and `group:write`.

```text
Invalid Group Permissions: Group 0b7c… carries unknown Permission 'audit:export'.
Delete the rows, or restore the Permission in code
```

So **removing a Permission from code needs a migration that deletes its rows**,
in the same change. The table has no `CHECK` listing the names on purpose: the
vocabulary is code's, and a second copy in SQL could silently disagree with it.

Startup also requires that the reserved Admin group exists. Seeding creates it on
a fresh database under the fixed id `00000000-0000-4000-8000-00000000a001`; an
Admin group seeded earlier under another id keeps it.

## Development fixtures

`src/main/resources/authorization.yaml` holds the development fixtures, off unless
`APP_DEV_FIXTURES_ENABLED=true` (`.env.example` sets it), and refused outside the
`local` profile, where startup fails rather than seeding them:

- `app.dev-fixtures.groups` — one Group per development access level, under a
  fixed id, with one User in it (Account admins, Auditors, Connector admins,
  Monitoring, Helpdesk, and Disposable auditors, which the e2e suite deletes);
- `baseline-member`, `dormant-member` and `dormant-groups` — see
  [dormancy](dormancy.md) for the dormant fixture;
- `password` — every fixture User's password, `APP_DEV_FIXTURES_PASSWORD`, with no
  fallback.

What each fixture Group confers is a separate document of the same file, active
under the **`local` profile only**:

```yaml
spring:
  config:
    activate:
      on-profile: local
app:
  dev-fixtures:
    group-permissions:
      - group: 00000000-0000-4000-8000-00000000a002 # Account admins
        permissions: [user:read, account:unlock, group:read]
      - group: 00000000-0000-4000-8000-00000000a003 # Auditors
        permissions: [audit:read]
      # ...
```

- It is applied at startup, after the fixture Groups are created, and **only to a
  Group that carries no Permission yet**, so a Group whose Permissions were set
  since keeps them and a restart changes nothing.
- Every entry is checked before any is applied: a name that is unknown or not
  delegatable, a Group that does not exist, and the Admin group each fail startup
  with nothing written.
- No other profile — a deployment's included — seeds a Group Permission. A
  list rather than a map, so a higher-precedence source replaces it whole.

Clean cut: the old role mapping was not converted. A database that ran with it
starts with no Group Permissions outside the `local` fixtures, and sessions
issued under it may simply be invalidated.

## Tests

The test resources' `application.yaml` does not import `authorization.yaml`, so
a test context starts with the seeded Admin group and no Group Permission; a
test inserts the rows it needs directly. A test exercising the shipped fixtures
activates the `dev-fixtures` test profile, which imports `authorization.yaml`,
and `local` beside it for their Group Permissions.

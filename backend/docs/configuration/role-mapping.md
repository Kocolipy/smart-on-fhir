---
type: Configuration Reference
title: Role mapping
description: The app.authorization block — defining Roles, mapping them to Groups, replacing the shipped lists, the development fixtures, and reading the mapping.
tags: [authorization, roles, configuration]
---

# Role mapping

The `app.authorization` block defines the deployment's **Roles** — named sets of
Permissions — and maps each Role to a Group by the Group's stable id. A User's
Permissions are the union of the Roles of the mapped Groups it is a direct member
of, resolved at login. It is read-only configuration: no endpoint creates or
changes a Role or a mapping entry. The shipped default is a development mapping,
in `src/main/resources/authorization.yaml`:

```yaml
app:
  authorization:
    roles:
      - name: Account admin
        permissions: [user:read, account:unlock, group:read]
      # ...
    groups:
      - id: 00000000-0000-4000-8000-00000000a001 # a Group's SCIM stable id
        role: Superuser
        superuser: true # exactly one entry
      - id: 00000000-0000-4000-8000-00000000a002
        role: Account admin
```

The Permission names are a closed set defined in code, in
`authorization/domain/Permission.java`.

`patient:read` is also a **baseline Permission**: every active User holds it at
sign-in whatever its Groups, so no Role needs to list it for its members to read
a patient. A session confined by a required password change does not hold it.
The Superuser Role must still list it, as it must list every Permission a
session may hold. The token-only `user:write` and `group:write` are the
exception: no session holds them, a Superuser's included, so no Role may list
them (ADR 0015).

Startup fails, naming every problem in one message, on: an unknown Permission; a
token-only Permission (`user:write`, `group:write`) in any Role; a Role defined
twice or without a name; an entry with no Group id, mapping a Group id twice, or
naming an undefined Role; anything but exactly one `superuser: true` entry; a
Superuser Role missing any Permission a session may hold; and a mapped Group id that does not
resolve to a Group, or a Superuser Group that is not the reserved Admin group.
The Superuser Group IS the Admin group: seeding creates the Admin group under
that entry's id, so give the Superuser entry the id the Admin group should have —
or, on a database seeded earlier, the id it already has. Every other id must
belong to a Group that exists when the service starts, so provision those Groups
before deploying a mapping that names them.

**Replace the list whole.** `roles` and `groups` are lists, and a list from a
higher-precedence source replaces the shipped one entirely — for example a file
passed with `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/backend/authorization.yaml`,
or indexed environment variables (`APP_AUTHORIZATION_GROUPS_0_ID`,
`APP_AUTHORIZATION_GROUPS_0_ROLE`, `APP_AUTHORIZATION_GROUPS_0_SUPERUSER`, …).

**Development fixtures.** The shipped mapping names four Groups no deployment
has. With the fixtures enabled, startup seeds them under those ids, each with one
User, so local runs and the e2e suite have a User per Role; without them, a
deployment that did not replace the mapping fails startup instead of running with
it. They also seed two Users in no Group: `user`, holding only baseline access, and
`dormant`, whose dormancy basis is reset
past the lockout window at every startup (see [Dormancy](dormancy.md)). Fixtures
never overwrite a User or Group that already exists.

| Variable                    | Default | Meaning                                                                  |
| --------------------------- | ------- | ------------------------------------------------------------------------ |
| `APP_DEV_FIXTURES_ENABLED`  | `false` | Seed the development Groups and their Users (`.env.example` sets `true`) |
| `APP_DEV_FIXTURES_PASSWORD` | none    | Every fixture User's password; required when enabled, with no fallback   |

Each development Role's Permissions are in `authorization.yaml`; the Superuser
Role holds every one a session may. The Connector admin Role holds `connector:read` only: creating
and deleting connectors and minting their tokens are the Superuser's alone, not a
Permission any Role holds. The Helpdesk Role holds `user:read` and the helpdesk
Permission `account:unlock`, which Unlock and the forced password change require.

| Role            | Fixture Group      | Fixture User        |
| --------------- | ------------------ | ------------------- |
| Superuser       | the Admin group    | the Bootstrap Admin |
| Account admin   | `Account admins`   | `account-admin`     |
| Auditor         | `Auditors`         | `auditor`           |
| Connector admin | `Connector admins` | `connector-admin`   |
| Monitoring      | `Monitoring`       | `monitoring`        |
| Helpdesk        | `Helpdesk`         | `helpdesk`          |

A session records the hash of the mapping its Permissions were resolved under,
and startup ends every authenticated session issued under a different hash, so a
redeploy that changes the mapping reaches live sessions whether or not Redis kept
them. A restart that keeps the mapping keeps its hash and ends nothing.

**Reading the mapping.** `GET /api/admin/roles`, for a holder of `group:read`,
returns every Role with its Permissions and the Groups that confer it, each by
stable id and current `displayName`, with the Superuser Group marked — so an
operator can see what each Group grants without reading this configuration. It is
the only Role endpoint: none creates, changes or deletes a Role or a mapping
entry.

**The Superuser is not a Role.** A direct member of the Admin group, the Superuser
Group, is the Superuser: its session holds the Superuser Role's Permissions
and `ROLE_SUPERUSER` and every delegatable Permission beside them, expanded in code
at sign-in. Nothing here configures it.

**Assigning a Role is Group membership**, which a connector writes over SCIM, so
`group:write` on a mapped Group is Role assignment. How a membership change reaches
live sessions, how it is audited, and what protects the Superuser Group are in
`/docs/domain/authorization.md`.

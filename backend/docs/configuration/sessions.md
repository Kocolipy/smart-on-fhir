---
type: Configuration Reference
title: Session storage
description: Why Spring Session's indexed Redis repository is required, what that means for a deployment's Redis, and where the session cookie is configured.
tags: [sessions, redis, configuration]
---

# Session storage

Sessions are stored through Spring Session's **indexed** Redis repository, which
keeps a per-principal index. That index is what lets the service end a User's
sessions — on deactivation, lockout, a password change, or a new login
under one session per User — so the setting is a requirement rather than a preference:
with the default repository the application does not start. It is configured in
`src/main/resources/session.yaml`, imported by both the main and the test
`application.yaml` so the two cannot drift. Two consequences for
a deployment — the Redis instance is not interchangeable with a plain cache
(session keys and one index set per signed-in account), and startup does not try
to `CONFIG SET notify-keyspace-events`, because ElastiCache refuses `CONFIG`. Set
`notify-keyspace-events` in the cache parameter group if you want expiry events;
without them expired sessions are reaped by the repository's cleanup cron.

## The session cookie

The session cookie's name (`JSESSIONID`) and attributes (`HttpOnly`,
`SameSite=Lax`, and `Secure` from `SESSION_COOKIE_SECURE`, default `false`) are
configured under `server.servlet.session.cookie` in the same
`src/main/resources/session.yaml`, so every test context issues the cookie
the deployed service does. The session timeout stays in the main
`application.yaml` (`SESSION_TIMEOUT`). Set `SESSION_COOKIE_SECURE=true` when
the service is served over HTTPS.

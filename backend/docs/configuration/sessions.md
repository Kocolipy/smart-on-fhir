---
type: Configuration Reference
title: Session storage
description: Why Spring Session's indexed Redis repository is required, and what that means for a deployment's Redis.
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

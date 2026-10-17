---
type: Deployment Reference
title: Database monitoring
description: Enhanced Monitoring, Performance Insights, and the deadlock and blocked-session alarms.
tags: [rds, monitoring, alarms]
---

# Database monitoring

- **Enhanced Monitoring** every 60 seconds (`RDSMonitoringRole`).
- **Performance Insights**, 7 days' retention, encrypted under the database key.
- **Alarms**, both on SQL Server's own performance counters, which RDS for SQL
  Server publishes through Performance Insights only — the `AWS/RDS` CloudWatch
  namespace has no deadlock or blocking metric for it — read through
  CloudWatch's `DB_PERF_INSIGHTS` metric math. Each fires only when **every
  minute of five** is above zero, and both notify the stack's notification
  target, the SNS topic `AlarmTopicArn`, on alarm and on recovery:

  | Alarm (stack output)         | Performance Insights counter              | Fires on                                   |
  | ---------------------------- | ----------------------------------------- | ------------------------------------------ |
  | `DBDeadlocksAlarmName`       | `db.Locks.Number of Deadlocks/sec`        | deadlocks in every minute for 5 minutes    |
  | `DBBlockedSessionsAlarmName` | `db.General Statistics.Processes blocked` | blocked sessions in every minute for 5 min |

  Both need Performance Insights on (the stack turns it on), and neither needs
  Enhanced Monitoring or a custom metric. `smoke.sh` checks that the instance's
  Performance Insights offers both counters
  (`aws pi list-available-resource-metrics`), so a renamed counter fails the
  deploy rather than leaving an alarm on a metric that never arrives. Missing
  data is not treated as OK: a counter that stops arriving shows as
  `INSUFFICIENT_DATA`, not as a quiet `OK`. Give `AlarmEmail` to subscribe an
  address (AWS mails a confirmation link first), or subscribe any other endpoint
  to `AlarmTopicArn`.

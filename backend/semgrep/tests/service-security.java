// Rule-test fixture for semgrep/rules/service-security.yml, run by `semgrep --test` from
// scripts/semgrep.sh. It is not compiled and .semgrepignore keeps it out of the scan itself.
// Each `ruleid:` line is a record built outside LogEvent that the named rule must report; each
// `ok:` line is a call site the rules must leave alone.
package com.example.backend.fixture;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditPasswordChangeRefusal;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.audit.domain.AuditSessionRevocationCause;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.AuditUserAttribute;
import com.example.backend.audit.domain.Audited;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

class PlantedRecords {

    private static final Logger log = LoggerFactory.getLogger(PlantedRecords.class);

    void openedBelowError() {
        // ruleid: be-log-record-outside-log-event
        log.atInfo().log();
        // ruleid: be-log-record-outside-log-event
        log.atWarn().log();
        // ruleid: be-log-record-outside-log-event
        log.atDebug().log();
        // ruleid: be-log-record-outside-log-event
        log.atTrace().log();
        // ruleid: be-log-record-outside-log-event
        log.info("planted");
        // ruleid: be-log-record-outside-log-event
        log.warn("planted");
        // ruleid: be-log-record-outside-log-event
        log.debug("planted");
        // ruleid: be-log-record-outside-log-event
        log.trace("planted");
    }

    void openedAtError() {
        // ruleid: be-log-record-outside-log-event, be-log-error-without-error-fields
        log.atError().log();
        // ruleid: be-log-record-outside-log-event, be-log-error-without-error-fields
        log.error("planted");
        // ruleid: be-log-record-outside-log-event, be-log-error-without-error-fields
        log.atLevel(Level.ERROR).log();
    }

    void openedAnotherWay() {
        // ruleid: be-log-record-outside-log-event
        log.makeLoggingEventBuilder(Level.INFO).log();
    }

    void aShapesFieldsSetByHand() {
        // ruleid: be-log-record-outside-log-event
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER)
                .addKeyValue(LogEvent.OUTCOME, LogEvent.FAILURE)
                .log();
        // ruleid: be-log-record-outside-log-event
        LogEvent.refused(log, Operation.LOGIN, Category.PROCESS, Type.USER)
                .addKeyValue(LogEvent.DURATION_MS, 1L)
                .log();
        // ruleid: be-log-record-outside-log-event
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER)
                .setMessage("planted")
                .log();
        // ruleid: be-log-record-outside-log-event
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER).log("planted");
    }

    void builtByAShape() {
        // ok: be-log-record-outside-log-event
        LogEvent.success(log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.ALLOWED).log();
        // ok: be-log-record-outside-log-event
        LogEvent.refused(log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.DENIED)
                .addKeyValue(LogEvent.REASON, "planted")
                .log();
        // ok: be-log-error-without-error-fields
        LogEvent.error(log, Operation.AUDIT_APPEND, 500, ErrorCategory.DATABASE,
                        Category.DATABASE, Type.ERROR)
                .log();
        // ok: be-log-record-outside-log-event
        LogEvent.deadlockRetried(log, Operation.SCIM_WRITE).log();
        // ok: be-log-record-outside-log-event
        LogEvent.withError(LogEvent.refused(log, Operation.HTTP_REQUEST_REFUSAL,
                        Category.PROCESS, Type.DENIED), 400, ErrorCategory.DATA, false)
                .log();
    }
}

class PlantedAuditEvents {

    private AuditTrail audit;

    void credentialNamedValuesReachAnEvent(String userName, String password, Object token) {
        // ruleid: be-audit-sensitive-value
        audit.append(new Audited.LoginFailure(userName, AuditRefusalReason.BAD_CREDENTIALS,
                AuditLoginMethod.PASSWORD));
        // ruleid: be-audit-sensitive-value
        Audited built = new Audited.ConnectorTokenRevoked(actor, token.connectorId());
        // ruleid: be-audit-sensitive-value
        audit.append(passwordChangedEvent);
    }

    void stableIdsAndClosedSetsDoNot(java.util.UUID subjectId, java.util.UUID actor,
            String password) {
        // ok: be-audit-sensitive-value
        audit.append(new Audited.LoginFailure(subjectId, AuditRefusalReason.BAD_CREDENTIALS,
                AuditLoginMethod.PASSWORD));
        // ok: be-audit-sensitive-value
        audit.append(new Audited.PasswordChangeRefused(subjectId,
                AuditPasswordChangeRefusal.BAD_CURRENT_PASSWORD));
        // ok: be-audit-sensitive-value
        audit.append(new Audited.Logout(actor));
        // ok: be-audit-sensitive-value
        audit.append(new Audited.PasswordChanged(subjectId));
        // ok: be-audit-sensitive-value
        audit.append(new Audited.ConnectorTokenRevoked(actor, subjectId));
        // ok: be-audit-sensitive-value
        audit.append(new Audited.UserSessionsRevoked(actor, subjectId,
                Set.of(AuditUserAttribute.USER_NAME, AuditUserAttribute.PASSWORD),
                Set.of(AuditSessionRevocationCause.PASSWORD_CHANGED), true));
        // ok: be-audit-sensitive-value
        new StringBuilder().append(password);
    }
}

class PlantedTimestampBinds {

    private java.sql.PreparedStatement statement;

    void bindAJvmLocalWallClock(java.time.Instant instant, long millis) throws Exception {
        // ruleid: be-jdbc-timestamp-bind
        jdbc.update("DELETE FROM audit_events WHERE occurred_at < ?", Timestamp.from(instant));
        // ruleid: be-jdbc-timestamp-bind
        jdbc.update("DELETE FROM audit_events WHERE occurred_at < ?", new Timestamp(millis));
        // ruleid: be-jdbc-timestamp-bind
        Object parsed = Timestamp.valueOf("2026-10-14 09:30:00");
        // ruleid: be-jdbc-timestamp-bind
        statement.setTimestamp(1, null);
        // ruleid: be-jdbc-timestamp-bind
        parameters.addValue("from", null, Types.TIMESTAMP);
        // ruleid: be-jdbc-timestamp-bind
        Object qualified = java.sql.Timestamp.from(instant);
    }

    void bindAnOffsetAtUtcAndReadEitherWay(java.time.Instant instant, java.sql.ResultSet row)
            throws Exception {
        // ok: be-jdbc-timestamp-bind
        jdbc.update("DELETE FROM audit_events WHERE occurred_at < ?",
                instant.atOffset(java.time.ZoneOffset.UTC));
        // ok: be-jdbc-timestamp-bind
        parameters.addValue("from", instant.atOffset(java.time.ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE);
        // ok: be-jdbc-timestamp-bind
        java.time.Instant read = row.getTimestamp("occurred_at").toInstant();
        // ok: be-jdbc-timestamp-bind
        java.time.OffsetDateTime at = row.getObject("occurred_at", java.time.OffsetDateTime.class);
    }
}

class PlantedPatientValues {

    private static final Logger log = LoggerFactory.getLogger(PlantedPatientValues.class);

    void nricNamedValuesReachARecord(Patient patient, String nric, String patientNric) {
        // ruleid: be-log-sensitive-value
        LogEvent.success(log, Operation.PATIENT_SEED, Category.DATABASE, Type.CREATION)
                .addKeyValue("patient.nric", patient.nric())
                .log();
        // ruleid: be-log-sensitive-value
        LogEvent.success(log, Operation.PATIENT_SEED, Category.DATABASE, Type.CREATION)
                .addKeyValue(LogEvent.REASON, nric)
                .log();
        // ruleid: be-log-sensitive-value
        LogEvent.success(log, Operation.PATIENT_SEED, Category.DATABASE, Type.CREATION)
                .addKeyValue(LogEvent.REASON, patientNric)
                .log();
        // ruleid: be-log-sensitive-value
        LogEvent.success(log, Operation.PATIENT_SEED, Category.DATABASE, Type.CREATION)
                .addKeyValue(LogEvent.REASON, NRIC_VALUE)
                .log();
    }

    void theIdAndAnEnrichedValueDoNot(Patient patient, String enrichment) {
        // ok: be-log-sensitive-value
        LogEvent.success(log, Operation.PATIENT_SEED, Category.DATABASE, Type.CREATION)
                .addKeyValue("patient.id", patient.id())
                .log();
        // ok: be-log-sensitive-value
        LogEvent.success(log, Operation.PATIENT_SEED, Category.DATABASE, Type.CREATION)
                .addKeyValue(LogEvent.REASON, enrichment)
                .log();
    }
}

// Rule-test fixture for semgrep/rules/service-security.yml, run by `semgrep --test` from
// scripts/semgrep.sh. It is not compiled and .semgrepignore keeps it out of the scan itself.
// Each `ruleid:` line is a record built outside LogEvent that the named rule must report; each
// `ok:` line is a call site the rules must leave alone.
package com.example.backend.fixture;

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
        LogEvent.jobSummary(log, Operation.AUDIT_RETENTION).log();
        // ok: be-log-record-outside-log-event
        LogEvent.withError(LogEvent.refused(log, Operation.HTTP_REQUEST_REFUSAL,
                        Category.PROCESS, Type.DENIED), 400, ErrorCategory.DATA, false)
                .log();
    }
}

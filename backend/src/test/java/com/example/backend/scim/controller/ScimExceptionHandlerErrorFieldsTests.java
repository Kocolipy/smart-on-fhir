package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.RedactedFaultException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Every {@code ERROR} the SCIM advice writes carries the {@code Log_Schema.md} §Error
 * classification and marks its request's fault as recorded, so the request record does not
 * report it a second time; a {@code WARN} refusal does neither. The responses themselves are
 * the other handler tests' concern, and are unchanged.
 */
class ScimExceptionHandlerErrorFieldsTests {

    private final ScimExceptionHandler handler = new ScimExceptionHandler(new RecordingAuditTrail());

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/scim/v2/Me");

    private CapturedLog logs;

    @BeforeEach
    void setUp() {
        logs = CapturedLog.attach();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        logs.close();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aServerSideRefusalIsAClassifiedApplicationErrorAndMarksTheFault() {
        handler.handle(ScimErrorException.notImplemented("/Me is not implemented."));

        ILoggingEvent record = onlyRecord();
        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, 501)
                .containsEntry(LogEvent.ERROR_CATEGORY, "application")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 501);
        assertThat(faultRecorded()).isTrue();
    }

    @Test
    void anIntegrityViolationIsAClassifiedDatabaseErrorAndMarksTheFault() {
        DataIntegrityViolationException violation =
                new DataIntegrityViolationException("x", new java.sql.SQLException("y"));

        handler.handle(violation);

        ILoggingEvent record = onlyRecord();
        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, 500)
                .containsEntry(LogEvent.ERROR_CATEGORY, "database")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                .containsEntry(LogEvent.REASON, "SQLException");
        assertThat(record.getThrowableProxy().getClassName())
                .isEqualTo(RedactedFaultException.class.getName());
        assertThat(record.getThrowableProxy().getMessage()).isEqualTo("SQLException");
        assertThat(record.getThrowableProxy().getStackTraceElementProxyArray())
                .hasSize(violation.getStackTrace().length);
        assertThat(faultRecorded()).isTrue();
    }

    @Test
    void aCallersRefusalIsUnclassifiedAndLeavesTheRequestUnmarked() {
        handler.handle(ScimErrorException.notFound("No User has that id."));

        ILoggingEvent record = onlyRecord();
        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(CapturedLog.fields(record)).doesNotContainKeys(
                LogEvent.ERROR_CODE, LogEvent.ERROR_CATEGORY, LogEvent.ERROR_FOLLOW_UP_ACTION);
        assertThat(faultRecorded()).isFalse();
    }

    private ILoggingEvent onlyRecord() {
        List<ILoggingEvent> records = logs.withAction(Level.TRACE, LogEvent.KIND, "event");
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    private boolean faultRecorded() {
        return Boolean.TRUE.equals(
                request.getAttribute("com.example.backend.observability.RequestFault.recorded"));
    }
}

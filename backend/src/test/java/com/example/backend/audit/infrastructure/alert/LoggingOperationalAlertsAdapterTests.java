package com.example.backend.audit.infrastructure.alert;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.observability.LogEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The audit-append alert as its record: one {@code ERROR}, classified as a database error
 * needing follow-up, and saying why it attaches no exception.
 */
class LoggingOperationalAlertsAdapterTests {

    @Test
    void anAppendFailureIsOneClassifiedErrorThatSaysWhyItCarriesNoException() {
        try (CapturedLog logs = CapturedLog.attach()) {
            new LoggingOperationalAlertsAdapter().auditAppendFailed(
                    AuditOperation.values()[0], DataAccessResourceFailureException.class);

            List<ILoggingEvent> records =
                    logs.withAction(Level.TRACE, LogEvent.LOCAL_ACTION, "audit.append");
            assertThat(records).hasSize(1);
            ILoggingEvent record = records.getFirst();
            assertThat(record.getLevel()).isEqualTo(Level.ERROR);
            assertThat(record.getThrowableProxy()).isNull();
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.ERROR_CODE, 500)
                    .containsEntry(LogEvent.ERROR_CATEGORY, "database")
                    .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                    .containsEntry(LogEvent.SEVERITY, "high")
                    .containsEntry(LogEvent.OUTCOME, "failure")
                    .containsEntry(LogEvent.REASON, "DataAccessResourceFailureException")
                    .containsEntry(LogEvent.AUDIT_OPERATION, AuditOperation.values()[0].name())
                    .containsEntry(LogEvent.ERROR_CAUSE_OMITTED,
                            LoggingOperationalAlertsAdapter.CAUSE_OMITTED)
                    .containsEntry(LogEvent.CATEGORY, List.of("database"))
                    .containsEntry(LogEvent.TYPE, List.of("error"));
            assertThat(LoggingOperationalAlertsAdapter.CAUSE_OMITTED)
                    .contains("type", "never the exception");
        }
    }

    /**
     * A session store that could not end a User's sessions is one high-severity {@code ERROR}
     * about the session end, naming the failure's type — the sessions it was meant to end may
     * still be live, whatever the request it followed answered.
     */
    @Test
    void aSessionRevocationFailureIsOneClassifiedErrorAboutTheSessionEnd() {
        try (CapturedLog logs = CapturedLog.attach()) {
            new LoggingOperationalAlertsAdapter().sessionRevocationFailed(
                    DataAccessResourceFailureException.class);

            List<ILoggingEvent> records =
                    logs.withAction(Level.TRACE, LogEvent.ACTION, "session-end");
            assertThat(records).hasSize(1);
            ILoggingEvent record = records.getFirst();
            assertThat(record.getLevel()).isEqualTo(Level.ERROR);
            assertThat(record.getFormattedMessage())
                    .isEqualTo("Sessions could not be ended; they may still be live");
            assertThat(record.getThrowableProxy()).isNull();
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.ERROR_CODE, 500)
                    .containsEntry(LogEvent.ERROR_CATEGORY, "database")
                    .containsEntry(LogEvent.SEVERITY, "high")
                    .containsEntry(LogEvent.OUTCOME, "failure")
                    .containsEntry(LogEvent.REASON, "DataAccessResourceFailureException")
                    .containsEntry(LogEvent.ERROR_CAUSE_OMITTED,
                            LoggingOperationalAlertsAdapter.CAUSE_OMITTED)
                    .containsEntry(LogEvent.TYPE, List.of("error"));
        }
    }
}

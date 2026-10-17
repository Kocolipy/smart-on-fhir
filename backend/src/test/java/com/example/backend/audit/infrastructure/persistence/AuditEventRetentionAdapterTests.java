package com.example.backend.audit.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ScriptedJdbc;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One retention batch at the driver's boundary: how many events it asks the server to delete,
 * by which cutoff, and how long it lets the statement run. That a batch really deletes, as the
 * retention login and without escalating, is {@code AuditRetentionSerializationIntegrationTests}'.
 */
class AuditEventRetentionAdapterTests {

    private static final String DELETE = "DELETE TOP (?) FROM audit_events";

    private static final Instant CUTOFF = Instant.parse("2025-09-25T03:30:00Z");

    private final ScriptedJdbc database = new ScriptedJdbc();

    private AuditEventRetentionAdapter adapter() {
        return new AuditEventRetentionAdapter(new JdbcTemplate(database.dataSource()));
    }

    @Test
    void aBatchReportsHowManyEventsTheServerDeleted() {
        database.update(DELETE, 1234);

        assertThat(adapter().deleteBatchOccurredBefore(CUTOFF)).isEqualTo(1234);
    }

    /**
     * At most 4,000 events, recorded before the cutoff, which is bound as an offset at UTC so the
     * same events age out in any JVM zone (ADR 0013, "Storage is UTC only").
     */
    @Test
    void aBatchDeletesAtMostFourThousandEventsRecordedBeforeTheCutoff() {
        database.update(DELETE, 0);

        adapter().deleteBatchOccurredBefore(CUTOFF);

        assertThat(database.executed()).singleElement()
                .extracting(ScriptedJdbc.Executed::arguments)
                .isEqualTo(List.of(4000, OffsetDateTime.of(2025, 9, 25, 3, 30, 0, 0, ZoneOffset.UTC)));
    }

    /** A batch that cannot finish within 60 s fails the run (ADR 0013, "Bounded timeouts"). */
    @Test
    void aBatchTimesOutAfterSixtySeconds() {
        database.update(DELETE, 0);

        adapter().deleteBatchOccurredBefore(CUTOFF);

        assertThat(database.executed()).singleElement()
                .extracting(ScriptedJdbc.Executed::queryTimeoutSeconds)
                .isEqualTo(60);
    }
}

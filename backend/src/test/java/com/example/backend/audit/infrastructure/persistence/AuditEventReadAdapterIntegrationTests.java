package com.example.backend.audit.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventPage;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The read adapter on its own, against the real Postgres, constructed by the test rather than taken
 * from the context — so the construction itself is something a test observes, not a side effect of
 * a context another test class may already have built.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class AuditEventReadAdapterIntegrationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID actor = UUID.randomUUID();

    private final Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(1, ChronoUnit.HOURS);

    @Test
    void readsTheStoredRowsOfAFreshlyConstructedAdapter() {
        UUID older = insert(at.minusSeconds(1), "active,password");
        UUID newer = insert(at, null);

        AuditEventPage page = new AuditEventReadAdapter(dataSource).find(query(0, 10));

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.events()).extracting(AuditEvent::id).containsExactly(newer, older);
        AuditEvent event = page.events().get(1);
        assertThat(event.occurredAt()).isEqualTo(at.minusSeconds(1));
        assertThat(event.operation()).isEqualTo(AuditOperation.LOCKOUT_SET);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.actorId()).isEqualTo(actor);
        assertThat(event.changedPaths()).containsExactly("active", "password");
        assertThat(page.events().get(0).changedPaths()).isEmpty();
    }

    /** A page past the end holds nothing, while the total still counts every match. */
    @Test
    void aPagePastTheEndIsEmptyAndStillCountsTheMatches() {
        insert(at, null);
        insert(at.minusSeconds(1), null);

        AuditEventReadAdapter adapter = new AuditEventReadAdapter(dataSource);

        assertThat(adapter.find(query(1, 1)).events()).hasSize(1);
        AuditEventPage past = adapter.find(query(2, 1));
        assertThat(past.events()).isEmpty();
        assertThat(past.totalElements()).isEqualTo(2);
        assertThat(past.totalPages()).isEqualTo(2);
    }

    private AuditEventQuery query(int page, int size) {
        return new AuditEventQuery(null, null, actor, null, null, null, page, size);
    }

    private UUID insert(Instant occurredAt, String changedPaths) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_events (id, occurred_at, operation, outcome, actor_id,
                                          resource_type, changed_paths, status_class)
                VALUES (?, ?, 'LOCKOUT_SET', 'SUCCESS', ?, 'User', ?, 'client_error')
                """, id, Timestamp.from(occurredAt), actor, changedPaths);
        return id;
    }
}

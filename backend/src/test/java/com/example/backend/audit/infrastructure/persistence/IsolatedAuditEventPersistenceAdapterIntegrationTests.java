package com.example.backend.audit.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.audit.domain.AuditAdministrativeRefusal;
import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import com.example.backend.database.ConnectionQualifiers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The fail-open append adapter on its own, against the real SQL Server and the audit append pool,
 * constructed by the test rather than taken from the context — so its construction, and every
 * value its {@code INSERT} binds, is something a test observes. Read back through the listing's
 * own adapter: what a fail-open event recorded is what an administrator would see.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class IsolatedAuditEventPersistenceAdapterIntegrationTests {

    @Autowired
    @Qualifier(ConnectionQualifiers.AUDIT_APPEND_JDBC)
    private JdbcTemplate auditPool;

    @Autowired
    private DataSource dataSource;

    @Test
    void aFailOpenAppendStoresEveryFieldOfTheEvent() {
        UUID actor = UUID.randomUUID();
        AuditEvent event = new AuditEvent(
                UUID.fromString("6f1c2a4e-3b5d-4e7f-8a9b-0c1d2e3f4a5b"),
                Instant.parse("2026-10-13T08:15:30.123456Z"),
                AuditOperation.CONNECTOR_TOKEN_ROTATE,
                AuditOutcome.FAILURE,
                actor,
                UUID.fromString("11111111-2222-4333-8444-555555555555"),
                AuditEvent.CONNECTOR_RESOURCE_TYPE,
                UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa"),
                List.of("active", "displayName"),
                AuditEvent.STATUS_CLIENT_ERROR,
                AuditAdministrativeRefusal.TOKEN_ALREADY_ROTATED.name(),
                "POST",
                "/api/admin/connectors/{id}",
                "req-4f81",
                7,
                "displayName eq ?",
                List.of("user:read", "group:write"),
                "sso",
                "otp",
                UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"),
                UUID.fromString("01234567-89ab-4cde-8f01-23456789abcd"),
                List.of("audit:read", "user:read"),
                List.of("user:read"),
                true,
                List.of(UUID.fromString("0aaaaaaa-0000-4000-8000-000000000001"),
                        UUID.fromString("0bbbbbbb-0000-4000-8000-000000000002")));

        new IsolatedAuditEventPersistenceAdapter(auditPool).append(event);

        assertThat(new AuditEventReadAdapter(dataSource)
                        .find(new AuditEventQuery(null, null, actor, null, null, null, 0, 10))
                        .events())
                .containsExactly(event);
    }
}

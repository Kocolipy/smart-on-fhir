package com.example.backend.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.paging.domain.PageRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventQueryTests {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant TO = Instant.parse("2026-02-01T00:00:00Z");

    @Test
    void keepsEveryFilterAndThePageItWasGiven() {
        AuditEventQuery query = new AuditEventQuery(
                AuditOperation.LOGIN_FAILURE, AuditOutcome.FAILURE, ACTOR, RESOURCE, FROM, TO,
                new PageRequest(3, 7));

        assertThat(query.operation()).isEqualTo(AuditOperation.LOGIN_FAILURE);
        assertThat(query.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(query.actorId()).isEqualTo(ACTOR);
        assertThat(query.resourceId()).isEqualTo(RESOURCE);
        assertThat(query.from()).isEqualTo(FROM);
        assertThat(query.to()).isEqualTo(TO);
        assertThat(query.page()).isEqualTo(new PageRequest(3, 7));
    }
}

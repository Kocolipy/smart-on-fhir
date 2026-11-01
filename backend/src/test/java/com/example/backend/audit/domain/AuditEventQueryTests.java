package com.example.backend.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.paging.domain.PageRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
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
                Set.of(AuditOperation.LOGIN_FAILURE), AuditOutcome.FAILURE, ACTOR, RESOURCE, FROM, TO,
                new PageRequest(3, 7));

        assertThat(query.operations()).containsExactly(AuditOperation.LOGIN_FAILURE);
        assertThat(query.outcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(query.actorId()).isEqualTo(ACTOR);
        assertThat(query.resourceId()).isEqualTo(RESOURCE);
        assertThat(query.from()).isEqualTo(FROM);
        assertThat(query.to()).isEqualTo(TO);
        assertThat(query.page()).isEqualTo(new PageRequest(3, 7));
    }

    @Test
    void noSetOfOperationsNamesNone() {
        assertThat(withOperations(null).operations()).isEmpty();
    }

    /** An empty value in the request binds to a null element, which names no operation. */
    @Test
    void aNullOperationAmongOthersNamesNone() {
        Set<AuditOperation> asked = new HashSet<>(Arrays.asList(AuditOperation.LOGOUT, null));

        assertThat(withOperations(asked).operations()).containsExactly(AuditOperation.LOGOUT);
    }

    @Test
    void theOperationsHeldCannotBeChangedAfterwards() {
        Set<AuditOperation> asked = new HashSet<>(Set.of(AuditOperation.LOGOUT));

        AuditEventQuery query = withOperations(asked);
        asked.add(AuditOperation.LOCKOUT_SET);

        assertThat(query.operations()).containsExactly(AuditOperation.LOGOUT);
        assertThatThrownBy(() -> query.operations().add(AuditOperation.LOCKOUT_SET))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static AuditEventQuery withOperations(Set<AuditOperation> operations) {
        return new AuditEventQuery(operations, null, null, null, null, null, new PageRequest(1, 1));
    }
}

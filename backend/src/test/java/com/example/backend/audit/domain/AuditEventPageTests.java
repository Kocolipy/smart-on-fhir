package com.example.backend.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.paging.domain.PageRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventPageTests {

    private static final AuditEvent EVENT = new AuditEvent(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Instant.parse("2026-01-01T00:00:00Z"),
            AuditOperation.LOGIN_SUCCESS,
            AuditOutcome.SUCCESS,
            null, null, AuditEvent.USER_RESOURCE_TYPE, null, List.of(),
            AuditEvent.STATUS_OK, null, null, null, null, null, null);

    @Test
    void carriesWhereTheQueryAskedToStartAndTheTotal() {
        AuditEventPage page = AuditEventPage.of(query(11, 10), List.of(EVENT), 21);

        assertThat(page.totalResults()).isEqualTo(21);
        assertThat(page.startIndex()).isEqualTo(11);
        assertThat(page.events()).containsExactly(EVENT);
    }

    /** The page is a snapshot: the list it was built from cannot change it afterwards. */
    @Test
    void theEventsAreCopiedAndUnmodifiable() {
        List<AuditEvent> source = new ArrayList<>(List.of(EVENT));
        AuditEventPage page = AuditEventPage.of(query(1, 10), source, 1);

        source.clear();

        assertThat(page.events()).containsExactly(EVENT);
        assertThatThrownBy(() -> page.events().add(EVENT))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static AuditEventQuery query(int startIndex, int count) {
        return new AuditEventQuery(
                null, null, null, null, null, null, new PageRequest(startIndex, count));
    }
}

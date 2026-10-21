package com.example.backend.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void carriesThePageTheQueryAskedForAndTheTotal() {
        AuditEventPage page = AuditEventPage.of(query(2, 10), List.of(EVENT), 21);

        assertThat(page.events()).containsExactly(EVENT);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(21);
    }

    @Test
    void aPartialLastPageStillCountsAsAPage() {
        assertThat(AuditEventPage.of(query(0, 10), List.of(), 21).totalPages()).isEqualTo(3);
        assertThat(AuditEventPage.of(query(0, 10), List.of(), 1).totalPages()).isEqualTo(1);
    }

    @Test
    void anExactMultipleFillsItsPagesAndNoMore() {
        assertThat(AuditEventPage.of(query(0, 10), List.of(), 20).totalPages()).isEqualTo(2);
        assertThat(AuditEventPage.of(query(0, 1), List.of(), 5).totalPages()).isEqualTo(5);
    }

    @Test
    void nothingMatchingIsNoPages() {
        assertThat(AuditEventPage.of(query(0, 10), List.of(), 0).totalPages()).isZero();
    }

    /** The page is a snapshot: the list it was built from cannot change it afterwards. */
    @Test
    void theEventsAreCopiedAndUnmodifiable() {
        List<AuditEvent> source = new ArrayList<>(List.of(EVENT));
        AuditEventPage page = AuditEventPage.of(query(0, 10), source, 1);

        source.clear();

        assertThat(page.events()).containsExactly(EVENT);
        assertThatThrownBy(() -> page.events().add(EVENT))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static AuditEventQuery query(int page, int size) {
        return new AuditEventQuery(null, null, null, null, null, null, page, size);
    }
}

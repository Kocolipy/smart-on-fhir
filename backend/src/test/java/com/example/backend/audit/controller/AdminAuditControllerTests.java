package com.example.backend.audit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.application.AuditEventListingService;
import com.example.backend.audit.domain.AuditEventPage;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import com.example.backend.paging.domain.PageRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminAuditControllerTests {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant TO = Instant.parse("2026-02-01T00:00:00Z");

    private final List<AuditEventQuery> asked = new ArrayList<>();

    private final AdminAuditController controller = new AdminAuditController(
            new AuditEventListingService(query -> {
                asked.add(query);
                return AuditEventPage.of(query, List.of(), 0);
            }));

    @Test
    void passesEveryFilterAndThePageToTheListing() {
        AuditEventPage page = controller.list(
                AuditOperation.PASSWORD_CHANGE, AuditOutcome.FAILURE, ACTOR, RESOURCE, FROM, TO, 4, 25);

        assertThat(asked).containsExactly(new AuditEventQuery(
                AuditOperation.PASSWORD_CHANGE, AuditOutcome.FAILURE, ACTOR, RESOURCE, FROM, TO,
                new PageRequest(4, 25)));
        assertThat(page.startIndex()).isEqualTo(4);
    }

    /** The coercion's table is {@code PageRequestTests}'; this proves the listing goes through it. */
    @Test
    void outOfRangePagingIsCoercedBeforeTheListingIsAsked() {
        controller.list(null, null, null, null, null, null, -3, 201);

        assertThat(asked).extracting(AuditEventQuery::page).containsExactly(new PageRequest(1, 200));
    }

    @Test
    void absentPagingIsTheDefaultFirstPage() {
        controller.list(null, null, null, null, null, null, null, null);

        assertThat(asked).extracting(AuditEventQuery::page).containsExactly(new PageRequest(1, 100));
    }
}

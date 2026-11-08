package com.example.backend.audit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.backend.audit.application.AuditEventListingService;
import com.example.backend.audit.domain.AuditEventPage;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import com.example.backend.paging.domain.PageParameter;
import com.example.backend.paging.domain.PageRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
                List.of(AuditOperation.PASSWORD_CHANGE, AuditOperation.LOGOUT),
                AuditOutcome.FAILURE, ACTOR, RESOURCE, FROM, TO,
                PageParameter.valueOf("4"), PageParameter.valueOf("25"));

        assertThat(asked).containsExactly(new AuditEventQuery(
                Set.of(AuditOperation.PASSWORD_CHANGE, AuditOperation.LOGOUT),
                AuditOutcome.FAILURE, ACTOR, RESOURCE, FROM, TO,
                new PageRequest(4, 25)));
        assertThat(page.startIndex()).isEqualTo(4);
    }

    /** The refusal is the binder's, so the listing is never asked. */
    @Test
    void oneUnknownOperationAmongValidOnesRefusesTheRequestAndReadsNothing() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        int status = mvc.perform(get("/api/admin/audit-events")
                        .param("operation", "LOGOUT", "NOT_AN_OPERATION"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(400);
        assertThat(asked).isEmpty();
    }

    @Test
    void repeatedOperationsReachTheListingOnce() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/admin/audit-events")
                .param("operation", "LOGOUT", "LOCKOUT_SET", "LOGOUT"));

        assertThat(asked).extracting(AuditEventQuery::operations)
                .containsExactly(Set.of(AuditOperation.LOGOUT, AuditOperation.LOCKOUT_SET));
    }

    @Test
    void noOperationAsksForEveryOperation() {
        controller.list(null, null, null, null, null, null, null, null);

        assertThat(asked).extracting(AuditEventQuery::operations).containsExactly(Set.of());
    }
}

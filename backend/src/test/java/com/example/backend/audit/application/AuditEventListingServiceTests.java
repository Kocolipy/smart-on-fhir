package com.example.backend.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.domain.AuditEventPage;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditEventReader;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.paging.domain.PageRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditEventListingServiceTests {

    @Test
    void answersWithThePageTheReaderFoundForTheQueryAsked() {
        AuditEventQuery query = new AuditEventQuery(
                AuditOperation.LOCKOUT_SET, null, null, null, null, null, new PageRequest(6, 5));
        AuditEventPage found = AuditEventPage.of(query, List.of(), 6);
        List<AuditEventQuery> asked = new ArrayList<>();
        AuditEventReader reader = q -> {
            asked.add(q);
            return found;
        };

        AuditEventPage page = new AuditEventListingService(reader).list(query);

        assertThat(page).isSameAs(found);
        assertThat(asked).containsExactly(query);
    }
}

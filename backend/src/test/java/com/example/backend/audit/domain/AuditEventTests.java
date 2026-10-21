package com.example.backend.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The stored row's own shape: a list it is handed as nothing reads back as empty. */
class AuditEventTests {

    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-0000000f0001");

    private static final Instant AT = Instant.parse("2026-10-21T00:00:00Z");

    @Test
    void everyListHandedAsNullReadsBackEmpty() {
        AuditEvent event = new AuditEvent(ID, AT, AuditOperation.LOGIN_SUCCESS, AuditOutcome.SUCCESS,
                null, null, AuditEvent.USER_RESOURCE_TYPE, null, null, AuditEvent.STATUS_OK, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);

        assertThat(List.of(event.changedPaths(), event.permissions(), event.permissionsBefore(),
                        event.permissionsAfter(), event.groupIds()))
                .allSatisfy(list -> assertThat(list).isEmpty());
    }

    /** The short form is every event that is no token's, login's or membership change's. */
    @Test
    void theShortFormNamesNoMembershipChange() {
        AuditEvent event = new AuditEvent(ID, AT, AuditOperation.LOGIN_SUCCESS, AuditOutcome.SUCCESS,
                null, null, AuditEvent.USER_RESOURCE_TYPE, null, List.of(), AuditEvent.STATUS_OK,
                null, null, null, null, null, null);

        assertThat(event)
                .extracting(AuditEvent::permissionsBefore, AuditEvent::permissionsAfter,
                        AuditEvent::superuserChanged, AuditEvent::groupIds)
                .containsExactly(List.of(), List.of(), null, List.of());
    }
}

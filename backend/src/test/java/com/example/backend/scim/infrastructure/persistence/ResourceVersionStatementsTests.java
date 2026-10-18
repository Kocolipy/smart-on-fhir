package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.backend.scim.domain.ResourceVersionAdvance;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * How a decided {@link ResourceVersionAdvance} reaches the database: the statements issued, with
 * the repository mocked. Which resources a write advances is the domain module's, and pinned in
 * {@code ResourceVersionAdvanceTests}; these pin only the carrying-out.
 */
class ResourceVersionStatementsTests {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final ScimResourceJpaRepository resources = mock(ScimResourceJpaRepository.class);

    /** A repeated id is still one resource, advanced once. */
    @Test
    void each_resource_is_named_once_in_one_statement_at_the_writes_timestamp() {
        ResourceVersionStatements.advance(
                ResourceVersionAdvance.groupCreated(List.of(ALICE, BOB, ALICE), NOW), resources);

        verify(resources).advanceVersions(
                argThat(ids -> Set.copyOf(ids).equals(Set.of(ALICE, BOB)) && ids.size() == 2),
                eq(NOW));
        verifyNoMoreInteractions(resources);
    }

    /**
     * More resources than SQL Server binds in one request (2,100 parameters) go out as
     * statements of at most 2,000 ids each, together naming every resource exactly once — 4,500
     * ids are three statements: 2,000, 2,000 and 500.
     */
    @Test
    void an_advance_naming_more_than_one_request_binds_is_split_into_statements_of_2000() {
        List<UUID> members = IntStream.range(0, 4_500).mapToObj(i -> UUID.randomUUID()).toList();
        List<Collection<UUID>> statements = new ArrayList<>();
        when(resources.advanceVersions(anyCollection(), eq(NOW))).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            statements.add(List.copyOf(ids));
            return ids.size();
        });

        ResourceVersionStatements.advance(
                ResourceVersionAdvance.groupDeleted(members, NOW), resources);

        assertThat(statements).extracting(Collection::size).containsExactly(2_000, 2_000, 500);
        assertThat(statements.stream().flatMap(Collection::stream))
                .containsExactlyInAnyOrderElementsOf(members);
    }

    @Test
    void an_advance_naming_nothing_issues_no_statement() {
        ResourceVersionStatements.advance(
                ResourceVersionAdvance.groupDeleted(List.of(), NOW), resources);

        verifyNoInteractions(resources);
    }
}

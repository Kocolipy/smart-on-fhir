package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.domain.DuplicateConnectorNameException;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ScimConnectorRepository} does, whatever stores the connectors: the SQL Server
 * adapter, and the in-memory store the connector administration and authentication unit tests run
 * against. Each runs it, so a unit test passing against the in-memory store says something about
 * production.
 *
 * <p>The store may hold connectors no case wrote — the SQL Server database is shared with other
 * classes — so a listing is read back as the cases' own connectors only, and every name a case
 * writes ends in a suffix of its own, so no other class's live connector already holds it. Every
 * instant is in whole seconds, so it survives the {@code DATETIMEOFFSET(6)} columns unchanged.
 *
 * <p>A display name is held by at most one live connector, compared exactly — the database's
 * binary collation, so case counts — and a deleted connector holds none.
 */
public abstract class ScimConnectorRepositoryContract {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant DELETED = Instant.parse("2026-02-01T00:00:00Z");

    /** Ends every name this instance writes, so a shared database's other rows never hold one. */
    private final String suffix = " " + UUID.randomUUID();

    /** The connector port under test. */
    protected abstract ScimConnectorRepository connectors();

    @Test
    void a_saved_connector_is_read_back_by_id_as_given() {
        ScimConnector okta = connector("Okta");
        connectors().save(okta);

        assertThat(connectors().findById(okta.id())).contains(okta);
    }

    @Test
    void an_id_naming_no_connector_finds_nothing() {
        assertThat(connectors().findById(UUID.randomUUID())).isEmpty();
    }

    /** Saving under a stored id replaces the connector wholly: here, its deletion. */
    @Test
    void saving_a_stored_connector_again_replaces_it() {
        ScimConnector okta = connector("Okta");
        connectors().save(okta);

        connectors().save(okta.deleted(DELETED));

        assertThat(connectors().findById(okta.id())).contains(okta.deleted(DELETED));
    }

    /** A deleted connector is still found by id, so a caller can tell deleted from unknown. */
    @Test
    void a_deleted_connector_is_still_found_by_id() {
        ScimConnector okta = connector("Okta").deleted(DELETED);
        connectors().save(okta);

        assertThat(connectors().findById(okta.id()).map(ScimConnector::isDeleted)).contains(true);
    }

    @Test
    void the_listing_omits_a_deleted_connector() {
        ScimConnector live = connector("Live");
        ScimConnector gone = connector("Gone").deleted(DELETED);
        connectors().save(live);
        connectors().save(gone);

        assertThat(ours(live, gone)).containsExactly(live);
    }

    /**
     * Ordered by display name, compared code point by code point — the database's binary
     * collation, and Java's own comparison of these names — so upper case sorts before lower.
     */
    @Test
    void the_listing_is_ordered_by_display_name() {
        ScimConnector lowerAlpha = connector("alpha");
        ScimConnector beta = connector("Beta");
        ScimConnector upperAlpha = connector("Alpha");
        connectors().save(lowerAlpha);
        connectors().save(beta);
        connectors().save(upperAlpha);

        assertThat(ours(lowerAlpha, beta, upperAlpha))
                .containsExactly(upperAlpha, beta, lowerAlpha);
    }

    /** A second live connector under a live one's name: refused, as the unique index refuses it. */
    @Test
    void a_second_live_connector_under_a_live_name_is_refused() {
        connectors().save(connector("Okta"));

        assertThatThrownBy(() -> connectors().save(connector("Okta")))
                .isInstanceOf(DuplicateConnectorNameException.class);
    }

    /** Names are compared exactly, as the binary collation compares them: case tells two apart. */
    @Test
    void a_name_differing_only_in_case_is_another_name() {
        ScimConnector upper = connector("Epic");
        ScimConnector lower = connector("epic");
        connectors().save(upper);

        connectors().save(lower);

        assertThat(ours(upper, lower)).containsExactly(upper, lower);
    }

    /** A deleted connector holds no name: a new live connector may take it. */
    @Test
    void a_deleted_connectors_name_is_free_to_reuse() {
        ScimConnector gone = connector("Okta");
        connectors().save(gone);
        connectors().save(gone.deleted(DELETED));
        ScimConnector successor = connector("Okta");

        connectors().save(successor);

        assertThat(ours(gone, successor)).containsExactly(successor);
    }

    /** The live listing, as far as it names these connectors, in its order. */
    private List<ScimConnector> ours(ScimConnector... written) {
        Set<UUID> ids = Arrays.stream(written).map(ScimConnector::id).collect(Collectors.toSet());
        return connectors().findAllLiveOrderedByDisplayName().stream()
                .filter(connector -> ids.contains(connector.id()))
                .toList();
    }

    private ScimConnector connector(String displayName) {
        return ScimConnector.create(UUID.randomUUID(), displayName + suffix, CREATED);
    }
}

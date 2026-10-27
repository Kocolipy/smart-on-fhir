package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;

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
 * classes — so a listing is read back as the cases' own connectors only. Every instant is in whole
 * seconds, so it survives the {@code DATETIMEOFFSET(6)} columns unchanged.
 */
public abstract class ScimConnectorRepositoryContract {

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant DELETED = Instant.parse("2026-02-01T00:00:00Z");

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

    /** The live listing, as far as it names these connectors, in its order. */
    private List<ScimConnector> ours(ScimConnector... written) {
        Set<UUID> ids = Arrays.stream(written).map(ScimConnector::id).collect(Collectors.toSet());
        return connectors().findAllLiveOrderedByDisplayName().stream()
                .filter(connector -> ids.contains(connector.id()))
                .toList();
    }

    private static ScimConnector connector(String displayName) {
        return ScimConnector.create(UUID.randomUUID(), displayName, CREATED);
    }
}

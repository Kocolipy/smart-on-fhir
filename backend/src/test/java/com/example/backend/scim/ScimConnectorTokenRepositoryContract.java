package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.domain.ConnectorTokenDigest;
import com.example.backend.scim.domain.ConnectorTokenPermissions;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorRepository;
import com.example.backend.scim.domain.ScimConnectorToken;
import com.example.backend.scim.domain.ScimConnectorTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What every {@link ScimConnectorTokenRepository} does, whatever stores the tokens: the SQL Server
 * adapter, and the in-memory store the connector administration and authentication unit tests run
 * against. Each runs it.
 *
 * <p>A token belongs to a connector, which the SQL Server table holds it to by a foreign key, so
 * every case first saves its connector through {@link #connectors()}. Every instant is in whole
 * seconds, so it survives the {@code DATETIMEOFFSET(6)} columns unchanged.
 *
 * <p>A lookup id is unique: the authentication path finds a token by it alone. A second token
 * under one is refused with the exception the adapter's flush raises. What that refusal does to
 * the rest of the transaction — on SQL Server it can no longer commit — no in-memory store models,
 * so {@code SqlServerScimConnectorTokenRepositoryContractTests} asserts it against the adapter
 * alone.
 */
public abstract class ScimConnectorTokenRepositoryContract {

    private static final Instant ISSUED = Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant EXPIRES = ISSUED.plus(Duration.ofDays(90));

    private static final Instant REVOKED = ISSUED.plus(Duration.ofDays(1));

    /** The token port under test. */
    protected abstract ScimConnectorTokenRepository tokens();

    /** The connector port over the same store, which holds each token's connector. */
    protected abstract ScimConnectorRepository connectors();

    @Test
    void a_saved_token_is_read_back_by_id_as_given() {
        ScimConnectorToken token = token(givenConnector(), ISSUED);
        tokens().save(token);

        assertThat(tokens().findById(token.id())).contains(token);
    }

    @Test
    void a_saved_token_is_found_by_its_lookup_id() {
        ScimConnectorToken token = token(givenConnector(), ISSUED);
        tokens().save(token);

        assertThat(tokens().findByLookupId(token.lookupId())).contains(token);
    }

    @Test
    void a_lookup_id_naming_no_token_finds_nothing() {
        assertThat(tokens().findByLookupId(lookupId())).isEmpty();
    }

    @Test
    void an_id_naming_no_token_finds_nothing() {
        assertThat(tokens().findById(UUID.randomUUID())).isEmpty();
    }

    /** A revoked token is found and then refused by its caller, never hidden by the lookup. */
    @Test
    void a_revoked_token_is_still_found_by_its_lookup_id() {
        ScimConnectorToken token = token(givenConnector(), ISSUED).revoked(REVOKED);
        tokens().save(token);

        assertThat(tokens().findByLookupId(token.lookupId())).contains(token);
    }

    /** Saving under a stored id replaces the token wholly: here, a rotation's shortened expiry. */
    @Test
    void saving_a_stored_token_again_replaces_it() {
        UUID connector = givenConnector();
        ScimConnectorToken old = token(connector, ISSUED);
        ScimConnectorToken replacement = token(connector, ISSUED.plusSeconds(60));
        tokens().save(old);
        tokens().save(replacement);
        ScimConnectorToken rotated = old.expiringAt(ISSUED.plus(Duration.ofDays(1)), replacement.id());

        tokens().save(rotated);

        assertThat(tokens().findById(old.id())).contains(rotated);
    }

    /** Another token under a lookup id a stored token holds: refused, as the unique key refuses it. */
    @Test
    void a_second_token_under_a_stored_lookup_id_is_refused() {
        UUID connector = givenConnector();
        ScimConnectorToken first = token(connector, ISSUED);
        tokens().save(first);
        ScimConnectorToken second = ScimConnectorToken.issue(UUID.randomUUID(), connector,
                first.lookupId(), ConnectorTokenDigest.of("another value"), permissions(),
                ISSUED, EXPIRES);

        assertThatThrownBy(() -> tokens().save(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Every token the connector ever held, newest issue first, and no other connector's. */
    @Test
    void a_connectors_tokens_are_listed_newest_issue_first() {
        UUID connector = givenConnector();
        ScimConnectorToken middle = token(connector, ISSUED.plusSeconds(60));
        ScimConnectorToken oldest = token(connector, ISSUED);
        ScimConnectorToken newest = token(connector, ISSUED.plusSeconds(120)).revoked(REVOKED);
        tokens().save(middle);
        tokens().save(oldest);
        tokens().save(newest);
        tokens().save(token(givenConnector(), ISSUED.plusSeconds(90)));

        assertThat(tokens().findByConnectorIdOrderedByIssuedAtDesc(connector))
                .containsExactly(newest, middle, oldest);
    }

    @Test
    void a_connector_with_no_token_lists_none() {
        assertThat(tokens().findByConnectorIdOrderedByIssuedAtDesc(givenConnector())).isEmpty();
    }

    /** Revoking all counts the tokens it revoked: the connector's live ones. */
    @Test
    void revoking_a_connectors_tokens_counts_those_not_yet_revoked() {
        UUID connector = givenConnector();
        tokens().save(token(connector, ISSUED));
        tokens().save(token(connector, ISSUED.plusSeconds(60)));
        tokens().save(token(connector, ISSUED.plusSeconds(120)).revoked(REVOKED));

        assertThat(tokens().revokeAllForConnector(connector, REVOKED.plusSeconds(1))).isEqualTo(2);
    }

    /** Each live token is revoked at the instant given; one already revoked keeps its instant. */
    @Test
    void revoking_a_connectors_tokens_revokes_each_live_one_and_keeps_an_earlier_revocation() {
        UUID connector = givenConnector();
        ScimConnectorToken live = token(connector, ISSUED);
        ScimConnectorToken revoked = token(connector, ISSUED.plusSeconds(60)).revoked(REVOKED);
        tokens().save(live);
        tokens().save(revoked);
        Instant now = REVOKED.plusSeconds(1);

        tokens().revokeAllForConnector(connector, now);

        assertThat(tokens().findByConnectorIdOrderedByIssuedAtDesc(connector))
                .containsExactly(revoked, live.revoked(now));
    }

    @Test
    void revoking_a_connectors_tokens_leaves_another_connectors() {
        UUID connector = givenConnector();
        ScimConnectorToken others = token(givenConnector(), ISSUED);
        tokens().save(token(connector, ISSUED));
        tokens().save(others);

        tokens().revokeAllForConnector(connector, REVOKED);

        assertThat(tokens().findById(others.id())).contains(others);
    }

    /** A live connector saved through {@link #connectors()}, for a token to belong to. */
    private UUID givenConnector() {
        ScimConnector connector = ScimConnector.create(UUID.randomUUID(), "Okta", ISSUED);
        connectors().save(connector);
        return connector.id();
    }

    private static ScimConnectorToken token(UUID connectorId, Instant issuedAt) {
        String lookupId = lookupId();
        return ScimConnectorToken.issue(UUID.randomUUID(), connectorId, lookupId,
                ConnectorTokenDigest.of("scim_" + lookupId + "_secret"), permissions(), issuedAt,
                issuedAt.plus(Duration.ofDays(90)));
    }

    /** A token's Permissions, from the four the table admits. */
    private static ConnectorTokenPermissions permissions() {
        return new ConnectorTokenPermissions(Set.of(Permission.USER_READ, Permission.GROUP_WRITE));
    }

    private static String lookupId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

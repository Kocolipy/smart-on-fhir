package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.TokenPermissions;
import com.example.backend.auth.MutableClock;
import com.example.backend.scim.InMemoryScimConnectorRepository;
import com.example.backend.scim.InMemoryScimConnectorTokenRepository;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.ConnectorTokenSecret;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorToken;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a presented bearer value resolves to.
 *
 * <p>Every refusal below asserts the same empty result, which is the point: the
 * service reports "not accepted" and never which of the several reasons it was, so the
 * filter has nothing to leak even if it wanted to.
 */
class ConnectorAuthenticationServiceTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryScimConnectorRepository connectors =
            new InMemoryScimConnectorRepository();

    private final InMemoryScimConnectorTokenRepository tokens =
            new InMemoryScimConnectorTokenRepository();

    private final MutableClock clock = new MutableClock(NOW);

    private final SecureRandom random = new SecureRandom();

    private ConnectorAuthenticationService service;

    private ScimConnector connector;

    @BeforeEach
    void setUp() {
        service = new ConnectorAuthenticationService(connectors, tokens, clock);
        connector = connectors.seed("Okta", NOW);
    }

    @Test
    void a_live_token_resolves_to_its_connector_token_and_permissions() {
        String value = mint(TokenPermissions.ALL, NOW.plus(Duration.ofDays(365)));

        Optional<AuthenticatedConnector> authenticated = service.authenticate(value);

        assertThat(authenticated).isPresent();
        assertThat(authenticated.get().connectorId()).isEqualTo(connector.id());
        assertThat(authenticated.get().permissions()).isEqualTo(TokenPermissions.of(TokenPermissions.ALL));
        assertThat(authenticated.get().tokenId())
                .isEqualTo(tokens.all().getFirst().id());
    }

    @Test
    void a_read_only_token_resolves_with_its_read_permissions() {
        String value = mint(TokenPermissions.READ, NOW.plus(Duration.ofDays(365)));

        assertThat(service.authenticate(value))
                .get()
                .extracting(AuthenticatedConnector::permissions)
                .isEqualTo(TokenPermissions.of(TokenPermissions.READ));
    }

    @Test
    void an_expired_token_resolves_to_nothing() {
        String value = mint(TokenPermissions.ALL, NOW.plus(Duration.ofDays(1)));

        clock.advanceBy(Duration.ofDays(2));

        assertThat(service.authenticate(value)).isEmpty();
    }

    @Test
    void a_revoked_token_resolves_to_nothing() {
        String value = mint(TokenPermissions.ALL, NOW.plus(Duration.ofDays(365)));
        ScimConnectorToken stored = tokens.all().getFirst();
        tokens.save(stored.revoked(NOW));

        assertThat(service.authenticate(value)).isEmpty();
    }

    @Test
    void a_token_whose_connector_was_deleted_resolves_to_nothing() {
        String value = mint(TokenPermissions.ALL, NOW.plus(Duration.ofDays(365)));
        connectors.save(connector.deleted(NOW));

        assertThat(service.authenticate(value)).isEmpty();
    }

    /**
     * The secret half is what authenticates. A value carrying a real lookup id and a
     * wrong secret must be refused, or the lookup id alone — which is not secret and
     * appears in nothing that protects it — would be the credential.
     */
    @Test
    void a_real_lookup_id_with_a_wrong_secret_resolves_to_nothing() {
        String value = mint(TokenPermissions.ALL, NOW.plus(Duration.ofDays(365)));
        String lookupId = value.substring(0, value.indexOf('.'));

        assertThat(service.authenticate(lookupId + ".not-the-secret")).isEmpty();
    }

    @Test
    void an_unknown_lookup_id_resolves_to_nothing() {
        mint(TokenPermissions.ALL, NOW.plus(Duration.ofDays(365)));

        assertThat(service.authenticate("no-such-lookup.whatever")).isEmpty();
    }

    /**
     * A value the parser refuses is never used as a query key. Which values are malformed is
     * {@code ConnectorTokenSecretTests}'s; this one carries a dot, so a lookup by its first
     * segment is what a parse skipped here would make.
     */
    @Test
    void a_malformed_value_resolves_to_nothing_without_reaching_a_lookup() {
        mint(TokenPermissions.ALL, NOW.plus(Duration.ofDays(365)));

        assertThat(service.authenticate("too.many.dots")).isEmpty();
        assertThat(tokens.lookups()).isZero();
    }

    /** Mints a token for the seeded connector and returns its plaintext. */
    private String mint(java.util.Set<com.example.backend.authorization.domain.Permission> scope, Instant expiresAt) {
        ConnectorTokenSecret.Minted minted = ConnectorTokenSecret.mint(random);
        tokens.save(ScimConnectorToken.issue(
                UUID.randomUUID(),
                connector.id(),
                minted.lookupId(),
                minted.digest(),
                TokenPermissions.of(scope),
                NOW,
                expiresAt));
        return minted.presentedValue();
    }
}

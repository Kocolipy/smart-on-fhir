package com.example.backend.scim;

import com.example.backend.scim.domain.ScimConnectorToken;
import com.example.backend.scim.domain.ScimConnectorTokenRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;

/** A {@link ScimConnectorTokenRepository} in memory. */
public final class InMemoryScimConnectorTokenRepository implements ScimConnectorTokenRepository {

    private final Map<UUID, ScimConnectorToken> tokens = new LinkedHashMap<>();

    private int saves;

    /**
     * Refuses a second token under a lookup id another token holds, as the table's unique key
     * does, with the exception the adapter's flush raises
     * ({@code ScimConnectorTokenRepositoryContract}).
     */
    @Override
    public void save(ScimConnectorToken token) {
        saves++;
        boolean lookupIdTaken = tokens.values().stream()
                .anyMatch(stored -> stored.lookupId().equals(token.lookupId())
                        && !stored.id().equals(token.id()));
        if (lookupIdTaken) {
            throw new DataIntegrityViolationException(
                    "uq_scim_connector_tokens_lookup_id: another token holds this lookup id");
        }
        tokens.put(token.id(), token);
    }

    /** How many writes have been made, so a test can assert that a use case wrote nothing. */
    public int saves() {
        return saves;
    }

    @Override
    public Optional<ScimConnectorToken> findByLookupId(String lookupId) {
        return tokens.values().stream()
                .filter(token -> token.lookupId().equals(lookupId))
                .findFirst();
    }

    @Override
    public Optional<ScimConnectorToken> findById(UUID id) {
        return Optional.ofNullable(tokens.get(id));
    }

    @Override
    public List<ScimConnectorToken> findByConnectorIdOrderedByIssuedAtDesc(UUID connectorId) {
        return tokens.values().stream()
                .filter(token -> token.connectorId().equals(connectorId))
                .sorted(Comparator.comparing(ScimConnectorToken::issuedAt).reversed())
                .toList();
    }

    @Override
    public int revokeAllForConnector(UUID connectorId, Instant revokedAt) {
        List<ScimConnectorToken> live = tokens.values().stream()
                .filter(token -> token.connectorId().equals(connectorId))
                .filter(token -> !token.isRevoked())
                .toList();
        live.forEach(token -> save(token.revoked(revokedAt)));
        return live.size();
    }

    /** Every token held, in no particular order, for an assertion about all of them. */
    public List<ScimConnectorToken> all() {
        return List.copyOf(tokens.values());
    }
}

package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.authorization.domain.Permission;
import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.domain.ActiveConnectorTokenPage;
import com.example.backend.scim.domain.ConnectorTokenDigest;
import com.example.backend.scim.domain.ConnectorTokenPermissions;
import com.example.backend.scim.domain.ScimConnectorToken;
import com.example.backend.scim.domain.ScimConnectorTokenRepository;
import com.example.backend.scim.infrastructure.persistence.entity.ScimConnectorTokenEntity;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/** Maps the connector token port onto JPA. */
@Repository
class ScimConnectorTokenPersistenceAdapter implements ScimConnectorTokenRepository {

    private final ScimConnectorTokenJpaRepository tokens;

    ScimConnectorTokenPersistenceAdapter(ScimConnectorTokenJpaRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * {@code saveAndFlush}, so the database's own lifetime constraint —
     * {@code ck_scim_connector_tokens_expiry_never_extended} — is evaluated at this
     * call rather than at a commit the use case has already returned from. The
     * constraint is the last line of defence on the rotation rule; a violation that
     * surfaced after the fact could not be attributed to the rotation that caused it.
     */
    @Override
    public void save(ScimConnectorToken token) {
        tokens.saveAndFlush(new ScimConnectorTokenEntity(
                token.id(),
                token.connectorId(),
                token.lookupId(),
                token.digest().toStoredBytes(),
                token.permissions().sortedValues().toArray(String[]::new),
                token.issuedAt(),
                token.expiresAt(),
                token.originalExpiresAt(),
                token.revokedAt(),
                token.replacedByTokenId()));
    }

    @Override
    public Optional<ScimConnectorToken> findByLookupId(String lookupId) {
        return tokens.findByLookupId(lookupId)
                .map(ScimConnectorTokenPersistenceAdapter::toToken);
    }

    @Override
    public Optional<ScimConnectorToken> findById(UUID id) {
        return tokens.findById(id).map(ScimConnectorTokenPersistenceAdapter::toToken);
    }

    @Override
    public List<ScimConnectorToken> findByConnectorIdOrderedByIssuedAtDesc(UUID connectorId) {
        return tokens.findByConnectorIdOrderByIssuedAtDesc(connectorId).stream()
                .map(ScimConnectorTokenPersistenceAdapter::toToken)
                .toList();
    }

    @Override
    public ActiveConnectorTokenPage findActiveByConnectorId(
            UUID connectorId, Instant now, PageRequest page) {
        long total = tokens.countActiveByConnectorId(connectorId, now);
        // SQL Server refuses FETCH NEXT 0 ROWS, so a count of zero reads no page. No other
        // short-circuit: a page past the end is read and comes back empty.
        if (page.count() == 0) {
            return new ActiveConnectorTokenPage(total, List.of());
        }
        return new ActiveConnectorTokenPage(total, tokens
                .findActivePageByConnectorId(connectorId, now, page.offset(), page.count())
                .stream()
                .map(ScimConnectorTokenPersistenceAdapter::toToken)
                .toList());
    }

    @Override
    public int revokeAllForConnector(UUID connectorId, Instant revokedAt) {
        return tokens.revokeAllForConnector(connectorId, revokedAt);
    }

    private static ScimConnectorToken toToken(ScimConnectorTokenEntity entity) {
        return new ScimConnectorToken(
                entity.getId(),
                entity.getConnectorId(),
                entity.getLookupId(),
                ConnectorTokenDigest.ofStoredBytes(entity.getTokenHash()),
                permissions(entity.getPermissions()),
                entity.getIssuedAt(),
                entity.getExpiresAt(),
                entity.getOriginalExpiresAt(),
                entity.getRevokedAt(),
                entity.getReplacedByTokenId());
    }

    /**
     * The stored spellings as Permissions. A spelling that names no Permission cannot be stored —
     * the table's check constraint admits only the four directory ones — so one here is a schema
     * this code does not understand, and is refused rather than silently dropped.
     */
    private static ConnectorTokenPermissions permissions(String[] stored) {
        return new ConnectorTokenPermissions(Arrays.stream(stored)
                .map(value -> Permission.fromValue(value).orElseThrow(() ->
                        new IllegalStateException("A stored token Permission is not a Permission")))
                .collect(Collectors.toSet()));
    }
}

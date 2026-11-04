package com.example.backend.scim.application;

import java.time.Instant;
import java.util.UUID;

/**
 * What an Admin may know about a connector itself: its identity.
 *
 * <p>Its tokens are not part of it. They are read a page at a time, per connector
 * ({@link ConnectorTokenPage}), and only the active ones: a connector rotated and revoked over
 * years holds far more tokens than credentials that still authenticate, and embedding every one of
 * them would grow the connector listing with that history rather than with the connectors.
 *
 * @param id          stable id, which every token and audit event names
 * @param displayName the Admin's own label for this integration
 * @param createdAt   when it was created
 */
public record ConnectorSummary(UUID id, String displayName, Instant createdAt) {
}

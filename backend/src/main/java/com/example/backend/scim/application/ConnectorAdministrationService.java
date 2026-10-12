package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scim.domain.ConnectorTokenPolicy;
import com.example.backend.scim.domain.ConnectorTokenPermissions;
import com.example.backend.scim.domain.ConnectorTokenSecret;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorRepository;
import com.example.backend.scim.domain.ScimConnectorToken;
import com.example.backend.scim.domain.ScimConnectorTokenRepository;
import com.example.backend.scim.domain.ScimExternalIdRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The connector and token use cases an administrator drives.
 *
 * <p>Separate from {@link ConnectorAuthenticationService}, which serves the request
 * path, for the reason {@code AccountAdministrationService} is separate from
 * {@code AccountService}: the class that verifies a presented credential does not
 * also mint and revoke them, so nothing on the authentication path can reach a
 * write by accident.
 *
 * <p>A minted plaintext value leaves this class exactly once, in the
 * {@link IssuedConnectorToken} the issue and rotation methods return. It is never
 * held in a field, never logged, and never written anywhere but that response — what
 * is persisted is its digest.
 *
 * <p>Every write names its acting administrator by {@code actorId}: the stable id its signed-in
 * session holds, recorded as the event's actor. Never a {@code userName}, which a connector may
 * rename between the administrator signing in and acting, handing it to another User. A
 * {@code null} id — a session that identifies nobody — records the event with no actor: no rule
 * here turns on who the actor is, and an event with no actor is more use than no event.
 */
@Service
public class ConnectorAdministrationService {

    private static final Logger log =
            LoggerFactory.getLogger(ConnectorAdministrationService.class);


    private final ScimConnectorRepository connectors;
    private final ScimConnectorTokenRepository tokens;
    private final ScimExternalIdRepository aliases;
    private final AuditTrail audit;
    private final SecureRandom random;
    private final Clock clock;

    public ConnectorAdministrationService(
            ScimConnectorRepository connectors,
            ScimConnectorTokenRepository tokens,
            ScimExternalIdRepository aliases,
            AuditTrail audit,
            SecureRandom random,
            Clock clock) {
        this.connectors = connectors;
        this.tokens = tokens;
        this.aliases = aliases;
        this.audit = audit;
        this.random = random;
        this.clock = clock;
    }

    /** Every live connector with the state of its tokens. Never carries a digest. */
    public List<ConnectorSummary> listConnectors() {
        Instant now = clock.instant();
        return connectors.findAllLiveOrderedByDisplayName().stream()
                .map(connector -> summarize(connector, now))
                .toList();
    }

    /** Creates a connector holding no tokens yet. */
    @Transactional
    public ConnectorSummary create(String displayName, UUID actorId) {
        Instant now = clock.instant();
        ScimConnector connector = ScimConnector.create(UUID.randomUUID(), displayName, now);
        connectors.save(connector);
        audit.append(new Audited.ConnectorCreated(actorId, connector.id()));
        succeeded(Operation.CONNECTOR_CREATE, Type.CREATION);
        return summarize(connector, now);
    }

    /**
     * Deletes a connector: every token it holds is revoked and every
     * {@code externalId} alias it owns is removed, in this one transaction.
     *
     * <p>One transaction is the requirement rather than an optimisation. A partial
     * delete is the one outcome that must be impossible: aliases gone with tokens
     * still live would leave a credential able to write to a directory it can no
     * longer address its own resources in, and tokens gone with aliases surviving
     * would leave a namespace reserved by nobody, blocking the reuse the RFC allows.
     * Both writes and the connector's own transition therefore commit or roll back
     * together, and the audit event joins them — it is fail-closed, so a delete this
     * service cannot account for does not happen.
     *
     * <p>Users and Groups are untouched. An alias is a connector's name for a
     * resource, not the resource.
     *
     * <p>Idempotent in effect: deleting an already-deleted connector is refused with
     * {@link UnknownConnectorException}, because an Admin repeating a delete is more
     * likely to have the wrong id than to want a second no-op.
     */
    @Transactional
    public void delete(UUID connectorId, UUID actorId) {
        ScimConnector connector = requireLive(connectorId);
        Instant now = clock.instant();
        int revoked = tokens.revokeAllForConnector(connectorId, now);
        aliases.deleteAllForConnector(connectorId);
        connectors.save(connector.deleted(now));
        // The connector's own event carries the actor; the per-token events do not,
        // so the trail reads as one administrative act with the credentials it took
        // down rather than as several independent revocations.
        for (int token = 0; token < revoked; token++) {
            audit.append(new Audited.ConnectorTokenRevoked(null, connectorId));
        }
        audit.append(new Audited.ConnectorDeleted(actorId, connectorId));
        succeeded(Operation.CONNECTOR_DELETE, Type.DELETION);
    }

    /**
     * Mints a token for a connector, carrying {@code requested}. The returned plaintext is the
     * only copy that will ever exist.
     *
     * <p><strong>No escalation.</strong> Refused unless the administrator holds every Permission
     * it asks for ({@code held}, the Permissions its session was issued with):
     * {@code connector:token} lets an administrator mint credentials, never credentials more
     * powerful than itself. The refusal is audited with the Permissions requested. Checked after
     * the request's own shape — an empty list or a Permission no token can carry is refused
     * whoever asks — and after the connector is found, so the refusal names a real connector.
     *
     * @param requested what the token is to carry: at least one, all directory Permissions
     * @param lifetime  how long it should live, or {@code null} for the default;
     *                  bounded by {@link ConnectorTokenPolicy}
     * @param held      the Permissions the requesting administrator holds
     */
    @Transactional
    public IssuedConnectorToken issueToken(
            UUID connectorId,
            Collection<Permission> requested,
            Duration lifetime,
            UUID actorId,
            Set<Permission> held) {
        ConnectorTokenPermissions permissions = ConnectorTokenPermissions.requested(requested);
        Duration validLifetime = ConnectorTokenPolicy.lifetime(lifetime);
        requireLive(connectorId);
        if (!permissions.heldWithin(held)) {
            audit.append(new Audited.ConnectorTokenIssueRefused(
                    actorId, connectorId, permissions.values()));
            escalationRefused(Operation.CONNECTOR_TOKEN_ISSUE);
            throw new ConnectorTokenEscalationException();
        }
        Instant now = clock.instant();
        ConnectorTokenSecret.Minted minted = ConnectorTokenSecret.mint(random);
        ScimConnectorToken issued = ScimConnectorToken.issue(
                UUID.randomUUID(),
                connectorId,
                minted.lookupId(),
                minted.digest(),
                permissions,
                now,
                now.plus(validLifetime));
        tokens.save(issued);
        audit.append(new Audited.ConnectorTokenIssued(actorId, connectorId, permissions.values()));
        succeeded(Operation.CONNECTOR_TOKEN_ISSUE, Type.CREATION);
        return disclose(issued, minted.presentedValue());
    }

    /**
     * Replaces a token with a new one, leaving the old one usable until the overlap window ends.
     * The replacement carries the old token's Permissions, or {@code requested} when given.
     *
     * <p>The no-escalation rule applies to what the REPLACEMENT carries, kept or new: rotating is
     * minting, so an administrator cannot renew a credential more powerful than itself because
     * someone more powerful issued the original. A kept set is validated like a requested one,
     * so a token stored before Permissions existed — which carries none — must be rotated with an
     * explicit list.
     *
     * <p>The old token's expiry is brought forward to the end of the overlap and
     * never pushed back: the window ends at the earlier of the requested overlap and
     * the expiry the token already had, so a rotation cannot buy a token more life
     * than it was issued with — nor more than a previous rotation left it. An overlap
     * of zero, or none at all, ends the old token at this instant.
     *
     * <p>The new token's own lifetime is a fresh full lifetime, not the remainder of
     * the old one's: rotation exists so an integration can keep running, and handing
     * it a replacement that expires next week would defeat that.
     *
     * @param requested the replacement's Permissions, or {@code null} to keep the old token's
     * @param overlap   how long the old token should keep working, clamped to
     *                  {@link ConnectorTokenPolicy#MAX_ROTATION_OVERLAP}
     * @param held      the Permissions the requesting administrator holds
     */
    @Transactional
    public IssuedConnectorToken rotateToken(
            UUID tokenId,
            Collection<Permission> requested,
            Duration overlap,
            UUID actorId,
            Set<Permission> held) {
        ScimConnectorToken existing = requireToken(tokenId);
        ConnectorTokenPermissions permissions = ConnectorTokenPermissions.requested(
                requested == null ? existing.permissions().values() : requested);
        ScimConnector connector = requireLive(existing.connectorId());
        if (!permissions.heldWithin(held)) {
            audit.append(new Audited.ConnectorTokenRotateRefused(
                    actorId, connector.id(), permissions.values()));
            escalationRefused(Operation.CONNECTOR_TOKEN_ROTATE);
            throw new ConnectorTokenEscalationException();
        }
        Instant now = clock.instant();

        ConnectorTokenSecret.Minted minted = ConnectorTokenSecret.mint(random);
        ScimConnectorToken replacement = ScimConnectorToken.issue(
                UUID.randomUUID(),
                connector.id(),
                minted.lookupId(),
                minted.digest(),
                permissions,
                now,
                now.plus(ConnectorTokenPolicy.DEFAULT_LIFETIME));
        tokens.save(replacement);
        tokens.save(existing.expiringAt(
                ConnectorTokenPolicy.overlapEnd(now, overlap, existing.expiresAt()),
                replacement.id()));

        audit.append(new Audited.ConnectorTokenRotated(
                actorId, connector.id(), permissions.values()));
        succeeded(Operation.CONNECTOR_TOKEN_ROTATE, Type.CHANGE);
        return disclose(replacement, minted.presentedValue());
    }

    /**
     * Revokes a token, effective immediately.
     *
     * <p>Idempotent: a token already revoked keeps its original revocation time and
     * nothing is written, but the event is still recorded — an administrator
     * revoking a credential twice is a thing worth being able to see.
     */
    @Transactional
    public void revokeToken(UUID tokenId, UUID actorId) {
        ScimConnectorToken existing = requireToken(tokenId);
        ScimConnectorToken revoked = existing.revoked(clock.instant());
        if (revoked != existing) {
            tokens.save(revoked);
        }
        audit.append(new Audited.ConnectorTokenRevoked(actorId, existing.connectorId()));
        succeeded(Operation.CONNECTOR_TOKEN_REVOKE, Type.DELETION);
    }

    private ScimConnector requireLive(UUID connectorId) {
        return connectors.findById(connectorId)
                .filter(connector -> !connector.isDeleted())
                .orElseThrow(() -> new UnknownConnectorException(
                        "No live connector with that id"));
    }

    private ScimConnectorToken requireToken(UUID tokenId) {
        return tokens.findById(tokenId)
                .orElseThrow(() -> new UnknownConnectorException("No token with that id"));
    }

    private ConnectorSummary summarize(ScimConnector connector, Instant now) {
        return new ConnectorSummary(
                connector.id(),
                connector.displayName(),
                connector.createdAt(),
                tokens.findByConnectorIdOrderedByIssuedAtDesc(connector.id()).stream()
                        .map(token -> summarize(token, now))
                        .toList());
    }

    private static ConnectorTokenSummary summarize(ScimConnectorToken token, Instant now) {
        return new ConnectorTokenSummary(
                token.id(),
                token.permissions().sortedValues(),
                token.issuedAt(),
                token.expiresAt(),
                token.originalExpiresAt(),
                token.revokedAt(),
                token.isUsable(now));
    }

    private static IssuedConnectorToken disclose(ScimConnectorToken token, String presentedValue) {
        return new IssuedConnectorToken(
                token.connectorId(),
                token.id(),
                token.permissions().sortedValues(),
                token.issuedAt(),
                token.expiresAt(),
                presentedValue);
    }

    /**
     * Records that a connector or token lifecycle write went through.
     *
     * <p>The action and the outcome; the administrator who acted is {@code user.id},
     * which the request's logging context already carries. No connector id, no token
     * id, and above all no token value: the log stream is read by more people and
     * retained longer than the database, so naming which credential changed is the
     * audit trail's job — which does it by stable id, and is the thing an
     * administrator investigating a connector actually reads. There is no
     * {@code user.target.id} either: the subject is a connector, not a User.
     */
    private static void succeeded(Operation operation, Type lifecycle) {
        LogEvent.success(log, operation, Category.CONFIGURATION, Type.ADMIN, lifecycle).log();
    }

    /**
     * Records that a token issue or rotation was refused as an escalation, at {@code WARN}: the
     * action and the outcome, never which Permissions — those are the audit trail's to name, and
     * a log line listing them would be a map of who may mint what.
     */
    private static void escalationRefused(Operation operation) {
        LogEvent.refused(log, operation, Category.CONFIGURATION, Type.ADMIN, Type.DENIED).log();
    }
}

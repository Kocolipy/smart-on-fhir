package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.observability.RetriedOnDeadlock;
import com.example.backend.scim.domain.ConnectorTokenLocks;
import com.example.backend.scim.domain.ConnectorTokenPolicy;
import com.example.backend.scim.domain.ConnectorTokenRotationRefusal;
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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The connector and token use cases an administrator drives.
 *
 * <p>Separate from {@link ConnectorAuthenticationService}, which serves the request
 * path, for the reason {@code IdentityAdministrationService} is separate from
 * {@code LoginIdentityService}: the class that verifies a presented credential does not
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
 *
 * <p>A refusal is thrown before anything is written, as a
 * {@link ConnectorAdministrationRefusedException} naming the administrator, what the operation
 * addressed and why, and is recorded nowhere here: the administrative edge answers, logs and
 * audits it. An id that names no live connector or token is an {@link UnknownConnectorException};
 * a token that can no longer be rotated, a {@link ConnectorTokenNotRotatableException}.
 */
@Service
public class ConnectorAdministrationService {

    private static final Logger log =
            LoggerFactory.getLogger(ConnectorAdministrationService.class);


    private final ScimConnectorRepository connectors;
    private final ScimConnectorTokenRepository tokens;
    private final ConnectorTokenLocks tokenLocks;
    private final ScimExternalIdRepository aliases;
    private final AuditTrail audit;
    private final SecureRandom random;
    private final Clock clock;

    public ConnectorAdministrationService(
            ScimConnectorRepository connectors,
            ScimConnectorTokenRepository tokens,
            ConnectorTokenLocks tokenLocks,
            ScimExternalIdRepository aliases,
            AuditTrail audit,
            SecureRandom random,
            Clock clock) {
        this.connectors = connectors;
        this.tokens = tokens;
        this.tokenLocks = tokenLocks;
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
    @RetriedOnDeadlock(Operation.CONNECTOR_CREATE)
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
    @RetriedOnDeadlock(Operation.CONNECTOR_DELETE)
    public void delete(UUID connectorId, UUID actorId) {
        ScimConnector connector = requireLive(
                connectorId, UnknownConnectorException.Operation.DELETE, actorId, null);
        Instant now = clock.instant();
        // Which tokens the revocation below takes down, read first so each event can name its
        // token: every one this connector holds that is not revoked already.
        List<UUID> takenDown = tokens.findByConnectorIdOrderedByIssuedAtDesc(connectorId).stream()
                .filter(held -> !held.isRevoked())
                .map(ScimConnectorToken::id)
                .toList();
        tokens.revokeAllForConnector(connectorId, now);
        aliases.deleteAllForConnector(connectorId);
        connectors.save(connector.deleted(now));
        // The connector's own event carries the actor; the per-token events do not,
        // so the trail reads as one administrative act with the credentials it took
        // down rather than as several independent revocations.
        for (UUID revokedId : takenDown) {
            audit.append(new Audited.ConnectorTokenRevoked(null, connectorId, revokedId));
        }
        audit.append(new Audited.ConnectorDeleted(actorId, connectorId));
        succeeded(Operation.CONNECTOR_DELETE, Type.DELETION);
    }

    /**
     * Mints a token for a connector, carrying {@code requested}. The returned plaintext is the
     * only copy that will ever exist.
     *
     * <p>Only the Superuser reaches this (the controller's method security), and a token with a
     * write Permission is Superuser-equivalent, so there is no further rule on what it may carry
     * beyond the vocabulary: an empty list or a Permission no token can carry is refused.
     *
     * @param requested what the token is to carry: at least one, all token-grantable Permissions
     * @param lifetime  how long it should live, or {@code null} for the default;
     *                  bounded by {@link ConnectorTokenPolicy}
     */
    @Transactional
    @RetriedOnDeadlock(Operation.CONNECTOR_TOKEN_ISSUE)
    public IssuedConnectorToken issueToken(
            UUID connectorId,
            Collection<Permission> requested,
            Duration lifetime,
            UUID actorId) {
        ConnectorTokenPermissions permissions = ConnectorTokenPermissions.requested(requested);
        Duration validLifetime = ConnectorTokenPolicy.lifetime(lifetime);
        requireLive(connectorId, UnknownConnectorException.Operation.TOKEN_ISSUE, actorId, null);
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
        audit.append(new Audited.ConnectorTokenIssued(
                actorId, connectorId, issued.id(), permissions.values()));
        succeeded(Operation.CONNECTOR_TOKEN_ISSUE, Type.CREATION);
        return disclose(issued, minted.presentedValue());
    }

    /**
     * Replaces a token with a new one, leaving the old one usable until the overlap window ends.
     * The replacement carries the old token's Permissions, or {@code requested} when given.
     *
     * <p>A kept set is validated like a requested one, so a token stored before Permissions
     * existed — which carries none — must be rotated with an explicit list.
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
     * @param connectorId the connector the token is addressed under, which must be live and hold
     *                    it: a token of another connector, or of a deleted one, is refused as
     *                    {@link UnknownConnectorException}
     * @param requested the replacement's Permissions, or {@code null} to keep the old token's
     * @param overlap   how long the old token should keep working, clamped to
     *                  {@link ConnectorTokenPolicy#MAX_ROTATION_OVERLAP}
     */
    @Transactional
    @RetriedOnDeadlock(Operation.CONNECTOR_TOKEN_ROTATE)
    public IssuedConnectorToken rotateToken(
            UUID connectorId,
            UUID tokenId,
            Collection<Permission> requested,
            Duration overlap,
            UUID actorId) {
        tokenLocks.lockToken(tokenId);
        ScimConnectorToken existing = requireTokenOf(
                connectorId, tokenId, UnknownConnectorException.Operation.TOKEN_ROTATE, actorId);
        ConnectorTokenPermissions permissions = ConnectorTokenPermissions.requested(
                requested == null ? existing.permissions().values() : requested);
        ScimConnector connector = requireLive(connectorId,
                UnknownConnectorException.Operation.TOKEN_ROTATE, actorId, tokenId);
        Instant now = clock.instant();
        Optional<ConnectorTokenRotationRefusal> refusal = existing.rotationRefusal(now);
        if (refusal.isPresent()) {
            throw new ConnectorTokenNotRotatableException(
                    actorId, connector.id(), existing.id(), refusal.get());
        }

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
                actorId, connector.id(), existing.id(), replacement.id(), permissions.values()));
        succeeded(Operation.CONNECTOR_TOKEN_ROTATE, Type.CHANGE);
        return disclose(replacement, minted.presentedValue());
    }

    /**
     * Revokes a token, effective immediately.
     *
     * <p>Idempotent: a token already revoked keeps its original revocation time and
     * nothing is written, but the event is still recorded — an administrator
     * revoking a credential twice is a thing worth being able to see.
     *
     * <p>Locks the token before reading it, as a rotation does: the revocation writes the whole
     * row back from what it read, so a rotation committed in between would otherwise be undone.
     *
     * <p>The token is addressed under its connector, which must be live and hold it. A token of
     * another connector is refused as {@link UnknownConnectorException}, so a revocation cannot
     * act on, or be audited under, a connector the administrator did not name; and so is a token
     * of a deleted connector, which the delete already revoked and recorded — repeating it would
     * record a revocation that did not happen.
     */
    @Transactional
    @RetriedOnDeadlock(Operation.CONNECTOR_TOKEN_REVOKE)
    public void revokeToken(UUID connectorId, UUID tokenId, UUID actorId) {
        tokenLocks.lockToken(tokenId);
        ScimConnectorToken existing = requireTokenOf(
                connectorId, tokenId, UnknownConnectorException.Operation.TOKEN_REVOKE, actorId);
        requireLive(
                connectorId, UnknownConnectorException.Operation.TOKEN_REVOKE, actorId, tokenId);
        ScimConnectorToken revoked = existing.revoked(clock.instant());
        if (revoked != existing) {
            tokens.save(revoked);
        }
        audit.append(new Audited.ConnectorTokenRevoked(
                actorId, existing.connectorId(), existing.id()));
        succeeded(Operation.CONNECTOR_TOKEN_REVOKE, Type.DELETION);
    }

    /**
     * The live connector behind {@code connectorId}, or the refusal of {@code operation} as
     * addressing nothing — naming the token it addressed too, when it addressed one.
     */
    private ScimConnector requireLive(UUID connectorId,
            UnknownConnectorException.Operation operation, UUID actorId, UUID tokenId) {
        return connectors.findById(connectorId)
                .filter(connector -> !connector.isDeleted())
                .orElseThrow(() -> new UnknownConnectorException(
                        operation, actorId, connectorId, tokenId));
    }

    /**
     * The token behind {@code tokenId} when it is one of {@code connectorId}'s, or the refusal of
     * {@code operation} as addressing nothing — naming the connector and the token it addressed.
     * A token of another connector is no token of this one: the path names both, and an operation
     * that took the token's own connector instead would act on, and audit under, a connector the
     * administrator never named.
     */
    private ScimConnectorToken requireTokenOf(UUID connectorId, UUID tokenId,
            UnknownConnectorException.Operation operation, UUID actorId) {
        return tokens.findById(tokenId)
                .filter(token -> token.connectorId().equals(connectorId))
                .orElseThrow(() -> new UnknownConnectorException(
                        operation, actorId, connectorId, tokenId));
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
}

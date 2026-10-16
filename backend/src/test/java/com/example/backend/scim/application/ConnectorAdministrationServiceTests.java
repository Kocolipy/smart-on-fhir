package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import com.example.backend.TokenPermissions;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditAdministrativeRefusal;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.MutableClock;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.InMemoryConnectorTokenLocks;
import com.example.backend.scim.InMemoryScimConnectorRepository;
import com.example.backend.scim.InMemoryScimConnectorTokenRepository;
import com.example.backend.scim.InMemoryScimExternalIdRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ConnectorTokenDigest;
import com.example.backend.scim.domain.ConnectorTokenPolicy;
import com.example.backend.scim.domain.ConnectorTokenSecret;
import com.example.backend.scim.domain.InvalidConnectorTokenLifetimeException;
import com.example.backend.scim.domain.InvalidConnectorTokenPermissionsException;
import com.example.backend.scim.domain.ScimConnectorToken;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The connector and token lifecycle, against in-memory stores.
 *
 * <p>At this level the claims are about what the use case decides: which events it
 * records, what the overlap arithmetic does to the stored rows, and that a delete
 * takes tokens and aliases with it. That a request arriving over HTTP produces those
 * decisions, and that the rows genuinely land in SQL Server, is
 * {@code ScimConnectorLifecycleIntegrationTests}' claim rather than this one's.
 */
class ConnectorAdministrationServiceTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static final String ADMIN = "an-admin";

    private final InMemoryScimConnectorRepository connectors =
            new InMemoryScimConnectorRepository();

    private final InMemoryScimConnectorTokenRepository tokens =
            new InMemoryScimConnectorTokenRepository();

    private final InMemoryScimExternalIdRepository aliases =
            new InMemoryScimExternalIdRepository();

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final MutableClock clock = new MutableClock(NOW);

    private final InMemoryConnectorTokenLocks tokenLocks = new InMemoryConnectorTokenLocks();

    private ConnectorAdministrationService service;

    private UUID adminId;

    @BeforeEach
    void setUp() {
        // The administrator is now an ordinary SCIM User: there is no role column, and what
        // makes this one an administrator is membership of the reserved Admin group — which
        // this use case never asks about. It resolves the actor's id by userName and nothing
        // more, so the Group port is not part of this test's arrangement.
        adminId = users.given(ScimIdentities.user(ADMIN)).id();
        service = new ConnectorAdministrationService(
                connectors, tokens, tokenLocks, aliases, audit, new SecureRandom(), clock);
    }

    @Nested
    class Connectors {

        @Test
        void a_created_connector_is_listed_and_holds_no_tokens() {
            ConnectorSummary created = service.create("Okta", adminId);

            assertThat(created.displayName()).isEqualTo("Okta");
            assertThat(created.createdAt()).isEqualTo(NOW);
            assertThat(created.tokens()).isEmpty();
            assertThat(service.listConnectors()).extracting(ConnectorSummary::id)
                    .containsExactly(created.id());
        }

        @Test
        void creation_records_one_event_naming_the_connector_and_the_admin() {
            ConnectorSummary created = service.create("Okta", adminId);

            assertThat(audit.of(AuditOperation.CONNECTOR_CREATE))
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.actorId()).isEqualTo(adminId);
                        assertThat(event.subjectId()).isEqualTo(created.id());
                    });
        }

        @Test
        void the_listing_is_alphabetical_and_omits_a_deleted_connector() {
            service.create("Zeta", adminId);
            ConnectorSummary alpha = service.create("Alpha", adminId);
            ConnectorSummary middle = service.create("Middle", adminId);

            service.delete(middle.id(), adminId);

            assertThat(service.listConnectors()).extracting(ConnectorSummary::displayName)
                    .containsExactly("Alpha", "Zeta");
            assertThat(alpha.id()).isNotEqualTo(middle.id());
        }

        /** The criterion, as one transaction's observable effect. */
        @Test
        void deleting_a_connector_revokes_every_token_and_removes_every_alias() {
            ConnectorSummary connector = service.create("Okta", adminId);
            service.issueToken(connector.id(), TokenPermissions.READ, null, adminId, TokenPermissions.ALL);
            service.issueToken(connector.id(), TokenPermissions.ALL, null, adminId, TokenPermissions.ALL);
            aliases.seed(connector.id(), "okta-user-1");
            aliases.seed(connector.id(), "okta-user-2");

            service.delete(connector.id(), adminId);

            assertThat(tokens.all()).isNotEmpty().allMatch(ScimConnectorToken::isRevoked);
            assertThat(aliases.all()).isEmpty();
        }

        /** Another connector's aliases are somebody else's namespace. */
        @Test
        void deleting_a_connector_leaves_another_connectors_aliases_alone() {
            ConnectorSummary doomed = service.create("Okta", adminId);
            ConnectorSummary survivor = service.create("Entra", adminId);
            aliases.seed(doomed.id(), "okta-user-1");
            aliases.seed(survivor.id(), "entra-user-1");

            service.delete(doomed.id(), adminId);

            assertThat(aliases.all()).extracting(
                            InMemoryScimExternalIdRepository.Alias::externalId)
                    .containsExactly("entra-user-1");
        }

        /**
         * One revocation event per token taken down, plus the delete itself. The
         * per-token events carry no actor: the delete is the administrative act, and
         * repeating the actor on each would read as several separate decisions.
         */
        @Test
        void deletion_records_the_delete_and_one_revocation_per_token_it_took_down() {
            ConnectorSummary connector = service.create("Okta", adminId);
            IssuedConnectorToken first = service.issueToken(
                    connector.id(), TokenPermissions.READ, null, adminId, TokenPermissions.ALL);
            IssuedConnectorToken second = service.issueToken(
                    connector.id(), TokenPermissions.ALL, null, adminId, TokenPermissions.ALL);
            IssuedConnectorToken alreadyRevoked = service.issueToken(
                    connector.id(), TokenPermissions.ALL, null, adminId, TokenPermissions.ALL);
            service.revokeToken(alreadyRevoked.tokenId(), adminId);
            audit.reset();

            service.delete(connector.id(), adminId);

            assertThat(audit.of(AuditOperation.CONNECTOR_TOKEN_REVOKE)).hasSize(2)
                    .allSatisfy(event -> {
                        assertThat(event.actorId()).isNull();
                        assertThat(event.subjectId()).isEqualTo(connector.id());
                    });
            assertThat(audit.events(Audited.ConnectorTokenRevoked.class))
                    .extracting(Audited.ConnectorTokenRevoked::revokedId)
                    .as("each revocation names the token it took down")
                    .containsExactlyInAnyOrder(first.tokenId(), second.tokenId());
            assertThat(audit.of(AuditOperation.CONNECTOR_DELETE)).singleElement()
                    .satisfies(event -> {
                        assertThat(event.actorId()).isEqualTo(adminId);
                        assertThat(event.subjectId()).isEqualTo(connector.id());
                    });
        }

        @Test
        void deleting_a_connector_twice_is_refused_the_second_time() {
            ConnectorSummary connector = service.create("Okta", adminId);
            service.delete(connector.id(), adminId);

            assertThatThrownBy(() -> service.delete(connector.id(), adminId))
                    .isInstanceOf(UnknownConnectorException.class);
        }

        @Test
        void an_unknown_connector_id_is_refused() {
            assertThatThrownBy(() -> service.delete(UUID.randomUUID(), adminId))
                    .isInstanceOf(UnknownConnectorException.class);
        }
    }

    @Nested
    class Tokens {

        @Test
        void an_issued_token_discloses_its_plaintext_and_stores_only_a_digest() {
            ConnectorSummary connector = service.create("Okta", adminId);

            IssuedConnectorToken issued = service.issueToken(
                    connector.id(), TokenPermissions.ALL, null, adminId, TokenPermissions.ALL);

            assertThat(issued.presentedValue()).isNotBlank();
            assertThat(issued.connectorId()).isEqualTo(connector.id());
            assertThat(issued.permissions()).containsExactly("group:read", "group:write", "user:read", "user:write");
            assertThat(issued.issuedAt()).isEqualTo(NOW);
            assertThat(issued.expiresAt())
                    .isEqualTo(NOW.plus(ConnectorTokenPolicy.DEFAULT_LIFETIME));

            ScimConnectorToken stored = tokens.findById(issued.tokenId()).orElseThrow();
            assertThat(stored.digest().matches(
                            ConnectorTokenSecret.parse(issued.presentedValue())
                                    .orElseThrow()
                                    .digest()))
                    .isTrue();
        }

        /**
         * The listing an Admin reads carries no field the plaintext could occupy, which
         * is checked here by reflecting over the projection rather than by reading it:
         * a value assertion would pass again the day somebody added such a field.
         */
        @Test
        void no_token_projection_field_can_hold_the_plaintext() {
            ConnectorSummary connector = service.create("Okta", adminId);
            IssuedConnectorToken issued = service.issueToken(
                    connector.id(), TokenPermissions.READ, null, adminId, TokenPermissions.ALL);

            ConnectorSummary listed = service.listConnectors().getFirst();

            assertThat(listed.tokens()).singleElement().satisfies(token -> {
                assertThat(token.toString()).doesNotContain(issued.presentedValue());
                assertThat(ConnectorTokenSummary.class.getRecordComponents())
                        .noneMatch(component ->
                                component.getType().equals(String.class)
                                        || component.getType().equals(byte[].class));
            });
        }

        @Test
        void a_shorter_lifetime_is_honoured_and_one_past_the_ceiling_is_refused() {
            ConnectorSummary connector = service.create("Okta", adminId);

            IssuedConnectorToken short30 = service.issueToken(
                    connector.id(), TokenPermissions.READ, Duration.ofDays(30), adminId, TokenPermissions.ALL);
            assertThat(short30.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));

            assertThatThrownBy(() -> service.issueToken(
                            connector.id(),
                            TokenPermissions.READ,
                            Duration.ofDays(400),
                            adminId,
                            TokenPermissions.ALL))
                    .isInstanceOf(InvalidConnectorTokenLifetimeException.class);
        }

        @Test
        void issuing_records_one_event_naming_the_connector() {
            ConnectorSummary connector = service.create("Okta", adminId);
            audit.reset();

            service.issueToken(connector.id(), TokenPermissions.READ, null, adminId, TokenPermissions.ALL);

            assertThat(audit.of(AuditOperation.CONNECTOR_TOKEN_ISSUE)).singleElement()
                    .satisfies(event -> {
                        assertThat(event.actorId()).isEqualTo(adminId);
                        assertThat(event.subjectId()).isEqualTo(connector.id());
                    });
        }

        @Test
        void a_token_may_not_be_issued_for_a_deleted_connector() {
            ConnectorSummary connector = service.create("Okta", adminId);
            service.delete(connector.id(), adminId);

            assertThatThrownBy(() -> service.issueToken(
                            connector.id(), TokenPermissions.READ, null, adminId, TokenPermissions.ALL))
                    .isInstanceOf(UnknownConnectorException.class);
        }

        @Test
        void rotation_keeps_the_scope_and_gives_the_replacement_a_full_lifetime() {
            IssuedConnectorToken original = issuedReadWriteToken();

            IssuedConnectorToken replacement =
                    service.rotateToken(original.tokenId(), null, Duration.ofDays(7), adminId, TokenPermissions.ALL);

            assertThat(replacement.tokenId()).isNotEqualTo(original.tokenId());
            assertThat(replacement.permissions()).containsExactly("group:read", "group:write", "user:read", "user:write");
            assertThat(replacement.presentedValue()).isNotEqualTo(original.presentedValue());
            assertThat(replacement.expiresAt())
                    .isEqualTo(NOW.plus(ConnectorTokenPolicy.DEFAULT_LIFETIME));
        }

        @Test
        void rotation_ends_the_old_token_at_the_requested_overlap() {
            IssuedConnectorToken original = issuedReadWriteToken();

            service.rotateToken(original.tokenId(), null, Duration.ofDays(7), adminId, TokenPermissions.ALL);

            ScimConnectorToken old = tokens.findById(original.tokenId()).orElseThrow();
            assertThat(old.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
            assertThat(old.isUsable(NOW.plus(Duration.ofDays(6)))).isTrue();
            assertThat(old.isUsable(NOW.plus(Duration.ofDays(8)))).isFalse();
        }

        @Test
        void an_overlap_past_fourteen_days_is_clamped_to_fourteen() {
            IssuedConnectorToken original = issuedReadWriteToken();

            service.rotateToken(original.tokenId(), null, Duration.ofDays(90), adminId, TokenPermissions.ALL);

            assertThat(tokens.findById(original.tokenId()).orElseThrow().expiresAt())
                    .isEqualTo(NOW.plus(ConnectorTokenPolicy.MAX_ROTATION_OVERLAP));
        }

        /** The criterion: the overlap never runs past the old token's original expiry. */
        @Test
        void an_overlap_never_outlives_the_old_tokens_original_expiry() {
            ConnectorSummary connector = service.create("Okta", adminId);
            IssuedConnectorToken shortLived = service.issueToken(
                    connector.id(), TokenPermissions.READ, Duration.ofDays(3), adminId, TokenPermissions.ALL);

            service.rotateToken(shortLived.tokenId(), null, Duration.ofDays(14), adminId, TokenPermissions.ALL);

            ScimConnectorToken old = tokens.findById(shortLived.tokenId()).orElseThrow();
            assertThat(old.expiresAt()).isEqualTo(shortLived.expiresAt());
            assertThat(old.expiresAt()).isEqualTo(old.originalExpiresAt());
        }

        /**
         * A second rotation of one token is refused outright, so it can neither give the old
         * token time back nor mint a second live replacement.
         */
        @Test
        void a_second_rotation_cannot_give_the_old_token_time_back() {
            IssuedConnectorToken original = issuedReadWriteToken();
            service.rotateToken(original.tokenId(), null, Duration.ofDays(1), adminId, TokenPermissions.ALL);

            assertThatThrownBy(() -> service.rotateToken(
                            original.tokenId(), null, Duration.ofDays(14), adminId, TokenPermissions.ALL))
                    .isInstanceOf(ConnectorTokenNotRotatableException.class);

            assertThat(tokens.findById(original.tokenId()).orElseThrow().expiresAt())
                    .isEqualTo(NOW.plus(Duration.ofDays(1)));
        }

        /**
         * A token that is revoked, has expired, or was already rotated is no longer one a rotation
         * may replace: refused, with nothing minted and nothing written.
         */
        @Test
        void a_revoked_token_is_not_rotated_and_nothing_is_written() {
            IssuedConnectorToken issued = issuedReadWriteToken();
            service.revokeToken(issued.tokenId(), adminId);
            List<ScimConnectorToken> before = tokens.all();
            int writesBefore = tokens.saves();

            assertThatThrownBy(() -> service.rotateToken(
                            issued.tokenId(), null, null, adminId, TokenPermissions.ALL))
                    .isInstanceOf(ConnectorTokenNotRotatableException.class);

            assertThat(List.of(tokens.all(), tokens.saves())).containsExactly(before, writesBefore);
        }

        @Test
        void an_expired_token_is_not_rotated() {
            IssuedConnectorToken issued = issuedReadWriteToken();
            clock.advanceBy(ConnectorTokenPolicy.DEFAULT_LIFETIME);

            assertThatThrownBy(() -> service.rotateToken(
                            issued.tokenId(), null, null, adminId, TokenPermissions.ALL))
                    .isInstanceOf(ConnectorTokenNotRotatableException.class);
        }

        /** A token one instant short of its expiry is still live, and still rotatable. */
        @Test
        void a_token_at_the_last_instant_of_its_life_is_still_rotated() {
            IssuedConnectorToken issued = issuedReadWriteToken();
            clock.advanceBy(ConnectorTokenPolicy.DEFAULT_LIFETIME.minusNanos(1));

            assertThat(service.rotateToken(issued.tokenId(), null, null, adminId, TokenPermissions.ALL)
                            .tokenId())
                    .isNotEqualTo(issued.tokenId());
        }

        /**
         * Each refusal is audited as one, naming the connector, the token addressed and why — and
         * which of the three applies follows from the token: revoked first, then replaced, then
         * expired.
         */
        @Test
        void a_refused_rotation_is_audited_naming_the_token_and_why() {
            IssuedConnectorToken revoked = issuedReadWriteToken();
            service.revokeToken(revoked.tokenId(), adminId);
            IssuedConnectorToken rotated = issuedReadWriteToken();
            service.rotateToken(rotated.tokenId(), null, Duration.ofDays(1), adminId, TokenPermissions.ALL);
            IssuedConnectorToken expired = issuedReadWriteToken();
            clock.advanceBy(ConnectorTokenPolicy.DEFAULT_LIFETIME);
            audit.reset();

            for (IssuedConnectorToken refused : List.of(revoked, rotated, expired)) {
                assertThatThrownBy(() -> service.rotateToken(
                                refused.tokenId(), null, null, adminId, TokenPermissions.ALL))
                        .isInstanceOf(ConnectorTokenNotRotatableException.class);
            }

            assertThat(audit.events(Audited.ConnectorTokenNotRotatable.class))
                    .extracting(Audited.ConnectorTokenNotRotatable::actorId,
                            Audited.ConnectorTokenNotRotatable::connectorId,
                            Audited.ConnectorTokenNotRotatable::rotatedId,
                            Audited.ConnectorTokenNotRotatable::reason)
                    .containsExactly(
                            tuple(adminId, revoked.connectorId(), revoked.tokenId(),
                                    AuditAdministrativeRefusal.TOKEN_REVOKED),
                            tuple(adminId, rotated.connectorId(), rotated.tokenId(),
                                    AuditAdministrativeRefusal.TOKEN_ALREADY_ROTATED),
                            tuple(adminId, expired.connectorId(), expired.tokenId(),
                                    AuditAdministrativeRefusal.TOKEN_EXPIRED));
            assertThat(audit.events()).as("nothing but the refusals")
                    .allMatch(Audited.ConnectorTokenNotRotatable.class::isInstance);
        }

        /** A revoked token that was also rotated is refused as revoked: revocation reads first. */
        @Test
        void a_rotated_token_revoked_since_is_refused_as_revoked() {
            IssuedConnectorToken original = issuedReadWriteToken();
            service.rotateToken(original.tokenId(), null, Duration.ofDays(1), adminId, TokenPermissions.ALL);
            service.revokeToken(original.tokenId(), adminId);
            audit.reset();

            assertThatThrownBy(() -> service.rotateToken(
                            original.tokenId(), null, null, adminId, TokenPermissions.ALL))
                    .isInstanceOf(ConnectorTokenNotRotatableException.class);

            assertThat(audit.events(Audited.ConnectorTokenNotRotatable.class))
                    .extracting(Audited.ConnectorTokenNotRotatable::reason)
                    .containsExactly(AuditAdministrativeRefusal.TOKEN_REVOKED);
        }

        /**
         * Rotation and revocation each lock the token they address before reading it, so a second
         * writer waits for the first and then reads what it committed.
         * {@code ConnectorTokenRotationRaceIntegrationTests} holds the race itself on SQL Server.
         */
        @Test
        void rotation_and_revocation_each_lock_the_token_they_address() {
            IssuedConnectorToken rotated = issuedReadWriteToken();
            IssuedConnectorToken revoked = issuedReadWriteToken();

            service.rotateToken(rotated.tokenId(), null, null, adminId, TokenPermissions.ALL);
            service.revokeToken(revoked.tokenId(), adminId);

            assertThat(tokenLocks.requests()).containsExactly(rotated.tokenId(), revoked.tokenId());
        }

        @Test
        void no_overlap_ends_the_old_token_at_the_rotation() {
            IssuedConnectorToken original = issuedReadWriteToken();

            service.rotateToken(original.tokenId(), null, null, adminId, TokenPermissions.ALL);

            ScimConnectorToken old = tokens.findById(original.tokenId()).orElseThrow();
            assertThat(old.expiresAt()).isEqualTo(NOW);
            assertThat(old.isUsable(NOW)).isFalse();
        }

        @Test
        void rotation_records_the_replacement_on_the_old_token() {
            IssuedConnectorToken original = issuedReadWriteToken();

            IssuedConnectorToken replacement =
                    service.rotateToken(original.tokenId(), null, Duration.ofDays(1), adminId, TokenPermissions.ALL);

            assertThat(tokens.findById(original.tokenId()).orElseThrow().replacedByTokenId())
                    .isEqualTo(replacement.tokenId());
        }

        @Test
        void rotation_records_one_event_naming_the_connector() {
            IssuedConnectorToken original = issuedReadWriteToken();
            audit.reset();

            service.rotateToken(original.tokenId(), null, Duration.ofDays(1), adminId, TokenPermissions.ALL);

            assertThat(audit.of(AuditOperation.CONNECTOR_TOKEN_ROTATE)).singleElement()
                    .satisfies(event -> {
                        assertThat(event.actorId()).isEqualTo(adminId);
                        assertThat(event.subjectId()).isEqualTo(original.connectorId());
                    });
        }

        /** Issue, rotation and revocation each name the token they concern by its stable id. */
        @Test
        void each_token_event_names_the_token_it_concerns() {
            IssuedConnectorToken original = issuedReadWriteToken();
            IssuedConnectorToken replacement = service.rotateToken(
                    original.tokenId(), null, Duration.ofDays(1), adminId, TokenPermissions.ALL);
            service.revokeToken(replacement.tokenId(), adminId);

            assertThat(List.of(
                            audit.events(Audited.ConnectorTokenIssued.class).getLast().issuedId(),
                            audit.events(Audited.ConnectorTokenRotated.class).getLast().rotatedId(),
                            audit.events(Audited.ConnectorTokenRotated.class).getLast()
                                    .replacementId(),
                            audit.events(Audited.ConnectorTokenRevoked.class).getLast().revokedId()))
                    .containsExactly(original.tokenId(), original.tokenId(),
                            replacement.tokenId(), replacement.tokenId());
        }

        @Test
        void revocation_is_immediate_and_records_one_event() {
            IssuedConnectorToken issued = issuedReadWriteToken();
            audit.reset();

            service.revokeToken(issued.tokenId(), adminId);

            ScimConnectorToken revoked = tokens.findById(issued.tokenId()).orElseThrow();
            assertThat(revoked.revokedAt()).isEqualTo(NOW);
            assertThat(revoked.isUsable(NOW)).isFalse();
            assertThat(audit.of(AuditOperation.CONNECTOR_TOKEN_REVOKE)).singleElement()
                    .satisfies(event -> {
                        assertThat(event.actorId()).isEqualTo(adminId);
                        assertThat(event.subjectId()).isEqualTo(issued.connectorId());
                    });
        }

        @Test
        void revoking_twice_keeps_the_first_time_and_still_records_the_second_attempt() {
            IssuedConnectorToken issued = issuedReadWriteToken();
            service.revokeToken(issued.tokenId(), adminId);
            clock.advanceBy(Duration.ofMinutes(5));
            audit.reset();
            int writesBefore = tokens.saves();

            service.revokeToken(issued.tokenId(), adminId);

            assertThat(tokens.saves()).as("an already-revoked token is not written again")
                    .isEqualTo(writesBefore);
            assertThat(tokens.findById(issued.tokenId()).orElseThrow().revokedAt())
                    .isEqualTo(NOW);
            assertThat(audit.of(AuditOperation.CONNECTOR_TOKEN_REVOKE)).hasSize(1);
        }

        @Test
        void an_unknown_token_id_is_refused_by_both_rotate_and_revoke() {
            UUID unknown = UUID.randomUUID();

            assertThatThrownBy(() -> service.rotateToken(unknown, null, null, adminId, TokenPermissions.ALL))
                    .isInstanceOf(UnknownConnectorException.class);
            assertThatThrownBy(() -> service.revokeToken(unknown, adminId))
                    .isInstanceOf(UnknownConnectorException.class);
        }

        @Test
        void the_listing_reports_a_tokens_state_as_of_now() {
            IssuedConnectorToken issued = issuedReadWriteToken();

            List<ConnectorTokenSummary> before = service.listConnectors().getFirst().tokens();
            assertThat(before).singleElement().satisfies(token -> {
                assertThat(token.id()).isEqualTo(issued.tokenId());
                assertThat(token.permissions()).containsExactly("group:read", "group:write", "user:read", "user:write");
                assertThat(token.issuedAt()).isEqualTo(NOW);
                assertThat(token.expiresAt()).isEqualTo(issued.expiresAt());
                assertThat(token.originalExpiresAt()).isEqualTo(issued.expiresAt());
                assertThat(token.revokedAt()).isNull();
                assertThat(token.active()).isTrue();
            });

            service.revokeToken(issued.tokenId(), adminId);

            assertThat(service.listConnectors().getFirst().tokens())
                    .singleElement()
                    .satisfies(token -> {
                        assertThat(token.active()).isFalse();
                        assertThat(token.revokedAt()).isEqualTo(NOW);
                    });
        }

        /**
         * A rotated token's projection reports the SHORTENED expiry beside the issued
         * one, which is the pair an Admin reads to see an overlap window in force.
         */
        @Test
        void the_listing_shows_a_shortened_overlap_beside_the_issued_expiry() {
            IssuedConnectorToken original = issuedReadWriteToken();
            service.rotateToken(original.tokenId(), null, Duration.ofDays(7), adminId, TokenPermissions.ALL);

            assertThat(service.listConnectors().getFirst().tokens())
                    .filteredOn(token -> token.id().equals(original.tokenId()))
                    .singleElement()
                    .satisfies(token -> {
                        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
                        assertThat(token.originalExpiresAt()).isEqualTo(original.expiresAt());
                    });
        }
    }

    /**
     * An empty token list rather than a null one, so a caller iterating a connector's
     * tokens never has to check. Asserted directly on the projection because no use
     * case produces the null — and a defensive coercion nothing exercises is a
     * coercion that quietly stops working.
     */
    @Test
    void a_connector_projection_normalises_a_missing_token_list_to_an_empty_one() {
        ConnectorSummary nullTokens =
                new ConnectorSummary(UUID.randomUUID(), "Okta", NOW, null);

        assertThat(nullTokens.tokens()).isEmpty();
    }

    /**
     * Each lifecycle write reports itself to the log stream, as a named action and
     * outcome and nothing else.
     *
     * <p>Asserted rather than assumed because a void call leaves no return value a
     * mutation could be traced through: {@code succeeded(...)} could be deleted from
     * all five use cases and every other test here would still pass. What is checked
     * is also what must NOT be there — no connector id, no token id, and no token
     * value, because the log stream is read by more people and kept longer than the
     * audit trail that names a subject properly.
     */
    @Test
    void every_lifecycle_write_reports_its_action_to_the_log_stream() {
        try (CapturedLog log = CapturedLog.attach()) {
            ConnectorSummary connector = service.create("Okta", adminId);
            IssuedConnectorToken issued = service.issueToken(
                    connector.id(), TokenPermissions.ALL, null, adminId, TokenPermissions.ALL);
            IssuedConnectorToken rotated =
                    service.rotateToken(issued.tokenId(), null, Duration.ofDays(1), adminId, TokenPermissions.ALL);
            service.revokeToken(rotated.tokenId(), adminId);
            service.delete(connector.id(), adminId);

            List<String> actions = List.of(
                    "scim.connector.create",
                    "scim.connector.token.issue",
                    "scim.connector.token.rotate",
                    "scim.connector.token.revoke",
                    "scim.connector.delete");
            for (String action : actions) {
                assertThat(log.withAction(Level.INFO, LogEvent.LOCAL_ACTION, action))
                        .as("%s", action)
                        .singleElement()
                        .satisfies(record -> {
                            assertThat(CapturedLog.fields(record))
                                    .containsEntry(LogEvent.OUTCOME, LogEvent.SUCCESS);
                            assertThat(record.getMessage())
                                    .isEqualTo("SCIM connector lifecycle change applied");
                            assertThat(record.getFormattedMessage())
                                    .doesNotContain(connector.id().toString())
                                    .doesNotContain(issued.tokenId().toString())
                                    .doesNotContain(issued.presentedValue())
                                    .doesNotContain(rotated.presentedValue());
                            assertThat(CapturedLog.fields(record).values())
                                    .allSatisfy(value -> assertThat(String.valueOf(value))
                                            .doesNotContain(issued.presentedValue())
                                            .doesNotContain(rotated.presentedValue()));
                        });
            }
        }
    }

    /**
     * A refused escalation is one {@code WARN} record classified as a denied administrative
     * change, naming no Permission — the audit trail names them, the log stream does not.
     */
    @Test
    void a_refused_escalation_is_one_warn_record_naming_no_permission() {
        ConnectorSummary connector = service.create("Okta", adminId);
        IssuedConnectorToken issued = service.issueToken(
                connector.id(), TokenPermissions.ALL, null, adminId, TokenPermissions.ALL);
        Set<Permission> held = Set.of(Permission.CONNECTOR_TOKEN, Permission.GROUP_READ);
        try (CapturedLog log = CapturedLog.attach()) {
            assertThatThrownBy(() -> service.issueToken(
                    connector.id(), List.of(Permission.USER_WRITE), null, adminId, held))
                    .isInstanceOf(ConnectorTokenEscalationException.class);
            assertThatThrownBy(() -> service.rotateToken(
                    issued.tokenId(), null, null, adminId, held))
                    .isInstanceOf(ConnectorTokenEscalationException.class);

            for (String action : List.of("scim.connector.token.issue", "scim.connector.token.rotate")) {
                assertThat(log.withAction(Level.TRACE, LogEvent.LOCAL_ACTION, action))
                        .as("%s", action)
                        .singleElement()
                        .satisfies(record -> {
                            assertThat(record.getLevel()).isEqualTo(Level.WARN);
                            assertThat(CapturedLog.fields(record))
                                    .containsEntry(LogEvent.OUTCOME, LogEvent.FAILURE)
                                    .containsEntry(LogEvent.ACTION, "user-administration")
                                    .containsEntry(LogEvent.TYPE, List.of("admin", "denied"));
                            assertThat(record.getFormattedMessage() + CapturedLog.fields(record))
                                    .doesNotContain("user:")
                                    .doesNotContain("group:");
                        });
            }
        }
    }

    /**
     * A rotation of a token that can no longer be rotated is one {@code WARN} record of the
     * refused rotation, naming neither the token nor why — the audit trail names both.
     */
    @Test
    void a_rotation_refused_as_not_rotatable_is_one_warn_record_naming_no_token() {
        ConnectorSummary connector = service.create("Okta", adminId);
        IssuedConnectorToken issued = service.issueToken(
                connector.id(), TokenPermissions.ALL, null, adminId, TokenPermissions.ALL);
        service.revokeToken(issued.tokenId(), adminId);
        try (CapturedLog log = CapturedLog.attach()) {
            assertThatThrownBy(() -> service.rotateToken(
                    issued.tokenId(), null, null, adminId, TokenPermissions.ALL))
                    .isInstanceOf(ConnectorTokenNotRotatableException.class);

            assertThat(log.withAction(
                            Level.TRACE, LogEvent.LOCAL_ACTION, "scim.connector.token.rotate"))
                    .singleElement()
                    .satisfies(record -> {
                        assertThat(record.getLevel()).isEqualTo(Level.WARN);
                        assertThat(CapturedLog.fields(record))
                                .containsEntry(LogEvent.OUTCOME, LogEvent.FAILURE)
                                .containsEntry(LogEvent.TYPE, List.of("admin", "denied"));
                        assertThat(record.getFormattedMessage() + CapturedLog.fields(record))
                                .doesNotContain(issued.tokenId().toString())
                                .doesNotContain("REVOKED");
                    });
        }
    }

    /**
     * The acting administrator is named by the stable id its session holds, so a rename
     * between signing in and acting — even one that hands its former name to another User —
     * changes nothing about who is recorded as the actor.
     */
    @Test
    void a_renamed_administrator_is_still_recorded_as_the_actor_by_its_stable_id() {
        users.rename(adminId, "renamed-admin");
        users.given(ScimIdentities.user(ADMIN));

        service.create("Okta", adminId);

        assertThat(audit.of(AuditOperation.CONNECTOR_CREATE)).singleElement()
                .satisfies(event -> assertThat(event.actorId()).isEqualTo(adminId));
    }

    /**
     * A session that identifies nobody still gets the event, with no actor: no guard here
     * turns on who the actor is, and refusing would fail an administrative action over the
     * record of it.
     */
    @Test
    void an_unidentified_administrator_leaves_the_actor_empty_rather_than_failing() {
        ConnectorSummary created = service.create("Okta", null);

        assertThat(audit.of(AuditOperation.CONNECTOR_CREATE)).singleElement()
                .satisfies(event -> {
                    assertThat(event.actorId()).isNull();
                    assertThat(event.subjectId()).isEqualTo(created.id());
                });
    }

    private IssuedConnectorToken issuedReadWriteToken() {
        ConnectorSummary connector = service.create("Okta", adminId);
        return service.issueToken(
                connector.id(), TokenPermissions.ALL, null, adminId, TokenPermissions.ALL);
    }

    /**
     * The stored row is the one the disclosed value names: its lookup id is the value's non-secret
     * half, and its digest is the value's — so the connector holding the value can authenticate.
     */
    private void assertStoredAs(IssuedConnectorToken issued) {
        ScimConnectorToken stored = tokens.findById(issued.tokenId()).orElseThrow();
        ConnectorTokenSecret.Presented presented =
                ConnectorTokenSecret.parse(issued.presentedValue()).orElseThrow();
        assertThat(stored.lookupId()).isEqualTo(presented.lookupId());
        assertThat(stored.digest().matches(presented.digest())).isTrue();
    }

    /** What a token carries, and the no-escalation rule on issue and rotation (ADR 0010). */
    @Nested
    class Permissions {

        /** A Connector admin's own directory Permissions, plus the right to mint tokens. */
        private static final Set<Permission> GROUPS_ADMIN = Set.of(
                Permission.CONNECTOR_TOKEN, Permission.GROUP_READ, Permission.GROUP_WRITE);

        @Test
        void an_issued_token_carries_and_records_exactly_the_permissions_asked_for() {
            ConnectorSummary connector = service.create("Okta", adminId);
            audit.reset();

            IssuedConnectorToken issued = service.issueToken(connector.id(),
                    List.of(Permission.GROUP_WRITE), null, adminId, GROUPS_ADMIN);

            assertThat(issued.permissions()).containsExactly("group:write");
            assertThat(tokens.findById(issued.tokenId()).orElseThrow().permissions().values())
                    .containsExactly(Permission.GROUP_WRITE);
            assertStoredAs(issued);
            assertThat(service.listConnectors().getFirst().tokens()).singleElement()
                    .satisfies(token -> assertThat(token.permissions())
                            .containsExactly("group:write"));
            assertThat(audit.recorded()).singleElement().satisfies(event -> {
                assertThat(event.operation()).isEqualTo(AuditOperation.CONNECTOR_TOKEN_ISSUE);
                assertThat(event.actorId()).isEqualTo(adminId);
                assertThat(event.subjectId()).isEqualTo(connector.id());
                assertThat(event.detail()).isEqualTo("group:write");
            });
        }

        @Test
        void an_issue_asking_for_a_permission_the_caller_lacks_is_refused_and_audited() {
            ConnectorSummary connector = service.create("Okta", adminId);
            audit.reset();

            assertThatThrownBy(() -> service.issueToken(connector.id(),
                    List.of(Permission.GROUP_WRITE, Permission.USER_WRITE), null, adminId,
                    GROUPS_ADMIN))
                    .isInstanceOf(ConnectorTokenEscalationException.class);

            assertThat(tokens.all()).isEmpty();
            assertThat(audit.recorded()).singleElement().satisfies(event -> {
                assertThat(event.operation()).isEqualTo(AuditOperation.CONNECTOR_TOKEN_ISSUE);
                assertThat(event.actorId()).isEqualTo(adminId);
                assertThat(event.subjectId()).isEqualTo(connector.id());
                assertThat(event.detail())
                        .isEqualTo("PERMISSION_ESCALATION group:write,user:write");
            });
        }

        /** Holding {@code connector:token} itself is not a Permission a token can be given. */
        @Test
        void holding_every_permission_does_not_make_a_non_directory_one_issuable() {
            ConnectorSummary connector = service.create("Okta", adminId);
            audit.reset();
            Set<Permission> everything = Set.of(Permission.values());

            assertThatThrownBy(() -> service.issueToken(connector.id(),
                    List.of(Permission.CONNECTOR_TOKEN), null, adminId, everything))
                    .isInstanceOf(InvalidConnectorTokenPermissionsException.class);
            assertThatThrownBy(() -> service.issueToken(connector.id(),
                    List.of(), null, adminId, everything))
                    .isInstanceOf(InvalidConnectorTokenPermissionsException.class);

            assertThat(tokens.all()).isEmpty();
            assertThat(audit.recorded()).as("a malformed request is not an escalation").isEmpty();
        }

        /** The request's own shape is checked before the connector is looked up. */
        @Test
        void a_malformed_request_is_refused_before_the_connector_is_looked_up() {
            assertThatThrownBy(() -> service.issueToken(UUID.randomUUID(),
                    List.of(), null, adminId, TokenPermissions.ALL))
                    .isInstanceOf(InvalidConnectorTokenPermissionsException.class);
            assertThatThrownBy(() -> service.issueToken(UUID.randomUUID(),
                    TokenPermissions.READ, Duration.ofDays(400), adminId, TokenPermissions.ALL))
                    .isInstanceOf(InvalidConnectorTokenLifetimeException.class);
        }

        @Test
        void an_escalation_on_an_unknown_connector_is_unknown_not_audited() {
            assertThatThrownBy(() -> service.issueToken(UUID.randomUUID(),
                    List.of(Permission.USER_WRITE), null, adminId, GROUPS_ADMIN))
                    .isInstanceOf(UnknownConnectorException.class);
            assertThat(audit.recorded()).isEmpty();
        }

        @Test
        void rotation_without_permissions_keeps_the_old_tokens_and_records_them() {
            ConnectorSummary connector = service.create("Okta", adminId);
            IssuedConnectorToken original = service.issueToken(connector.id(),
                    List.of(Permission.GROUP_READ, Permission.GROUP_WRITE), null, adminId,
                    GROUPS_ADMIN);
            audit.reset();

            IssuedConnectorToken replacement =
                    service.rotateToken(original.tokenId(), null, null, adminId, GROUPS_ADMIN);

            assertThat(replacement.permissions()).containsExactly("group:read", "group:write");
            assertStoredAs(replacement);
            assertThat(audit.of(AuditOperation.CONNECTOR_TOKEN_ROTATE)).singleElement()
                    .satisfies(event -> {
                        assertThat(event.actorId()).isEqualTo(adminId);
                        assertThat(event.subjectId()).isEqualTo(connector.id());
                        assertThat(event.detail()).isEqualTo("group:read,group:write");
                    });
        }

        @Test
        void rotation_with_permissions_replaces_them() {
            IssuedConnectorToken original = issuedReadWriteToken();

            IssuedConnectorToken replacement = service.rotateToken(original.tokenId(),
                    List.of(Permission.USER_READ), null, adminId, TokenPermissions.ALL);

            assertThat(replacement.permissions()).containsExactly("user:read");
            assertThat(tokens.findById(original.tokenId()).orElseThrow().permissions().values())
                    .as("the old token keeps what it was issued with")
                    .containsExactlyInAnyOrderElementsOf(TokenPermissions.ALL);
        }

        /**
         * Rotating is minting: a token a Superuser issued cannot be renewed with its Permissions
         * by a caller who does not hold them, and the old token is left exactly as it was.
         */
        @Test
        void rotation_keeping_permissions_the_caller_lacks_is_refused_and_audited() {
            IssuedConnectorToken original = issuedReadWriteToken();
            ScimConnectorToken before = tokens.findById(original.tokenId()).orElseThrow();
            audit.reset();

            assertThatThrownBy(() -> service.rotateToken(
                    original.tokenId(), null, Duration.ofDays(7), adminId, GROUPS_ADMIN))
                    .isInstanceOf(ConnectorTokenEscalationException.class);

            assertThat(tokens.all()).singleElement().isEqualTo(before);
            assertThat(audit.recorded()).singleElement().satisfies(event -> {
                assertThat(event.operation()).isEqualTo(AuditOperation.CONNECTOR_TOKEN_ROTATE);
                assertThat(event.actorId()).isEqualTo(adminId);
                assertThat(event.subjectId()).isEqualTo(original.connectorId());
                assertThat(event.detail()).isEqualTo(
                        "PERMISSION_ESCALATION group:read,group:write,user:read,user:write");
            });
            assertThat(audit.events(Audited.ConnectorTokenRotateRefused.class))
                    .extracting(Audited.ConnectorTokenRotateRefused::rotatedId)
                    .as("the refusal names the token the rotation addressed")
                    .containsExactly(original.tokenId());
        }

        @Test
        void rotation_asking_for_a_permission_the_caller_lacks_is_refused() {
            ConnectorSummary connector = service.create("Okta", adminId);
            IssuedConnectorToken original = service.issueToken(connector.id(),
                    List.of(Permission.GROUP_READ), null, adminId, GROUPS_ADMIN);

            assertThatThrownBy(() -> service.rotateToken(original.tokenId(),
                    List.of(Permission.USER_READ), null, adminId, GROUPS_ADMIN))
                    .isInstanceOf(ConnectorTokenEscalationException.class);
            assertThat(tokens.all()).hasSize(1);
        }

        /** A token stored before Permissions existed has none to keep. */
        @Test
        void a_token_carrying_no_permissions_must_be_rotated_with_some() {
            ConnectorSummary connector = service.create("Okta", adminId);
            ScimConnectorToken legacy = ScimConnectorToken.issue(UUID.randomUUID(),
                    connector.id(), "legacy", ConnectorTokenDigest.of("legacy.secret"),
                    TokenPermissions.of(Set.of()), NOW, NOW.plus(Duration.ofDays(30)));
            tokens.save(legacy);

            assertThatThrownBy(() -> service.rotateToken(
                    legacy.id(), null, null, adminId, TokenPermissions.ALL))
                    .isInstanceOf(InvalidConnectorTokenPermissionsException.class);

            IssuedConnectorToken replacement = service.rotateToken(legacy.id(),
                    List.of(Permission.USER_READ), null, adminId, TokenPermissions.ALL);
            assertThat(replacement.permissions()).containsExactly("user:read");
        }
    }
}

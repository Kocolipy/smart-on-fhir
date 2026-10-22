package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditEventReader;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.application.ConnectorSummary;
import com.example.backend.scim.application.ConnectorTokenNotRotatableException;
import com.example.backend.scim.application.ConnectorTokenSummary;
import com.example.backend.scim.application.IssuedConnectorToken;
import com.example.backend.scim.domain.ConnectorTokenRotationRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Two real rotations of one connector token on SQL Server, released together. The token row is
 * locked before either decides whether the token may still be rotated, so the second waits for
 * the first to commit and then finds the token already replaced: exactly one live replacement,
 * and the other rotation refused as not rotatable — every round.
 *
 * <p>Repeated, because two writers that happen to serialize once prove nothing. Before the lock,
 * both rotations read the token unrotated and both minted a live replacement for one credential.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ConnectorTokenRotationRaceIntegrationTests {

    private static final int ROUNDS = 10;

    /** What a rotation that went through is reported as, beside a refusal's exception name. */
    private static final String ROTATED = "rotated";

    /** The refusal the losing rotation must meet: its class's simple name. */
    private static final String NOT_ROTATABLE = "ConnectorTokenNotRotatableException";

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private AuditEventReader trail;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void deleteOnlyTheConnectorsThisTestCreated() {
        for (UUID connector : created) {
            connectors.delete(connector, null);
        }
        created.clear();
    }

    @Test
    void two_concurrent_rotations_of_one_token_mint_exactly_one_live_replacement() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            ConnectorSummary connector = connectors.create(
                    "Rotation race " + UUID.randomUUID().toString().substring(0, 8), null);
            created.add(connector.id());
            IssuedConnectorToken original = connectors.issueToken(connector.id(),
                    TokenPermissions.READ, null, null);

            Callable<String> rotation = () -> {
                connectors.rotateToken(original.tokenId(), null, null, null);
                return ROTATED;
            };
            List<String> outcomes = race(rotation, rotation);

            List<ConnectorTokenSummary> live = connectors.listConnectors().stream()
                    .filter(listed -> listed.id().equals(connector.id()))
                    .flatMap(listed -> listed.tokens().stream())
                    .filter(ConnectorTokenSummary::active)
                    .toList();
            if (!outcomes.stream().sorted().toList().equals(List.of(NOT_ROTATABLE, ROTATED))
                    || live.size() != 1) {
                failures.add("round " + round + ": outcomes " + outcomes
                        + ", live tokens " + live.size());
            }
        }
        assertThat(failures).isEmpty();
    }

    /**
     * The trail names the tokens a rotation concerns, as stored ids, appended in the rotation's own
     * transaction: the rotated token and its replacement. The refusal of a second rotation names
     * the token it addressed and why, and records nothing itself — the administrative edge appends
     * it on the audit pool, which {@code ScimConnectorLifecycleIntegrationTests} drives over HTTP.
     */
    @Test
    void a_rotation_and_the_refusal_of_a_second_name_the_tokens_they_concern() {
        ConnectorSummary connector = connectors.create(
                "Rotation trail " + UUID.randomUUID().toString().substring(0, 8), null);
        created.add(connector.id());
        IssuedConnectorToken original = connectors.issueToken(connector.id(),
                TokenPermissions.READ, null, null);
        IssuedConnectorToken replacement = connectors.rotateToken(
                original.tokenId(), null, null, null);
        assertThatExceptionOfType(ConnectorTokenNotRotatableException.class)
                .isThrownBy(() -> connectors.rotateToken(original.tokenId(), null, null, null))
                .satisfies(refused -> assertThat(List.of(refused.connectorId(),
                                refused.rotatedId(), refused.reason()))
                        .containsExactly(connector.id(), original.tokenId(),
                                ConnectorTokenRotationRefusal.ALREADY_ROTATED));

        List<AuditEvent> rotations = trail.find(new AuditEventQuery(
                AuditOperation.CONNECTOR_TOKEN_ROTATE, null, null, connector.id(), null, null, 0,
                10)).events();

        assertThat(rotations)
                .extracting(AuditEvent::outcome, AuditEvent::errorCode, AuditEvent::tokenId,
                        AuditEvent::replacementTokenId)
                .containsExactly(
                        tuple(AuditOutcome.SUCCESS, null, original.tokenId(),
                                replacement.tokenId()));
    }

    /** Runs both calls released together; reports each as its result or its failure's name. */
    private static List<String> race(Callable<String> first, Callable<String> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> calls = new ArrayList<>();
            for (Callable<String> call : List.of(first, second)) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> call : calls) {
                try {
                    outcomes.add(call.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException failed) {
                    outcomes.add(failed.getCause().getClass().getSimpleName());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}

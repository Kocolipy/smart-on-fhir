package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditEventReader;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditOutcome;
import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.application.ConnectorSummary;
import com.example.backend.scim.application.IssuedConnectorToken;
import com.example.backend.scim.application.UnknownConnectorException;
import com.example.backend.scim.domain.ScimConnectorToken;
import com.example.backend.scim.domain.ScimConnectorTokenRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A connector delete raced on SQL Server, against a second delete and against a token issue,
 * released together. Each locks the connector's row before reading it, so the later one waits for
 * the earlier to commit and then reads what it committed: the second delete finds the connector
 * deleted and is refused, and an issue either commits first — and the delete then revokes its
 * token — or finds the connector deleted and is refused. Every round.
 *
 * <p>Repeated, because two writers that happen to serialize once prove nothing. Before the lock,
 * both deletes read the connector live under read-committed snapshot and both went through,
 * recording the delete and every revocation twice, and an issue could commit a live token for a
 * connector already deleted.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ConnectorLifecycleRaceIntegrationTests {

    private static final int ROUNDS = 10;

    /** What a write that went through is reported as, beside a refusal's exception name. */
    private static final String DONE = "done";

    /** The refusal the losing write must meet: its class's simple name. */
    private static final String UNKNOWN = UnknownConnectorException.class.getSimpleName();

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private ScimConnectorTokenRepository tokens;

    @Autowired
    private AuditEventReader trail;

    @Test
    void of_two_concurrent_deletes_of_one_connector_exactly_one_goes_through_and_is_recorded()
            throws Exception {
        List<String> failures = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            ConnectorSummary connector = connectors.create(
                    "Delete race " + UUID.randomUUID().toString().substring(0, 8), null);
            connectors.issueToken(connector.id(), TokenPermissions.READ, null, null);
            connectors.issueToken(connector.id(), TokenPermissions.READ, null, null);

            Callable<String> delete = () -> {
                connectors.delete(connector.id(), null);
                return DONE;
            };
            List<String> outcomes = race(delete, delete);

            Map<AuditOperation, Long> recorded = successes(connector.id());
            Map<AuditOperation, Long> expected = Map.of(
                    AuditOperation.CONNECTOR_DELETE, 1L,
                    AuditOperation.CONNECTOR_TOKEN_REVOKE, 2L);
            if (!outcomes.stream().sorted().toList().equals(List.of(UNKNOWN, DONE))
                    || !recorded.equals(expected)) {
                failures.add("round " + round + ": outcomes " + outcomes
                        + ", recorded " + recorded);
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void a_token_issued_racing_a_delete_is_revoked_by_it_or_refused() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            ConnectorSummary connector = connectors.create(
                    "Issue race " + UUID.randomUUID().toString().substring(0, 8), null);
            List<UUID> issued = new ArrayList<>();

            Callable<String> issue = () -> {
                IssuedConnectorToken token = connectors.issueToken(
                        connector.id(), TokenPermissions.READ, null, null);
                synchronized (issued) {
                    issued.add(token.tokenId());
                }
                return DONE;
            };
            Callable<String> delete = () -> {
                connectors.delete(connector.id(), null);
                return DONE;
            };
            List<String> outcomes = race(issue, delete);

            List<UUID> survivors = issued.stream()
                    .filter(id -> !tokens.findById(id).map(ScimConnectorToken::isRevoked)
                            .orElse(false))
                    .toList();
            boolean issueAccountedFor = outcomes.get(0).equals(UNKNOWN)
                    || (outcomes.get(0).equals(DONE) && issued.size() == 1);
            if (!issueAccountedFor || !outcomes.get(1).equals(DONE) || !survivors.isEmpty()) {
                failures.add("round " + round + ": outcomes " + outcomes
                        + ", live tokens after the delete " + survivors);
            }
        }
        assertThat(failures).isEmpty();
    }

    /** How many successful events of each operation the trail holds about this connector. */
    private Map<AuditOperation, Long> successes(UUID connectorId) {
        return trail.find(new AuditEventQuery(
                        Set.of(AuditOperation.CONNECTOR_DELETE,
                                AuditOperation.CONNECTOR_TOKEN_REVOKE),
                        AuditOutcome.SUCCESS, null, connectorId, null, null,
                        new PageRequest(1, 20)))
                .events().stream()
                .collect(Collectors.groupingBy(AuditEvent::operation, Collectors.counting()));
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

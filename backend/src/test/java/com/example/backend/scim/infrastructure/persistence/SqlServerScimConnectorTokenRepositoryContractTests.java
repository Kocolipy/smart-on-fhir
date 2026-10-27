package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.database.ConnectionQualifiers;
import com.example.backend.scim.ScimConnectorTokenRepositoryContract;
import com.example.backend.scim.domain.ConnectorTokenDigest;
import com.example.backend.scim.domain.ConnectorTokenPermissions;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorRepository;
import com.example.backend.scim.domain.ScimConnectorToken;
import com.example.backend.scim.domain.ScimConnectorTokenRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The production connector token port — the JPA adapter over SQL Server — holds to the rules the
 * in-memory store does, and one rule no in-memory store models: a refused duplicate lookup id
 * leaves its transaction unable to commit.
 *
 * <p>It shares the plain cached context {@link ScimUserPersistenceAdapterIntegrationTests} carries,
 * and with it a database other classes use, so each case runs in a transaction rolled back after
 * it ({@link RollsBackEachCase}). The adapter flushes each save as it makes it, and its bulk
 * revocation clears the persistence context, so every read-back is the row's.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
@ExtendWith(RollsBackEachCase.class)
class SqlServerScimConnectorTokenRepositoryContractTests
        extends ScimConnectorTokenRepositoryContract {

    private static final Instant ISSUED = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ScimConnectorTokenRepository tokens;

    @Autowired
    private ScimConnectorRepository connectors;

    @Autowired
    @Qualifier(ConnectionQualifiers.MAIN_TRANSACTIONS)
    private PlatformTransactionManager transactions;

    @Override
    protected ScimConnectorTokenRepository tokens() {
        return tokens;
    }

    @Override
    protected ScimConnectorRepository connectors() {
        return connectors;
    }

    /**
     * Code that catches the refusal and carries on — as a fake that only throws would let it —
     * commits nothing on SQL Server: the violation marked the transaction rollback-only, so the
     * commit fails and the connector saved before it is gone too. Its own transaction, so the
     * commit is a real one; nothing survives it to clean up.
     */
    @Test
    void a_refused_duplicate_lookup_id_leaves_its_transaction_unable_to_commit() {
        UUID connector = UUID.randomUUID();
        TransactionTemplate own = new TransactionTemplate(transactions);
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> own.executeWithoutResult(status -> {
            connectors.save(ScimConnector.create(connector, "Okta", ISSUED));
            ScimConnectorToken first = token(connector, "duplicate-" + connector);
            tokens.save(first);
            try {
                tokens.save(token(connector, first.lookupId()));
            } catch (DataIntegrityViolationException refused) {
                // carried on past, as catch-and-continue code would
            }
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(connectors.findById(connector)).isEmpty();
    }

    private static ScimConnectorToken token(UUID connector, String lookupId) {
        return ScimConnectorToken.issue(UUID.randomUUID(), connector, lookupId,
                ConnectorTokenDigest.of(UUID.randomUUID().toString()),
                new ConnectorTokenPermissions(Set.of(Permission.USER_READ)), ISSUED,
                ISSUED.plusSeconds(3600));
    }
}

package com.example.backend.scim.infrastructure.persistence;

import com.example.backend.database.ConnectionQualifiers;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * Runs each case of a {@code @SpringBootTest} class in a transaction and rolls it back after the
 * case, so nothing a case writes to the shared database outlives it, the seeded Bootstrap Admin a
 * case deletes included. The transaction is the context's main one, which the adapters write
 * through; it is named because the audit retention pool has a manager of its own.
 *
 * <p>For a class whose cases are declared on an abstract contract it extends. Spring's test
 * transactions read {@code @Transactional} off the class declaring the test method, which is the
 * contract, so the annotation on the subclass opens nothing. Registered with
 * {@code @ExtendWith} after {@code @SpringBootTest}, its callbacks run inside the
 * {@code SpringExtension}'s test, as {@code EpicTestEnvironment}'s do.
 */
final class RollsBackEachCase implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(RollsBackEachCase.class);

    @Override
    public void beforeEach(ExtensionContext extension) {
        TransactionStatus transaction = transactionManager(extension)
                .getTransaction(TransactionDefinition.withDefaults());
        extension.getStore(NAMESPACE).put(TransactionStatus.class, transaction);
    }

    @Override
    public void afterEach(ExtensionContext extension) {
        TransactionStatus transaction = extension.getStore(NAMESPACE)
                .remove(TransactionStatus.class, TransactionStatus.class);
        transactionManager(extension).rollback(transaction);
    }

    private static PlatformTransactionManager transactionManager(ExtensionContext extension) {
        return SpringExtension.getApplicationContext(extension).getBean(
                ConnectionQualifiers.MAIN_TRANSACTIONS, PlatformTransactionManager.class);
    }
}

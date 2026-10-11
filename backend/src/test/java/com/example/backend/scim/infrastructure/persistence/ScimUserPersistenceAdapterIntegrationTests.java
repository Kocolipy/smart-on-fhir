package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.Lockout;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The User store against a real Postgres, asserting only that it stores what it was given. The
 * login-state rules — a Reactivation, the Dormancy lock, a connector-set password owing a change —
 * are the login-state module's and are asserted in {@code LoginStateServiceTests}; the store must
 * hold none of them, so each case here hands it a value no rule would produce and reads back that
 * same value.
 *
 * <p>Each write runs in its own transaction, after a locked read of the User, as the SCIM write
 * path calls it.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimUserPersistenceAdapterIntegrationTests {

    /** Whole microseconds, so the instant survives the {@code timestamptz} column unchanged. */
    private static final Instant EARLIER = Instant.parse("2026-03-01T08:00:00Z");

    private static final Instant LATER = Instant.parse("2026-05-01T08:00:00Z");

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        inTransaction(() -> {
            created.forEach(id -> users.deleteById(id, Instant.now()));
            return null;
        });
    }

    /**
     * A replacement that takes {@code active} from false to true, given the login state as it was
     * read, stores that login state: the store performs no Reactivation of its own.
     */
    @Test
    void aReplacementActivatingTheUserStoresTheLoginStateItWasGiven() {
        ScimUser stored = store(ScimIdentities.inactiveUser(unique("adapter-activate")),
                new ScimLoginState("hash", 0, null, null, EARLIER, null));

        ScimLoginState written = replace(stored, true, stored.login());

        assertThat(written).isEqualTo(new ScimLoginState("hash", 0, null, null, EARLIER, null));
    }

    /**
     * The credential, the change-required flag and the dormancy basis are stored as given — a
     * cleared flag included, which no directory write's rule produces but the store must not
     * second-guess.
     */
    @Test
    void aReplacementStoresTheGivenCredentialFlagAndBasis() {
        ScimUser stored = store(ScimIdentities.user(unique("adapter-store")),
                new ScimLoginState("hash", 0, null, null, EARLIER, EARLIER));

        ScimLoginState written = replace(stored, true,
                new ScimLoginState("new-hash", 0, null, null, LATER, null));

        assertThat(written).isEqualTo(new ScimLoginState("new-hash", 0, null, null, LATER, null));
    }

    /** The failure run and the lock are the login path's; a replacement never stores them. */
    @Test
    void aReplacementLeavesTheStoredFailureRunAndLock() {
        ScimUser stored = store(ScimIdentities.user(unique("adapter-run")),
                new ScimLoginState("hash", 2, EARLIER, LockCause.DORMANCY, EARLIER, null));

        ScimLoginState written = replace(stored, true,
                new ScimLoginState("hash", 0, null, null, EARLIER, null));

        assertThat(written).isEqualTo(
                new ScimLoginState("hash", 2, EARLIER, LockCause.DORMANCY, EARLIER, null));
    }

    /**
     * The resource lock the SCIM write holds does not cover the User row, so a Login can record
     * its authentication between the write's locked read and its replacement. The replacement is
     * given the basis as read, older than the one just committed; it must not write it back.
     */
    @Test
    void aReplacementKeepsADormancyBasisALoginRecordedAfterTheLockedRead() {
        ScimUser stored = store(ScimIdentities.user(unique("adapter-race")),
                new ScimLoginState("hash", 0, null, null, EARLIER, null));

        inTransaction(() -> {
            ScimUser locked = users.findByIdForUpdate(stored.id()).orElseThrow();
            inNewTransaction(() -> {
                users.recordAuthentication(stored.id(), LATER);
                return null;
            });
            return users.replace(ScimIdentities.withActive(locked, true, locked.login()),
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
        });

        assertThat(read(stored.id()).lastAuthenticatedAt()).isEqualTo(LATER);
    }

    /** The lock is stored with the cause given, and the failure run is not written. */
    @Test
    void imposingALockStoresTheGivenInstantAndCause() {
        ScimUser stored = store(ScimIdentities.user(unique("adapter-lock")),
                new ScimLoginState("hash", 1, null, null, EARLIER, null));

        boolean imposed = inTransaction(() -> users.imposeLock(stored.id(),
                new Lockout(LATER, LockCause.FAILURES)));

        assertThat(imposed).as("stored").isTrue();
        assertThat(read(stored.id())).isEqualTo(
                new ScimLoginState("hash", 1, LATER, LockCause.FAILURES, EARLIER, null));
    }

    /** A lock that stands is kept with its own cause: the statement is a compare-and-set. */
    @Test
    void imposingALockOverAStandingOneStoresNothing() {
        ScimUser stored = store(ScimIdentities.user(unique("adapter-locked")),
                new ScimLoginState("hash", 3, EARLIER, LockCause.FAILURES, EARLIER, null));

        boolean imposed = inTransaction(() -> users.imposeLock(stored.id(),
                new Lockout(LATER, LockCause.DORMANCY)));

        assertThat(imposed).as("stored").isFalse();
        assertThat(read(stored.id())).isEqualTo(
                new ScimLoginState("hash", 3, EARLIER, LockCause.FAILURES, EARLIER, null));
    }

    // ---- helpers --------------------------------------------------------------------------

    private ScimUser store(ScimUser user, ScimLoginState login) {
        ScimUser withLogin = ScimIdentities.withActive(user, user.profile().active(), login);
        ScimUser stored = inTransaction(() -> users.create(withLogin));
        created.add(stored.id());
        return stored;
    }

    /** A replacement as the SCIM write path makes it: locked read, then the write, one transaction. */
    private ScimLoginState replace(ScimUser user, boolean active, ScimLoginState login) {
        return inTransaction(() -> {
            ScimUser locked = users.findByIdForUpdate(user.id()).orElseThrow();
            return users.replace(ScimIdentities.withActive(locked, active, login),
                    Instant.now().truncatedTo(ChronoUnit.MICROS)).orElseThrow().login();
        });
    }

    private ScimLoginState read(UUID id) {
        return users.findById(id).orElseThrow().login();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    /** Commits {@code work} on its own, while the calling transaction stays open. */
    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}

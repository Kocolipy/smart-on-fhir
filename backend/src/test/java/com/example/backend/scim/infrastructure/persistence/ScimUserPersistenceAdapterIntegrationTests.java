package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.Lockout;
import com.example.backend.scim.domain.ScimEmail;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * The User store against a real SQL Server, asserting only that it stores what it was given. The
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

    /** Whole microseconds, so the instant survives the {@code DATETIMEOFFSET(6)} column unchanged. */
    private static final Instant EARLIER = Instant.parse("2026-03-01T08:00:00Z");

    private static final Instant LATER = Instant.parse("2026-05-01T08:00:00Z");

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimWriteLocks writeLocks;

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
     * The store does not rely on every writer of the User row taking the resource lock: a
     * dormancy basis recorded between the write's locked read and its replacement — here by a
     * transaction that took no lock — survives. The replacement is given the basis as read, older
     * than the one just committed; it must not write it back.
     */
    @Test
    void aReplacementKeepsADormancyBasisALoginRecordedAfterTheLockedRead() {
        ScimUser stored = store(ScimIdentities.user(unique("adapter-race")),
                new ScimLoginState("hash", 0, null, null, EARLIER, null));

        inTransaction(() -> {
            writeLocks.lockUserWrite(stored.id());
            ScimUser locked = users.findById(stored.id()).orElseThrow();
            inNewTransaction(() -> {
                users.recordAuthentication(stored.id(), LATER);
                return null;
            });
            return users.replace(ScimIdentities.withActive(locked, true, locked.login()),
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
        });

        assertThat(read(stored.id()).lastAuthenticatedAt()).isEqualTo(LATER);
    }

    /**
     * The login-state module's read under the lock answers with the committed row, not the copy
     * its transaction read before the lock: a failure another transaction committed in between is
     * the state the module must decide from.
     */
    @Test
    void theLoginStateIsReadFromTheRowAndNotFromACopyReadEarlierInTheTransaction() {
        ScimUser stored = store(ScimIdentities.user(unique("adapter-fresh")),
                new ScimLoginState("hash", 1, null, null, EARLIER, LATER));

        ScimLoginState reread = inTransaction(() -> {
            users.findById(stored.id()).orElseThrow();
            inNewTransaction(() -> {
                users.updateLoginState(stored.id(),
                        new ScimLoginState("hash", 2, null, null, EARLIER, null));
                return null;
            });
            return users.findLoginState(stored.id()).orElseThrow();
        });

        assertThat(reread).isEqualTo(new ScimLoginState("hash", 2, null, null, EARLIER, LATER));
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

    /**
     * The administrative listing reads each User whole or not at all: a User whose deletion
     * commits while the listing is reading is either listed as it was stored or absent, never a
     * row whose resource has gone.
     */
    @Test
    void aUserDeletedDuringTheListingIsListedWholeOrNotAtAll() {
        ScimUser victim = storedWithEmail(unique("adapter-listing"));

        List<ScimUser> listed = inTransaction(() -> CommitBetweenStatements.afterReading(
                "scim_users",
                () -> deleteCommitted(victim.id()),
                users::findAllOrderedByNormalizedUserName));

        assertThat(listed.stream().filter(user -> user.id().equals(victim.id())).toList())
                .isIn(List.of(), List.of(victim));
    }

    /** A page of Users by id, as a SCIM query loads one, reads each User whole or not at all. */
    @Test
    void aUserDeletedDuringAPageReadIsReadWholeOrNotAtAll() {
        ScimUser victim = storedWithEmail(unique("adapter-page"));

        List<ScimUser> page = inTransaction(() -> CommitBetweenStatements.afterReading(
                "scim_users",
                () -> deleteCommitted(victim.id()),
                () -> users.findAllById(List.of(victim.id()))));

        assertThat(page).isIn(List.of(), List.of(victim));
    }

    /** The login path's lookup reads the User whole or not at all. */
    @Test
    void aUserDeletedDuringTheLookupByUserNameIsReadWholeOrNotAtAll() {
        ScimUser victim = storedWithEmail(unique("adapter-lookup"));

        Optional<ScimUser> found = inTransaction(() -> CommitBetweenStatements.afterReading(
                "scim_users",
                () -> deleteCommitted(victim.id()),
                () -> users.findByNormalizedUserName(victim.profile().normalizedUserName())));

        assertThat(found).isIn(Optional.empty(), Optional.of(victim));
    }

    // ---- helpers --------------------------------------------------------------------------

    /** A stored User with an email, read back as the store holds it. */
    private ScimUser storedWithEmail(String userName) {
        ScimUser user = ScimIdentities.user(userName);
        ScimUserProfile profile = user.profile();
        ScimUser withEmail = new ScimUser(user.id(),
                new ScimUserProfile(profile.userName(), profile.name(), "Victim",
                        profile.preferredLanguage(), profile.locale(), profile.timezone(), true,
                        List.of(new ScimEmail(userName + "@example.test", "work", true))),
                user.login(), null, user.version(), user.createdAt(), user.lastModifiedAt());
        UUID id = store(withEmail, user.login()).id();
        return inTransaction(() -> users.findById(id).orElseThrow());
    }

    /** Deletes the User in a transaction of its own, which commits before this returns. */
    private void deleteCommitted(UUID id) {
        inTransaction(() -> {
            users.deleteById(id, Instant.now().truncatedTo(ChronoUnit.MICROS));
            return null;
        });
    }

    private ScimUser store(ScimUser user, ScimLoginState login) {
        ScimUser withLogin = ScimIdentities.withActive(user, user.profile().active(), login);
        ScimUser stored = inTransaction(() -> users.create(withLogin));
        created.add(stored.id());
        return stored;
    }

    /** A replacement as the SCIM write path makes it: locked read, then the write, one transaction. */
    private ScimLoginState replace(ScimUser user, boolean active, ScimLoginState login) {
        return inTransaction(() -> {
            writeLocks.lockUserWrite(user.id());
            ScimUser locked = users.findById(user.id()).orElseThrow();
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

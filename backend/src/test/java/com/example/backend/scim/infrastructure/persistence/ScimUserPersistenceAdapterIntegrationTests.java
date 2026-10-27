package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimEmail;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimLoginStateRepository;
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
 * The User store against a real SQL Server, in what depends on transactions: a write committed by
 * another transaction between a caller's read and its write, and a deletion committed between the
 * statements of a read. What each port operation stores, keeps and advances — the same for the
 * in-memory store — is the contracts' ({@code SqlServerScimUserRepositoryContractTests},
 * {@code SqlServerScimLoginStateRepositoryContractTests}).
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
    private ScimLoginStateRepository loginStates;

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
                ScimLoginState read = loginStates.findLoginState(stored.id()).orElseThrow();
                loginStates.writeChange(stored.id(), read, read.withDormancyBasisReset(LATER));
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
                loginStates.writeChange(stored.id(),
                        new ScimLoginState("hash", 1, null, null, EARLIER, LATER),
                        new ScimLoginState("hash", 2, null, null, EARLIER, LATER));
                return null;
            });
            return loginStates.findLoginState(stored.id()).orElseThrow();
        });

        assertThat(reread).isEqualTo(new ScimLoginState("hash", 2, null, null, EARLIER, LATER));
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

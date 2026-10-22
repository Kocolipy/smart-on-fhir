package com.example.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.DatabaseLogins;
import com.example.backend.audit.domain.AuditEventQuery;
import com.example.backend.audit.domain.AuditEventReader;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.application.LoginAttemptService;
import com.example.backend.auth.application.NoLockoutInForceException;
import com.example.backend.auth.application.PasswordRefusalReason;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimLoginStateRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The login-state module serializes its own writes (spec C6, ADR 0007), against a real database:
 * events racing on one User end as some serial order of them would, never as a mix.
 *
 * <p>Each race is forced rather than hoped for. A holder transaction takes the User's resource
 * lock and writes its login-state row, so it holds every lock a racer could need; the racers are
 * started and each reads the User before blocking; only once all of them are waiting is the
 * holder released. Every racer has therefore read the state before any other one wrote — the
 * interleaving a lost update needs — whichever lock it is waiting on.
 *
 * <p>Run on SQL Server under read-committed snapshot (ADR 0013's as-4): a racer reads the User
 * from its own statement's snapshot, never blocked by the holder, and waits only on a lock;
 * the wait is observed in {@code sys.dm_exec_requests}, never assumed after a sleep.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class LoginStateSerializationIntegrationTests {

    /** How many failed logins race on the User. */
    private static final int RACERS = 4;

    @Autowired
    private LoginAttemptService attempts;

    @Autowired
    private IdentityAdministrationService administration;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimLoginStateRepository loginStates;

    @Autowired
    private ScimWriteLocks writeLocks;

    @Autowired
    private AuditEventReader auditEvents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DatabaseLogins logins;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${app.auth.lockout.max-attempts}")
    private int threshold;

    private final List<UUID> created = new ArrayList<>();

    private final ExecutorService racers = Executors.newFixedThreadPool(RACERS + 1);

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        racers.shutdownNow();
        for (UUID id : created) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ?", id);
        }
    }

    /**
     * N parallel failed logins against a User one short of the threshold impose exactly one
     * Lockout, audited once. Attempts made once it stands are not counted (the documented cap:
     * a locked User's run stays at the threshold), so the run ends at the threshold.
     */
    @Test
    void parallelFailuresAtThresholdLessOneImposeExactlyOneLockout() throws Exception {
        ScimUser user = userWithFailures("serialized-lockout", threshold - 1);

        race(user.id(), failedLogins(user, RACERS));

        assertThat(List.of(stored(user).failedLoginAttempts(), stored(user).isLocked(),
                lockoutsSetOn(user)))
                .containsExactly(threshold, true, 1L);
    }

    /** Parallel failures below the threshold are each counted: no failure overwrites another. */
    @Test
    void parallelFailuresBelowTheThresholdAreEachCounted() throws Exception {
        ScimUser user = userWithFailures("serialized-count", 0);

        race(user.id(), failedLogins(user, threshold - 1));

        assertThat(stored(user).failedLoginAttempts()).isEqualTo(threshold - 1);
    }

    /**
     * An Unlock racing the failure that reaches the threshold ends as one of the two serial
     * orders: the failure locks and the Unlock then lifts it, requiring a change and restarting
     * the dormancy basis — or the Unlock, finding no lock, is refused and leaves the run as it
     * stands, and the failure then reaches the threshold and locks.
     */
    @Test
    void anUnlockRacingAFailedLoginEndsInOneOfTheTwoSerialOrders() throws Exception {
        ScimUser user = userWithFailures("serialized-unlock", threshold - 1);
        UUID actor = UUID.randomUUID();
        List<Callable<?>> both = new ArrayList<>(failedLogins(user, 1));
        both.add(() -> {
            try {
                return administration.unlock(user.id(), actor);
            } catch (NoLockoutInForceException refused) {
                return refused;
            }
        });

        race(user.id(), both);

        ScimLoginState after = stored(user);
        Outcome failureThenUnlock = new Outcome(0, false, true, true);
        Outcome unlockThenFailure = new Outcome(threshold, true, false, false);
        assertThat(new Outcome(after.failedLoginAttempts(), after.isLocked(),
                after.isPasswordChangeRequired(),
                !Objects.equals(after.lastAuthenticatedAt(),
                        user.login().lastAuthenticatedAt())))
                .isIn(Set.of(failureThenUnlock, unlockThenFailure));
    }

    /** What a race left, in the terms the two serial orders differ in. */
    private record Outcome(
            int failedLoginAttempts, boolean locked, boolean changeRequired, boolean basisMoved) {
    }

    private List<Callable<?>> failedLogins(ScimUser user, int count) {
        List<Callable<?>> logins = new ArrayList<>();
        for (int login = 0; login < count; login++) {
            logins.add(() -> attempts.recordFailure(
                    user.profile().userName(), PasswordRefusalReason.BAD_CREDENTIALS));
        }
        return logins;
    }

    /**
     * Runs {@code work} concurrently while a holder keeps every lock on the User's rows, and
     * releases the holder once every racer is waiting for one of them.
     */
    private void race(UUID userId, List<Callable<?>> work) throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = racers.submit(() -> inTransaction(() -> {
            writeLocks.lock(List.of(userId));
            // A before differing in the run makes the store write the run as it stands: the
            // row's lock taken, and nothing on it changed.
            ScimLoginState stored = loginStates.findLoginState(userId).orElseThrow();
            loginStates.writeChange(userId, stored.withFailureCounted(), stored);
            holding.countDown();
            await(release);
        }));
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();
        List<Future<?>> running = new ArrayList<>();
        for (Callable<?> racer : work) {
            running.add(racers.submit(racer));
        }
        try {
            awaitWaitingSessions(work.size());
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
        for (Future<?> racer : running) {
            racer.get(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Until this many requests on this database are waiting for a lock, as the server lists them.
     * Read as the server's administrator: another session's requests are visible only with
     * {@code VIEW SERVER STATE}.
     */
    private void awaitWaitingSessions(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiting = logins.superuser().jdbc().queryForObject("""
                    SELECT count(*) FROM sys.dm_exec_requests
                     WHERE database_id = DB_ID() AND wait_type LIKE 'LCK[_]M[_]%'""",
                    Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("fewer than " + count + " racers waited for the User's lock");
    }

    private ScimUser userWithFailures(String userName, int failures) {
        ScimUser stored = new TransactionTemplate(transactionManager).execute(status ->
                users.create(ScimIdentities.user(
                        userName + "-" + UUID.randomUUID().toString().substring(0, 8))));
        created.add(stored.id());
        for (int failure = 0; failure < failures; failure++) {
            attempts.recordFailure(
                    stored.profile().userName(), PasswordRefusalReason.BAD_CREDENTIALS);
        }
        return users.findById(stored.id()).orElseThrow();
    }

    private ScimLoginState stored(ScimUser user) {
        return users.findById(user.id()).orElseThrow().login();
    }

    private long lockoutsSetOn(ScimUser user) {
        return auditEvents.find(new AuditEventQuery(
                AuditOperation.LOCKOUT_SET, null, null, user.id(), null, null, 0, 50))
                .totalElements();
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}

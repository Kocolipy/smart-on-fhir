package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.ScimTestRows;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SCIM write lock module against a real Postgres: which resource rows a write holds once it
 * has asked for its locks. Observed the way a second writer would meet them — a probe from another
 * connection that skips every row someone holds — while the locking transaction stays open.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimWriteLockAdapterIntegrationTests {

    @Autowired
    private ScimWriteLocks writeLocks;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimGroupRepository groups;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> created = new ArrayList<>();

    private final ExecutorService holders = Executors.newFixedThreadPool(2);

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        holders.shutdownNow();
        for (UUID id : created) {
            ScimTestRows.deleteUnreserved(jdbc, id);
        }
    }

    /** A User write holds the User and every Group it is in, and nothing else. */
    @Test
    void aUserWriteHoldsTheUserAndEveryGroupItIsIn() throws Exception {
        UUID user = user("lock-user");
        UUID first = group("Lock user first", user);
        UUID second = group("Lock user second", user);
        UUID unrelated = group("Lock user unrelated");

        Set<UUID> held = heldWhile(() -> writeLocks.lockUserWrite(user),
                List.of(user, first, second, unrelated));

        assertThat(held).containsExactlyInAnyOrder(user, first, second);
    }

    /** A Group write holds the Group, its current members and every User the request names. */
    @Test
    void aGroupWriteHoldsTheGroupItsMembersAndEveryNamedUser() throws Exception {
        UUID member = user("lock-group-member");
        UUID named = user("lock-group-named");
        UUID bystander = user("lock-group-bystander");
        UUID group = group("Lock group", member);

        Set<UUID> held = heldWhile(() -> writeLocks.lockGroupWrite(group, List.of(named)),
                List.of(group, member, named, bystander));

        assertThat(held).containsExactlyInAnyOrder(group, member, named);
    }

    /** Exactly the resources named: none of their relatives. */
    @Test
    void aLockOfNamedResourcesHoldsExactlyThose() throws Exception {
        UUID user = user("lock-named");
        UUID group = group("Lock named", user);
        UUID other = user("lock-named-other");

        Set<UUID> held = heldWhile(() -> writeLocks.lock(List.of(user, other)),
                List.of(user, group, other));

        assertThat(held).containsExactlyInAnyOrder(user, other);
    }

    /**
     * A Group the User joined while this write waited for the User's lock — added by the writer
     * holding it — is held too, though it was not there when the write first read the User's
     * Groups.
     */
    @Test
    void aGroupJoinedWhileWaitingForTheUserIsHeldToo() throws Exception {
        UUID user = user("lock-joined");
        UUID joined = group("Lock joined");
        CountDownLatch adding = new CountDownLatch(1);
        CountDownLatch added = new CountDownLatch(1);
        Future<?> adder = holders.submit(() -> inTransaction(() -> {
            writeLocks.lockGroupWrite(joined, List.of(user));
            ScimGroup current = groups.findById(joined).orElseThrow();
            groups.replace(current.replacedWith(current.displayName(),
                    List.of(ScimGroupMember.reference(user)), now()));
            adding.countDown();
            await(added);
        }));
        assertThat(adding.await(30, TimeUnit.SECONDS)).isTrue();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> writer = holders.submit(() -> inTransaction(() -> {
            writeLocks.lockUserWrite(user);
            locked.countDown();
            await(release);
        }));
        awaitAWaitingLock();
        added.countDown();
        adder.get(30, TimeUnit.SECONDS);
        try {
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(held(List.of(user, joined))).containsExactlyInAnyOrder(user, joined);
        } finally {
            release.countDown();
            writer.get(30, TimeUnit.SECONDS);
        }
    }

    /** Runs {@code lock} in a transaction held open on another thread, and probes meanwhile. */
    private Set<UUID> heldWhile(Runnable lock, Collection<UUID> probe) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = holders.submit(() -> inTransaction(() -> {
            lock.run();
            locked.countDown();
            await(release);
        }));
        try {
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            return held(probe);
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }

    /** Which of these rows another transaction holds: every one a skipping probe cannot take. */
    private Set<UUID> held(Collection<UUID> ids) {
        Set<UUID> held = new HashSet<>(ids);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (UUID id : ids) {
                held.removeAll(jdbc.queryForList(
                        "SELECT id FROM scim_resources WHERE id = ? FOR UPDATE SKIP LOCKED",
                        UUID.class, id));
            }
        });
        return held;
    }

    /** Until a session on this database is waiting for a row lock. */
    private void awaitAWaitingLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND wait_event_type = 'Lock'""",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no writer waited for the lock");
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

    private UUID user(String userName) {
        ScimUser stored = new TransactionTemplate(transactionManager).execute(status ->
                users.create(ScimIdentities.user(
                        userName + "-" + UUID.randomUUID().toString().substring(0, 8))));
        created.add(stored.id());
        return stored.id();
    }

    private UUID group(String displayName, UUID... members) {
        ScimGroup stored = new TransactionTemplate(transactionManager).execute(status ->
                groups.create(ScimGroup.created(UUID.randomUUID(),
                        displayName + " " + UUID.randomUUID().toString().substring(0, 8),
                        List.of(members).stream().map(ScimGroupMember::reference).toList(),
                        now())));
        created.add(stored.id());
        return stored.id();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}

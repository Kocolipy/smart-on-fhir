package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.ScimTestRows;
import com.example.backend.TokenPermissions;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.application.NewScimGroup;
import com.example.backend.scim.application.NewScimUser;
import com.example.backend.scim.application.ScimGroupPatchOperation;
import com.example.backend.scim.application.ScimGroupReplacement;
import com.example.backend.scim.application.ScimGroupService;
import com.example.backend.scim.application.ScimUserReplacement;
import com.example.backend.scim.application.ScimUserService;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimVersionPrecondition;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two real writers on SQL Server whose lock sets overlap, released together: a User write and a
 * Group write that each advance the other's version. Each locks the rows its write will touch in
 * the one canonical order before changing anything, so neither can hold a row the other already
 * waits for — the pair serializes instead of deadlocking.
 *
 * <p>Repeated, because two writers that happen to serialize once prove nothing. Each round makes
 * its own fixtures, since a deletion consumes them.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimWriteLockOrderIntegrationTests {

    private static final int ROUNDS = 10;

    private static final ScimVersionPrecondition UNCONDITIONAL =
            ScimVersionPrecondition.ofIfMatch(List.of());

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private ScimUserService userService;

    @Autowired
    private ScimGroupService groupService;

    @Autowired
    private JdbcTemplate jdbc;

    private AuthenticatedConnector connector;

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        UUID connectorId = connectors.create("Lock order Okta", null).id();
        connector = new AuthenticatedConnector(
                connectorId, UUID.randomUUID(), TokenPermissions.of(TokenPermissions.ALL));
    }

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        for (UUID id : created) {
            ScimTestRows.deleteUnreserved(jdbc, id);
        }
        created.clear();
    }

    /**
     * A User rename advances every Group the User is in; a Group rename advances every member.
     * Racing the two on a shared membership completes both, every round.
     */
    @Test
    void a_user_rename_racing_a_group_put_on_a_shared_membership_both_complete() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            UUID user = createUser("lock-order-rename-" + tag);
            UUID other = createUser("lock-order-other-" + tag);
            UUID group = createGroup("Lock order rename " + tag, List.of(user, other));

            failures.addAll(race(round,
                    () -> userService.replace(connector, user, UNCONDITIONAL,
                            new ScimUserReplacement(
                                    profile("lock-order-rename-" + tag, "Renamed " + tag),
                                    null, null, false)),
                    () -> groupService.replace(connector, group, UNCONDITIONAL,
                            new ScimGroupReplacement(
                                    "Lock order renamed " + tag, List.of(user, other), null))));
        }
        assertThat(failures).isEmpty();
    }

    /**
     * A User DELETE advances every Group it leaves; a Group rename advances every member. Racing
     * the two on a shared membership completes both, every round — the rename either still sees
     * the User or no longer does, and neither is an error.
     */
    @Test
    void a_user_delete_racing_a_group_rename_on_a_shared_membership_both_complete()
            throws Exception {
        List<String> failures = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++) {
            String tag = UUID.randomUUID().toString().substring(0, 8);
            UUID user = createUser("lock-order-delete-" + tag);
            UUID other = createUser("lock-order-stays-" + tag);
            UUID group = createGroup("Lock order delete " + tag, List.of(user, other));

            failures.addAll(race(round,
                    () -> userService.delete(connector, user, UNCONDITIONAL),
                    () -> groupService.patch(connector, group, UNCONDITIONAL, List.of(
                            new ScimGroupPatchOperation.SetDisplayName(
                                    "Lock order deleted " + tag)))));
        }
        assertThat(failures).isEmpty();
    }

    /** Runs both writes released together; reports each one that failed, naming the round. */
    private static List<String> race(int round, Callable<?> first, Callable<?> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> writes = new ArrayList<>();
            for (Callable<?> write : List.of(first, second)) {
                writes.add(pool.submit(() -> {
                    start.await();
                    return write.call();
                }));
            }
            start.countDown();
            List<String> failures = new ArrayList<>();
            for (Future<?> write : writes) {
                try {
                    write.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException failed) {
                    failures.add("round " + round + ": " + failed.getCause());
                }
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID createUser(String userName) {
        UUID id = userService.create(connector,
                new NewScimUser(profile(userName, null), null, null)).id();
        created.add(id);
        return id;
    }

    private UUID createGroup(String displayName, List<UUID> members) {
        UUID id = groupService.create(connector, new NewScimGroup(displayName, members, null)).id();
        created.add(id);
        return id;
    }

    private static ScimUserProfile profile(String userName, String displayName) {
        return new ScimUserProfile(userName, null, displayName, null, null, null, true, List.of());
    }
}

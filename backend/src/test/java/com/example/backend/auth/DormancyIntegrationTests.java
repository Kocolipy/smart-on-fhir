package com.example.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.InMemorySessionRegistryConfiguration;
import com.example.backend.TokenPermissions;
import com.example.backend.auth.application.DormancyRun;
import com.example.backend.auth.application.DormancyService;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.application.LoginService;
import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scheduling.domain.ScheduledJobLock;
import com.example.backend.scim.application.ConnectorAdministrationService;
import com.example.backend.scim.application.NewScimGroup;
import com.example.backend.scim.application.NewScimUser;
import com.example.backend.scim.application.ScimGroupPatchOperation;
import com.example.backend.scim.application.ScimGroupService;
import com.example.backend.scim.application.ScimUserReplacement;
import com.example.backend.scim.application.ScimUserService;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.DormancyPolicy;
import com.example.backend.scim.domain.DormancyVerdict;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroupMembership;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUserPatchOperation;
import com.example.backend.scim.domain.ScimUserProfile;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimVersionPrecondition;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.LockedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The dormancy job end to end (ADR 0011), against a real Postgres, the real seeded identities,
 * the real role mapping and the real job lock: the lockout at the lockout window with
 * {@code DORMANCY}, every mapped Group membership removed at the role-revocation window and the
 * unmapped ones kept, a re-added membership removed again, the Bootstrap Admin's exemption, an
 * existing {@code FAILURES} cause surviving, Unlock restarting the window, {@code active} never
 * written, the audit events, and serialization.
 *
 * <p>Dormancy is produced by BACKDATING the fixture's dormancy basis — {@code last_authenticated_at},
 * or {@code created_at} for a User that never authenticated — never by moving the clock. The job
 * measures every User in the database against the clock, so moving it past the windows would make
 * every other fixture dormant too and put live sessions past their absolute lifetime; a backdated
 * basis makes exactly the User under test dormant. Each test removes what it created.
 */
@SpringBootTest
@Import({
        ContainerTestConfiguration.class,
        InMemorySessionRegistryConfiguration.class,
        DormancyTestClockConfiguration.class})
class DormancyIntegrationTests {

    private static final Duration PAST_LOCKOUT =
            DormancyPolicy.DEFAULT_LOCKOUT_WINDOW.plusDays(1);

    private static final Duration PAST_ROLE_REVOCATION =
            DormancyPolicy.DEFAULT_ROLE_REVOCATION_WINDOW.plusDays(1);

    private static final String PASSWORD = "first-correct-horse";

    @Autowired
    private DormancyService dormancy;

    @Autowired
    private IdentityAdministrationService administration;

    @Autowired
    private ScheduledJobLock jobLock;

    @Autowired
    private LoginService logins;

    @Autowired
    private ScimUserService userService;

    @Autowired
    private ScimGroupService groupService;

    @Autowired
    private ScimUserRepository users;

    @Autowired
    private ScimGroupRepository groups;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private InMemoryAccountSessions sessions;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AuthenticatedConnector connector;

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        UUID connectorId = connectors.create("Dormancy Okta", "test-admin").id();
        connector = new AuthenticatedConnector(
                connectorId, UUID.randomUUID(), TokenPermissions.of(TokenPermissions.ALL));
    }

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM scim_resources WHERE id = ? AND reserved_name IS NULL", id);
        }
        created.clear();
    }

    // ---- the dormancy basis ---------------------------------------------------------------

    /**
     * Every successful Login records the instant it happened, and recording it moves neither the
     * SCIM version nor {@code lastModified}.
     */
    @Test
    void everySuccessfulLoginRecordsLastAuthenticatedAtWithoutMovingTheVersion() {
        UUID ada = createSettledUser("dormancy-login");
        long version = version(ada);
        backdateBasis(ada, Duration.ofDays(3));

        logins.logIn("dormancy-login", PASSWORD);

        assertThat(lastAuthenticatedAt(ada)).isEqualTo(clock.instant());
        assertThat(version(ada)).isEqualTo(version);
    }

    // ---- the lockout step: the demo oracle --------------------------------------------------

    /**
     * A User whose basis lies past the lockout window is locked with {@code DORMANCY}: the lock
     * and its cause are stored together, {@code active} and the version are untouched, its session
     * ends after the commit, and an actorless {@code DORMANCY_LOCKOUT} plus the revocation's own
     * event are recorded. Login is then refused, exactly as for a failure lockout.
     */
    @Test
    void aUserPastTheLockoutWindowIsLockedForDormancyAndCannotSignIn() {
        UUID ada = createSettledUser("dormancy-lockout");
        sessions.open(ada, "ada-session");
        long version = version(ada);
        backdateBasis(ada, PAST_LOCKOUT);
        Instant runAt = clock.instant();

        DormancyRun run = dormancy.run();

        assertThat(run.skipped()).isFalse();
        assertThat(run.locked()).contains(ada);
        assertThat(run.rolesRevoked()).doesNotContain(ada);
        assertThat(lockedAt(ada)).isEqualTo(runAt);
        assertThat(lockCause(ada)).isEqualTo("DORMANCY");
        assertThat(active(ada)).as("active is the directory's, never written").isTrue();
        assertThat(version(ada)).as("the lock is not a SCIM attribute").isEqualTo(version);
        assertThat(sessions.sessionsOf(ada)).isEmpty();
        assertThat(auditRows("DORMANCY_LOCKOUT", ada)).singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("outcome", "SUCCESS")
                        .containsEntry("actor_id", null)
                        .containsEntry("resource_type", "User")
                        .containsEntry("changed_paths", "lockedAt,lockCause"));
        assertThat(auditRows("USER_SESSIONS_REVOKE", ada)).singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("outcome", "SUCCESS")
                        .containsEntry("actor_id", null));
        assertThatThrownBy(() -> logins.logIn("dormancy-lockout", PASSWORD))
                .isInstanceOf(LockedException.class);
    }

    @Test
    void theLockoutWindowIsMeasuredFromTheBasis() {
        UUID ada = createSettledUser("dormancy-boundary");

        backdateBasis(ada, DormancyPolicy.DEFAULT_LOCKOUT_WINDOW.minusSeconds(1));
        assertThat(dormancy.run().locked()).doesNotContain(ada);
        assertThat(lockedAt(ada)).isNull();

        backdateBasis(ada, PAST_LOCKOUT);
        assertThat(dormancy.run().locked()).contains(ada);
    }

    /** A User provisioned and never authenticated is measured from its creation. */
    @Test
    void aUserThatNeverAuthenticatedIsMeasuredFromItsCreation() {
        UUID never = createUser("dormancy-never", null);
        assertThat(lastAuthenticatedAt(never)).isNull();

        backdateCreation(never, DormancyPolicy.DEFAULT_LOCKOUT_WINDOW.minusDays(1));
        assertThat(dormancy.run().locked()).doesNotContain(never);

        backdateCreation(never, PAST_LOCKOUT);
        assertThat(dormancy.run().locked()).contains(never);
        assertThat(lockCause(never)).isEqualTo("DORMANCY");
    }

    /**
     * The candidate queries spell the verdict's boundary a second time, in SQL — the basis as
     * {@code coalesce(last_authenticated_at, created_at)} and "strictly before the cutoff" as
     * {@code <} — so this pins the two together where they could drift: on what Postgres stores,
     * one microsecond either side of each window and exactly on it, for each basis. A query
     * narrower than the verdict would leave a due User unprocessed; the job re-deciding on the
     * locked read only guards the other direction.
     */
    @Test
    void theCandidateQueriesSelectExactlyTheUsersTheVerdictFindsDue() {
        DormancyPolicy policy = DormancyPolicy.defaults();
        Instant now = clock.instant();
        Duration microsecond = Duration.ofNanos(1_000);
        List<UUID> fixtures = new ArrayList<>();
        for (Duration window : List.of(policy.lockoutWindow(), policy.roleRevocationWindow())) {
            for (Duration offset : List.of(microsecond.negated(), Duration.ZERO, microsecond)) {
                Duration ago = window.plus(offset);
                UUID authenticated = createSettledUser("dormancy-sql-" + fixtures.size());
                backdateBasis(authenticated, ago);
                fixtures.add(authenticated);
                UUID never = createUser("dormancy-sql-" + fixtures.size(), null);
                backdateCreation(never, ago);
                fixtures.add(never);
            }
        }
        UUID group = createGroup("Dormancy SQL boundary", fixtures.getFirst());
        groupService.patch(connector, group, ifMatch(group), List.of(
                new ScimGroupPatchOperation.AddMembers(fixtures.subList(1, fixtures.size()))));

        TransactionTemplate read = new TransactionTemplate(transactionManager);
        Map<UUID, DormancyVerdict> verdicts = read.execute(status -> fixtures.stream()
                .collect(Collectors.toMap(Function.identity(),
                        id -> policy.verdict(users.findById(id).orElseThrow(), now))));
        List<UUID> lockoutCandidates = read.execute(status ->
                users.findDormantUnlockedUserIds(policy.lockoutCutoff(now))).stream()
                .filter(fixtures::contains)
                .toList();
        List<UUID> roleRevocationCandidates = read.execute(status ->
                groups.findDormantMemberships(List.of(group), policy.roleRevocationCutoff(now)))
                .stream()
                .map(ScimGroupMembership::userId)
                .toList();

        List<UUID> lockedOut = fixtures.stream().filter(id -> verdicts.get(id).locksOut()).toList();
        List<UUID> revoked = fixtures.stream().filter(id -> verdicts.get(id).revokesRoles()).toList();
        // Past the lockout window by a microsecond, for each basis, and all six at the second.
        assertThat(lockedOut).hasSize(8);
        assertThat(revoked).hasSize(2);
        assertThat(lockoutCandidates).containsExactlyInAnyOrderElementsOf(lockedOut);
        assertThat(roleRevocationCandidates).containsExactlyInAnyOrderElementsOf(revoked);
    }

    /**
     * A User already locked by its failure run keeps that lock and that cause: the job writes
     * nothing and records nothing for it, and the failure run is untouched.
     */
    @Test
    void anExistingFailuresCauseSurvivesTheJob() {
        UUID ada = createSettledUser("dormancy-failures");
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> logins.logIn("dormancy-failures", "wrong-password"))
                    .isNotNull();
        }
        Instant failureLock = lockedAt(ada);
        assertThat(failureLock).isNotNull();
        assertThat(lockCause(ada)).isEqualTo("FAILURES");
        backdateBasis(ada, PAST_LOCKOUT);

        assertThat(dormancy.run().locked()).doesNotContain(ada);

        assertThat(lockCause(ada)).isEqualTo("FAILURES");
        assertThat(lockedAt(ada)).isEqualTo(failureLock);
        assertThat(failedAttempts(ada)).isEqualTo(3);
        assertThat(auditRows("DORMANCY_LOCKOUT", ada)).isEmpty();
    }

    /** The schema refuses a lock without a cause, and a cause without a lock. */
    @Test
    void theSchemaKeepsTheLockAndItsCauseTogether() {
        UUID ada = createSettledUser("dormancy-schema");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE scim_users SET locked_at = now() WHERE resource_id = ?", ada))
                .hasMessageContaining("ck_scim_users_lock_cause");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE scim_users SET lock_cause = 'DORMANCY' WHERE resource_id = ?", ada))
                .hasMessageContaining("ck_scim_users_lock_cause");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE scim_users SET locked_at = now(), lock_cause = 'OTHER'"
                        + " WHERE resource_id = ?", ada))
                .hasMessageContaining("ck_scim_users_lock_cause");
    }

    /**
     * A connector re-asserting {@code active=true} resets nothing, so the User is still locked; a
     * reactivation afterwards restarts the basis but does not lift the lock — only Unlock does.
     */
    @Test
    void reAssertingActiveResetsNothingAndAReactivationDoesNotLiftTheLock() {
        UUID ada = createSettledUser("dormancy-reassert");
        backdateBasis(ada, PAST_LOCKOUT);
        Instant basis = lastAuthenticatedAt(ada);

        userService.replace(connector, ada, ifMatch(ada), new ScimUserReplacement(
                profile("dormancy-reassert", null, true), null, null, true));
        userService.patch(connector, ada, ifMatch(ada), List.of(
                new ScimUserPatchOperation.SetActive(true),
                new ScimUserPatchOperation.SetText(
                        ScimUserPatchOperation.TextAttribute.DISPLAY_NAME, "Still Here")));
        assertThat(lastAuthenticatedAt(ada)).as("re-asserting active reset nothing").isEqualTo(basis);

        assertThat(dormancy.run().locked()).contains(ada);

        userService.patch(connector, ada, ifMatch(ada), List.of(
                new ScimUserPatchOperation.SetActive(false)));
        userService.patch(connector, ada, ifMatch(ada), List.of(
                new ScimUserPatchOperation.SetActive(true)));
        assertThat(lastAuthenticatedAt(ada)).isEqualTo(clock.instant());
        assertThat(lockCause(ada)).as("the directory cannot lift the lock").isEqualTo("DORMANCY");
    }

    // ---- the role-revocation step ---------------------------------------------------------

    /**
     * Past the role-revocation window a User loses its direct membership of the mapped Group —
     * here the Superuser Group, the one the test mapping names — and keeps its unmapped one, whose
     * version does not move. The mapped Group's and the User's versions advance, its session ends,
     * and one actorless {@code DORMANCY_ROLE_REVOCATION} names the Role lost. A connector that then
     * re-adds the membership sees it removed again on the next run while the User stays dormant.
     */
    @Test
    void aUserPastTheRoleRevocationWindowLosesItsMappedMembershipAndAReAddIsRemovedAgain() {
        UUID ada = createSettledUser("dormancy-roles");
        UUID ordinary = createGroup("Dormancy Engineering", ada);
        UUID adminGroup = adminGroupId();
        addToAdminGroup(ada);
        long adminGroupVersion = version(adminGroup);
        long ordinaryVersion = version(ordinary);
        long userVersion = version(ada);
        sessions.open(ada, "admin-session");
        backdateBasis(ada, PAST_ROLE_REVOCATION);

        DormancyRun run = dormancy.run();

        assertThat(run.rolesRevoked()).contains(ada);
        assertThat(run.locked()).contains(ada);
        assertThat(isMember(adminGroup, ada)).isFalse();
        assertThat(isMember(adminGroup, bootstrapAdmin())).isTrue();
        assertThat(isMember(ordinary, ada)).as("unmapped membership intact").isTrue();
        assertThat(version(ordinary)).isEqualTo(ordinaryVersion);
        assertThat(version(adminGroup)).isEqualTo(adminGroupVersion + 1);
        assertThat(version(ada)).isEqualTo(userVersion + 1);
        assertThat(active(ada)).isTrue();
        assertThat(sessions.sessionsOf(ada)).isEmpty();
        assertThat(auditRows("DORMANCY_ROLE_REVOCATION", ada)).singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("outcome", "SUCCESS")
                        .containsEntry("actor_id", null)
                        .containsEntry("resource_type", "User")
                        .containsEntry("changed_paths", "groups")
                        .containsEntry("role_name", "Superuser"));

        addToAdminGroup(ada);
        assertThat(isMember(adminGroup, ada)).as("the connector re-added it").isTrue();

        DormancyRun second = dormancy.run();

        assertThat(second.rolesRevoked()).contains(ada);
        assertThat(second.locked()).as("already locked").doesNotContain(ada);
        assertThat(isMember(adminGroup, ada)).isFalse();
        assertThat(isMember(ordinary, ada)).isTrue();
        assertThat(auditRows("DORMANCY_ROLE_REVOCATION", ada)).hasSize(2);
        assertThat(auditRows("DORMANCY_LOCKOUT", ada)).hasSize(1);
    }

    /** Between the two windows a User is locked but keeps its Roles. */
    @Test
    void aUserBetweenTheWindowsIsLockedButKeepsItsRoles() {
        UUID ada = createSettledUser("dormancy-between");
        addToAdminGroup(ada);
        backdateBasis(ada, PAST_LOCKOUT);

        DormancyRun run = dormancy.run();

        assertThat(run.locked()).contains(ada);
        assertThat(run.rolesRevoked()).doesNotContain(ada);
        assertThat(isMember(adminGroupId(), ada)).isTrue();
    }

    // ---- Unlock ---------------------------------------------------------------------------

    /**
     * Unlock lifts a dormancy lock, restarts the dormancy basis so the next run does not lock the
     * User again, requires a password change of a User that holds one, and records the cause of
     * the lock it lifted. The User can then sign in — confined to the change — and once it has
     * changed its password signs in normally.
     */
    @Test
    void unlockLiftsADormancyLockAndRestartsTheWindow() {
        UUID ada = createSettledUser("dormancy-unlock");
        backdateBasis(ada, PAST_LOCKOUT);
        assertThat(dormancy.run().locked()).contains(ada);

        administration.unlock(ada, "test-admin");

        assertThat(lockedAt(ada)).isNull();
        assertThat(lockCause(ada)).isNull();
        assertThat(lastAuthenticatedAt(ada)).as("the basis restarted").isEqualTo(clock.instant());
        assertThat(passwordChangeRequiredSince(ada)).isEqualTo(clock.instant());
        assertThat(auditRows("LOCKOUT_LIFT", ada)).singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("actor_id", testAdmin())
                        .containsEntry("error_code", "DORMANCY"));

        assertThat(dormancy.run().locked()).as("the next run does not lock again")
                .doesNotContain(ada);
        assertThat(lockedAt(ada)).isNull();
        assertThat(logins.logIn("dormancy-unlock", PASSWORD).userId()).isEqualTo(ada);
    }

    // ---- the Bootstrap Admin --------------------------------------------------------------

    /**
     * The real seeded Bootstrap Admin, dormant far past both windows, is exempt from both steps:
     * not locked, still in the Admin group, its version unmoved, its session intact, no event.
     */
    @Test
    void theSeededBootstrapAdminIsExemptFromBothSteps() {
        UUID bootstrap = bootstrapAdmin();
        Timestamp originalBasis = jdbc.queryForObject(
                "SELECT last_authenticated_at FROM scim_users WHERE resource_id = ?",
                Timestamp.class, bootstrap);
        backdateBasis(bootstrap, PAST_ROLE_REVOCATION.multipliedBy(2));
        sessions.open(bootstrap, "bootstrap-session");
        long version = version(bootstrap);
        long adminGroupVersion = version(adminGroupId());
        try {
            DormancyRun run = dormancy.run();

            assertThat(run.locked()).doesNotContain(bootstrap);
            assertThat(run.rolesRevoked()).doesNotContain(bootstrap);
            assertThat(lockedAt(bootstrap)).isNull();
            assertThat(isMember(adminGroupId(), bootstrap)).isTrue();
            assertThat(version(bootstrap)).isEqualTo(version);
            assertThat(version(adminGroupId())).isEqualTo(adminGroupVersion);
            assertThat(sessions.sessionsOf(bootstrap)).containsExactly("bootstrap-session");
            assertThat(auditRows("DORMANCY_LOCKOUT", bootstrap)).isEmpty();
            assertThat(auditRows("DORMANCY_ROLE_REVOCATION", bootstrap)).isEmpty();
        } finally {
            sessions.revokeAll(bootstrap);
            jdbc.update("UPDATE scim_users SET last_authenticated_at = ? WHERE resource_id = ?",
                    originalBasis, bootstrap);
        }
    }

    // ---- serialization --------------------------------------------------------------------

    /**
     * Two runs started together process each dormant User exactly once: one lock and one event
     * per User, one membership removal, whichever run did it.
     */
    @Test
    void twoConcurrentRunsDoNotDoubleProcessAUser() throws Exception {
        List<UUID> dormant = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID user = createUser("dormancy-concurrent-" + i, null);
            addToAdminGroup(user);
            backdateCreation(user, PAST_ROLE_REVOCATION);
            dormant.add(user);
        }

        List<DormancyRun> runs = concurrently(dormancy::run);

        for (UUID user : dormant) {
            assertThat(runs.stream().filter(run -> run.locked().contains(user)).count())
                    .as("runs that locked %s", user).isEqualTo(1);
            assertThat(runs.stream().filter(run -> run.rolesRevoked().contains(user)).count())
                    .as("runs that revoked %s", user).isEqualTo(1);
            assertThat(auditRows("DORMANCY_LOCKOUT", user)).hasSize(1);
            assertThat(auditRows("DORMANCY_ROLE_REVOCATION", user)).hasSize(1);
            assertThat(isMember(adminGroupId(), user)).isFalse();
        }
    }

    /**
     * While another transaction holds the dormancy job's lock row, a run skips and changes
     * nothing; once the holder commits, the job runs again. The retention job's row is a
     * different one and does not block it.
     */
    @Test
    void theJobIsSerializedOnItsOwnLockRow() throws Exception {
        UUID ada = createSettledUser("dormancy-serialized");
        backdateBasis(ada, PAST_LOCKOUT);

        whileHolding(ScheduledJob.DORMANCY, () -> {
            DormancyRun blocked = dormancy.run();
            assertThat(blocked.skipped()).isTrue();
            assertThat(lockedAt(ada)).isNull();
        });
        whileHolding(ScheduledJob.AUDIT_RETENTION, () -> {
            DormancyRun other = dormancy.run();
            assertThat(other.skipped()).as("another job's lock does not block it").isFalse();
            assertThat(other.locked()).contains(ada);
        });
        assertThat(jdbc.queryForList("SELECT job_name FROM scheduled_job_locks ORDER BY job_name",
                String.class)).containsExactly("audit-retention", "dormancy");
    }

    // ---- reactivation (the directory's own rule, which the dormancy basis follows) --------

    /**
     * Reactivating a credentialed User requires a password change, by either SCIM path that can
     * reactivate — a PATCH of {@code active} and a PUT restating it — because a credential that
     * sat unused across a deactivation is not trusted on return. The flag is dated by the
     * reactivation that imposed it.
     */
    @Test
    void reactivatingACredentialedUserRequiresAPasswordChangeByEitherPath() {
        UUID ada = createSettledUser("reactivate-credentialed");
        assertThat(passwordChangeRequiredSince(ada)).isNull();

        userService.patch(connector, ada, ifMatch(ada), List.of(
                new ScimUserPatchOperation.SetActive(false)));
        userService.patch(connector, ada, ifMatch(ada), List.of(
                new ScimUserPatchOperation.SetActive(true)));
        assertThat(passwordChangeRequiredSince(ada))
                .as("PATCH active=true reactivated a credentialed User")
                .isEqualTo(clock.instant());

        completePasswordChange(ada);
        userService.patch(connector, ada, ifMatch(ada), List.of(
                new ScimUserPatchOperation.SetActive(false)));
        userService.replace(connector, ada, ifMatch(ada), new ScimUserReplacement(
                profile("reactivate-credentialed", null, true), null, null, true));
        assertThat(passwordChangeRequiredSince(ada))
                .as("PUT active=true reactivated a credentialed User")
                .isEqualTo(clock.instant());

        completePasswordChange(ada);
        userService.patch(connector, ada, ifMatch(ada), List.of(
                new ScimUserPatchOperation.SetActive(true)));
        userService.replace(connector, ada, ifMatch(ada), new ScimUserReplacement(
                profile("reactivate-credentialed", null, true), null, null, true));
        assertThat(passwordChangeRequiredSince(ada))
                .as("re-asserting active over an active User is not a reactivation")
                .isNull();

        userService.patch(connector, ada, ifMatch(ada), List.of(
                new ScimUserPatchOperation.SetActive(false)));
        long versionBeforeUpdateActive = version(ada);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                users.updateActive(ada, true, clock.instant()));
        assertThat(passwordChangeRequiredSince(ada))
                .as("updateActive(true) reactivated a credentialed User")
                .isEqualTo(clock.instant());
        // `active` is a SCIM attribute, so the write is a RepresentationChange: a connector
        // holding the old ETag must see it go stale.
        assertThat(version(ada))
                .as("updateActive advanced the User's version exactly once")
                .isEqualTo(versionBeforeUpdateActive + 1);
    }

    /** A credentialless User has no credential to distrust: reactivating it sets no flag. */
    @Test
    void reactivatingACredentiallessUserRequiresNoPasswordChange() {
        UUID grace = createUser("reactivate-credentialless", null);

        userService.patch(connector, grace, ifMatch(grace), List.of(
                new ScimUserPatchOperation.SetActive(false)));
        userService.patch(connector, grace, ifMatch(grace), List.of(
                new ScimUserPatchOperation.SetActive(true)));
        assertThat(passwordChangeRequiredSince(grace)).as("after PATCH").isNull();

        userService.patch(connector, grace, ifMatch(grace), List.of(
                new ScimUserPatchOperation.SetActive(false)));
        userService.replace(connector, grace, ifMatch(grace), new ScimUserReplacement(
                profile("reactivate-credentialless", null, true), null, null, true));
        assertThat(passwordChangeRequiredSince(grace)).as("after PUT").isNull();
    }

    // ---- helpers --------------------------------------------------------------------------

    /**
     * A credentialed User whose connector-imposed first password has been "changed" (the same
     * credential kept), so it signs in unconfined and a login moves its basis.
     */
    private UUID createSettledUser(String userName) {
        UUID id = createUser(userName, PASSWORD);
        completePasswordChange(id);
        return id;
    }

    private UUID createUser(String userName, String password) {
        UUID id = userService.create(connector, new NewScimUser(
                profile(userName, null, true), password, null)).id();
        created.add(id);
        return id;
    }

    private UUID createGroup(String displayName, UUID member) {
        UUID id = groupService.create(
                connector, new NewScimGroup(displayName, List.of(member), null)).id();
        created.add(id);
        return id;
    }

    /** As a successful self-service change leaves it, keeping the same credential. */
    private void completePasswordChange(UUID user) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                users.completePasswordChange(
                        user, users.findById(user).orElseThrow().login().passwordHash(),
                        clock.instant()));
    }

    /** Puts the User's last authentication {@code ago} before the clock — the clock stays put. */
    private void backdateBasis(UUID user, Duration ago) {
        jdbc.update("UPDATE scim_users SET last_authenticated_at = ? WHERE resource_id = ?",
                Timestamp.from(clock.instant().minus(ago)), user);
    }

    /** Puts a never-authenticated User's creation {@code ago} before the clock. */
    private void backdateCreation(UUID user, Duration ago) {
        jdbc.update("UPDATE scim_resources SET created_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minus(ago)), user);
    }

    private UUID bootstrapAdmin() {
        return users.findByReservedName(ReservedResourceName.BOOTSTRAP_ADMIN).orElseThrow().id();
    }

    private UUID testAdmin() {
        return jdbc.queryForObject(
                "SELECT resource_id FROM scim_users WHERE normalized_user_name = 'test-admin'",
                UUID.class);
    }

    private UUID adminGroupId() {
        return groups.findByReservedName(ReservedResourceName.ADMIN_GROUP).orElseThrow().id();
    }

    /** Adds the User to the Admin group the way a connector does: a conditional SCIM PATCH. */
    private void addToAdminGroup(UUID user) {
        UUID adminGroup = adminGroupId();
        groupService.patch(connector, adminGroup, ifMatch(adminGroup),
                List.of(new ScimGroupPatchOperation.AddMembers(List.of(user))));
    }

    private static ScimUserProfile profile(String userName, String displayName, boolean active) {
        return new ScimUserProfile(userName, null, displayName, null, null, null, active, List.of());
    }

    private ScimVersionPrecondition ifMatch(UUID resource) {
        return ScimVersionPrecondition.ofIfMatch(List.of("\"" + version(resource) + "\""));
    }

    /** Runs the job on two threads released together, and returns both runs. */
    private static List<DormancyRun> concurrently(Callable<DormancyRun> job) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<DormancyRun>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return job.call();
                }));
            }
            start.countDown();
            List<DormancyRun> results = new ArrayList<>();
            for (Future<DormancyRun> run : runs) {
                results.add(run.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Holds the job's lock in a transaction on another thread — as a run on another instance
     * would — while {@code work} runs here, then commits it.
     */
    private void whileHolding(ScheduledJob job, ThrowingRunnable work) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> acquired = holder.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        boolean got = jobLock.tryAcquire(job);
                        held.countDown();
                        try {
                            release.await(60, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return got;
                    }));
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            try {
                work.run();
            } finally {
                release.countDown();
            }
            assertThat(acquired.get(60, TimeUnit.SECONDS)).as("the holder had the lock").isTrue();
        } finally {
            holder.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private long version(UUID resource) {
        return jdbc.queryForObject(
                "SELECT version FROM scim_resources WHERE id = ?", Long.class, resource);
    }

    private boolean active(UUID user) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM scim_users WHERE resource_id = ?", Boolean.class, user));
    }

    private Instant lockedAt(UUID user) {
        return instant("locked_at", user);
    }

    private String lockCause(UUID user) {
        return jdbc.queryForObject(
                "SELECT lock_cause FROM scim_users WHERE resource_id = ?", String.class, user);
    }

    private int failedAttempts(UUID user) {
        return jdbc.queryForObject(
                "SELECT failed_login_attempts FROM scim_users WHERE resource_id = ?",
                Integer.class, user);
    }

    private Instant lastAuthenticatedAt(UUID user) {
        return instant("last_authenticated_at", user);
    }

    private Instant passwordChangeRequiredSince(UUID user) {
        return instant("password_change_required_since", user);
    }

    private Instant instant(String column, UUID user) {
        Timestamp at = jdbc.queryForObject(
                "SELECT " + column + " FROM scim_users WHERE resource_id = ?",
                Timestamp.class, user);
        return at == null ? null : at.toInstant();
    }

    private boolean isMember(UUID group, UUID user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM scim_group_members WHERE group_id = ? AND user_id = ?",
                Integer.class, group, user) == 1;
    }

    private List<Map<String, Object>> auditRows(String operation, UUID subject) {
        return jdbc.queryForList("""
                SELECT outcome, actor_id, resource_type, changed_paths, role_name, error_code
                  FROM audit_events
                 WHERE operation = ? AND subject_id = ?
                 ORDER BY occurred_at""", operation, subject);
    }
}

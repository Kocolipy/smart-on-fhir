package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingAuditTrail.Recorded;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.LoginStateModules;
import com.example.backend.auth.MutableClock;
import com.example.backend.auth.PendingCommit;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.InMemoryGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.LockCause;
import com.example.backend.scim.domain.LockoutPolicy;
import com.example.backend.scim.domain.NormalizedUserName;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimLoginState;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserProfile;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * The identity use cases an administrator drives, against the unified SCIM identity.
 *
 * <p>Replaces {@code AccountAdministrationServiceTests}. Things that changed shape rather
 * than meaning and are worth naming:
 *
 * <ul>
 *   <li>The role column became DERIVED membership of the reserved Admin group.
 *   <li>The Bootstrap Admin is recognised by its reservation marker rather than by a
 *       configured name — which is why it is seeded through {@code createReserved} and why
 *       its protections cannot be moved by a rename.
 *   <li>Deactivate and Activate are gone: {@code active} is the directory's to set over
 *       SCIM, so the listing reports it and nothing here writes it. Unlock is addressed by
 *       stable id.
 * </ul>
 *
 * <p>Every refusal is thrown as an {@link UnlockRefusedException} naming who was refused, what
 * it addressed and why, with nothing written: the administrative edge audits and logs it, once,
 * and {@code AdminRefusalsTests} holds what it records.
 */
class IdentityAdministrationServiceTests {

    /** One instant for the store and the clock, so a summary's timestamps are comparable. */
    private static final Instant NOW = ScimIdentities.NOW;

    /** Ten years: far past any window the former expiring lockout could have had. */
    private static final Duration A_LONG_TIME = Duration.ofDays(3650);

    /** The recovery identity's name, which is deliberately NOT what protects it. */
    private static final String BOOTSTRAP = "root";

    /** The acting administrator of a test that does not provision one. */
    private static final UUID UNPROVISIONED_ADMIN =
            UUID.fromString("00000000-0000-0000-0000-0000000ad001");

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();
    private final PendingCommit transaction = new PendingCommit();
    private final MutableClock clock = new MutableClock(NOW);
    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final SessionRevocationService revocation = new SessionRevocationService(
            sessions,
            transaction,
            audit,
            new RecordingOperationalAlerts());

    private final InMemoryGroupPermissions groupPermissions = InMemoryGroupPermissions.over(groups);

    private final IdentityAdministrationService service = new IdentityAdministrationService(
            users,
            groups,
            LoginStateModules.over(
                        users, revocation, new LockoutPolicy(5), audit, clock),
            audit,
            groupPermissions);

    // Reviewing who has access

    @Test
    void listsEveryIdentityWithoutItsPasswordHash() {
        ScimUser ada = given("ada");
        ScimUser bob = given("bob");
        ScimGroup admins = givenAdminGroup(ada);

        assertThat(service.listIdentities()).containsExactly(
                new IdentitySummary(ada.id(), "ada", null, true, false, true, false, null, true,
                        false, null, NOW,
                        List.of(new IdentitySummary.DirectGroup(admins.id(), "Admins"))),
                new IdentitySummary(bob.id(), "bob", null, false, false, true, false, null, true,
                        false, null, NOW, List.of()));
    }

    /**
     * The directory-owned half of a row — the name the directory displays and the Groups it
     * put the User in — and the application-owned instant it last authenticated, each read
     * from the stored User rather than assumed.
     */
    @Test
    void reportsTheDisplayNameLastAuthenticationAndDirectGroups() {
        Instant lastLogin = NOW.minus(Duration.ofDays(2));
        ScimUser grace = users.given(new ScimUser(
                UUID.randomUUID(),
                new ScimUserProfile("grace", null, "Grace Hopper", null, null, null, true, List.of()),
                new ScimLoginState("hash", 0, null, lastLogin),
                null,
                ScimUser.INITIAL_VERSION,
                NOW.minus(Duration.ofDays(30)),
                NOW));
        ScimUser bob = given("bob");
        // Created out of display-name order, so the row's order is the listing's own sort.
        ScimGroup ops = groups.create(ScimIdentities.group("Operators", grace));
        ScimGroup eng = groups.create(ScimIdentities.group("Engineering", grace, bob));

        IdentitySummary row = service.listIdentities().getFirst();

        assertThat(row.userName()).isEqualTo("bob");
        assertThat(row.groups()).containsExactly(
                new IdentitySummary.DirectGroup(eng.id(), "Engineering"));
        IdentitySummary graceRow = service.listIdentities().get(1);
        assertThat(graceRow.displayName()).isEqualTo("Grace Hopper");
        // Read through the accessors, not only by record equality: an expected record built by
        // the same constructor would agree with a field the constructor dropped.
        assertThat(graceRow.active()).isTrue();
        assertThat(graceRow.hasPassword()).isTrue();
        assertThat(graceRow.locked()).isFalse();
        assertThat(row.groups()).isNotNull();
        assertThat(graceRow.lastAuthenticatedAt()).isEqualTo(lastLogin);
        assertThat(graceRow.createdAt()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        assertThat(graceRow.groups()).containsExactly(
                new IdentitySummary.DirectGroup(eng.id(), "Engineering"),
                new IdentitySummary.DirectGroup(ops.id(), "Operators"));
    }

    /**
     * The Bootstrap Admin is flagged so the page can show it with no lockout state and no
     * Unlock: it can never be locked. Recognised by the reservation marker, so a User merely
     * NAMED like it is not.
     */
    @Test
    void flagsTheBootstrapAdminByItsReservationMarker() {
        givenBootstrapAdmin();
        given("root-lookalike");

        assertThat(service.listIdentities())
                .extracting(IdentitySummary::userName, IdentitySummary::bootstrapAdmin)
                .containsExactly(tuple(BOOTSTRAP, true), tuple("root-lookalike", false));
    }

    /** An operation's response is the same row the listing would show, Groups included. */
    @Test
    void anOperationAnswersWithTheFullRowGroupsIncluded() {
        givenBootstrapAdmin();
        ScimUser bob = givenLocked("bob");
        ScimGroup admins = givenAdminGroup(bob);
        ScimGroup eng = groups.create(ScimIdentities.group("Engineering", bob));

        IdentitySummary unlocked = service.unlock(bob.id(), actor(BOOTSTRAP));

        assertThat(unlocked.admin()).isTrue();
        assertThat(unlocked.bootstrapAdmin()).isFalse();
        assertThat(unlocked.groups()).containsExactly(
                new IdentitySummary.DirectGroup(admins.id(), "Admins"),
                new IdentitySummary.DirectGroup(eng.id(), "Engineering"));
        assertThat(unlocked).isEqualTo(service.listIdentities().stream()
                .filter(row -> row.id().equals(bob.id()))
                .findFirst()
                .orElseThrow());
    }

    // Reviewing the Groups

    /**
     * Every Group with its direct member count, the protected Admin group marked by its
     * reservation — not by its name, which a connector-created Group can share in spirit — and
     * the Permissions each carries, sorted by name: none for the Admin group, whose members are
     * the Superuser, and none for a Group that confers nothing.
     */
    @Test
    void listsEveryGroupWithItsMemberCountTheProtectedAdminMarkerAndItsPermissions() {
        ScimUser ada = given("ada");
        ScimUser bob = given("bob");
        ScimGroup admins = givenAdminGroup(ada);
        ScimGroup lookalike = groups.create(ScimIdentities.group("Administrators", ada, bob));
        ScimGroup empty = groups.create(ScimIdentities.group("Empty"));
        groupPermissions.carrying(lookalike.id(), Permission.USER_READ, Permission.OPS_READ);

        // Normalized display-name order: "administrators" sorts before "admins". Read field by
        // field rather than compared as records, so the expected rows cannot share a defect of
        // the record they would be built with.
        assertThat(service.listGroups())
                .extracting(GroupSummary::id, GroupSummary::displayName,
                        GroupSummary::memberCount, GroupSummary::adminGroup,
                        GroupSummary::permissions)
                .containsExactly(
                        tuple(lookalike.id(), "Administrators", 2, false,
                                List.of("ops:read", "user:read")),
                        tuple(admins.id(), "Admins", 1, true, List.of()),
                        tuple(empty.id(), "Empty", 0, false, List.of()));
    }

    @Test
    void listsNoGroupsWhenNoneAreStored() {
        assertThat(service.listGroups()).isEmpty();
    }

    /** Reading the directory writes nothing and records nothing. */
    @Test
    void reviewingTheDirectoryChangesNothing() {
        given("ada");
        givenAdminGroup(users.require("ada"));
        int before = users.writes();

        service.listIdentities();
        service.listGroups();

        assertThat(users.writes()).isEqualTo(before);
        assertThat(audit.recorded()).isEmpty();
        assertThat(transaction.pending()).isZero();
    }

    @Test
    void listsIdentitiesOrderedByUserName() {
        given("zoe");
        given("ada");
        given("bob");

        assertThat(service.listIdentities())
                .extracting(IdentitySummary::userName)
                .containsExactly("ada", "bob", "zoe");
    }

    @Test
    void listsNoIdentitiesWhenNoneAreStored() {
        assertThat(service.listIdentities()).isEmpty();
    }

    /**
     * The listing reports the lock and no expiry, because there is none: the state does not
     * change with the clock, so a reader has nothing to compare and no reason to wait.
     */
    @Test
    void reportsALockoutAsInForceHoweverLongItHasStood() {
        givenLocked("ada");

        assertThat(service.listIdentities()).first()
                .extracting(IdentitySummary::locked)
                .isEqualTo(true);

        clock.advanceBy(A_LONG_TIME);

        assertThat(service.listIdentities()).first()
                .extracting(IdentitySummary::locked)
                .isEqualTo(true);
    }

    /**
     * {@code admin} is derived from Group membership rather than read from a column, so the
     * listing reports the same fact the login path derives and there is no column the two
     * could disagree about.
     */
    @Test
    void reportsTheAdminFlagFromMembershipOfTheReservedAdminGroup() {
        ScimUser ada = given("ada");
        given("bob");
        // A Group that merely displays as the administrators' one confers nothing.
        groups.createReserved(
                ScimIdentities.group("Reserved administrators", ada),
                ReservedResourceName.ADMIN_GROUP);
        groups.create(ScimIdentities.group("Admins", users.require("bob")));

        assertThat(service.listIdentities())
                .extracting(IdentitySummary::userName, IdentitySummary::admin)
                .containsExactly(tuple("ada", true), tuple("bob", false));
    }

    /**
     * A credentialless identity exists and cannot log in, which is otherwise
     * indistinguishable from a forgotten password — so the listing says which it is.
     */
    @Test
    void reportsWhetherACredentialIsSetAtAll() {
        users.given(ScimIdentities.credentiallessUser("nopass"));

        assertThat(service.listIdentities()).first()
                .extracting(IdentitySummary::hasPassword)
                .isEqualTo(false);
    }

    // Unlocking

    @Test
    void unlockingEndsTheLockoutAndTheFailureRun() {
        givenLocked("bob");

        IdentitySummary unlocked = service.unlock(id("bob"), actor(BOOTSTRAP));

        assertThat(unlocked.locked()).isFalse();
        assertThat(users.require("bob").login().lockedAt()).isNull();
        assertThat(users.require("bob").login().failedLoginAttempts()).isZero();
    }

    /** The converse of the decision above: unlocking is not a reinstatement. */
    @Test
    void unlockingDoesNotActivateAnInactiveIdentity() {
        users.given(lockedAndInactive("bob"));

        IdentitySummary unlocked = service.unlock(id("bob"), actor(BOOTSTRAP));

        assertThat(unlocked.locked()).isFalse();
        assertThat(unlocked.active()).isFalse();
        assertThat(users.require("bob").profile().active()).isFalse();
    }

    /**
     * An Unlock has no effect on a User serving no lockout: it is refused, and nothing is written —
     * not even the failure run (ADR 0015).
     */
    @Test
    void unlockingAnIdentityThatIsNotLockedIsRefused() {
        users.given(withRun("bob", 2));

        assertUnlockRefused(() -> service.unlock(id("bob"), actor(BOOTSTRAP)),
                UnlockRefusedException.Reason.NOT_LOCKED, actor(BOOTSTRAP), id("bob"));
    }

    @Test
    void aRefusedUnlockOfAnUnlockedIdentityKeepsItsFailureRunAndWritesNothing() {
        users.given(withRun("bob", 2));
        int before = users.writes();

        assertUnlockRefused(() -> service.unlock(id("bob"), actor(BOOTSTRAP)),
                UnlockRefusedException.Reason.NOT_LOCKED, actor(BOOTSTRAP), id("bob"));

        assertThat(users.writes()).isEqualTo(before);
        assertThat(users.require("bob").login()).isEqualTo(withRun("bob", 2).login());
    }

    /**
     * The refusal names the actor, the target and the reason, and the use case records none of it:
     * the administrative edge audits and logs it once the transaction has rolled back.
     */
    @Test
    void aRefusedUnlockOfAnUnlockedIdentityNamesWhoAndWhyAndRecordsNothing() {
        ScimUser recovery = given(BOOTSTRAP);
        ScimUser bob = given("bob");

        assertUnlockRefused(() -> service.unlock(bob.id(), recovery.id()),
                UnlockRefusedException.Reason.NOT_LOCKED, recovery.id(), bob.id());

        assertThat(audit.recorded()).isEmpty();
    }

    /** A refused Unlock revokes nothing: the User's sessions are untouched. */
    @Test
    void aRefusedUnlockOfAnUnlockedIdentityTouchesNoSessions() {
        ScimUser bob = given("bob");
        sessions.open(bob.id(), "session-1");

        assertUnlockRefused(() -> service.unlock(bob.id(), actor(BOOTSTRAP)),
                UnlockRefusedException.Reason.NOT_LOCKED, actor(BOOTSTRAP), bob.id());

        assertThat(transaction.pending()).isZero();
        assertThat(sessions.sessionsOf(bob.id())).containsExactly("session-1");
    }

    /**
     * The Bootstrap Admin can never be locked, so another administrator's Unlock of it is always
     * refused as not locked.
     */
    @Test
    void anUnlockOfTheBootstrapAdminByAnotherAdministratorIsRefusedAsNotLocked() {
        ScimUser recovery = givenBootstrapAdmin();
        ScimUser ada = given("ada");

        assertUnlockRefused(() -> service.unlock(recovery.id(), ada.id()),
                UnlockRefusedException.Reason.NOT_LOCKED, ada.id(), recovery.id());

        assertThat(audit.recorded()).isEmpty();
    }

    /**
     * The self-target refusal comes first: an administrator unlocking its own unlocked account is
     * refused for targeting itself, whatever its state.
     */
    @Test
    void anAdministratorUnlockingItsOwnUnlockedAccountIsRefusedAsSelfTargeting() {
        ScimUser ada = given("ada");

        assertUnlockRefused(() -> service.unlock(ada.id(), ada.id()),
                UnlockRefusedException.Reason.SELF_TARGET, ada.id(), ada.id());

        assertThat(audit.recorded()).isEmpty();
    }

    /**
     * Unlocking clears the failure run, which is not a SCIM attribute — so it must not move
     * the resource's ETag either.
     */
    @Test
    void unlockingDoesNotAdvanceTheResourceVersion() {
        ScimUser bob = givenLocked("bob");

        service.unlock(id("bob"), actor(BOOTSTRAP));

        assertThat(users.require("bob").version()).isEqualTo(bob.version());
    }

    @Test
    void unlockingTouchesNoSessions() {
        ScimUser bob = givenLocked("bob");
        sessions.open(bob.id(), "session-1");

        service.unlock(id("bob"), actor(BOOTSTRAP));

        assertThat(sessions.revocations()).isEmpty();
        assertThat(sessions.sessionsOf(bob.id())).containsExactly("session-1");
    }

    /**
     * Time is not a lift, so an identity locked long ago is still locked and the unlock is
     * what clears it — both the recorded instant and the run behind it.
     */
    @Test
    void unlockingClearsALockoutHoweverLongItHasStood() {
        givenLocked("bob");
        clock.advanceBy(A_LONG_TIME);

        service.unlock(id("bob"), actor(BOOTSTRAP));

        assertThat(users.require("bob").login().lockedAt()).isNull();
        assertThat(users.require("bob").login().failedLoginAttempts()).isZero();
    }

    // Unknown identities

    @Test
    void refusesToActOnAnIdentityThatDoesNotExist() {
        given(BOOTSTRAP);
        UUID nobody = UUID.randomUUID();
        int before = users.writes();

        assertUnlockRefused(() -> service.unlock(nobody, actor(BOOTSTRAP)),
                UnlockRefusedException.Reason.UNKNOWN_IDENTITY, actor(BOOTSTRAP), nobody);

        assertThat(users.writes()).isEqualTo(before);
        assertThat(audit.recorded()).isEmpty();
    }

    /**
     * The operations address a User by its stable id, not its {@code userName}: a User renamed
     * after the Admin read the row is still the one acted on, and the name it used to have
     * names nobody.
     */
    @Test
    void anOperationFollowsTheIdAcrossARename() {
        ScimUser bob = givenLocked("bob");
        users.given(new ScimUser(
                bob.id(),
                ScimIdentities.profile("robert", true),
                bob.login(),
                null,
                bob.version() + 1,
                bob.createdAt(),
                NOW));

        IdentitySummary unlocked = service.unlock(bob.id(), actor(BOOTSTRAP));

        assertThat(unlocked.userName()).isEqualTo("robert");
        assertThat(unlocked.locked()).isFalse();
        assertThat(users.require("robert").login().isLocked()).isFalse();
    }

    // What the trail is told

    @Test
    void unlockingIsRecordedAsALiftCausedByAnAdministrator() {
        ScimUser recovery = given(BOOTSTRAP);
        ScimUser bob = givenLocked("bob");

        service.unlock(id("bob"), actor(BOOTSTRAP));

        assertThat(audit.recorded()).containsExactly(
                new Recorded(AuditOperation.LOCKOUT_LIFT, recovery.id(), bob.id(), "FAILURES"),
                new Recorded(AuditOperation.PASSWORD_CHANGE_REQUIRE, recovery.id(), bob.id(), null));
    }

    /**
     * The Unlock's two rows split what it moved: the lift names the run, the lock and the basis,
     * and the required change names the flag.
     */
    @Test
    void anUnlocksRowsNameWhatItMovedBetweenThem() {
        givenLocked("bob");

        service.unlock(id("bob"), actor(BOOTSTRAP));

        assertThat(List.of(audit.changedPaths(AuditOperation.LOCKOUT_LIFT),
                audit.changedPaths(AuditOperation.PASSWORD_CHANGE_REQUIRE)))
                .containsExactly(
                        List.of(List.of("failedLoginAttempts", "lockedAt", "lockCause",
                                "lastAuthenticatedAt")),
                        List.of(List.of("passwordChangeRequiredSince")));
    }

    // Dormancy locks (ADR 0007)

    /**
     * The listing says why a User is locked, so an operator can tell a forgotten password from
     * an abandoned account before unlocking; an unlocked User has no cause.
     */
    @Test
    void theListingCarriesEachLocksCause() {
        givenLocked("bob");
        givenLockedForDormancy("carol");
        given("dave");

        assertThat(service.listIdentities())
                .extracting(IdentitySummary::userName, IdentitySummary::locked,
                        IdentitySummary::lockCause)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("bob", true, LockCause.FAILURES),
                        org.assertj.core.groups.Tuple.tuple("carol", true, LockCause.DORMANCY),
                        org.assertj.core.groups.Tuple.tuple("dave", false, null));
    }

    /**
     * Unlock lifts a dormancy lock exactly as it lifts a failure lock — the lock and its cause
     * cleared, a password change required of a credentialed User — and also restarts the
     * dormancy window, so the next run does not lock the User again before it can sign in. The
     * returned row shows the restarted basis; the version does not move.
     */
    @Test
    void unlockingADormancyLockRestartsTheDormancyWindow() {
        ScimUser recovery = given(BOOTSTRAP);
        ScimUser carol = givenLockedForDormancy("carol");
        clock.advanceBy(A_LONG_TIME);

        IdentitySummary unlocked = service.unlock(carol.id(), actor(BOOTSTRAP));

        ScimLoginState after = users.require("carol").login();
        assertThat(after.isLocked()).isFalse();
        assertThat(after.lockCause()).isNull();
        assertThat(after.lastAuthenticatedAt()).isEqualTo(clock.instant());
        assertThat(after.passwordChangeRequiredSince()).isEqualTo(clock.instant());
        assertThat(users.require("carol").version()).isEqualTo(carol.version());
        assertThat(unlocked.locked()).isFalse();
        assertThat(unlocked.lockCause()).isNull();
        assertThat(unlocked.lastAuthenticatedAt()).isEqualTo(clock.instant());
        assertThat(unlocked.passwordChangeRequired()).isTrue();
        assertThat(audit.recorded()).containsExactly(
                new Recorded(AuditOperation.LOCKOUT_LIFT, recovery.id(), carol.id(), "DORMANCY"),
                new Recorded(AuditOperation.PASSWORD_CHANGE_REQUIRE, recovery.id(), carol.id(),
                        null));
    }

    /** A failure lock's Unlock restarts the window too: the lift is one operation. */
    @Test
    void unlockingAFailureLockRestartsTheDormancyWindowToo() {
        givenLocked("bob");
        clock.advanceBy(A_LONG_TIME);

        service.unlock(id("bob"), actor(BOOTSTRAP));

        assertThat(users.require("bob").login().lastAuthenticatedAt()).isEqualTo(clock.instant());
    }

    /**
     * A refused Unlock of a User that is not locked does not restart its window: an Unlock is not a
     * way to keep an unused account from going dormant.
     */
    @Test
    void aRefusedUnlockOfAnUnlockedUserLeavesItsDormancyBasis() {
        Instant lastLogin = NOW.minus(Duration.ofDays(80));
        ScimUser dave = users.given(ScimIdentities.userAuthenticatedAt("dave", lastLogin));
        clock.advanceBy(Duration.ofDays(1));

        assertUnlockRefused(() -> service.unlock(dave.id(), actor(BOOTSTRAP)),
                UnlockRefusedException.Reason.NOT_LOCKED, actor(BOOTSTRAP), dave.id());

        assertThat(users.require("dave").login().lastAuthenticatedAt()).isEqualTo(lastLogin);
    }

    /** A credentialless User locked for dormancy is unlocked without a change requirement. */
    @Test
    void unlockingACredentiallessDormancyLockRequiresNoChange() {
        ScimUser idle = users.given(ScimIdentities.userWithLoginState("idle",
                ScimLoginState.CREDENTIALLESS.withDormancyLock(NOW)));

        IdentitySummary unlocked = service.unlock(idle.id(), actor(BOOTSTRAP));

        assertThat(unlocked.locked()).isFalse();
        assertThat(unlocked.passwordChangeRequired()).isFalse();
        assertThat(users.require("idle").login().lastAuthenticatedAt()).isEqualTo(clock.instant());
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE_REQUIRE)).isEmpty();
    }

    /**
     * The acting administrator is named by the stable id its session holds, so a rename between
     * signing in and acting changes nothing about who is recorded as the actor.
     */
    @Test
    void aRenamedAdministratorIsStillRecordedAsTheActorByItsStableId() {
        ScimUser admin = given("admin");
        ScimUser bob = givenLocked("bob");
        users.rename(admin.id(), "admin-renamed");

        service.unlock(bob.id(), admin.id());

        assertThat(audit.of(AuditOperation.LOCKOUT_LIFT)).containsExactly(new Recorded(
                AuditOperation.LOCKOUT_LIFT, admin.id(), bob.id(), "FAILURES"));
    }

    /**
     * The self-target check compares stable ids: an administrator acting on the User that took
     * its former name is acting on somebody else, and is not refused.
     */
    @Test
    void anAdminActingOnTheUserWhoTookItsFormerNameIsNotSelfTargeting() {
        ScimUser admin = given("admin");
        users.rename(admin.id(), "admin-renamed");
        ScimUser successor = givenLocked("admin");

        assertThat(service.unlock(successor.id(), admin.id()).locked()).isFalse();
    }

    /** An administrator renamed since signing in still cannot act on its own account. */
    @Test
    void aRenamedAdminStillCannotUnlockItsOwnAccount() {
        ScimUser admin = givenLocked("admin");
        users.rename(admin.id(), "admin-renamed");

        assertUnlockRefused(() -> service.unlock(admin.id(), admin.id()),
                UnlockRefusedException.Reason.SELF_TARGET, admin.id(), admin.id());
    }

    /**
     * Each administrative write reports itself to the log stream as a named action with a
     * success outcome, separately from the audit row. The two serve different readers — an
     * operator watching for unexpected activity, and an auditor asking who changed what — so
     * a change that produced the row but no record, or the record but no row, is a defect in
     * one of them rather than a duplication.
     *
     * <p>Asserted here rather than left to review because it is the only proof that the call
     * is made at all: a removed log call changes nothing a test that reads only the returned
     * summary or the recorded event can see.
     */
    @Test
    void eachAdministrativeWriteReportsItsActionAndSuccessToTheLogStream() {
        ScimUser recovery = given(BOOTSTRAP);
        ScimUser ada = given("ada");
        givenAdminGroup(recovery, ada);
        givenLocked("bob");

        try (CapturedLog captured = CapturedLog.attach()) {
            service.unlock(id("bob"), actor(BOOTSTRAP));

            assertThat(captured.withAction(Level.INFO, LogEvent.LOCAL_ACTION, "identity.unlock"))
                    .singleElement()
                    .satisfies(record -> assertThat(CapturedLog.fields(record))
                            .containsEntry(LogEvent.OUTCOME, LogEvent.SUCCESS)
                            .containsEntry(LogEvent.USER_TARGET_ID, id("bob").toString()));
        }
    }

    /**
     * A refused Unlock reports nothing to the log stream from here — neither a success nor the
     * refusal, which the administrative edge records once ({@code AdminRefusalsTests}).
     */
    @Test
    void aRefusedUnlockWritesNoRecordOfItsOwn() {
        ScimUser ada = givenLocked("ada");
        ScimUser bob = given("bob");

        try (CapturedLog captured = CapturedLog.attach()) {
            assertUnlockRefused(() -> service.unlock(ada.id(), ada.id()),
                    UnlockRefusedException.Reason.SELF_TARGET, ada.id(), ada.id());
            assertUnlockRefused(() -> service.unlock(bob.id(), actor(BOOTSTRAP)),
                    UnlockRefusedException.Reason.NOT_LOCKED, actor(BOOTSTRAP), bob.id());

            assertThat(captured.withAction(Level.TRACE, LogEvent.LOCAL_ACTION, "identity.unlock"))
                    .isEmpty();
        }
    }

    // Unlock requires a password change (IM8 as-15 / ac-6)

    /**
     * The credential that reached the lockout threshold may be the one an attacker was guessing,
     * so lifting the lock requires it to be replaced: the flag is set as of the Unlock, reported
     * in the summary, and recorded as a requirement the Admin imposed.
     */
    @Test
    void unlockingALockedCredentialedUserRequiresAPasswordChange() {
        ScimUser recovery = givenBootstrapAdmin();
        ScimUser bob = givenLocked("bob");
        clock.advanceBy(Duration.ofHours(1));

        IdentitySummary unlocked = service.unlock(id("bob"), actor(BOOTSTRAP));

        assertThat(unlocked.passwordChangeRequired()).isTrue();
        assertThat(users.require("bob").login().passwordChangeRequiredSince())
                .isEqualTo(clock.instant());
        assertThat(users.require("bob").version())
                .as("the flag is not a SCIM attribute")
                .isEqualTo(bob.version());
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE_REQUIRE)).containsExactly(
                new Recorded(AuditOperation.PASSWORD_CHANGE_REQUIRE, recovery.id(), bob.id(), null));
    }

    /** A credentialless User has no password to replace, so it is unlocked without the flag. */
    @Test
    void unlockingACredentiallessUserDoesNotRequireAChange() {
        users.given(ScimIdentities.userWithLoginState(
                "carol", new ScimLoginState(null, 3, NOW)));

        IdentitySummary unlocked = service.unlock(id("carol"), actor(BOOTSTRAP));

        assertThat(unlocked.locked()).isFalse();
        assertThat(unlocked.passwordChangeRequired()).isFalse();
        assertThat(users.require("carol").login().isPasswordChangeRequired()).isFalse();
        assertThat(audit.of(AuditOperation.PASSWORD_CHANGE_REQUIRE)).isEmpty();
    }

    @Test
    void anAdminCannotUnlockTheirOwnAccount() {
        ScimUser ada = givenLocked("ada");
        int before = users.writes();

        assertUnlockRefused(() -> service.unlock(ada.id(), ada.id()),
                UnlockRefusedException.Reason.SELF_TARGET, ada.id(), ada.id());

        assertThat(users.writes()).isEqualTo(before);
        assertThat(users.require("ada").login().isLocked()).isTrue();
        assertThat(audit.recorded()).isEmpty();
    }

    /**
     * An operation with no acting administrator — which only a bypassed web adapter could send —
     * is refused before anything is written: whether it targets the actor itself cannot be
     * decided without one.
     */
    @Test
    void anUnlockWithNoActorIsRefusedBeforeAnythingIsWritten() {
        ScimUser bob = givenLocked("bob");

        assertThatNullPointerException().isThrownBy(() -> service.unlock(bob.id(), null));

        assertThat(users.require("bob").login().isLocked()).isTrue();
    }

    /** The Unlock is refused for {@code reason}, naming the actor and the User it addressed. */
    private static void assertUnlockRefused(
            ThrowingCallable unlock, UnlockRefusedException.Reason reason, UUID actorId,
            UUID userId) {
        assertThatExceptionOfType(UnlockRefusedException.class)
                .isThrownBy(unlock)
                .satisfies(refused -> assertThat(List.<Object>of(
                                refused.reason(), refused.actorId(), refused.userId()))
                        .containsExactly(reason, actorId, userId));
    }

    /**
     * The stable id the acting administrator's session holds: its own, when the test provisioned
     * it, or that of an administrator the test did not need to provision.
     */
    private UUID actor(String userName) {
        return users.findByNormalizedUserName(NormalizedUserName.of(userName))
                .map(ScimUser::id)
                .orElse(UNPROVISIONED_ADMIN);
    }

    /** The stable id every operation addresses a User by. */
    private UUID id(String userName) {
        return users.require(userName).id();
    }

    private ScimUser given(String userName) {
        return users.given(ScimIdentities.user(userName));
    }

    /** A User serving no lockout, with {@code failures} attempts in its failure run. */
    private static ScimUser withRun(String userName, int failures) {
        return ScimIdentities.userWithLoginState(
                userName, new ScimLoginState("hash", failures, null, null, NOW, null));
    }

    private ScimUser givenLocked(String userName) {
        return users.given(ScimIdentities.userWithLoginState(
                userName, new ScimLoginState("hash", 3, NOW)));
    }

    private ScimUser givenLockedForDormancy(String userName) {
        return users.given(ScimIdentities.userWithLoginState(
                userName, ScimLoginState.of("hash").withDormancyLock(NOW)));
    }

    /**
     * Locked <em>and</em> deactivated, which the shared fixture has no single factory for
     * because the two states are independent — the point of the pair of tests below is that
     * lifting one leaves the other standing.
     */
    private static ScimUser lockedAndInactive(String userName) {
        return new ScimUser(
                UUID.randomUUID(),
                ScimIdentities.profile(userName, false),
                new ScimLoginState("hash", 3, NOW),
                null,
                ScimUser.INITIAL_VERSION,
                NOW,
                NOW);
    }

    /**
     * The reservation is applied through the port, because production has no other way to
     * produce one: there is deliberately no factory that mints a reserved resource.
     */
    private ScimUser givenBootstrapAdmin() {
        return users.createReserved(
                ScimIdentities.user(BOOTSTRAP), ReservedResourceName.BOOTSTRAP_ADMIN);
    }

    private ScimGroup givenAdminGroup(ScimUser... members) {
        return groups.createReserved(
                ScimIdentities.group("Admins", members), ReservedResourceName.ADMIN_GROUP);
    }
}

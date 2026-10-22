package com.example.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.AuditAdministrativeRefusal;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditSessionRevocationCause;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.PendingCommit;
import com.example.backend.authorization.config.AuthorizationChangeMetrics;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.InMemoryGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.InMemoryScimWriteLocks;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A Superuser sets a Group's Permissions (ADR 0015, #159): the complete set replaces the Group's,
 * every success and every refusal is audited, and taking a Permission away ends every direct
 * member's sessions once the write commits (ADR 0002).
 */
class GroupPermissionAdministrationServiceTests {

    /** The Superuser making every change, by the stable id its session holds. */
    private static final UUID SUPERUSER = UUID.fromString("00000000-0000-4000-8000-00000000a0a0");

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();
    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);
    private final InMemoryGroupPermissions groupPermissions = InMemoryGroupPermissions.over(groups);
    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();
    private final PendingCommit transaction = new PendingCommit();
    private final RecordingAuditTrail audit = new RecordingAuditTrail();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InMemoryScimWriteLocks writeLocks = new InMemoryScimWriteLocks();

    private final GroupPermissionAdministrationService service =
            new GroupPermissionAdministrationService(
                    groups,
                    writeLocks,
                    groupPermissions,
                    groupPermissions,
                    new SessionRevocationService(
                            sessions, transaction, audit, new RecordingOperationalAlerts()),
                    audit,
                    new AuthorizationChangeMetrics(registry));

    // The replacement

    @Test
    void theRequestedSetReplacesWhatTheGroupCarried() {
        ScimGroup auditors = givenGroup("Auditors", Permission.USER_READ, Permission.AUDIT_READ);

        GroupSummary changed = service.setPermissions(
                auditors.id(), List.of("group:read", "audit:read"), SUPERUSER);

        assertThat(changed)
                .extracting(GroupSummary::id, GroupSummary::displayName,
                        GroupSummary::memberCount, GroupSummary::adminGroup,
                        GroupSummary::permissions)
                .containsExactly(
                        auditors.id(), "Auditors", 0, false, List.of("audit:read", "group:read"));
        assertThat(carried(auditors)).containsExactlyInAnyOrder(
                Permission.AUDIT_READ, Permission.GROUP_READ);
    }

    @Test
    void anEmptySetClearsTheGroup() {
        ScimGroup auditors = givenGroup("Auditors", Permission.USER_READ, Permission.AUDIT_READ);

        GroupSummary changed = service.setPermissions(auditors.id(), List.of(), SUPERUSER);

        assertThat(changed.permissions()).isEmpty();
        assertThat(carried(auditors)).isEmpty();
    }

    @Test
    void aValueNamedTwiceIsCarriedOnce() {
        ScimGroup auditors = givenGroup("Auditors");

        GroupSummary changed = service.setPermissions(
                auditors.id(), List.of("audit:read", "ops:read", "audit:read"), SUPERUSER);

        assertThat(changed.permissions()).containsExactly("audit:read", "ops:read");
    }

    @Test
    void theSummaryCountsTheGroupsDirectMembers() {
        ScimGroup auditors = givenGroup("Auditors", member("ada"), member("bob"));

        assertThat(service.setPermissions(auditors.id(), List.of("audit:read"), SUPERUSER)
                .memberCount()).isEqualTo(2);
    }

    // Session effects

    /** Growth reaches a member at its next sign-in, which reads the Group's Permissions anew. */
    @Test
    void aMemberHoldsAnAddedPermissionFromItsNextSignInAndKeepsItsLiveSession() {
        ScimUser ada = member("ada");
        ScimGroup auditors = givenGroup("Auditors", ada);
        sessions.open(ada.id(), "ada-session");

        service.setPermissions(auditors.id(), List.of("audit:read"), SUPERUSER);
        transaction.commit();

        assertThat(groupPermissions.effectivePermissionsOf(List.of(auditors.id())))
                .containsExactly(Permission.AUDIT_READ);
        assertThat(sessions.isLive("ada-session")).isTrue();
        assertThat(audit.of(AuditOperation.USER_SESSIONS_REVOKE)).isEmpty();
    }

    @Test
    void takingAPermissionAwayEndsEveryDirectMembersSessionsOnceTheChangeCommits() {
        ScimUser ada = member("ada");
        ScimUser bob = member("bob");
        ScimUser outsider = member("outsider");
        ScimGroup auditors = givenGroup("Auditors", ada, bob);
        givenGroup("Operators", Permission.OPS_READ, outsider);
        sessions.open(ada.id(), "ada-session");
        sessions.open(bob.id(), "bob-session");
        sessions.open(outsider.id(), "outsider-session");
        groupPermissions.carrying(auditors.id(), Permission.AUDIT_READ, Permission.USER_READ);

        service.setPermissions(auditors.id(), List.of("audit:read", "ops:read"), SUPERUSER);
        boolean liveBeforeCommit = sessions.isLive("ada-session") && sessions.isLive("bob-session");
        transaction.commit();

        assertThat(liveBeforeCommit).isTrue();
        assertThat(Map.of(
                        "ada", sessions.isLive("ada-session"),
                        "bob", sessions.isLive("bob-session"),
                        "outsider", sessions.isLive("outsider-session")))
                .isEqualTo(Map.of("ada", false, "bob", false, "outsider", true));
    }

    @Test
    void aRolledBackShrinkEndsNoSession() {
        ScimUser ada = member("ada");
        ScimGroup auditors = givenGroup("Auditors", Permission.AUDIT_READ, ada);
        sessions.open(ada.id(), "ada-session");

        service.setPermissions(auditors.id(), List.of(), SUPERUSER);
        transaction.rollback();

        assertThat(sessions.isLive("ada-session")).isTrue();
    }

    /** The revocation names the Superuser as its actor and the shrink as its cause. */
    @Test
    void theEndedSessionsAreAuditedAsTheSuperusersShrinkOfTheGroup() {
        ScimUser ada = member("ada");
        ScimGroup auditors = givenGroup("Auditors", Permission.AUDIT_READ, ada);
        sessions.open(ada.id(), "ada-session");

        service.setPermissions(auditors.id(), List.of(), SUPERUSER);
        transaction.commit();

        assertThat(audit.events(Audited.UserSessionsRevoked.class))
                .extracting(Audited.UserSessionsRevoked::actorId,
                        Audited.UserSessionsRevoked::userId, Audited.UserSessionsRevoked::causes)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SUPERUSER, ada.id(),
                        Set.of(AuditSessionRevocationCause.GROUP_PERMISSIONS_REDUCED)));
    }

    // Audit, log and counter of a success

    @Test
    void aChangeIsAuditedWithTheSuperuserTheGroupAndItsPermissionsBeforeAndAfter() {
        ScimGroup auditors = givenGroup("Auditors", Permission.USER_READ, Permission.AUDIT_READ);

        service.setPermissions(auditors.id(), List.of("audit:read", "group:read"), SUPERUSER);

        assertThat(audit.events()).containsExactly(new Audited.GroupPermissionsChanged(
                SUPERUSER, auditors.id(),
                Set.of(Permission.USER_READ, Permission.AUDIT_READ),
                Set.of(Permission.AUDIT_READ, Permission.GROUP_READ)));
    }

    @Test
    void aChangeIsLoggedAtInfoNamingTheGroupAndNoPermission() {
        ScimGroup auditors = givenGroup("Auditors", Permission.USER_READ);

        List<ILoggingEvent> records;
        try (CapturedLog log = CapturedLog.attach()) {
            service.setPermissions(auditors.id(), List.of("audit:read"), SUPERUSER);
            records = log.withAction(Level.INFO, LogEvent.LOCAL_ACTION,
                    "authorization.group_permissions_change");
        }

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.INFO);
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.ACTION, "user-administration")
                    .containsEntry(LogEvent.OUTCOME, "success")
                    .containsEntry(LogEvent.GROUP_ID, auditors.id().toString());
        });
    }

    @Test
    void eachChangeCountsOnceOnTheGroupPermissionsChangeSeries() {
        ScimGroup auditors = givenGroup("Auditors");

        service.setPermissions(auditors.id(), List.of("audit:read"), SUPERUSER);
        service.setPermissions(auditors.id(), List.of("audit:read"), SUPERUSER);

        assertThat(changesCounted()).isEqualTo(2.0);
    }

    // Refusals: each audited, logged at WARN, and nothing written

    @Test
    void aMissingBodyIsRefusedAsInvalidAndChangesNothing() {
        ScimGroup auditors = givenGroup("Auditors", Permission.AUDIT_READ);

        Throwable refused = catchThrowable(
                () -> service.setPermissions(auditors.id(), null, SUPERUSER));

        assertThat(refused).isInstanceOf(InvalidGroupPermissionsRequestException.class);
        assertThat(audit.events()).containsExactly(new Audited.GroupPermissionsChangeRefused(
                SUPERUSER, auditors.id(), AuditAdministrativeRefusal.INVALID_PERMISSIONS));
        assertThat(carried(auditors)).containsExactly(Permission.AUDIT_READ);
    }

    /**
     * Neither a name the vocabulary lacks nor a Permission no Group may carry — the token-only
     * write halves, the baseline — and not the Superuser, which is no Permission at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"user:write", "group:write", "patient:read", "superuser",
            "AUDIT:READ", "user:frobnicate", ""})
    void aValueNoGroupMayCarryRefusesTheWholeSet(String value) {
        ScimGroup auditors = givenGroup("Auditors", Permission.AUDIT_READ);

        assertThatThrownBy(() -> service.setPermissions(
                auditors.id(), List.of("ops:read", value), SUPERUSER))
                .isInstanceOf(InvalidGroupPermissionsRequestException.class);
        assertThat(audit.events()).containsExactly(new Audited.GroupPermissionsChangeRefused(
                SUPERUSER, auditors.id(), AuditAdministrativeRefusal.INVALID_PERMISSIONS));
        assertThat(carried(auditors)).containsExactly(Permission.AUDIT_READ);
    }

    @Test
    void aGroupThatDoesNotExistIsRefusedAsUnknown() {
        UUID nowhere = UUID.fromString("00000000-0000-4000-8000-0000000dead0");

        assertThatThrownBy(() -> service.setPermissions(nowhere, List.of("audit:read"), SUPERUSER))
                .isInstanceOf(UnknownGroupException.class);
        assertThat(audit.events()).containsExactly(new Audited.GroupPermissionsChangeRefused(
                SUPERUSER, nowhere, AuditAdministrativeRefusal.UNKNOWN_RESOURCE));
    }

    @Test
    void aDeletedGroupIsRefusedAsUnknown() {
        ScimGroup gone = givenGroup("Gone", Permission.AUDIT_READ);
        groups.deleteById(gone.id(), ScimIdentities.NOW);

        assertThatThrownBy(() -> service.setPermissions(gone.id(), List.of("ops:read"), SUPERUSER))
                .isInstanceOf(UnknownGroupException.class);
        assertThat(audit.events()).containsExactly(new Audited.GroupPermissionsChangeRefused(
                SUPERUSER, gone.id(), AuditAdministrativeRefusal.UNKNOWN_RESOURCE));
    }

    /** The Admin group's members are the Superuser, which is not a Permission: nothing to set. */
    @Test
    void theAdminGroupIsRefusedAsProtectedAndKeepsCarryingNothing() {
        ScimGroup admins = groups.createReserved(
                ScimIdentities.group("Admins", member("root")), ReservedResourceName.ADMIN_GROUP);

        assertThatThrownBy(() -> service.setPermissions(
                admins.id(), List.of("audit:read"), SUPERUSER))
                .isInstanceOf(ProtectedGroupException.class);
        assertThat(audit.events()).containsExactly(new Audited.GroupPermissionsChangeRefused(
                SUPERUSER, admins.id(), AuditAdministrativeRefusal.PROTECTED_RESOURCE));
        assertThat(carried(admins)).isEmpty();
    }

    @Test
    void aRefusalIsLoggedAtWarnWithItsReasonAndCountsNothing() {
        ScimGroup auditors = givenGroup("Auditors");

        List<ILoggingEvent> records;
        try (CapturedLog log = CapturedLog.attach()) {
            catchThrowable(() -> service.setPermissions(
                    auditors.id(), List.of("user:write"), SUPERUSER));
            records = log.withAction(Level.INFO, LogEvent.LOCAL_ACTION,
                    "authorization.group_permissions_change");
        }

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARN);
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.OUTCOME, "failure")
                    .containsEntry(LogEvent.REASON, "INVALID_PERMISSIONS")
                    .containsEntry(LogEvent.GROUP_ID, auditors.id().toString());
        });
        assertThat(changesCounted()).isZero();
    }

    @Test
    void aChangeNamingNoSuperuserIsRefusedBeforeAnythingIsWritten() {
        ScimGroup auditors = givenGroup("Auditors", Permission.AUDIT_READ);

        assertThatThrownBy(() -> service.setPermissions(auditors.id(), List.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.refuseUnreadableBody(auditors.id(), null))
                .isInstanceOf(NullPointerException.class);
        assertThat(audit.events()).isEmpty();
        assertThat(carried(auditors)).containsExactly(Permission.AUDIT_READ);
    }

    /**
     * The Group's resource lock is asked for, so a connector's membership change and a second
     * replacement wait for this one: the members a shrink ends are the members the Group has when
     * the new set is stored. That the lock excludes a second writer is the real adapter's, proven
     * against the database.
     */
    @Test
    void theGroupsWriteLockIsTakenForAChange() {
        ScimGroup auditors = givenGroup("Auditors");

        service.setPermissions(auditors.id(), List.of("audit:read"), SUPERUSER);

        assertThat(writeLocks.requests()).containsExactly(Set.of(auditors.id()));
    }

    /** A body that was not JSON at all is refused, audited and logged as one that is no set. */
    @Test
    void anUnreadableBodyIsAuditedAsInvalidAndChangesNothing() {
        ScimGroup auditors = givenGroup("Auditors", Permission.AUDIT_READ);

        service.refuseUnreadableBody(auditors.id(), SUPERUSER);

        assertThat(audit.events()).containsExactly(new Audited.GroupPermissionsChangeRefused(
                SUPERUSER, auditors.id(), AuditAdministrativeRefusal.INVALID_PERMISSIONS));
        assertThat(carried(auditors)).containsExactly(Permission.AUDIT_READ);
    }

    /** A refusal before anything was written ends no session, whatever the Group carried. */
    @Test
    void aRefusalEndsNoSession() {
        ScimUser ada = member("ada");
        ScimGroup auditors = givenGroup("Auditors", Permission.AUDIT_READ, ada);
        sessions.open(ada.id(), "ada-session");

        catchThrowable(() -> service.setPermissions(auditors.id(), null, SUPERUSER));
        transaction.commit();

        assertThat(sessions.isLive("ada-session")).isTrue();
    }

    // Helpers

    private ScimUser member(String userName) {
        return users.given(ScimIdentities.user(userName));
    }

    private ScimGroup givenGroup(String displayName, ScimUser... members) {
        return groups.create(ScimIdentities.group(displayName, members));
    }

    private ScimGroup givenGroup(String displayName, Permission permission, ScimUser... members) {
        ScimGroup group = givenGroup(displayName, members);
        groupPermissions.carrying(group.id(), permission);
        return group;
    }

    private ScimGroup givenGroup(String displayName, Permission first, Permission second) {
        ScimGroup group = givenGroup(displayName);
        groupPermissions.carrying(group.id(), first, second);
        return group;
    }

    private Set<Permission> carried(ScimGroup group) {
        return groupPermissions.carriedBy(List.of(group.id())).getOrDefault(group.id(), Set.of());
    }

    private double changesCounted() {
        return registry.get("authorization.changes")
                .tag("operation", "group-permissions-change").counter().count();
    }
}

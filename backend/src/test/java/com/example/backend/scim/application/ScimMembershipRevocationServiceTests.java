package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditPermissionChange;
import com.example.backend.audit.domain.Audited;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.InMemoryGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupReference;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserSessions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The dormancy job's membership revocation, as the directory carries it out: each candidate Group
 * the dormant User is still in loses it, and what that removal implies — one actorless audit event,
 * one Session revocation — is applied once, naming only the Groups actually left. A Group deleted
 * since the job's candidate query has no membership to take, and a revocation that took none
 * records and ends nothing.
 */
class ScimMembershipRevocationServiceTests {

    private static final Instant NOW = ScimIdentities.NOW;

    /** Sorts before {@link #AUDITORS}, so the Groups an event names read in a fixed order. */
    private static final UUID HELPDESK = UUID.fromString("00000000-0000-4000-8000-00000000a000");

    private static final UUID AUDITORS = UUID.fromString("00000000-0000-4000-8000-00000000a001");

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    private final InMemoryGroupPermissions permissions = InMemoryGroupPermissions.over(groups);

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    /** One Session revocation requested: the actor it names, the User, and why. */
    private record Revocation(UUID actorId, UUID userId, Set<ScimUserSessions.Cause> causes) {
    }

    private final List<Revocation> revocations = new ArrayList<>();

    private int superuserGrants;

    private final ScimMembershipRevocationService service = new ScimMembershipRevocationService(
            users,
            groups,
            (connectorId, userId, causes) ->
                    revocations.add(new Revocation(connectorId, userId, causes)),
            audit,
            permissions,
            () -> superuserGrants++);

    private final ScimUser ada = users.create(ScimIdentities.user("ada"));

    @Test
    void removing_the_user_from_every_candidate_group_answers_true() {
        UUID helpdesk = conferring("Helpdesk", Permission.USER_READ);
        UUID auditors = conferring("Auditors", Permission.AUDIT_READ);

        assertThat(service.revokeMemberships(ada.id(), List.of(helpdesk, auditors), NOW)).isTrue();
    }

    @Test
    void the_user_is_left_in_none_of_the_candidate_groups() {
        UUID helpdesk = conferring("Helpdesk", Permission.USER_READ);
        UUID auditors = conferring("Auditors", Permission.AUDIT_READ);

        service.revokeMemberships(ada.id(), List.of(helpdesk, auditors), NOW);

        assertThat(groups.findGroupsOfUser(ada.id())).isEmpty();
    }

    /**
     * Applied once for the whole revocation, not once per Group: one actorless event naming every
     * Group removed, with the Permissions the User held before and after.
     */
    @Test
    void every_removal_is_audited_as_one_actorless_event_naming_each_group() {
        UUID helpdesk = conferring(HELPDESK, Permission.USER_READ);
        UUID auditors = conferring(AUDITORS, Permission.AUDIT_READ);

        service.revokeMemberships(ada.id(), List.of(helpdesk, auditors), NOW);

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.DORMANCY_MEMBERSHIP_REVOCATION, null, ada.id(),
                "00000000-0000-4000-8000-00000000a000,00000000-0000-4000-8000-00000000a001"
                        + ":audit:read,user:read>:false"));
    }

    /** One Session revocation for the whole revocation, naming no actor: the job is nobody. */
    @Test
    void every_removal_ends_the_users_sessions_once_naming_no_actor() {
        UUID helpdesk = conferring("Helpdesk", Permission.USER_READ);
        UUID auditors = conferring("Auditors", Permission.AUDIT_READ);

        service.revokeMemberships(ada.id(), List.of(helpdesk, auditors), NOW);

        assertThat(revocations).containsExactly(new Revocation(
                null, ada.id(), Set.of(ScimUserSessions.Cause.MEMBERSHIP_REVOKED)));
    }

    /** Every candidate Group was deleted since the job's query: nothing was removed. */
    @Test
    void a_revocation_whose_every_group_is_gone_answers_false() {
        assertThat(service.revokeMemberships(
                ada.id(), List.of(deletedGroup(), deletedGroup()), NOW)).isFalse();
    }

    @Test
    void a_revocation_whose_every_group_is_gone_records_nothing() {
        service.revokeMemberships(ada.id(), List.of(deletedGroup(), deletedGroup()), NOW);

        assertThat(audit.recorded()).isEmpty();
    }

    @Test
    void a_revocation_whose_every_group_is_gone_ends_no_session() {
        service.revokeMemberships(ada.id(), List.of(deletedGroup(), deletedGroup()), NOW);

        assertThat(revocations).isEmpty();
    }

    /** A User no longer in a Group that still exists is the same case: nothing to take. */
    @Test
    void a_revocation_from_groups_the_user_has_since_left_answers_false() {
        UUID emptied = groups.create(ScimIdentities.group("Emptied")).id();
        permissions.carrying(emptied, Permission.USER_READ);

        assertThat(service.revokeMemberships(ada.id(), List.of(emptied), NOW)).isFalse();
    }

    /** Some Groups gone, one still holding the User: that one removal is still a revocation. */
    @Test
    void a_revocation_that_removes_from_some_groups_answers_true() {
        UUID helpdesk = conferring("Helpdesk", Permission.USER_READ);

        assertThat(service.revokeMemberships(
                ada.id(), List.of(deletedGroup(), helpdesk, deletedGroup()), NOW)).isTrue();
    }

    /** The event names the Group the User was removed from, and none of those already gone. */
    @Test
    void a_partial_revocation_audits_only_the_groups_actually_removed() {
        UUID helpdesk = conferring("Helpdesk", Permission.USER_READ);

        service.revokeMemberships(ada.id(), List.of(deletedGroup(), helpdesk, deletedGroup()), NOW);

        assertThat(audit.events(Audited.DormancyMembershipRevocation.class))
                .extracting(Audited.DormancyMembershipRevocation::groupIds)
                .containsExactly(List.of(helpdesk));
    }

    @Test
    void a_partial_revocation_ends_the_users_sessions_once() {
        UUID helpdesk = conferring("Helpdesk", Permission.USER_READ);

        service.revokeMemberships(ada.id(), List.of(deletedGroup(), helpdesk), NOW);

        assertThat(revocations).containsExactly(new Revocation(
                null, ada.id(), Set.of(ScimUserSessions.Cause.MEMBERSHIP_REVOKED)));
    }

    /** The User keeps every Group the job did not name. */
    @Test
    void a_group_the_job_did_not_name_keeps_the_user() {
        UUID helpdesk = conferring("Helpdesk", Permission.USER_READ);
        UUID kept = conferring("Auditors", Permission.AUDIT_READ);

        service.revokeMemberships(ada.id(), List.of(helpdesk), NOW);

        assertThat(groups.findGroupsOfUser(ada.id()))
                .extracting(ScimGroupReference::id)
                .containsExactly(kept);
    }

    /**
     * Removing a dormant User from the Admin group takes the Superuser away, and the event says so
     * with its marker.
     */
    @Test
    void a_removal_from_the_admin_group_is_audited_as_a_change_of_the_superuser() {
        UUID admins = adminGroupOfAda();

        service.revokeMemberships(ada.id(), List.of(admins), NOW);

        assertThat(audit.events(Audited.DormancyMembershipRevocation.class))
                .extracting(Audited.DormancyMembershipRevocation::change)
                .extracting(AuditPermissionChange::superuserChanged)
                .containsExactly(true);
    }

    /**
     * The counter behind {@code authorization.changes} counts a Superuser <em>grant</em>
     * ({@code operation="superuser-grant"}), and has no series for a removal: the dormancy job
     * only removes, so taking the Admin group away counts nothing. The removal is the audit
     * event's to carry, as the case above asserts.
     */
    @Test
    void a_removal_from_the_admin_group_counts_no_superuser_grant() {
        UUID admins = adminGroupOfAda();

        service.revokeMemberships(ada.id(), List.of(admins), NOW);

        assertThat(superuserGrants).isZero();
    }

    /** A Group holding Ada that carries these Permissions. */
    private UUID conferring(String displayName, Permission... carried) {
        return conferring(UUID.randomUUID(), displayName, carried);
    }

    /** {@link #conferring(String, Permission...)}, under a fixed id. */
    private UUID conferring(UUID id, Permission... carried) {
        return conferring(id, "Group " + id, carried);
    }

    private UUID conferring(UUID id, String displayName, Permission... carried) {
        groups.create(ScimGroup.created(
                id, displayName, List.of(ScimGroupMember.reference(ada.id())), NOW));
        permissions.carrying(id, carried);
        return id;
    }

    /** The Admin group, its marker held, with Ada its one direct member: the Superuser. */
    private UUID adminGroupOfAda() {
        return groups.createReserved(
                        ScimGroup.created(TestGroupPermissions.SUPERUSER_GROUP_ID, "Admins",
                                List.of(ScimGroupMember.reference(ada.id())), NOW),
                        ReservedResourceName.ADMIN_GROUP)
                .id();
    }

    /** A Group that held Ada when the job's query ran and has been deleted since. */
    private UUID deletedGroup() {
        UUID id = conferring("Gone " + UUID.randomUUID(), Permission.GROUP_READ);
        groups.deleteById(id, NOW);
        return id;
    }
}

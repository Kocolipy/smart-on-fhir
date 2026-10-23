package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.authorization.domain.ConferredPower;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroupPermissionStore;
import com.example.backend.scim.domain.ScimGroupReference;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@link GroupPermissions} answers, whatever stores the Groups: the SQL Server adapter,
 * and the in-memory one Login, the Accounts list and the SCIM power audit run their unit tests
 * against. Which Users are the Superuser — direct members of the Group carrying the
 * {@code admin-group} reservation marker, never a Group recognised by its name (ADR 0015) — is
 * answered here, together with the Permissions their Groups carry, so a unit test passing against
 * the in-memory adapter says something about production only while both hold to this.
 *
 * <p>Each User's memberships are read through the directory, as every caller reads them. Groups are
 * named uniquely: the SQL Server adapter's database is shared with other classes.
 */
public abstract class GroupPermissionsContract {

    /** The adapter under test. */
    protected abstract GroupPermissions permissions();

    /** The Group Permissions store over the same storage, which Groups are given Permissions by. */
    protected abstract ScimGroupPermissionStore store();

    /** The Group port over the same storage. */
    protected abstract ScimGroupRepository groups();

    /** The User port over the same storage. */
    protected abstract ScimUserRepository users();

    // ---- the Superuser -------------------------------------------------------------------------

    /**
     * A direct member of the Admin group is the Superuser and holds every delegatable Permission,
     * though the Admin group carries none.
     */
    @Test
    void a_direct_member_of_the_admin_group_is_the_superuser_holding_every_delegatable_permission() {
        ScimUser ada = user();
        adminGroupWith(ada);

        assertThat(permissions().powerOf(memberships(ada)))
                .isEqualTo(new ConferredPower(Permission.allDelegatable(), true));
    }

    /** Only the marker decides it: a Group a connector names like the Admin group confers nothing. */
    @Test
    void a_member_of_a_group_named_like_the_admin_group_is_not_the_superuser() {
        ScimUser ada = user();
        groups().create(ScimIdentities.group("Admins " + UUID.randomUUID(), ada));

        assertThat(permissions().powerOf(memberships(ada))).isEqualTo(ConferredPower.NONE);
    }

    /** Where no Group carries the marker, nobody is the Superuser. */
    @Test
    void no_group_is_the_admin_group_once_the_marker_is_held_by_none() {
        groups().findByReservedName(ReservedResourceName.ADMIN_GROUP)
                .ifPresent(holder -> groups().deleteById(holder.id(), ScimIdentities.NOW));

        assertThat(permissions().adminGroup()).isEmpty();
    }

    // ---- the Permissions -----------------------------------------------------------------------

    /** A User in several Groups holds the union of what they carry, and is not the Superuser. */
    @Test
    void a_users_groups_confer_the_union_of_what_each_carries() {
        ScimUser ada = user();
        UUID auditors = groupWith(ada);
        UUID helpdesk = groupWith(ada);
        store().add(auditors, Set.of(Permission.AUDIT_READ, Permission.USER_READ));
        store().add(helpdesk, Set.of(Permission.USER_READ, Permission.ACCOUNT_UNLOCK));

        assertThat(permissions().powerOf(memberships(ada))).isEqualTo(new ConferredPower(
                Set.of(Permission.AUDIT_READ, Permission.USER_READ, Permission.ACCOUNT_UNLOCK),
                false));
    }

    /** A deleted Group's Permissions went with it. */
    @Test
    void a_deleted_group_confers_nothing() {
        UUID gone = groupWith();
        store().add(gone, Set.of(Permission.AUDIT_READ));
        groups().deleteById(gone, ScimIdentities.NOW);

        assertThat(permissions().powerOf(List.of(gone))).isEqualTo(ConferredPower.NONE);
    }

    /**
     * A deletion's audit still reads what the deleted Group carried, remembered, beside what every
     * other Group carries now and the Superuser as the marker says.
     */
    @Test
    void a_remembered_deleted_group_still_confers_what_it_carried() {
        ScimUser ada = user();
        UUID gone = groupWith(ada);
        UUID auditors = groupWith(ada);
        store().add(auditors, Set.of(Permission.AUDIT_READ));
        List<UUID> before = memberships(ada);
        groups().deleteById(gone, ScimIdentities.NOW);

        assertThat(permissions().remembering(gone, Set.of(Permission.OPS_READ))
                        .powerOf(before))
                .isEqualTo(new ConferredPower(Set.of(Permission.OPS_READ, Permission.AUDIT_READ),
                        false));
    }

    // ---- several Users at once -----------------------------------------------------------------

    /** Several Users, each answered from its own memberships and nothing another's confer. */
    @Test
    void each_user_is_answered_from_its_own_memberships() {
        ScimUser ada = user();
        ScimUser bob = user();
        ScimUser cy = user();
        adminGroupWith(bob);
        UUID auditors = groupWith(ada, bob);
        store().add(auditors, Set.of(Permission.AUDIT_READ));

        assertThat(permissions().powersOf(Map.of(
                        ada.id(), memberships(ada),
                        bob.id(), memberships(bob),
                        cy.id(), memberships(cy))))
                .isEqualTo(Map.of(
                        ada.id(), new ConferredPower(Set.of(Permission.AUDIT_READ), false),
                        bob.id(), new ConferredPower(Permission.allDelegatable(), true),
                        cy.id(), ConferredPower.NONE));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private ScimUser user() {
        return users().create(ScimIdentities.user("user-" + UUID.randomUUID()));
    }

    /** A new unreserved Group with these members, by id. */
    private UUID groupWith(ScimUser... members) {
        return groups().create(ScimIdentities.group("Group " + UUID.randomUUID(), members)).id();
    }

    /**
     * Makes {@code member} the one direct member of the Admin group, reserved here: whoever held
     * the marker — the seeded Admin group, where there is one — is deleted first, since it is held
     * once per store.
     */
    private void adminGroupWith(ScimUser member) {
        groups().findByReservedName(ReservedResourceName.ADMIN_GROUP)
                .ifPresent(holder -> groups().deleteById(holder.id(), ScimIdentities.NOW));
        groups().createReserved(
                ScimIdentities.group("Admins " + UUID.randomUUID(), member),
                ReservedResourceName.ADMIN_GROUP);
    }

    /** The User's direct Groups, as the directory reads them for every caller. */
    private List<UUID> memberships(ScimUser user) {
        return groups().findGroupsOfUser(user.id()).stream().map(ScimGroupReference::id).toList();
    }
}

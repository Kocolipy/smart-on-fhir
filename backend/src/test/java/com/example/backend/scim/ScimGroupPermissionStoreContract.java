package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.StoredGroupPermission;
import com.example.backend.scim.domain.GroupPermissionRefusal;
import com.example.backend.scim.domain.GroupPermissionsRefusedException;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroupPermissionStore;
import com.example.backend.scim.domain.ScimGroupRepository;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ScimGroupPermissionStore} does, whatever stores the Groups' Permissions: the
 * SQL Server adapter, and the in-memory store the Superuser's write and the seeding run their unit
 * tests against. The rule of what a Group may carry (ADR 0015) is the port's, so a writer's unit
 * test passing against the in-memory store says something about production only while both hold
 * to it, and each runs this.
 *
 * <p>Each refusal is pinned three ways — asked, by {@code add}, by {@code replace} — because a
 * writer may take any of the three, and a refused write must leave the Group carrying what it did.
 * Rows are read back through the port, filtered to the case's own Group: the SQL Server adapter's
 * database is shared with other classes.
 */
public abstract class ScimGroupPermissionStoreContract {

    /** The store under test. */
    protected abstract ScimGroupPermissionStore store();

    /** The Group port over the same storage, which the store's Groups are created through. */
    protected abstract ScimGroupRepository groups();

    private static final UUID NOWHERE = UUID.fromString("00000000-0000-4000-8000-0000000dead1");

    // ---- writes that go through ----------------------------------------------------------------

    @Test
    void adding_gives_a_group_the_permissions_beside_those_it_carries() {
        UUID auditors = group("Auditors");
        store().add(auditors, Set.of(Permission.AUDIT_READ));

        store().add(auditors, Set.of(Permission.OPS_READ));

        assertThat(carried(auditors)).containsExactlyInAnyOrder("audit:read", "ops:read");
    }

    @Test
    void a_group_given_a_permission_carries_some() {
        UUID auditors = group("Auditors");

        store().add(auditors, Set.of(Permission.AUDIT_READ));

        assertThat(store().carriesAny(auditors)).isTrue();
    }

    @Test
    void replacing_leaves_a_group_carrying_exactly_the_new_set() {
        UUID auditors = group("Auditors");
        store().add(auditors, Set.of(Permission.AUDIT_READ, Permission.OPS_READ));

        store().replace(auditors, Set.of(Permission.USER_READ, Permission.OPS_READ));

        assertThat(carried(auditors)).containsExactlyInAnyOrder("user:read", "ops:read");
    }

    @Test
    void replacing_with_nothing_clears_a_group() {
        UUID auditors = group("Auditors");
        store().add(auditors, Set.of(Permission.AUDIT_READ));

        store().replace(auditors, Set.of());

        assertThat(store().carriesAny(auditors)).isFalse();
    }

    @Test
    void delegatable_permissions_for_an_ordinary_group_are_not_refused() {
        UUID auditors = group("Auditors");

        assertThat(store().refusal(auditors, Set.of(Permission.AUDIT_READ, Permission.ACCOUNT_UNLOCK)))
                .isEmpty();
    }

    // ---- a Permission no Group may carry -------------------------------------------------------

    @Test
    void permissions_no_group_may_carry_are_refused_naming_each() {
        UUID auditors = group("Auditors");

        assertThat(store().refusal(auditors,
                Set.of(Permission.AUDIT_READ, Permission.USER_WRITE, Permission.PATIENT_READ)))
                .contains(new GroupPermissionRefusal.NotDelegatable(
                        Set.of(Permission.USER_WRITE, Permission.PATIENT_READ)));
    }

    @Test
    void adding_a_permission_no_group_may_carry_is_refused_and_writes_nothing() {
        UUID auditors = group("Auditors");

        assertThatThrownBy(() -> store().add(auditors,
                Set.of(Permission.AUDIT_READ, Permission.GROUP_WRITE)))
                .isInstanceOfSatisfying(GroupPermissionsRefusedException.class, refused ->
                        assertThat(refused.refusal()).isEqualTo(
                                new GroupPermissionRefusal.NotDelegatable(
                                        Set.of(Permission.GROUP_WRITE))));
        assertThat(store().carriesAny(auditors)).isFalse();
    }

    @Test
    void replacing_with_a_permission_no_group_may_carry_is_refused_and_keeps_the_old_set() {
        UUID auditors = group("Auditors");
        store().add(auditors, Set.of(Permission.AUDIT_READ));

        assertThatThrownBy(() -> store().replace(auditors, Set.of(Permission.USER_WRITE)))
                .isInstanceOfSatisfying(GroupPermissionsRefusedException.class, refused ->
                        assertThat(refused.refusal()).isEqualTo(
                                new GroupPermissionRefusal.NotDelegatable(
                                        Set.of(Permission.USER_WRITE))));
        assertThat(carried(auditors)).containsExactly("audit:read");
    }

    /** What is asked for is read before the Group it is asked for: invalid Permissions come first. */
    @Test
    void a_permission_no_group_may_carry_is_refused_first_even_for_a_group_that_does_not_exist() {
        assertThat(store().refusal(NOWHERE, Set.of(Permission.USER_WRITE)))
                .contains(new GroupPermissionRefusal.NotDelegatable(Set.of(Permission.USER_WRITE)));
    }

    // ---- a Group that does not exist -----------------------------------------------------------

    @Test
    void a_group_that_does_not_exist_is_refused_as_unknown() {
        assertThat(store().refusal(NOWHERE, Set.of(Permission.AUDIT_READ)))
                .contains(new GroupPermissionRefusal.UnknownGroup());
    }

    @Test
    void a_deleted_group_is_refused_as_unknown() {
        UUID gone = group("Gone");
        groups().deleteById(gone, ScimIdentities.NOW);

        assertThat(store().refusal(gone, Set.of()))
                .contains(new GroupPermissionRefusal.UnknownGroup());
    }

    @Test
    void adding_to_a_group_that_does_not_exist_is_refused_and_writes_nothing() {
        assertThatThrownBy(() -> store().add(NOWHERE, Set.of(Permission.AUDIT_READ)))
                .isInstanceOfSatisfying(GroupPermissionsRefusedException.class, refused ->
                        assertThat(refused.refusal())
                                .isEqualTo(new GroupPermissionRefusal.UnknownGroup()));
        assertThat(carried(NOWHERE)).isEmpty();
    }

    @Test
    void replacing_for_a_group_that_does_not_exist_is_refused_and_writes_nothing() {
        assertThatThrownBy(() -> store().replace(NOWHERE, Set.of(Permission.AUDIT_READ)))
                .isInstanceOfSatisfying(GroupPermissionsRefusedException.class, refused ->
                        assertThat(refused.refusal())
                                .isEqualTo(new GroupPermissionRefusal.UnknownGroup()));
        assertThat(carried(NOWHERE)).isEmpty();
    }

    // ---- the Admin group -----------------------------------------------------------------------

    /** Even an empty set: the Admin group has no Permissions to set, cleared or otherwise. */
    @Test
    void the_admin_group_is_refused_whatever_it_is_given() {
        UUID admins = adminGroup();

        assertThat(store().refusal(admins, Set.of()))
                .contains(new GroupPermissionRefusal.AdminGroup());
    }

    @Test
    void adding_to_the_admin_group_is_refused_and_it_keeps_carrying_nothing() {
        UUID admins = adminGroup();

        assertThatThrownBy(() -> store().add(admins, Set.of(Permission.AUDIT_READ)))
                .isInstanceOfSatisfying(GroupPermissionsRefusedException.class, refused ->
                        assertThat(refused.refusal())
                                .isEqualTo(new GroupPermissionRefusal.AdminGroup()));
        assertThat(store().carriesAny(admins)).isFalse();
    }

    @Test
    void replacing_for_the_admin_group_is_refused_and_it_keeps_carrying_nothing() {
        UUID admins = adminGroup();

        assertThatThrownBy(() -> store().replace(admins, Set.of(Permission.AUDIT_READ)))
                .isInstanceOfSatisfying(GroupPermissionsRefusedException.class, refused ->
                        assertThat(refused.refusal())
                                .isEqualTo(new GroupPermissionRefusal.AdminGroup()));
        assertThat(store().carriesAny(admins)).isFalse();
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A new unreserved Group with no members, by id. */
    private UUID group(String displayName) {
        return groups().create(ScimIdentities.group(displayName + " " + UUID.randomUUID())).id();
    }

    /** The Admin group: the one seeding made where there is one, else one reserved here. */
    private UUID adminGroup() {
        return groups().findByReservedName(ReservedResourceName.ADMIN_GROUP)
                .orElseGet(() -> groups().createReserved(
                        ScimIdentities.group("Admins " + UUID.randomUUID()),
                        ReservedResourceName.ADMIN_GROUP))
                .id();
    }

    /** The names stored for this Group, read back through the port. */
    private Set<String> carried(UUID groupId) {
        return store().findAll().stream()
                .filter(row -> row.groupId().equals(groupId))
                .map(StoredGroupPermission::value)
                .collect(Collectors.toSet());
    }
}

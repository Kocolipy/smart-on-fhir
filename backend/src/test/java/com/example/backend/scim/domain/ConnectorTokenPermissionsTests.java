package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.TokenPermissions;
import com.example.backend.authorization.domain.Permission;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** What a connector token may carry, and the questions asked of it. */
class ConnectorTokenPermissionsTests {

    @Test
    void a_token_may_carry_all_four_directory_permissions() {
        assertThat(new ConnectorTokenPermissions(Set.of(
                Permission.USER_READ, Permission.USER_WRITE,
                Permission.GROUP_READ, Permission.GROUP_WRITE)).values())
                .containsExactlyInAnyOrder(
                        Permission.USER_READ, Permission.USER_WRITE,
                        Permission.GROUP_READ, Permission.GROUP_WRITE);
    }

    @ParameterizedTest
    @EnumSource(value = Permission.class,
            names = {"USER_READ", "USER_WRITE", "GROUP_READ", "GROUP_WRITE"},
            mode = EnumSource.Mode.EXCLUDE)
    void every_other_permission_is_refused_on_a_token(Permission application) {
        assertThatThrownBy(() -> ConnectorTokenPermissions.requested(
                List.of(Permission.USER_READ, application)))
                .isInstanceOf(InvalidConnectorTokenPermissionsException.class);
        assertThatThrownBy(() -> new ConnectorTokenPermissions(Set.of(application)))
                .isInstanceOf(InvalidConnectorTokenPermissionsException.class);
    }

    @Test
    void a_requested_token_needs_at_least_one_permission() {
        assertThatThrownBy(() -> ConnectorTokenPermissions.requested(List.of()))
                .isInstanceOf(InvalidConnectorTokenPermissionsException.class);
        assertThatThrownBy(() -> ConnectorTokenPermissions.requested(null))
                .isInstanceOf(InvalidConnectorTokenPermissionsException.class);
    }

    /** A stored token's Permissions are held to the same floor as a requested token's. */
    @Test
    void a_token_carrying_no_permission_is_refused_however_it_is_built() {
        assertThatThrownBy(() -> new ConnectorTokenPermissions(Set.of()))
                .isInstanceOf(InvalidConnectorTokenPermissionsException.class)
                .hasMessage("A token must carry at least one Permission");
        assertThatThrownBy(() -> new ConnectorTokenPermissions(null))
                .isInstanceOf(InvalidConnectorTokenPermissionsException.class)
                .hasMessage("A token must carry at least one Permission");
    }

    @Test
    void a_requested_set_keeps_its_permissions_and_drops_duplicates() {
        ConnectorTokenPermissions requested = ConnectorTokenPermissions.requested(
                List.of(Permission.GROUP_WRITE, Permission.USER_READ, Permission.GROUP_WRITE));

        assertThat(requested.values())
                .containsExactlyInAnyOrder(Permission.GROUP_WRITE, Permission.USER_READ);
        assertThat(requested.permits(Permission.GROUP_WRITE)).isTrue();
        assertThat(requested.permits(Permission.GROUP_READ)).isFalse();
    }

    @Test
    void the_values_are_spelled_and_sorted_as_the_wire_spells_them() {
        assertThat(new ConnectorTokenPermissions(TokenPermissions.ALL)
                .sortedValues())
                .containsExactly("group:read", "group:write", "user:read", "user:write");
    }

    @Test
    void the_readable_types_follow_the_read_permissions_alone() {
        assertThat(new ConnectorTokenPermissions(Set.of(Permission.USER_READ, Permission.GROUP_WRITE))
                .readableTypes()).containsExactly(ScimResourceType.USER);
        assertThat(new ConnectorTokenPermissions(Set.of(Permission.GROUP_READ)).readableTypes())
                .containsExactly(ScimResourceType.GROUP);
        assertThat(new ConnectorTokenPermissions(TokenPermissions.ALL)
                .readableTypes())
                .containsExactlyInAnyOrder(ScimResourceType.USER, ScimResourceType.GROUP);
    }

    @Test
    void any_read_means_either_read_permission() {
        assertThat(new ConnectorTokenPermissions(Set.of(Permission.USER_READ)).permitsAnyRead())
                .isTrue();
        assertThat(new ConnectorTokenPermissions(Set.of(Permission.GROUP_READ)).permitsAnyRead())
                .isTrue();
        assertThat(new ConnectorTokenPermissions(
                Set.of(Permission.USER_WRITE, Permission.GROUP_WRITE)).permitsAnyRead()).isFalse();
    }

    @Test
    void the_values_cannot_be_changed_from_outside() {
        Set<Permission> mutable = new HashSet<>(Set.of(Permission.USER_READ));
        ConnectorTokenPermissions token = new ConnectorTokenPermissions(mutable);
        mutable.add(Permission.USER_WRITE);

        assertThat(token.values()).containsExactly(Permission.USER_READ);
    }
}

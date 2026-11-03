package com.example.backend.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** The vocabulary's two flags: what a Group may carry, and what a connector token may carry. */
class PermissionTests {

    @Test
    void a_group_may_carry_exactly_the_delegatable_permissions() {
        assertThat(Arrays.stream(Permission.values()).filter(Permission::delegatable))
                .containsExactlyInAnyOrder(
                        Permission.USER_READ, Permission.ACCOUNT_UNLOCK, Permission.GROUP_READ,
                        Permission.AUDIT_READ, Permission.OPS_READ, Permission.CONNECTOR_READ);
    }

    @Test
    void a_connector_token_may_carry_exactly_the_token_grantable_permissions() {
        assertThat(Arrays.stream(Permission.values()).filter(Permission::tokenGrantable))
                .containsExactlyInAnyOrder(
                        Permission.USER_READ, Permission.USER_WRITE,
                        Permission.GROUP_READ, Permission.GROUP_WRITE);
    }

    @Test
    void only_the_write_halves_are_token_only() {
        assertThat(Arrays.stream(Permission.values()).filter(Permission::tokenOnly))
                .containsExactlyInAnyOrder(Permission.USER_WRITE, Permission.GROUP_WRITE);
    }

    @Test
    void allDelegatable_is_the_set_a_superuser_session_is_expanded_with() {
        assertThat(Permission.allDelegatable()).isEqualTo(EnumSet.of(
                Permission.USER_READ, Permission.ACCOUNT_UNLOCK, Permission.GROUP_READ,
                Permission.AUDIT_READ, Permission.OPS_READ, Permission.CONNECTOR_READ));
    }

    @Test
    void a_permission_is_read_from_its_wire_value_and_the_superuser_is_not_one() {
        assertThat(Permission.fromValue("account:unlock")).contains(Permission.ACCOUNT_UNLOCK);
        assertThat(Permission.fromValue("superuser")).isEmpty();
        assertThat(Permission.fromValue("ROLE_SUPERUSER")).isEmpty();
    }

    @Test
    void a_set_of_permissions_is_spelled_as_the_wire_spells_it_in_alphabetical_order() {
        assertThat(Permission.valuesOf(EnumSet.of(
                        Permission.USER_READ, Permission.AUDIT_READ, Permission.ACCOUNT_UNLOCK)))
                .containsExactly("account:unlock", "audit:read", "user:read");
    }
}

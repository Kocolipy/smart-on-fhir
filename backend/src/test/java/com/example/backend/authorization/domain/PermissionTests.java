package com.example.backend.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The vocabulary's two flags: what a Group may carry, and what a connector token may carry. */
class PermissionTests {

    @Test
    void a_group_may_carry_exactly_the_delegatable_permissions() {
        assertThat(Arrays.stream(Permission.values()).filter(Permission::delegatable))
                .containsExactlyInAnyOrder(
                        Permission.USER_READ, Permission.GROUP_READ, Permission.AUDIT_READ,
                        Permission.OPS_READ, Permission.CONNECTOR_READ);
    }

    @Test
    void a_connector_token_may_carry_exactly_the_token_grantable_permissions() {
        assertThat(Arrays.stream(Permission.values()).filter(Permission::tokenGrantable))
                .containsExactlyInAnyOrder(
                        Permission.USER_READ, Permission.USER_WRITE,
                        Permission.GROUP_READ, Permission.GROUP_WRITE);
    }

    @Test
    void the_baseline_patient_read_is_neither_delegatable_nor_token_grantable() {
        assertThat(Permission.PATIENT_READ.delegatable()).isFalse();
        assertThat(Permission.PATIENT_READ.tokenGrantable()).isFalse();
    }

    @Test
    void the_write_halves_are_token_only() {
        assertThat(Set.of(Permission.USER_WRITE, Permission.GROUP_WRITE))
                .allSatisfy(write -> {
                    assertThat(write.delegatable()).isFalse();
                    assertThat(write.tokenGrantable()).isTrue();
                });
    }

    @Test
    void allDelegatable_is_the_set_a_superuser_session_is_expanded_with() {
        assertThat(Permission.allDelegatable()).isEqualTo(EnumSet.of(
                Permission.USER_READ, Permission.GROUP_READ, Permission.AUDIT_READ,
                Permission.OPS_READ, Permission.CONNECTOR_READ));
    }

    @Test
    void the_superuser_is_not_a_permission() {
        assertThat(Permission.fromValue("superuser")).isEmpty();
        assertThat(Permission.fromValue("ROLE_SUPERUSER")).isEmpty();
        assertThat(Permission.fromValue("connector:write")).isEmpty();
        assertThat(Permission.fromValue("connector:token")).isEmpty();
    }
}

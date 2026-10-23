package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.backend.authorization.domain.Permission;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The store's refusal of a writer that did not ask first reaches a log as this exception's message,
 * which says which refusal it was and names no Permission.
 */
class GroupPermissionsRefusedExceptionTests {

    @Test
    void the_message_names_the_refusal_and_no_permission() {
        GroupPermissionsRefusedException refused = new GroupPermissionsRefusedException(
                new GroupPermissionRefusal.NotDelegatable(Set.of(Permission.USER_WRITE)));

        assertThat(refused).hasMessage(
                "The Group Permissions store refused the write: NotDelegatable");
    }

    @Test
    void a_refusal_is_thrown_carrying_it() {
        GroupPermissionRefusal adminGroup = new GroupPermissionRefusal.AdminGroup();

        GroupPermissionsRefusedException refused = catchThrowableOfType(
                GroupPermissionsRefusedException.class,
                () -> GroupPermissionsRefusedException.refuseIfPresent(Optional.of(adminGroup)));

        assertThat(refused.refusal()).isEqualTo(adminGroup);
    }

    @Test
    void no_refusal_lets_the_write_go_on() {
        assertThatCode(() -> GroupPermissionsRefusedException.refuseIfPresent(Optional.empty()))
                .doesNotThrowAnyException();
    }
}

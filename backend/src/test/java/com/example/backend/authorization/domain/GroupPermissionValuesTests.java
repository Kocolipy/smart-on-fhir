package com.example.backend.authorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Reading the Permissions Groups carry from storage: a stored value is a name, and only a
 * {@linkplain Permission#delegatable() delegatable} Permission's name is one a Group may carry.
 */
class GroupPermissionValuesTests {

    private static final UUID AUDITORS = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    private static final UUID HELPDESK = UUID.fromString("00000000-0000-4000-8000-0000000000b2");

    /** Each Group's values become its Permissions; a Group with no row is not in the answer. */
    @Test
    void storedValuesBecomeEachGroupsPermissions() {
        Map<UUID, Set<Permission>> carried = GroupPermissionValues.validated(List.of(
                new StoredGroupPermission(AUDITORS, "audit:read"),
                new StoredGroupPermission(HELPDESK, "user:read"),
                new StoredGroupPermission(HELPDESK, "account:unlock")));

        assertThat(carried).isEqualTo(Map.of(
                AUDITORS, Set.of(Permission.AUDIT_READ),
                HELPDESK, Set.of(Permission.USER_READ, Permission.ACCOUNT_UNLOCK)));
    }

    /** No rows, no Group carries anything. */
    @Test
    void noStoredValuesCarryNothing() {
        assertThat(GroupPermissionValues.validated(List.of())).isEmpty();
    }

    /**
     * An unknown name and a known one no Group may carry are both refused, and every problem is
     * named in one message, with its Group, so one fix covers them all.
     */
    @Test
    void anUnknownOrNonDelegatableValueIsRefusedNamingEveryProblem() {
        assertThatThrownBy(() -> GroupPermissionValues.validated(List.of(
                        new StoredGroupPermission(AUDITORS, "audit:read"),
                        new StoredGroupPermission(AUDITORS, "connector:write"),
                        new StoredGroupPermission(HELPDESK, "user:write"),
                        new StoredGroupPermission(HELPDESK, "patient:read"))))
                .isInstanceOf(InvalidGroupPermissionsException.class)
                .hasMessage("Invalid Group Permissions: Group " + AUDITORS
                        + " carries unknown Permission 'connector:write'; Group " + HELPDESK
                        + " carries Permission 'user:write', which no Group may carry; Group "
                        + HELPDESK + " carries Permission 'patient:read', which no Group may carry."
                        + " Delete the rows, or restore the Permission in code");
    }

    /** Names are case-sensitive, as the vocabulary is. */
    @Test
    void aValueInAnotherCaseIsUnknown() {
        assertThatThrownBy(() -> GroupPermissionValues.validated(List.of(
                        new StoredGroupPermission(AUDITORS, "Audit:Read"))))
                .isInstanceOf(InvalidGroupPermissionsException.class)
                .hasMessageContaining("unknown Permission 'Audit:Read'");
    }
}

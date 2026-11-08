package com.example.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.domain.LockCause;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Every closed set the API exposes is the same set in code and in {@code docs/openapi.yaml}, in
 * both directions.
 *
 * <p>The fixture suites see an enum value only when a response carries it, so a member added to a
 * Java enum that no fixture emits would pass them while the document — and the SPA, which mirrors
 * the document — stays without it. This sweep compares each Java vocabulary with every enum the
 * document gives it, member by member, whatever any response says. The SPA's copy is then held to
 * the same enums by the root's SPA–API contract check ({@code /scripts/spa-contract/README.md}).
 *
 * <p>The Permissions split as the domain splits them (ADR 0015): a session holds every Permission
 * that is not token-only — the delegatable ones and the baseline {@code patient:read} — a Group
 * carries only the delegatable ones, and a connector token only the token-grantable ones, whose
 * write halves no session holds. No Permission escapes the comparison: one no session holds is
 * {@linkplain Permission#tokenOnly() token-only}, so token-grantable by that definition, and the
 * token enums compare it.
 *
 * <p>An enum the document refers to by {@code $ref} — the audit trail's {@code operation} query
 * parameter, the Group Permissions' request and listing — is the schema compared here, so it is
 * not listed again.
 */
class VocabularyContractTests {

    private static final OpenApiContract CONTRACT = OpenApiContract.load();

    private static final String SESSION_PERMISSIONS =
            "#/components/schemas/UserResponse/properties/permissions/items";

    private static final String DELEGATABLE_PERMISSIONS =
            "#/components/schemas/DelegatablePermission";

    private static final String TOKEN_PERMISSIONS_REQUESTED =
            "#/components/schemas/ConnectorTokenPermissions/items";

    private static final String TOKEN_PERMISSIONS_LISTED =
            "#/components/schemas/ConnectorTokenSummary/properties/permissions/items";

    private static final String AUDIT_OPERATIONS = "#/components/schemas/AuditOperation";

    private static final String LOCK_CAUSES =
            "#/components/schemas/AccountSummary/properties/lockCause";

    private static final Predicate<Permission> HELD_BY_A_SESSION = permission -> !permission.tokenOnly();

    @Test
    void the_permissions_a_session_can_hold_are_the_documented_session_permissions() {
        assertThat(CONTRACT.enumDisagreements(SESSION_PERMISSIONS, permissions(HELD_BY_A_SESSION)))
                .isEmpty();
    }

    @Test
    void the_delegatable_permissions_are_the_documented_delegatable_permissions() {
        assertThat(CONTRACT.enumDisagreements(DELEGATABLE_PERMISSIONS,
                permissions(Permission::delegatable))).isEmpty();
    }

    @Test
    void the_token_grantable_permissions_are_the_permissions_a_token_is_requested_with() {
        assertThat(CONTRACT.enumDisagreements(TOKEN_PERMISSIONS_REQUESTED,
                permissions(Permission::tokenGrantable))).isEmpty();
    }

    @Test
    void the_token_grantable_permissions_are_the_permissions_a_listed_token_carries() {
        assertThat(CONTRACT.enumDisagreements(TOKEN_PERMISSIONS_LISTED,
                permissions(Permission::tokenGrantable))).isEmpty();
    }

    @Test
    void every_audit_operation_is_a_documented_audit_operation_and_no_other_is() {
        assertThat(CONTRACT.enumDisagreements(AUDIT_OPERATIONS, names(AuditOperation.values())))
                .isEmpty();
    }

    @Test
    void every_lock_cause_is_a_documented_lock_cause_and_no_other_is() {
        assertThat(CONTRACT.enumDisagreements(LOCK_CAUSES, names(LockCause.values()))).isEmpty();
    }

    /** The Permissions the filter admits, as the wire spells them. */
    private static List<String> permissions(Predicate<Permission> which) {
        return Arrays.stream(Permission.values()).filter(which).map(Permission::value).toList();
    }

    /** A vocabulary's members as the wire spells them: by {@code name()}. */
    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}

package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.application.AuditLogFields;
import com.example.backend.auth.application.DormancyLogFields;
import com.example.backend.auth.application.EpicLoginLogFields;
import com.example.backend.authorization.domain.PermissionLogFields;
import com.example.backend.scim.application.ScimLogFields;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The field names each feature adds to its records, now declared beside the feature rather than
 * in {@link LogEvent}, keep the spellings ADR 0003 and the log searches written against it use.
 * The call sites' own tests assert through these constants, so only a literal here can catch a
 * spelling that changed.
 */
class FeatureLogFieldsTests {

    @Test
    void theDormancyJobsFieldsKeepTheirSpellings() {
        assertThat(List.of(DormancyLogFields.DORMANCY_LOCKOUT_WINDOW,
                        DormancyLogFields.DORMANCY_MEMBERSHIP_REVOCATION_WINDOW,
                        DormancyLogFields.DORMANCY_LOCKED_COUNT,
                        DormancyLogFields.DORMANCY_MEMBERSHIPS_REVOKED_COUNT))
                .containsExactly("dormancy.lockout.window", "dormancy.membership_revocation.window",
                        "dormancy.locked_count", "dormancy.memberships_revoked_count");
    }

    @Test
    void theAuditTrailsFieldsKeepTheirSpellings() {
        assertThat(List.of(AuditLogFields.AUDIT_OPERATION, AuditLogFields.RETENTION_PERIOD))
                .containsExactly("audit.operation", "audit.retention.period");
    }

    @Test
    void theAuthorizationFieldsKeepTheirSpellings() {
        assertThat(List.of(PermissionLogFields.GROUP_PERMISSIONS,
                        PermissionLogFields.PERMISSIONS_LOST))
                .containsExactly("app.authorization.group_permissions",
                        "app.authorization.permissions_lost");
    }

    @Test
    void epicLoginsFieldsKeepTheirSpellings() {
        assertThat(List.of(EpicLoginLogFields.EPIC_INPUT_FIELD,
                        EpicLoginLogFields.EPIC_INPUT_RULE, EpicLoginLogFields.MFA_FACTOR))
                .containsExactly("app.epic.input.field", "app.epic.input.rule",
                        "app.login.mfa_factor");
    }

    @Test
    void scimsFieldKeepsItsSpelling() {
        assertThat(ScimLogFields.SCIM_RESOURCE_TYPE).isEqualTo("scim.resource.type");
    }
}

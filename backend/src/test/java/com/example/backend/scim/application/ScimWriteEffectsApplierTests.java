package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The apply step's own refusal. What it carries out for each writer is observed through the use
 * cases that call it — {@code ScimUserServiceTests}, {@code ScimGroupServiceTests} and
 * {@code DormancyServiceTests} — since every one of them goes through it.
 */
class ScimWriteEffectsApplierTests {

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final List<UUID> revoked = new ArrayList<>();

    private final ScimWriteEffectsApplier applier = new ScimWriteEffectsApplier(
            users,
            new InMemoryScimGroupRepository(users),
            (connectorId, userId, causes) -> revoked.add(userId),
            audit,
            TestGroupPermissions.superuserOnly(),
            () -> {
            });

    /**
     * A connector's write names its connector: the dormancy job is the one writer with no actor,
     * and it is its own writer rather than a connector with none.
     */
    @Test
    void a_connector_write_names_its_connector() {
        assertThatThrownBy(() -> ScimWriteEffectsApplier.Writer.connector(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("connectorId");
    }

    /**
     * The dormancy job only takes memberships away, so effects that grant one are not the job's to
     * carry out: refused before anything is recorded or revoked, rather than audited as a
     * revocation.
     */
    @Test
    void the_dormancy_job_carries_out_no_membership_grant() {
        UUID group = UUID.randomUUID();
        UUID ada = UUID.randomUUID();
        var granting = ScimWriteEffects.ofGroupWrite(group,
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(), null),
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(ada), null),
                true);

        assertThatThrownBy(() -> applier.apply(
                        ScimWriteEffectsApplier.Writer.DORMANCY_JOB, granting, ScimIdentities.NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(audit.recorded()).isEmpty();
        assertThat(revoked).isEmpty();
    }

    /**
     * A deleted Group's every member loses what it conferred, and the audit of it asks the Groups
     * what each member held before and after in one question: the Groups are read as often for a
     * Group of three members as for a Group of one, where each member once cost eight reads.
     */
    @Test
    void a_group_deletions_power_audit_reads_the_groups_no_more_often_for_more_members() {
        assertThat(groupReadsToAuditDeletingAGroupOf(3))
                .isEqualTo(groupReadsToAuditDeletingAGroupOf(1));
    }

    /**
     * How many times the power audit of deleting a Group of {@code members} Users, which carried a
     * Permission, read the Groups' Permissions or the Admin group.
     */
    private static int groupReadsToAuditDeletingAGroupOf(int members) {
        InMemoryScimUserRepository directory = new InMemoryScimUserRepository();
        Set<UUID> memberIds = new LinkedHashSet<>();
        for (int member = 0; member < members; member++) {
            memberIds.add(directory.create(ScimIdentities.user("member-" + member)).id());
        }
        CountingGroupPermissions permissions =
                new CountingGroupPermissions(TestGroupPermissions.superuserOnly());
        UUID deleted = UUID.randomUUID();
        new ScimWriteEffectsApplier(
                directory,
                new InMemoryScimGroupRepository(directory),
                (connectorId, userId, causes) -> { },
                new RecordingAuditTrail(),
                permissions,
                () -> { })
                .afterDeleting(deleted, Set.of(Permission.AUDIT_READ))
                .apply(ScimWriteEffectsApplier.Writer.connector(UUID.randomUUID()),
                        ScimWriteEffects.ofGroupDeletion(deleted, memberIds, true),
                        ScimIdentities.NOW);
        return permissions.reads;
    }

    /** Group Permissions that count every read of the Groups they answer from. */
    private static final class CountingGroupPermissions implements GroupPermissions {

        private final GroupPermissions groups;

        private int reads;

        CountingGroupPermissions(GroupPermissions groups) {
            this.groups = groups;
        }

        @Override
        public Map<UUID, Set<Permission>> carriedBy(Collection<UUID> groupIds) {
            reads++;
            return groups.carriedBy(groupIds);
        }

        @Override
        public Optional<UUID> adminGroup() {
            reads++;
            return groups.adminGroup();
        }
    }
}

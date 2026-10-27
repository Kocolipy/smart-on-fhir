package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.authorization.TestGroupPermissions;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.EcsLogCapture;
import com.example.backend.scim.InMemoryGroupPermissions;
import com.example.backend.scim.InMemoryScimGroupRepository;
import com.example.backend.scim.InMemoryScimUserRepository;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserSessions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.JsonNode;

/**
 * The apply step, for each of its two writers: a connector's write and the dormancy job's. A
 * removal by either is recorded and ends Sessions alike but for the actor it names and the event
 * it is recorded as, so each such case runs once per {@link Writers writer}; what only one writer
 * does — the job's refusal to grant, a connector's counted Superuser grant — is a case of its own.
 * Every use case that writes goes through this step, so {@code ScimUserServiceTests},
 * {@code ScimGroupServiceTests} and {@code ScimMembershipRevocationServiceTests} observe it too.
 */
class ScimWriteEffectsApplierTests {

    private static final UUID CONNECTOR = UUID.fromString("00000000-0000-4000-8000-0000000c0001");

    /**
     * The two writers, each with the actor it names and the event a removal is recorded as. Each
     * case builds its writer afresh from {@link #writer}, so the writer is the code under test as
     * it stands when the case runs, not one built once with the enum.
     */
    enum Writers {
        CONNECTOR_WRITE(CONNECTOR, AuditOperation.PERMISSION_REVOKE,
                () -> ScimWriteEffectsApplier.Writer.connector(CONNECTOR)),
        DORMANCY_JOB(null, AuditOperation.DORMANCY_MEMBERSHIP_REVOCATION,
                () -> ScimWriteEffectsApplier.Writer.DORMANCY_JOB);

        private final UUID actor;

        private final AuditOperation removal;

        private final Supplier<ScimWriteEffectsApplier.Writer> writer;

        Writers(UUID actor, AuditOperation removal,
                Supplier<ScimWriteEffectsApplier.Writer> writer) {
            this.actor = actor;
            this.removal = removal;
            this.writer = writer;
        }

        ScimWriteEffectsApplier.Writer writer() {
            return writer.get();
        }
    }

    /** One Session revocation requested: the actor it names, the User, and why. */
    private record Revocation(UUID actorId, UUID userId, Set<ScimUserSessions.Cause> causes) {
    }

    private final InMemoryScimUserRepository users = new InMemoryScimUserRepository();

    private final InMemoryScimGroupRepository groups = new InMemoryScimGroupRepository(users);

    private final InMemoryGroupPermissions permissions = TestGroupPermissions.superuserOnly();

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final List<Revocation> revoked = new ArrayList<>();

    private int superuserGrants;

    private final ScimWriteEffectsApplier applier = applierOver(users, groups);

    private final ScimUser ada = users.create(ScimIdentities.user("ada"));

    /**
     * Ada's removal from a Group conferring {@code user:read}: recorded as the writer records a
     * removal, naming its actor — the connector, or nobody for the job — with what Ada held before
     * and after.
     */
    @ParameterizedTest
    @EnumSource(Writers.class)
    void a_removal_is_recorded_as_the_writers_event_naming_its_actor(Writers writer) {
        UUID helpdesk = conferringUserRead();

        applier.apply(writer.writer(), removalOfAdaFrom(helpdesk), ScimIdentities.NOW);

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                writer.removal, writer.actor, ada.id(), helpdesk + ":user:read>:false"));
    }

    /** The removal ends Ada's Sessions once the write commits, naming the writer's actor. */
    @ParameterizedTest
    @EnumSource(Writers.class)
    void a_removal_ends_the_users_sessions_naming_the_writers_actor(Writers writer) {
        UUID helpdesk = conferringUserRead();

        applier.apply(writer.writer(), removalOfAdaFrom(helpdesk), ScimIdentities.NOW);

        assertThat(revoked).containsExactly(new Revocation(
                writer.actor, ada.id(), Set.of(ScimUserSessions.Cause.MEMBERSHIP_REVOKED)));
    }

    /**
     * Only a Superuser grant is counted: a removal from the Admin group, by either writer, takes
     * the Superuser away and counts nothing.
     */
    @ParameterizedTest
    @EnumSource(Writers.class)
    void a_removal_from_the_admin_group_counts_no_superuser_grant(Writers writer) {
        applier.apply(writer.writer(), removalOfAdaFrom(TestGroupPermissions.SUPERUSER_GROUP_ID),
                ScimIdentities.NOW);

        assertThat(superuserGrants).isZero();
    }

    /**
     * A write that moved only the calling connector's alias stored no row the version could
     * advance with, so the apply step advances it in the Group store — whichever writer is named.
     * The applier is given a User store that knows nothing of the Group, so only the Group store
     * can advance it.
     */
    @ParameterizedTest
    @EnumSource(Writers.class)
    void an_alias_only_write_advances_the_written_resources_version(Writers writer) {
        ScimGroup helpdesk = groups.create(ScimIdentities.group("Helpdesk"));
        var aliasOnly = ScimWriteEffects.ofGroupWrite(helpdesk.id(),
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(), "old-alias"),
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(), "new-alias"),
                false);

        applierOver(new InMemoryScimUserRepository(), groups)
                .apply(writer.writer(), aliasOnly, ScimIdentities.NOW);

        assertThat(groups.findById(helpdesk.id()).orElseThrow().version())
                .isEqualTo(helpdesk.version() + 1);
    }

    /**
     * The same for a User: its version advances in the User store, whichever writer is named. The
     * applier is given a Group store over another directory, so only the User store can advance it.
     */
    @ParameterizedTest
    @EnumSource(Writers.class)
    void an_alias_only_user_write_advances_the_users_version(Writers writer) {
        var aliasOnly = ScimWriteEffects.ofUserWrite(ada.id(),
                new ScimWriteEffects.UserState(ada.profile(), "old-alias", "hash"),
                new ScimWriteEffects.UserState(ada.profile(), "new-alias", "hash"));

        applierOver(users, new InMemoryScimGroupRepository(new InMemoryScimUserRepository()))
                .apply(writer.writer(), aliasOnly, ScimIdentities.NOW);

        assertThat(users.findById(ada.id()).orElseThrow().version()).isEqualTo(ada.version() + 1);
    }

    /**
     * A write that was not the alias alone leaves the version to the store that made it: the
     * apply step advances nothing, so Ada's removal leaves her version where the removal put it.
     */
    @ParameterizedTest
    @EnumSource(Writers.class)
    void a_write_that_was_not_the_alias_alone_advances_no_version_here(Writers writer) {
        UUID helpdesk = conferringUserRead();

        applier.apply(writer.writer(), removalOfAdaFrom(helpdesk), ScimIdentities.NOW);

        assertThat(users.findById(ada.id()).orElseThrow().version()).isEqualTo(ada.version());
    }

    /** Each writer's removal of Ada is one {@code INFO} record naming her. */
    @ParameterizedTest
    @EnumSource(Writers.class)
    void a_removal_is_logged_once_naming_the_user(Writers writer) {
        UUID helpdesk = conferringUserRead();

        try (EcsLogCapture ecs = EcsLogCapture.attach(new StandardEnvironment())) {
            applier.apply(writer.writer(), removalOfAdaFrom(helpdesk), ScimIdentities.NOW);

            assertThat(applierRecords(ecs))
                    .extracting(record -> record.at("/user/target/id").asText())
                    .containsExactly(ada.id().toString());
        }
    }

    /**
     * The dormancy job's record names the Permissions Ada lost and only those: what the Group she
     * left conferred, less what a Group she is still in confers, comma-joined in value order.
     */
    @Test
    void the_dormancy_jobs_record_names_only_the_permissions_the_user_lost() {
        UUID helpdesk = groups.create(ScimIdentities.group("Helpdesk")).id();
        permissions.carrying(helpdesk, Permission.USER_READ, Permission.GROUP_READ,
                Permission.AUDIT_READ);
        UUID readers = groups.create(ScimIdentities.group("Readers", ada)).id();
        permissions.carrying(readers, Permission.GROUP_READ);

        try (EcsLogCapture ecs = EcsLogCapture.attach(new StandardEnvironment())) {
            applier.apply(ScimWriteEffectsApplier.Writer.DORMANCY_JOB, removalOfAdaFrom(helpdesk),
                    ScimIdentities.NOW);

            assertThat(applierRecords(ecs))
                    .extracting(record -> record.at("/app/authorization/permissions_lost")
                            .asText())
                    .containsExactly("audit:read,user:read");
        }
    }

    /**
     * A connector's grant is a {@code PERMISSION_GRANT} naming the connector, Ada and the Group,
     * with what she held before and after.
     */
    @Test
    void a_connectors_grant_is_recorded_naming_the_connector_the_user_and_the_group() {
        UUID helpdesk = conferringUserReadHoldingAda();

        applier.apply(ScimWriteEffectsApplier.Writer.connector(CONNECTOR), grantOfAdaIn(helpdesk),
                ScimIdentities.NOW);

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.PERMISSION_GRANT, CONNECTOR, ada.id(),
                helpdesk + ":>user:read:false"));
    }

    /** A connector's grant is logged naming the Group it was made in. */
    @Test
    void a_connectors_grant_is_logged_naming_the_group() {
        UUID helpdesk = conferringUserReadHoldingAda();

        try (EcsLogCapture ecs = EcsLogCapture.attach(new StandardEnvironment())) {
            applier.apply(ScimWriteEffectsApplier.Writer.connector(CONNECTOR),
                    grantOfAdaIn(helpdesk), ScimIdentities.NOW);

            assertThat(applierRecords(ecs))
                    .extracting(record -> record.at("/group/id").asText())
                    .containsExactly(helpdesk.toString());
        }
    }

    /** A grant of a Group that confers Permissions but not the Superuser counts nothing. */
    @Test
    void a_connectors_grant_of_another_group_counts_no_superuser_grant() {
        UUID helpdesk = conferringUserReadHoldingAda();

        applier.apply(ScimWriteEffectsApplier.Writer.connector(CONNECTOR), grantOfAdaIn(helpdesk),
                ScimIdentities.NOW);

        assertThat(superuserGrants).isZero();
    }

    /**
     * A deleted Group's Permissions went with it, so what Ada held before the deletion is read as
     * though the Group still carried what the write read before deleting it.
     */
    @Test
    void a_group_deletion_records_what_the_deleted_group_conferred_before_it() {
        UUID deleted = UUID.randomUUID();

        applier.afterDeleting(deleted, Set.of(Permission.AUDIT_READ))
                .apply(ScimWriteEffectsApplier.Writer.connector(CONNECTOR),
                        ScimWriteEffects.ofGroupDeletion(deleted, Set.of(ada.id()), true),
                        ScimIdentities.NOW);

        assertThat(audit.recorded()).containsExactly(new RecordingAuditTrail.Recorded(
                AuditOperation.PERMISSION_REVOKE, CONNECTOR, ada.id(),
                deleted + ":audit:read>:false"));
    }

    /**
     * A connector's write that added Ada to the Admin group made her the Superuser, and is counted
     * once.
     */
    @Test
    void a_connectors_grant_of_the_admin_group_counts_one_superuser_grant() {
        UUID admins = groups.createReserved(
                        ScimGroup.created(TestGroupPermissions.SUPERUSER_GROUP_ID, "Admins",
                                List.of(ScimGroupMember.reference(ada.id())), ScimIdentities.NOW),
                        ReservedResourceName.ADMIN_GROUP)
                .id();
        var granting = ScimWriteEffects.ofGroupWrite(admins,
                new ScimWriteEffects.GroupState("Admins", Set.of(), null),
                new ScimWriteEffects.GroupState("Admins", Set.of(ada.id()), null),
                true);

        applier.apply(ScimWriteEffectsApplier.Writer.connector(CONNECTOR), granting,
                ScimIdentities.NOW);

        assertThat(superuserGrants).isEqualTo(1);
    }

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
        var granting = ScimWriteEffects.ofGroupWrite(group,
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(), null),
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(ada.id()), null),
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

    /** An applier over these stores, recording into this case's trail and revocations. */
    private ScimWriteEffectsApplier applierOver(
            InMemoryScimUserRepository users, InMemoryScimGroupRepository groups) {
        return new ScimWriteEffectsApplier(
                users,
                groups,
                (connectorId, userId, causes) ->
                        revoked.add(new Revocation(connectorId, userId, causes)),
                audit,
                permissions,
                () -> superuserGrants++);
    }

    /** A Group conferring {@code user:read}, which Ada is no longer in. */
    private UUID conferringUserRead() {
        UUID id = groups.create(ScimIdentities.group("Helpdesk")).id();
        permissions.carrying(id, Permission.USER_READ);
        return id;
    }

    /** A Group conferring {@code user:read}, which a write has just added Ada to. */
    private UUID conferringUserReadHoldingAda() {
        UUID id = groups.create(ScimIdentities.group("Helpdesk", ada)).id();
        permissions.carrying(id, Permission.USER_READ);
        return id;
    }

    /** A connector's write that added Ada to {@code groupId}, a Group conferring Permissions. */
    private ScimWriteEffects<?> grantOfAdaIn(UUID groupId) {
        return ScimWriteEffects.ofGroupWrite(groupId,
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(), null),
                new ScimWriteEffects.GroupState("Helpdesk", Set.of(ada.id()), null),
                true);
    }

    /** The records the apply step logged on this thread. */
    private static List<JsonNode> applierRecords(EcsLogCapture ecs) {
        String thread = Thread.currentThread().getName();
        return ecs.records().stream()
                .filter(record -> record.at("/log/logger").asText()
                        .equals(ScimWriteEffectsApplier.class.getName()))
                .filter(record -> thread.equals(record.at("/process/thread/name").asText()))
                .toList();
    }

    /** Ada's removal from {@code groupId}, the one membership change the effects report. */
    private ScimWriteEffects<?> removalOfAdaFrom(UUID groupId) {
        return ScimWriteEffects.ofMembershipRevocation(ada.id(), List.of(groupId));
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

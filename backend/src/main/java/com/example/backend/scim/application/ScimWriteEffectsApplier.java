package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.authorization.domain.Role;
import com.example.backend.authorization.domain.RoleLogFields;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimUserSessions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries out what a SCIM write implies, once {@link ScimWriteEffects} has decided it — the one
 * apply step every write that changes the directory goes through: the User write, the Group
 * write, their deletions, and the dormancy job's Role revocation.
 *
 * <p>In order, over the ports:
 *
 * <ol>
 *   <li><strong>Version advancement.</strong> A write whose only change is the calling
 *       connector's {@code externalId} advances the written resource's version on its own: the
 *       alias is not a column, so the use case stores no row the version could advance with. Every
 *       other advance happens in the store itself, which asks
 *       {@link com.example.backend.scim.domain.ResourceVersionAdvance} who moved from the state
 *       the adapter read under the row lock — the label a User replacement overwrote, the
 *       membership a Group replacement or a removal changed, the Groups a deletion took a member
 *       from. That is deliberate: the who-moved rule stays in its one place, applied by the
 *       statement that stores the change, so no caller of a store — this step, the seed, a later
 *       use case — can store a change and forget the advance.
 *   <li><strong>Role changes</strong>, audited and logged at {@code INFO}, each the way its
 *       {@link Writer} records them.
 *   <li><strong>Session revocations</strong>, requested of {@link ScimUserSessions}, which ends
 *       them only once the write commits (ADR 0002), so a write that rolls back ends none.
 * </ol>
 *
 * <p>Decides nothing: which resources, Roles and Users these are is the effects' decision, and
 * how each is recorded follows from the {@link Writer}.
 */
final class ScimWriteEffectsApplier {

    private static final Logger log = LoggerFactory.getLogger(ScimWriteEffectsApplier.class);

    private final ScimUserRepository users;
    private final ScimGroupRepository groups;
    private final ScimUserSessions sessions;
    private final AuditTrail audit;

    ScimWriteEffectsApplier(
            ScimUserRepository users,
            ScimGroupRepository groups,
            ScimUserSessions sessions,
            AuditTrail audit) {
        this.users = users;
        this.groups = groups;
        this.sessions = sessions;
        this.audit = audit;
    }

    /**
     * Who made the write. Each writer records a change of power its own way, and names its own
     * actor on the Session revocations the write causes.
     */
    sealed interface Writer {

        /** The dormancy job (ADR 0007): run by nobody recorded as an actor. */
        Writer DORMANCY_JOB = new DormancyJob();

        /** A connector's write, recorded as that connector's. */
        static Writer connector(UUID connectorId) {
            return new Connector(connectorId);
        }

        /** Audits and logs the Role changes the write made. */
        void recordRoleChanges(AuditTrail audit, List<ScimWriteEffects.RoleChange> changes);

        /** Requests one User's Session revocation, naming this writer's actor. */
        void revokeAfterCommit(
                ScimUserSessions sessions, ScimWriteEffects.SessionRevocation revocation);
    }

    /**
     * A connector's write: each change of power is its own {@code ROLE_GRANT} or
     * {@code ROLE_REVOKE} event and log record naming the connector, fail-open (ADR 0004), and the
     * connector is the actor of every revocation.
     */
    record Connector(UUID connectorId) implements Writer {

        Connector {
            Objects.requireNonNull(connectorId, "connectorId");
        }

        @Override
        public void recordRoleChanges(
                AuditTrail audit, List<ScimWriteEffects.RoleChange> changes) {
            for (ScimWriteEffects.RoleChange change : changes) {
                switch (change.kind()) {
                    case GRANTED -> {
                        audit.append(new Audited.RoleGranted(
                                connectorId, change.userId(), change.groupId(), change.role()));
                        logRoleChange(Operation.ROLE_GRANT, change);
                    }
                    case REVOKED -> {
                        audit.append(new Audited.RoleRevoked(
                                connectorId, change.userId(), change.groupId(), change.role()));
                        logRoleChange(Operation.ROLE_REVOKE, change);
                    }
                }
            }
        }

        @Override
        public void revokeAfterCommit(
                ScimUserSessions sessions, ScimWriteEffects.SessionRevocation revocation) {
            sessions.revokeAfterCommit(connectorId, revocation.userId(), revocation.causes());
        }
    }

    /**
     * The dormancy job's write: one actorless {@code DORMANCY_ROLE_REVOCATION} event and one
     * {@code INFO} record per User, naming every Role it lost, inside the job's transaction, so an
     * append that cannot commit rolls the run back. The job only revokes, so a grant is refused
     * before anything is recorded. Its Session revocations name no actor.
     */
    record DormancyJob() implements Writer {

        @Override
        public void recordRoleChanges(
                AuditTrail audit, List<ScimWriteEffects.RoleChange> changes) {
            Map<UUID, List<Role>> lostByUser = new LinkedHashMap<>();
            for (ScimWriteEffects.RoleChange change : changes) {
                if (change.kind() != ScimWriteEffects.RoleChange.Kind.REVOKED) {
                    throw new IllegalArgumentException("the dormancy job grants no Role");
                }
                lostByUser.computeIfAbsent(change.userId(), user -> new ArrayList<>())
                        .add(change.role());
            }
            lostByUser.forEach((userId, lost) -> {
                audit.append(new Audited.DormancyRoleRevocation(userId, lost));
                logDormancyRoleRevocation(userId, lost);
            });
        }

        @Override
        public void revokeAfterCommit(
                ScimUserSessions sessions, ScimWriteEffects.SessionRevocation revocation) {
            sessions.revokeAfterCommit(null, revocation.userId(), revocation.causes());
        }
    }

    /**
     * Carries out the effects of a write to the resource they name.
     *
     * @param now the {@code lastModified} a version advanced here records
     */
    void apply(Writer writer, ScimWriteEffects<?> effects, Instant now) {
        if (effects.stored().aliasOnly()) {
            UUID id = effects.written().id();
            switch (effects.written().type()) {
                case USER -> users.advanceVersion(id, now);
                case GROUP -> groups.advanceVersion(id, now);
            }
        }
        writer.recordRoleChanges(audit, effects.roleChanges());
        for (ScimWriteEffects.SessionRevocation revocation : effects.revocations()) {
            writer.revokeAfterCommit(sessions, revocation);
        }
    }

    /** The Roles lost sorted and distinct, whatever order the Groups were left in. */
    private static void logDormancyRoleRevocation(UUID userId, List<Role> lost) {
        LogEvent.success(log, Operation.DORMANCY_ROLE_REVOCATION, Category.PROCESS, Type.CHANGE)
                .addKeyValue(LogEvent.USER_TARGET_ID, userId.toString())
                .addKeyValue(RoleLogFields.ROLE_NAME, lost.stream().map(Role::name).distinct()
                        .sorted().collect(Collectors.joining(",")))
                .log();
    }

    /**
     * A connector's change of power in the application log, beside its audit event: {@code INFO},
     * classified {@code user-administration} / {@code change} / {@code success}, naming the User,
     * the Group by id and the Role — never a {@code userName} or the Group's {@code displayName}.
     */
    private static void logRoleChange(Operation operation, ScimWriteEffects.RoleChange change) {
        LogEvent.success(log, operation, Category.PROCESS, Type.CHANGE)
                .addKeyValue(LogEvent.USER_TARGET_ID, change.userId().toString())
                .addKeyValue(LogEvent.GROUP_ID, change.groupId().toString())
                .addKeyValue(RoleLogFields.ROLE_NAME, change.role().name())
                .log();
    }
}

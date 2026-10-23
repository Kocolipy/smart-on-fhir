package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditPermissionChange;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.authorization.domain.AuthorizationChangeCounts;
import com.example.backend.authorization.domain.ConferredPower;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.authorization.domain.PermissionLogFields;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scim.application.ScimWriteEffects.MembershipChange.Kind;
import com.example.backend.scim.domain.ScimGroupReference;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimUserSessions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries out what a SCIM write implies, once {@link ScimWriteEffects} has decided it — the one
 * apply step every write that changes the directory goes through: the User write, the Group
 * write, their deletions, and the dormancy job's membership revocation.
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
 *   <li><strong>Membership changes of power</strong>, audited and logged at {@code INFO}, each
 *       the way its {@link Writer} records them, with the User's effective Permissions before and
 *       after ({@link GroupPermissions}). Read from the User's memberships as the write left them,
 *       in the write's own transaction: after is what it now holds, and before is that with the
 *       write's changes to its memberships undone.
 *   <li><strong>Session revocations</strong>, requested of {@link ScimUserSessions}, which ends
 *       them only once the write commits (ADR 0002), so a write that rolls back ends none.
 * </ol>
 *
 * <p>Decides nothing: which resources, memberships and Users these are is the effects' decision,
 * and how each is recorded follows from the {@link Writer}.
 */
final class ScimWriteEffectsApplier {

    private static final Logger log = LoggerFactory.getLogger(ScimWriteEffectsApplier.class);

    private final ScimUserRepository users;
    private final ScimGroupRepository groups;
    private final ScimUserSessions sessions;
    private final Recording recording;

    ScimWriteEffectsApplier(
            ScimUserRepository users,
            ScimGroupRepository groups,
            ScimUserSessions sessions,
            AuditTrail audit,
            GroupPermissions permissions,
            AuthorizationChangeCounts counts) {
        this(users, groups, sessions, new PowerRecording(audit, counts, groups, permissions));
    }

    private ScimWriteEffectsApplier(
            ScimUserRepository users,
            ScimGroupRepository groups,
            ScimUserSessions sessions,
            Recording recording) {
        this.users = users;
        this.groups = groups;
        this.sessions = sessions;
        this.recording = recording;
    }

    /**
     * How a writer records a membership change of power: audited, logged and — for a grant of the
     * Superuser — counted, with the User's effective Permissions before and after.
     */
    sealed interface Recording {

        /**
         * A connector's write added Users to Groups of power, or removed them, one event per
         * change, in order.
         */
        void connectorChanges(UUID connectorId, List<ScimWriteEffects.MembershipChange> changes);

        /** The dormancy job removed the User from every one of these Groups. */
        void dormancyRevoked(UUID userId, List<UUID> groupIds);

        /** This recording, reading {@code groupId} as still carrying {@code carried}. */
        Recording remembering(UUID groupId, Set<Permission> carried);
    }

    /**
     * Records over the trail, the counter, and the User's effective Permissions before and after,
     * resolved from its memberships as they stand.
     *
     * <p>Every conferred power a write's recording needs — each User's before and after, and what each
     * Group it names confers — is asked of {@link GroupPermissions} in one question, so its cost is
     * one membership read per User beside a fixed number of reads of the Groups, however many
     * members a Group's deletion takes.
     */
    private record PowerRecording(
            AuditTrail audit,
            AuthorizationChangeCounts counts,
            ScimGroupRepository groups,
            GroupPermissions permissions) implements Recording {

        /**
         * Each change its own {@code PERMISSION_GRANT} or {@code PERMISSION_REVOKE} event and log
         * record, naming the connector. The User's Permissions before are read with every change
         * the write made to its memberships undone, not only this one, so a User's deletion that
         * took it out of two Groups of power records what it held before the deletion on both.
         */
        @Override
        public void connectorChanges(
                UUID connectorId, List<ScimWriteEffects.MembershipChange> changes) {
            Map<UUID, List<UUID>> gainedByUser = groupsByUser(changes, Kind.GRANTED);
            Map<UUID, List<UUID>> lostByUser = groupsByUser(changes, Kind.REVOKED);
            Map<Ask, Set<UUID>> asks = new LinkedHashMap<>();
            Set<UUID> userIds = changes.stream()
                    .map(ScimWriteEffects.MembershipChange::userId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (UUID userId : userIds) {
                askAboutUser(asks, userId,
                        gainedByUser.getOrDefault(userId, List.of()),
                        lostByUser.getOrDefault(userId, List.of()));
            }
            for (ScimWriteEffects.MembershipChange change : changes) {
                asks.putIfAbsent(new GroupAlone(change.groupId()), Set.of(change.groupId()));
            }
            Map<Ask, ConferredPower> answers = permissions.powersOf(asks);
            for (ScimWriteEffects.MembershipChange change : changes) {
                AuditPermissionChange power = powerChange(answers, change.userId());
                Set<Permission> conferred =
                        answers.get(new GroupAlone(change.groupId())).permissions();
                switch (change.kind()) {
                    case GRANTED -> {
                        audit.append(new Audited.PermissionGranted(
                                connectorId, change.userId(), change.groupId(), power));
                        logMembershipChange(Operation.PERMISSION_GRANT, change, conferred);
                        if (power.superuserChanged()) {
                            counts.superuserGranted();
                        }
                    }
                    case REVOKED -> {
                        audit.append(new Audited.PermissionRevoked(
                                connectorId, change.userId(), change.groupId(), power));
                        logMembershipChange(Operation.PERMISSION_REVOKE, change, conferred);
                    }
                }
            }
        }

        @Override
        public Recording remembering(UUID groupId, Set<Permission> carried) {
            return new PowerRecording(audit, counts, groups, permissions.remembering(groupId, carried));
        }

        @Override
        public void dormancyRevoked(UUID userId, List<UUID> groupIds) {
            Map<Ask, Set<UUID>> asks = new LinkedHashMap<>();
            askAboutUser(asks, userId, List.of(), groupIds);
            AuditPermissionChange power = powerChange(permissions.powersOf(asks), userId);
            audit.append(new Audited.DormancyMembershipRevocation(userId, groupIds, power));
            logDormancyMembershipRevocation(userId, power);
        }

        /** The Groups each User gained, or lost, in the write: {@code kind}'s changes by User. */
        private static Map<UUID, List<UUID>> groupsByUser(
                List<ScimWriteEffects.MembershipChange> changes, Kind kind) {
            Map<UUID, List<UUID>> byUser = new LinkedHashMap<>();
            for (ScimWriteEffects.MembershipChange change : changes) {
                if (change.kind() == kind) {
                    byUser.computeIfAbsent(change.userId(), user -> new ArrayList<>())
                            .add(change.groupId());
                }
            }
            return byUser;
        }

        /**
         * Adds the User's memberships before and after the write, which {@code gained} and
         * {@code lost} memberships changed: after is its memberships as the write left them, read
         * once, and before is the same with the change undone.
         */
        private void askAboutUser(Map<Ask, Set<UUID>> asks, UUID userId,
                Collection<UUID> gained, Collection<UUID> lost) {
            Set<UUID> after = groups.findGroupsOfUser(userId).stream()
                    .map(ScimGroupReference::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<UUID> before = new LinkedHashSet<>(after);
            before.removeAll(gained);
            before.addAll(lost);
            asks.put(new UserBefore(userId), before);
            asks.put(new UserAfter(userId), after);
        }

        /** The User's effective Permissions before and after, and whether the Superuser moved. */
        private static AuditPermissionChange powerChange(
                Map<Ask, ConferredPower> answers, UUID userId) {
            ConferredPower before = answers.get(new UserBefore(userId));
            ConferredPower after = answers.get(new UserAfter(userId));
            return new AuditPermissionChange(before.permissions(), after.permissions(),
                    before.superuser() != after.superuser());
        }
    }

    /**
     * One conferred power a recording asks about: a User's memberships before the write or after
     * it, or one Group's alone, for what that Group confers.
     */
    private sealed interface Ask {}

    /** The User's memberships as they stood before the write. */
    private record UserBefore(UUID userId) implements Ask {}

    /** The User's memberships as the write left them. */
    private record UserAfter(UUID userId) implements Ask {}

    /** Membership of this Group alone: what the Group itself confers. */
    private record GroupAlone(UUID groupId) implements Ask {}

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

        /** Audits, logs and counts the membership changes of power the write made. */
        void recordMembershipChanges(
                Recording recording, List<ScimWriteEffects.MembershipChange> changes);

        /** Requests one User's Session revocation, naming this writer's actor. */
        void revokeAfterCommit(
                ScimUserSessions sessions, ScimWriteEffects.SessionRevocation revocation);
    }

    /**
     * A connector's write: each change of power is its own {@code PERMISSION_GRANT} or
     * {@code PERMISSION_REVOKE} event and log record naming the connector, fail-closed (ADR 0004),
     * a grant of the Superuser is counted, and the connector is the actor of every revocation.
     */
    record Connector(UUID connectorId) implements Writer {

        Connector {
            Objects.requireNonNull(connectorId, "connectorId");
        }

        @Override
        public void recordMembershipChanges(
                Recording recording, List<ScimWriteEffects.MembershipChange> changes) {
            recording.connectorChanges(connectorId, changes);
        }

        @Override
        public void revokeAfterCommit(
                ScimUserSessions sessions, ScimWriteEffects.SessionRevocation revocation) {
            sessions.revokeAfterCommit(connectorId, revocation.userId(), revocation.causes());
        }
    }

    /**
     * The dormancy job's write: one actorless {@code DORMANCY_MEMBERSHIP_REVOCATION} event and one
     * {@code INFO} record per User, naming every Group it was removed from and its effective
     * Permissions before and after, inside the job's transaction, so an append that cannot commit
     * rolls the run back. The job only revokes, so a grant is refused before anything is
     * recorded. Its Session revocations name no actor.
     */
    record DormancyJob() implements Writer {

        @Override
        public void recordMembershipChanges(
                Recording recording, List<ScimWriteEffects.MembershipChange> changes) {
            Map<UUID, List<UUID>> removedByUser = new LinkedHashMap<>();
            for (ScimWriteEffects.MembershipChange change : changes) {
                if (change.kind() != ScimWriteEffects.MembershipChange.Kind.REVOKED) {
                    throw new IllegalArgumentException("the dormancy job grants no membership");
                }
                removedByUser.computeIfAbsent(change.userId(), user -> new ArrayList<>())
                        .add(change.groupId());
            }
            removedByUser.forEach(recording::dormancyRevoked);
        }

        @Override
        public void revokeAfterCommit(
                ScimUserSessions sessions, ScimWriteEffects.SessionRevocation revocation) {
            sessions.revokeAfterCommit(null, revocation.userId(), revocation.causes());
        }
    }

    /**
     * This apply step, for a write that has just deleted the Group {@code groupId}: the deletion
     * took the Group's Permissions with it, so what its former members held before it is read as
     * though the Group still carried {@code carried}, which the write read before deleting it.
     */
    ScimWriteEffectsApplier afterDeleting(UUID groupId, Set<Permission> carried) {
        return new ScimWriteEffectsApplier(
                users, groups, sessions, recording.remembering(groupId, carried));
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
        writer.recordMembershipChanges(recording, effects.membershipChanges());
        for (ScimWriteEffects.SessionRevocation revocation : effects.revocations()) {
            writer.revokeAfterCommit(sessions, revocation);
        }
    }

    /** The Permissions the dormant User lost, whatever order the Groups were left in. */
    private static void logDormancyMembershipRevocation(UUID userId, AuditPermissionChange power) {
        Set<Permission> lost = EnumSet.noneOf(Permission.class);
        lost.addAll(power.before());
        lost.removeAll(power.after());
        LogEvent.success(log, Operation.DORMANCY_MEMBERSHIP_REVOCATION, Category.PROCESS,
                        Type.CHANGE)
                .addKeyValue(LogEvent.USER_TARGET_ID, userId.toString())
                .addKeyValue(PermissionLogFields.PERMISSIONS_LOST, joined(lost))
                .log();
    }

    /**
     * A connector's change of power in the application log, beside its audit event: {@code INFO},
     * classified {@code user-administration} / {@code change} / {@code success}, naming the User,
     * the Group by id and the Permissions it confers — never a {@code userName} or the Group's
     * {@code displayName}.
     */
    private static void logMembershipChange(Operation operation,
            ScimWriteEffects.MembershipChange change, Set<Permission> conferred) {
        LogEvent.success(log, operation, Category.PROCESS, Type.CHANGE)
                .addKeyValue(LogEvent.USER_TARGET_ID, change.userId().toString())
                .addKeyValue(LogEvent.GROUP_ID, change.groupId().toString())
                .addKeyValue(PermissionLogFields.GROUP_PERMISSIONS, joined(conferred))
                .log();
    }

    private static String joined(Set<Permission> permissions) {
        return String.join(",", Permission.valuesOf(permissions));
    }
}

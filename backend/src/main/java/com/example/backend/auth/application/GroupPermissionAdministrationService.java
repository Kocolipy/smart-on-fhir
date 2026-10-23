package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.authorization.domain.GroupPermissionChangeCounts;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.observability.RetriedOnDeadlock;
import com.example.backend.scim.domain.GroupPermissionRefusal;
import com.example.backend.scim.domain.ScimGroup;
import com.example.backend.scim.domain.ScimGroupMember;
import com.example.backend.scim.domain.ScimGroupPermissionStore;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Superuser sets a Group's Permissions (ADR 0015): the one write of what a Group confers, and
 * never reachable over SCIM.
 *
 * <p>The request is the complete set, and it replaces the Group's; an empty set clears it, and a
 * name given twice is carried once. There is no precondition — the last writer wins — but the
 * Group's resource lock is taken first, so two replacements, or a replacement and a connector's
 * membership change, never interleave: the members whose sessions a shrink ends are the members
 * the Group has when the new set is stored.
 *
 * <p>Refused before anything is written by throwing a {@link GroupPermissionsChangeRefusedException}
 * naming the Superuser, the Group addressed and one generic reason — never a Permission. The
 * administrative edge answers, logs and audits it as a {@code GROUP_PERMISSIONS_CHANGE} failure
 * ({@code auth.controller.AdminRefusals}); nothing here records it. A body that is no set of names
 * is refused here, before the lock; every other refusal is the Group Permissions store's
 * ({@link ScimGroupPermissionStore#refusal}), asked once the lock is held and reported in its
 * order:
 *
 * <ul>
 *   <li>a body that is not a set of Permissions a Group may carry — missing, not an array of names,
 *       or naming something that is not a {@linkplain Permission#delegatable() delegatable}
 *       Permission ({@code INVALID_PERMISSIONS}) — a body that is no set of names here, before the
 *       lock, and a name that is no delegatable Permission by the store; a body that is not JSON
 *       at all never reaches this use case, and the edge refuses it the same way;
 *   <li>a Group that does not exist, or no longer does ({@code UNKNOWN_GROUP});
 *   <li>the Admin group, recognised by its reservation marker, whose direct members' power is the
 *       Superuser rather than a Permission ({@code ADMIN_GROUP}).
 * </ul>
 *
 * <p>A change that goes through is audited with the Group's Permissions before and after, logged at
 * {@code INFO} and counted. If the new set lacks a Permission the Group carried, every direct
 * member's sessions end once the change commits (ADR 0002), named as the Superuser's revocation; a
 * rolled-back change ends none. Growth ends nothing: the members hold what was added from their
 * next sign-in. There is no step-up.
 *
 * <p>In {@code auth} rather than the directory for the reason {@link IdentityAdministrationService}
 * is: ending sessions is this slice's, and the directory is reached through its ports.
 */
@Service
public class GroupPermissionAdministrationService {

    private static final Logger log =
            LoggerFactory.getLogger(GroupPermissionAdministrationService.class);

    private static final String ACTOR_REQUIRED =
            "a change of a Group's Permissions names its Superuser by stable id";

    private final ScimGroupRepository groups;
    private final ScimWriteLocks writeLocks;
    private final ScimGroupPermissionStore store;
    private final GroupPermissions permissions;
    private final SessionRevocationService sessions;
    private final AuditTrail audit;
    private final GroupPermissionChangeCounts counts;

    public GroupPermissionAdministrationService(
            ScimGroupRepository groups,
            ScimWriteLocks writeLocks,
            ScimGroupPermissionStore store,
            GroupPermissions permissions,
            SessionRevocationService sessions,
            AuditTrail audit,
            GroupPermissionChangeCounts counts) {
        this.groups = groups;
        this.writeLocks = writeLocks;
        this.store = store;
        this.permissions = permissions;
        this.sessions = sessions;
        this.audit = audit;
        this.counts = counts;
    }

    /**
     * Replaces the Group's Permissions with {@code requested}.
     *
     * @param requested the complete set by name, or {@code null} for a body that is missing or is
     *                  not an array of names
     * @param actorId   the Superuser's stable id, as its signed-in session holds it
     * @return the Group as it now stands
     */
    @Transactional
    @RetriedOnDeadlock(Operation.GROUP_PERMISSIONS_CHANGE)
    public GroupSummary setPermissions(UUID groupId, List<String> requested, UUID actorId) {
        Objects.requireNonNull(actorId, ACTOR_REQUIRED);
        Optional<Set<Permission>> named = named(requested);
        if (named.isEmpty()) {
            throw new GroupPermissionsChangeRefusedException(
                    GroupPermissionsChangeRefusedException.Reason.INVALID_PERMISSIONS, actorId, groupId);
        }
        Set<Permission> after = named.get();
        writeLocks.lock(List.of(groupId));
        Optional<GroupPermissionRefusal> refusal = store.refusal(groupId, after);
        if (refusal.isPresent()) {
            throw refusedFor(actorId, groupId, refusal.get());
        }
        // The store found the Group, and the lock keeps it until the transaction ends.
        ScimGroup group = groups.findById(groupId).orElseThrow();
        Set<Permission> before =
                permissions.carriedBy(List.of(groupId)).getOrDefault(groupId, Set.of());
        store.replace(groupId, after);
        audit.append(new Audited.GroupPermissionsChanged(actorId, groupId, before, after));
        counts.groupPermissionsChanged();
        succeeded(groupId);
        if (!after.containsAll(before)) {
            for (ScimGroupMember member : group.members()) {
                sessions.revokeAllAfterCommit(
                        member.userId(), SessionRevocationCause.GROUP_PERMISSIONS_REDUCED, actorId);
            }
        }
        return GroupSummary.of(group, after);
    }

    /**
     * The requested names as the Permissions they spell, duplicates collapsed, or empty when the
     * body is missing or a name is not a Permission at all. Whether a Group may carry them is the
     * store's to say ({@link ScimGroupPermissionStore#refusal}).
     */
    private static Optional<Set<Permission>> named(List<String> requested) {
        if (requested == null) {
            return Optional.empty();
        }
        Set<Permission> named = EnumSet.noneOf(Permission.class);
        for (String name : requested) {
            Optional<Permission> permission = Permission.fromValue(name);
            if (permission.isEmpty()) {
                return Optional.empty();
            }
            named.add(permission.get());
        }
        return Optional.of(named);
    }

    /**
     * The store's refusal as this change's own: a Permission no Group may carry is a body that is
     * no set of them.
     */
    private static GroupPermissionsChangeRefusedException refusedFor(
            UUID actorId, UUID groupId, GroupPermissionRefusal refusal) {
        GroupPermissionsChangeRefusedException.Reason reason = switch (refusal) {
            case GroupPermissionRefusal.NotDelegatable _ ->
                    GroupPermissionsChangeRefusedException.Reason.INVALID_PERMISSIONS;
            case GroupPermissionRefusal.UnknownGroup _ ->
                    GroupPermissionsChangeRefusedException.Reason.UNKNOWN_GROUP;
            case GroupPermissionRefusal.AdminGroup _ ->
                    GroupPermissionsChangeRefusedException.Reason.ADMIN_GROUP;
        };
        return new GroupPermissionsChangeRefusedException(reason, actorId, groupId);
    }

    /**
     * Records the change in the application log: the action and the Group by id. Never the Group's
     * {@code displayName}, and no Permission — what the Group carried before and carries now is
     * the audit trail's to name. The Superuser who acted is {@code user.id}, which the request's
     * logging context already carries.
     */
    private static void succeeded(UUID groupId) {
        LogEvent.success(log, Operation.GROUP_PERMISSIONS_CHANGE, Category.PROCESS, Type.ADMIN,
                        Type.GROUP, Type.CHANGE)
                .addKeyValue(LogEvent.GROUP_ID, groupId.toString())
                .log();
    }
}

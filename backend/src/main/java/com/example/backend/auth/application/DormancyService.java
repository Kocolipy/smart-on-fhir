package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.authorization.domain.Role;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scheduling.domain.ScheduledJobLock;
import com.example.backend.scim.domain.DormancyPolicy;
import com.example.backend.scim.domain.ScimGroupMembership;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dormancy job (ADR 0011): locks every User that has gone longer than the lockout window
 * without authenticating, and removes the mapped Group memberships of every User that has gone
 * longer than the role-revocation window.
 *
 * <h2>The two steps</h2>
 *
 * <ul>
 *   <li><strong>Role revocation</strong> — the User's direct membership of every Group the role
 *       mapping names is removed through {@link ScimGroupRepository#removeMember}, which advances
 *       the Group's and the User's versions exactly as a connector-driven removal does. Unmapped
 *       memberships confer nothing and are untouched. One actorless
 *       {@code DORMANCY_ROLE_REVOCATION} event names the User and the Roles lost, and its sessions
 *       end after the commit. A connector may re-add a membership; while the User stays dormant
 *       the next run removes it again.
 *   <li><strong>Lockout</strong> — an unlocked User is locked with {@code lock_cause=DORMANCY}
 *       through {@link ScimUserRepository#lockForDormancy}, which writes only where no lock
 *       stands, so a User already locked keeps its lock and its cause. An actorless
 *       {@code DORMANCY_LOCKOUT} event is recorded, and the User's sessions end after the commit
 *       (ADR 0002). {@code active} is never written: it is the directory's.
 * </ul>
 *
 * <p>Only an administrator's Unlock lifts a dormancy lock, and it restarts the dormancy window,
 * so a User brought back is not locked again by the next run before it can sign in.
 *
 * <h2>What stops a User being processed twice</h2>
 *
 * <ul>
 *   <li>The job's lock ({@link ScheduledJobLock}) — a second run skips while one is in
 *       progress, on any instance.
 *   <li>The resource locks and a second look. Role revocation locks every affected Group before
 *       any User — the order a connector's write to a Group takes — and then each User; the
 *       lockout step re-reads each candidate under its User lock. Every candidate is decided again
 *       on the locked read by the policy's {@link DormancyVerdict} — the job's only copy of the
 *       rule — so a User that logged in or was unlocked since the candidate query is
 *       left alone. Role revocation runs first so that no Group lock is ever requested while this
 *       transaction already holds a User lock.
 * </ul>
 *
 * <p>The Bootstrap Admin is never processed: both candidate queries exclude reserved Users, and
 * the exemption is checked again on the locked read.
 *
 * <p>One transaction per run. A failure on one User — an audit append that cannot commit, most of
 * all — rolls the whole run back and revokes nothing; the next run repeats it. That is the
 * fail-closed half of ADR 0004, and it keeps "locked but not recorded" impossible.
 *
 * <p>Logging: the run's start and end are {@code ScheduledJobMetrics}'s, the end carrying this
 * run's counts. Inside the run the only records are one per User changed, beside its audit
 * event: a lockout at {@code WARN} — it signals inactivity, not the attack an authentication
 * lockout's {@code ERROR} does — and a role revocation at {@code INFO}. Neither names a
 * {@code userName}.
 */
@Service
public class DormancyService {

    /** The operation the job's own records — its schedule, start and end — are classified as. */
    public static final Operation OPERATION = Operation.DORMANCY;

    private static final Logger log = LoggerFactory.getLogger(DormancyService.class);

    private final ScimUserRepository users;
    private final ScimGroupRepository groups;
    private final SessionRevocationService sessions;
    private final ScheduledJobLock lock;
    private final DormancyPolicy policy;
    private final RoleMapping roleMapping;
    private final AuditTrail audit;
    private final Clock clock;

    public DormancyService(
            ScimUserRepository users,
            ScimGroupRepository groups,
            SessionRevocationService sessions,
            ScheduledJobLock lock,
            DormancyPolicy policy,
            RoleMapping roleMapping,
            AuditTrail audit,
            Clock clock) {
        this.users = users;
        this.groups = groups;
        this.sessions = sessions;
        this.lock = lock;
        this.policy = policy;
        this.roleMapping = roleMapping;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * One run: revokes the Roles of every User past the role-revocation window, then locks every
     * unlocked User past the lockout window — or skips when another run holds the job's lock.
     */
    @Transactional
    public DormancyRun run() {
        if (!lock.tryAcquire(ScheduledJob.DORMANCY)) {
            return DormancyRun.skippedRun();
        }
        Instant now = clock.instant();
        List<UUID> rolesRevoked = revokeRoles(now);
        List<UUID> locked = lockDormantUsers(now);
        return new DormancyRun(false, locked, rolesRevoked);
    }

    private List<UUID> revokeRoles(Instant now) {
        List<ScimGroupMembership> candidates = groups.findDormantMemberships(
                roleMapping.mappedGroupIds(), policy.roleRevocationCutoff(now));
        lockGroups(candidates);
        Map<UUID, List<UUID>> groupsByUser = new LinkedHashMap<>();
        for (ScimGroupMembership membership : candidates) {
            groupsByUser.computeIfAbsent(membership.userId(), user -> new ArrayList<>())
                    .add(membership.groupId());
        }
        List<UUID> revoked = new ArrayList<>();
        groupsByUser.forEach((userId, groupIds) -> {
            Optional<ScimUser> user = users.findByIdForUpdate(userId);
            // Decided again on the locked read; the verdict is NOT_DUE for the Bootstrap Admin.
            if (user.isEmpty() || !policy.verdict(user.get(), now).revokesRoles()) {
                return;
            }
            List<Role> lost = new ArrayList<>();
            for (UUID groupId : groupIds) {
                // Every candidate Group is already held under its lock; one deleted since the
                // candidate query has no membership left to remove, so this removes nothing.
                if (groups.removeMember(groupId, userId, now)) {
                    roleMapping.roleOf(groupId).ifPresent(lost::add);
                }
            }
            if (lost.isEmpty()) {
                return;
            }
            audit.recordDormancyRoleRevocation(userId, lost);
            logRoleRevocation(userId, lost);
            sessions.revokeAllAfterCommit(userId, SessionRevocationCause.ROLE_REVOKED, null);
            revoked.add(userId);
        });
        return revoked;
    }

    /**
     * Every Group the candidates belong to, read under its resource lock in id order — the same
     * serialization point a connector's conditional write to it takes, and a fixed order so two
     * writers taking several Group locks cannot deadlock on each other. A Group deleted since the
     * candidate query is simply absent from the reads.
     */
    private void lockGroups(List<ScimGroupMembership> candidates) {
        Set<UUID> ordered = candidates.stream()
                .map(ScimGroupMembership::groupId)
                .collect(Collectors.toCollection(TreeSet::new));
        for (UUID groupId : ordered) {
            groups.findByIdForUpdate(groupId);
        }
    }

    private List<UUID> lockDormantUsers(Instant now) {
        List<UUID> locked = new ArrayList<>();
        for (UUID candidate : users.findDormantUnlockedUserIds(policy.lockoutCutoff(now))) {
            Optional<ScimUser> user = users.findByIdForUpdate(candidate);
            if (user.isEmpty() || !policy.verdict(user.get(), now).locksOut()
                    || !users.lockForDormancy(candidate, now)) {
                continue;
            }
            audit.recordDormancyLockout(candidate);
            logLockout(candidate);
            sessions.revokeAllAfterCommit(candidate, SessionRevocationCause.DORMANCY_LOCKOUT, null);
            locked.add(candidate);
        }
        return locked;
    }

    /** {@code WARN}: a dormancy lockout signals inactivity, not an attack — never {@code ERROR}. */
    private static void logLockout(UUID userId) {
        LogEvent.successAtWarn(log, Operation.DORMANCY_LOCKOUT, Category.PROCESS, Type.CHANGE)
                .addKeyValue(LogEvent.USER_TARGET_ID, userId.toString())
                .log();
    }

    private static void logRoleRevocation(UUID userId, List<Role> lost) {
        LogEvent.success(log, Operation.DORMANCY_ROLE_REVOCATION, Category.PROCESS, Type.CHANGE)
                .addKeyValue(LogEvent.USER_TARGET_ID, userId.toString())
                .addKeyValue(LogEvent.ROLE_NAME, lost.stream().map(Role::name).distinct()
                        .sorted().collect(Collectors.joining(",")))
                .log();
    }
}

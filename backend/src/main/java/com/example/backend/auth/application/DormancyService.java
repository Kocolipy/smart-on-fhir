package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginStateAttribute;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scheduling.application.ScheduledJobRunService;
import com.example.backend.scheduling.domain.ScheduledJob;
import com.example.backend.scim.domain.DormancyPolicy;
import com.example.backend.scim.domain.LoginStateChange;
import com.example.backend.scim.domain.ScimGroupMembership;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimLoginStateRepository;
import com.example.backend.scim.domain.ScimMembershipRevocation;
import com.example.backend.scim.domain.ScimUser;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimWriteLocks;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The dormancy job (ADR 0007): locks every User that has gone longer than the lockout window
 * without authenticating, and removes every direct Group membership of every User that has gone
 * longer than the revocation window.
 *
 * <h2>The two steps</h2>
 *
 * <ul>
 *   <li><strong>Membership revocation</strong> — the User's direct membership of every Group,
 *       the Admin group's included, is removed through the directory's
 *       {@link ScimMembershipRevocation}, which advances the Group's and the User's versions
 *       exactly as a connector-driven removal does and carries out what it implies through the
 *       same apply step as a connector's write: one actorless
 *       {@code DORMANCY_MEMBERSHIP_REVOCATION} event names the User, every Group removed and its
 *       effective Permissions before and after, and its sessions end after the commit. This
 *       follows the access-control standard's "a user inactive for 180 days loses all roles"; a
 *       Group that confers nothing is removed too (ADR 0007). A connector may
 *       re-add a membership; while the User stays dormant the next run removes it again.
 *   <li><strong>Lockout</strong> — the job reports each due User to the login-state module
 *       ({@link LoginStateService#dormancyLocked}), which decides the lock — {@code DORMANCY}, the
 *       failure run untouched — and stores it only where no lock stands, so a User already locked
 *       keeps its lock and its cause. What a lock it imposed implies is the module's too, as a
 *       failure run's Lockout is: an actorless {@code DORMANCY_LOCKOUT} event is recorded, and the
 *       User's sessions end after the commit (ADR 0002). The job logs the lock, and counts it.
 *       {@code active} is never written: it is the directory's.
 * </ul>
 *
 * <p>Only an administrator's Unlock lifts a dormancy lock, and it restarts the dormancy window,
 * so a User brought back is not locked again by the next run before it can sign in.
 *
 * <h2>What stops a User being processed twice</h2>
 *
 * <ul>
 *   <li>The job's lock ({@link ScheduledJob#DORMANCY}), which the module that runs a scheduled
 *       job ({@link ScheduledJobRunService}) takes and holds for the whole run — a second run
 *       skips while one is in progress, on any instance.
 *   <li>The resource locks and a second look. Both steps' candidates are found first, and then
 *       every Group and User either step may change is locked in one batch, through the SCIM write
 *       lock module ({@link ScimWriteLocks}) and in its one order — the order a connector's User
 *       and Group writes lock in too, so the run cannot deadlock against them. Every candidate is
 *       then read and decided again by the policy's {@link DormancyVerdict} — the job's only copy
 *       of the rule — so a User that logged in or was unlocked since the candidate queries is left
 *       alone.
 * </ul>
 *
 * <p>The Bootstrap Admin is never processed: both candidate queries exclude reserved Users, and
 * the exemption is checked again on the locked read.
 *
 * <p>One transaction per run: the run's own, which {@link ScheduledJobRunService} opens on the
 * primary data source and holds the job's lock in, and which every write here — the directory's
 * {@code @Transactional} membership revocation and the fail-closed audit appends included —
 * joins. A
 * failure on one User — an audit append that cannot commit, most of all — rolls the whole run back
 * and revokes nothing; the next run repeats it. That is the fail-closed half of ADR 0004, and it
 * keeps "locked but not recorded" impossible.
 *
 * <p>Logging: the run's start and end are {@code ScheduledJobMetrics}'s, the end carrying this
 * run's counts. Inside the run the only records are one per User changed, beside its audit
 * event: a lockout at {@code WARN} — it signals inactivity, not the attack an authentication
 * lockout's {@code ERROR} does — and a membership revocation at {@code INFO}. Neither names a
 * {@code userName}.
 */
@Service
public class DormancyService {

    private static final Logger log = LoggerFactory.getLogger(DormancyService.class);

    private final ScimUserRepository users;
    private final ScimLoginStateRepository loginStates;
    private final ScimGroupRepository groups;
    private final ScimWriteLocks writeLocks;
    private final ScimMembershipRevocation membershipRevocation;
    private final LoginStateService loginState;
    private final ScheduledJobRunService runs;
    private final DormancyPolicy policy;
    private final Clock clock;

    public DormancyService(
            ScimUserRepository users,
            ScimLoginStateRepository loginStates,
            ScimGroupRepository groups,
            ScimWriteLocks writeLocks,
            ScimMembershipRevocation membershipRevocation,
            LoginStateService loginState,
            ScheduledJobRunService runs,
            DormancyPolicy policy,
            Clock clock) {
        this.users = users;
        this.loginStates = loginStates;
        this.groups = groups;
        this.writeLocks = writeLocks;
        this.membershipRevocation = membershipRevocation;
        this.loginState = loginState;
        this.runs = runs;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * One run: revokes the memberships of every User past the revocation window, then locks every
     * unlocked User past the lockout window — or skips when another run holds the job's lock.
     */
    public DormancyRun run() {
        return runs.run(ScheduledJob.DORMANCY, this::runHoldingTheLock)
                .orElseGet(DormancyRun::skippedRun);
    }

    /** The run's body, inside the run's transaction and under the job's lock. */
    private DormancyRun runHoldingTheLock() {
        Instant now = clock.instant();
        List<ScimGroupMembership> revocationCandidates =
                groups.findDormantMemberships(policy.membershipRevocationCutoff(now));
        List<UUID> lockoutCandidates =
                loginStates.findDormantUnlockedUserIds(policy.lockoutCutoff(now));
        writeLocks.lock(resourcesToLock(revocationCandidates, lockoutCandidates));
        List<UUID> membershipsRevoked = revokeMemberships(revocationCandidates, now);
        List<UUID> locked = lockDormantUsers(lockoutCandidates, now);
        return new DormancyRun(false, locked, membershipsRevoked);
    }

    /**
     * Every Group and User the run may change: each candidate membership's Group and User, whose
     * versions a removal advances, and each lockout candidate, which is decided on a read the lock
     * makes current. Locked together because a lock taken later, while this run already holds
     * others, would be out of the module's order. A resource deleted since the candidate queries
     * locks nothing, and the reads below find it gone.
     */
    private static Set<UUID> resourcesToLock(
            List<ScimGroupMembership> revocationCandidates, List<UUID> lockoutCandidates) {
        Set<UUID> resources = new LinkedHashSet<>();
        for (ScimGroupMembership membership : revocationCandidates) {
            resources.add(membership.groupId());
            resources.add(membership.userId());
        }
        resources.addAll(lockoutCandidates);
        return resources;
    }

    private List<UUID> revokeMemberships(List<ScimGroupMembership> candidates, Instant now) {
        Map<UUID, List<UUID>> groupsByUser = new LinkedHashMap<>();
        for (ScimGroupMembership membership : candidates) {
            groupsByUser.computeIfAbsent(membership.userId(), user -> new ArrayList<>())
                    .add(membership.groupId());
        }
        List<UUID> revoked = new ArrayList<>();
        groupsByUser.forEach((userId, groupIds) -> {
            Optional<ScimUser> user = users.findById(userId);
            // Decided again on the locked read; the verdict is NOT_DUE for the Bootstrap Admin.
            if (user.isEmpty() || !policy.verdict(user.get(), now).revokesMemberships()) {
                return;
            }
            // The User and every candidate Group are already held. What removing the memberships
            // implies — versions, event, log, Session revocation — is the directory's to carry out.
            if (membershipRevocation.revokeMemberships(userId, groupIds, now)) {
                revoked.add(userId);
            }
        });
        return revoked;
    }

    private List<UUID> lockDormantUsers(List<UUID> candidates, Instant now) {
        List<UUID> locked = new ArrayList<>();
        for (UUID candidate : candidates) {
            Optional<ScimUser> user = users.findById(candidate);
            if (user.isEmpty() || !policy.verdict(user.get(), now).locksOut()) {
                continue;
            }
            // Its audit event and its Sessions' revocation are the module's. Only a lock the
            // module imposed is counted: one already held moves nothing, and a User gone under
            // the lock — which the lock this run holds excludes — imposes nothing either.
            Optional<LoginStateChange> imposed = loginState.dormancyLocked(candidate, now)
                    .filter(lock -> lock.hasMoved(AuditLoginStateAttribute.LOCKED_AT));
            if (imposed.isEmpty()) {
                continue;
            }
            logLockout(candidate);
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

}

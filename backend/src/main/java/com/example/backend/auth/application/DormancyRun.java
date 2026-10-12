package com.example.backend.auth.application;

import com.example.backend.observability.SkippableJobRun;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one run of the dormancy job did: skipped because another run held its lock, or ran and
 * locked these Users and revoked the Roles of those.
 *
 * @param skipped      whether another run held the lock, so this one did nothing
 * @param locked       the Users this run locked for dormancy, in id order; empty when it skipped
 *                     or found nobody to lock
 * @param rolesRevoked the Users this run removed mapped Group memberships from, in id order;
 *                     empty when it skipped or found nobody past the role-revocation window
 */
public record DormancyRun(boolean skipped, List<UUID> locked, List<UUID> rolesRevoked)
        implements SkippableJobRun {

    public DormancyRun {
        locked = List.copyOf(locked);
        rolesRevoked = List.copyOf(rolesRevoked);
    }

    /** A run that found the job's lock held and did nothing. */
    static DormancyRun skippedRun() {
        return new DormancyRun(true, List.of(), List.of());
    }

    /**
     * The counts the run's {@code job-end} record carries: Users locked and Users whose Roles
     * were revoked. Counts only — the stable ids are the audit trail's to carry. The same two
     * counts move the dormancy counters, which the job's schedule declares against these fields.
     */
    @Override
    public Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(DormancyLogFields.DORMANCY_LOCKED_COUNT, (long) locked.size());
        counts.put(DormancyLogFields.DORMANCY_ROLES_REVOKED_COUNT, (long) rolesRevoked.size());
        return counts;
    }
}

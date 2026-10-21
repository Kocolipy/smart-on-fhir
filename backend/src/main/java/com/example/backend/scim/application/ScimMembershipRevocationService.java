package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.authorization.domain.AuthorizationChangeCounts;
import com.example.backend.authorization.domain.GroupPermissions;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimMembershipRevocation;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimUserSessions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The directory's answer to {@link ScimMembershipRevocation}: removes a dormant User's Group
 * memberships one by one, then hands what that implies — decided by
 * {@link ScimWriteEffects#ofMembershipRevocation} — to the apply step a connector's Group write
 * uses, as the dormancy job's own write.
 */
@Service
public class ScimMembershipRevocationService implements ScimMembershipRevocation {

    private final ScimGroupRepository groups;
    private final ScimWriteEffectsApplier effects;

    public ScimMembershipRevocationService(
            ScimUserRepository users,
            ScimGroupRepository groups,
            ScimUserSessions sessions,
            AuditTrail audit,
            GroupPermissions permissions,
            AuthorizationChangeCounts counts) {
        this.groups = groups;
        this.effects = new ScimWriteEffectsApplier(
                users, groups, sessions, audit, permissions, counts);
    }

    /**
     * Joins the dormancy run's transaction, so a failure anywhere in the run rolls every removal
     * back and ends no Session.
     */
    @Override
    @Transactional
    public boolean revokeMemberships(UUID userId, Collection<UUID> groupIds, Instant now) {
        List<UUID> removed = new ArrayList<>();
        for (UUID groupId : groupIds) {
            // A Group deleted since the candidate query has no membership left to remove, so this
            // removes nothing; each removal advances the Group's version and the User's.
            if (groups.removeMember(groupId, userId, now)) {
                removed.add(groupId);
            }
        }
        if (removed.isEmpty()) {
            return false;
        }
        effects.apply(ScimWriteEffectsApplier.Writer.DORMANCY_JOB,
                ScimWriteEffects.ofMembershipRevocation(userId, removed), now);
        return true;
    }
}

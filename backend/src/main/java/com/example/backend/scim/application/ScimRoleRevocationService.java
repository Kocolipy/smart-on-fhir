package com.example.backend.scim.application;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.authorization.domain.Role;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.scim.domain.ScimGroupRepository;
import com.example.backend.scim.domain.ScimRoleRevocation;
import com.example.backend.scim.domain.ScimUserRepository;
import com.example.backend.scim.domain.ScimUserSessions;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The directory's answer to {@link ScimRoleRevocation}: removes a dormant User's mapped Group
 * memberships one by one, then hands what that implies — decided by
 * {@link ScimWriteEffects#ofRoleRevocation} — to the apply step a connector's Group write uses, as
 * the dormancy job's own write.
 */
@Service
public class ScimRoleRevocationService implements ScimRoleRevocation {

    private final ScimGroupRepository groups;
    private final RoleMapping roleMapping;
    private final ScimWriteEffectsApplier effects;

    public ScimRoleRevocationService(
            ScimUserRepository users,
            ScimGroupRepository groups,
            ScimUserSessions sessions,
            AuditTrail audit,
            RoleMapping roleMapping) {
        this.groups = groups;
        this.roleMapping = roleMapping;
        this.effects = new ScimWriteEffectsApplier(users, groups, sessions, audit);
    }

    /**
     * Joins the dormancy run's transaction, so a failure anywhere in the run rolls every removal
     * back and ends no Session.
     */
    @Override
    @Transactional
    public boolean revokeRoles(UUID userId, Collection<UUID> groupIds, Instant now) {
        Map<UUID, Role> lost = new LinkedHashMap<>();
        for (UUID groupId : groupIds) {
            // A Group deleted since the candidate query has no membership left to remove, so this
            // removes nothing; each removal advances the Group's version and the User's.
            if (groups.removeMember(groupId, userId, now)) {
                Optional<Role> role = roleMapping.roleOf(groupId);
                role.ifPresent(conferred -> lost.put(groupId, conferred));
            }
        }
        if (lost.isEmpty()) {
            return false;
        }
        effects.apply(ScimWriteEffectsApplier.Writer.DORMANCY_JOB,
                ScimWriteEffects.ofRoleRevocation(userId, lost), now);
        return true;
    }
}

package com.example.backend.auth.application;

import com.example.backend.auth.domain.RoleMappingSessions;
import com.example.backend.authorization.domain.RoleMapping;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scim.domain.ScimUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Makes the running role mapping the one every live session's Permissions were resolved under, by
 * ending the sessions that were not.
 *
 * <p>Run once at startup. A session holds the Permissions it was issued with and the hash of the
 * mapping that produced them; when a redeploy changes the mapping, a session store that survived
 * the redeploy still holds sessions whose Permissions the new mapping might not grant — or might
 * grant more of. Ending them is the "a removed power stops immediately" rule applied to a change
 * of configuration rather than of membership, and the next sign-in resolves the Permissions
 * afresh. A mapping that did not change has the same hash, so a plain restart ends nothing.
 */
@Service
public class RoleMappingSessionService {

    /** The operation both startup records are classified as. */
    public static final Operation OPERATION = Operation.ROLE_MAPPING_STARTUP;

    private static final Logger log = LoggerFactory.getLogger(RoleMappingSessionService.class);

    private final RoleMapping mapping;
    private final RoleMappingSessions sessions;
    private final ScimUserRepository users;

    public RoleMappingSessionService(
            RoleMapping mapping, RoleMappingSessions sessions, ScimUserRepository users) {
        this.mapping = mapping;
        this.sessions = sessions;
        this.users = users;
    }

    /**
     * Reports the validated mapping's hash, then ends every authenticated session issued under
     * another — reported too, with how many, when there were any.
     *
     * @return how many sessions were ended
     */
    public int revokeSessionsIssuedUnderAnotherMapping() {
        // be-log-sensitive-value matches any value named "hash", for the password and
        // bearer hashes it exists to keep out of logs. This one is the SHA-256 of the
        // role mapping -- deployment configuration, not a secret and not derived from
        // one -- and ADR 0003 ("Role changes and the role mapping") puts it on both startup
        // records, so an operator can tell which mapping a deploy validated. Suppressed on exactly these two records and nowhere else.
        LogEvent.success(log, OPERATION, Category.CONFIGURATION, Type.INFO) // nosemgrep: be-log-sensitive-value
                .addKeyValue(LogEvent.ROLE_MAPPING_HASH, mapping.hash())
                .log();
        int revoked = sessions.revokeIssuedUnderAnotherMapping(users.findAllIds(), mapping.hash());
        if (revoked > 0) {
            LogEvent.success(log, OPERATION, Category.CONFIGURATION, Type.CHANGE) // nosemgrep: be-log-sensitive-value
                    .addKeyValue(LogEvent.ROLE_MAPPING_HASH, mapping.hash())
                    .addKeyValue(LogEvent.SESSIONS_ENDED, revoked)
                    .log();
        }
        return revoked;
    }
}

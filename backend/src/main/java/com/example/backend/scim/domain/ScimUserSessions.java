package com.example.backend.scim.domain;

import java.util.Set;
import java.util.UUID;

/**
 * Ending a User's sessions because a SCIM write changed something they were issued against.
 *
 * <p>A port in the directory's domain for a capability the login surface owns. Sessions are a
 * fact about the login surface, and the dependency between the two slices runs one way —
 * {@code auth} reaches the directory, never the reverse — so the SCIM write use case cannot name
 * the session port or the after-commit seam that live there. It names this instead, and the
 * login surface's Session revocation module answers it, as one trigger among the several it
 * serves.
 *
 * <p>The name says <em>after commit</em> because that is the whole contract, and a call site
 * should read as what it does (see {@code /docs/adr/0002-revoke-sessions-after-commit.md}): the
 * sessions end once the calling transaction commits, and not at all if it rolls back — so a
 * refused, stale or rolled-back write revokes nothing. The outcome of a revocation that ended a
 * session, or failed to, is audited by the implementation, because only it knows whether the
 * revocation succeeded; a failure reaches the connector as an error.
 */
public interface ScimUserSessions {

    /**
     * Why a write ends a User's sessions — each is a change a live session must not outlast. The
     * directory's own narrow vocabulary: the login surface records each under the Session
     * revocation cause of the same name.
     */
    enum Cause {
        /** {@code active} went from true to false. */
        DEACTIVATED,
        /** The password was set, changed or removed. */
        PASSWORD_CHANGED,
        /** {@code userName} changed. */
        USER_NAME_CHANGED,
        /** The User was deleted. */
        DELETED,
        /**
         * The User lost a Role: its direct membership of a mapped Group was removed by a
         * connector's write. Gaining one revokes nothing — the new Permissions arrive at the
         * next sign-in.
         */
        ROLE_REVOKED
    }

    /**
     * Ends every session the User holds once the current transaction commits.
     *
     * @param connectorId the connector whose write caused it, recorded as the actor
     * @param userId      the User whose sessions end
     * @param causes      why; never empty
     */
    void revokeAfterCommit(UUID connectorId, UUID userId, Set<Cause> causes);
}

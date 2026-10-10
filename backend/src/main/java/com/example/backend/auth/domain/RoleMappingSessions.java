package com.example.backend.auth.domain;

import java.util.Collection;
import java.util.UUID;

/**
 * The live sessions issued under a role mapping other than the running one, as something that can
 * be taken away.
 *
 * <p>A session carries the Permissions it was resolved at sign-in, and the hash of the role mapping
 * they were resolved under ({@link SignedInSession#issuedUnder}). A redeploy that changes the
 * mapping changes what those Permissions should be, and a session store that survived the
 * redeploy would otherwise keep serving the old ones until each session expired. Ending every
 * such session at startup makes a mapping change take effect on the next request, whether or not
 * the store was emptied.
 *
 * <p>A port for the reason {@link AccountSessions} is one: the domain says that these sessions
 * end, and the adapter owns what a session is and how the store is searched.
 */
public interface RoleMappingSessions {

    /**
     * Ends every session these Users hold that was not issued under this mapping hash — one
     * issued under another, and one carrying none at all, which predates the hash being recorded.
     *
     * <p>Sessions are found by User, as every revocation in this application finds them: the
     * store is indexed by principal, and an authenticated session always names one. A session
     * nobody has signed in to holds no Permissions, names no User, and is not reached.
     *
     * @param userIds     the Users whose sessions are checked — every User, at startup
     * @param currentHash the running role mapping's hash
     * @return how many sessions were ended
     */
    int revokeIssuedUnderAnotherMapping(Collection<UUID> userIds, String currentHash);
}

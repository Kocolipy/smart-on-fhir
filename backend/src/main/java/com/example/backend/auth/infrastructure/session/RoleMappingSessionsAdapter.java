package com.example.backend.auth.infrastructure.session;

import com.example.backend.auth.domain.RoleMappingSessions;
import com.example.backend.auth.domain.SignedInSession;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Outbound adapter for {@link RoleMappingSessions}, over Spring Session's principal-name index —
 * the same index, keyed by the User's stable id, that {@link AccountSessionsAdapter} revokes
 * through.
 *
 * <p>By User rather than by walking the store: Spring Session offers no "every session" query, and
 * reaching past it to the Redis keys would tie this adapter to the store's key layout. A login
 * writes the principal index entry and the mapping hash together ({@link SignedInSession#signIn}),
 * so every session that holds Permissions is found under its User, and the session itself says
 * which mapping it was issued under. It costs one index read per User, once, at startup.
 */
@Component
public class RoleMappingSessionsAdapter implements RoleMappingSessions {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public RoleMappingSessionsAdapter(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    @Override
    public int revokeIssuedUnderAnotherMapping(Collection<UUID> userIds, String currentHash) {
        int revoked = 0;
        for (UUID userId : userIds) {
            // Copied out before deleting, for the reason AccountSessionsAdapter copies: the
            // lookup's result is the repository's own view.
            Map<String, ? extends Session> held = sessions.findByPrincipalName(userId.toString());
            List<String> stale = held.entrySet().stream()
                    .filter(entry -> !SpringSessionAttributes.signedIn(entry.getValue())
                            .issuedUnder(currentHash))
                    .map(Map.Entry::getKey)
                    .toList();
            stale.forEach(sessions::deleteById);
            revoked += stale.size();
        }
        return revoked;
    }
}

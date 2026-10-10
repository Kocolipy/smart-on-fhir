package com.example.backend.auth.infrastructure.session;

import com.example.backend.auth.domain.AccountSessions;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Outbound adapter for {@link AccountSessions}, backed by Spring Session's
 * principal-name index.
 *
 * <p>The index is what makes this possible at all, and it is not free: a plain
 * session store can only be read by id, so the sessions belonging to an account
 * cannot be found. This class therefore requires the indexed session repository,
 * configured in {@code session.yaml} — with the default repository there is no
 * bean to inject and the application does not start, which is the intended
 * failure. It is loud, and it happens at startup, rather than a disable quietly
 * leaving sessions running.
 *
 * <p>The index name says "principal name" because that is Spring Session's own
 * vocabulary, and by default it is populated from the security context's
 * {@code Authentication.getName()} — the login username. This application
 * overrides that: {@code SessionEstablishment} writes the account's stable id into the
 * session attribute {@link FindByIndexNameSessionRepository#PRINCIPAL_NAME_INDEX_NAME}
 * explicitly on login, so the index this adapter searches is keyed by the stable
 * id and survives a later username change, while {@code Authentication.getName()}
 * — and everything that reads it, including the login/{@code /me} response — is
 * untouched and keeps naming the username.
 *
 * <p>It ends sessions and reports how many, and nothing more. The {@code session-end}
 * record and the audit event are the Session revocation module's
 * ({@code SessionRevocationService}), which knows the revocation's cause; this adapter is
 * not told it.
 */
@Component
public class AccountSessionsAdapter implements AccountSessions {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AccountSessionsAdapter(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    @Override
    public int revokeAll(UUID accountId) {
        return revoke(accountId, null);
    }

    @Override
    public int revokeAllExcept(UUID accountId, String retainedSessionId) {
        return revoke(accountId, retainedSessionId);
    }

    private int revoke(UUID accountId, String retainedSessionId) {
        // Copied out of the returned map before deleting: the lookup's result is
        // the repository's own view, and deleting through it while iterating is
        // not something the interface promises to tolerate.
        Set<String> ids = sessions.findByPrincipalName(accountId.toString()).keySet().stream()
                .filter(id -> !id.equals(retainedSessionId))
                .collect(Collectors.toUnmodifiableSet());
        ids.forEach(sessions::deleteById);
        return ids.size();
    }
}

package com.example.backend.auth.infrastructure.session;

import com.example.backend.auth.domain.AccountSessions;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Component
public class AccountSessionsAdapter implements AccountSessions {

    /** The cause a session-end record names for {@link #revokeAll}: the account was taken away. */
    static final String REVOKED = "revoked";

    /** The cause for {@link #revokeAllExcept}: a login keeping one session ended the rest. */
    static final String REPLACED_BY_LOGIN = "replaced-by-login";

    private static final Logger log = LoggerFactory.getLogger(AccountSessionsAdapter.class);

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AccountSessionsAdapter(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    @Override
    public int revokeAll(UUID accountId) {
        return revoke(accountId, null, REVOKED);
    }

    @Override
    public int revokeAllExcept(UUID accountId, String retainedSessionId) {
        return revoke(accountId, retainedSessionId, REPLACED_BY_LOGIN);
    }

    private int revoke(UUID accountId, String retainedSessionId, String cause) {
        // Copied out of the returned map before deleting: the lookup's result is
        // the repository's own view, and deleting through it while iterating is
        // not something the interface promises to tolerate.
        Set<String> ids = sessions.findByPrincipalName(accountId.toString()).keySet().stream()
                .filter(id -> !id.equals(retainedSessionId))
                .collect(Collectors.toUnmodifiableSet());
        ids.forEach(sessions::deleteById);
        if (!ids.isEmpty()) {
            recordEnded(accountId, cause, ids.size());
        }
        return ids.size();
    }

    /**
     * One {@code session-end} record per revocation that ended anything. The account whose
     * sessions ended is {@code user.target.id}: {@code user.id} stays the actor the request
     * already carries — the administrator, or nobody for a connector or a scheduled job.
     * No session id is named; an id is the session's bearer credential.
     */
    private static void recordEnded(UUID accountId, String cause, int ended) {
        LogEvent.success(log, Operation.SESSION_END, Category.PROCESS, Type.END)
                .addKeyValue(LogEvent.REASON, cause)
                .addKeyValue(LogEvent.USER_TARGET_ID, accountId.toString())
                .addKeyValue(LogEvent.SESSIONS_ENDED, ended)
                .log();
    }
}

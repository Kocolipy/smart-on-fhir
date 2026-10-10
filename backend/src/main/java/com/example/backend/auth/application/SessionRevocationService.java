package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditSessionRevocationCause;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.AuditUserAttribute;
import com.example.backend.audit.domain.OperationalAlerts;
import com.example.backend.auth.domain.AccountSessions;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.scim.domain.ScimUserSessions;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Session revocation: ending the sessions a User already holds, because something about its
 * identity, credential or authority changed. Every trigger comes here — a SCIM write, the dormancy
 * job, a failure-run lockout, a forced or self-service password change, and an accepted Login's
 * one-session-per-User sweep — so each one ends, audits and logs the same way, under one cause
 * vocabulary ({@link SessionRevocationCause}).
 *
 * <p>In order, once the caller's transaction commits ({@link AfterCommit}, ADR 0002): the sessions
 * end through {@link AccountSessions}, then the outcome is audited as one
 * {@code USER_SESSIONS_REVOKE} event naming the causes as its reason, and logged as one
 * {@code session-end} record under the same names. A rolled-back change ends nothing and records
 * nothing. A revocation that ended no session — the User was signed in nowhere else — records
 * nothing either: there is nothing to account for.
 *
 * <p>The audit append is fail-open with an alert, because the change the revocation follows is
 * already committed and a failed append could undo nothing (ADR 0004). A session store that fails
 * is always recorded, as a {@code FAILURE} event, and always alerted; what the caller sees then
 * follows ADR 0004's axis (see its addendum):
 *
 * <ul>
 *   <li>a request that would otherwise <em>succeed</em> — a SCIM write, the dormancy job, a
 *       password change, a forced change, an accepted Login — sees the failure: the change stays
 *       durable, as ADR 0002 argues, but the caller is not told a change fully succeeded while the
 *       sessions it should have ended survive;
 *   <li>a request already being <em>refused</em> — a failure-run lockout, imposed by a rejected
 *       Login or a rejected self-service password change — does not, so it keeps answering with
 *       the same bare refusal whatever the state of the session store.
 * </ul>
 *
 * <p>Also the login surface's answer to {@link ScimUserSessions}, the narrow port the directory
 * names because the dependency between the slices runs from here to there and never back; each of
 * the directory's causes is recorded as the cause of the same name.
 */
@Service
public class SessionRevocationService implements ScimUserSessions {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationService.class);

    private final AccountSessions sessions;
    private final AfterCommit afterCommit;
    private final AuditTrail audit;
    private final OperationalAlerts alerts;

    public SessionRevocationService(
            AccountSessions sessions,
            AfterCommit afterCommit,
            AuditTrail audit,
            OperationalAlerts alerts) {
        this.sessions = sessions;
        this.afterCommit = afterCommit;
        this.audit = audit;
        this.alerts = alerts;
    }

    /**
     * Ends every session the User holds once the current transaction commits.
     *
     * @param userId  the User whose sessions end
     * @param cause   why; never {@link SessionRevocationCause#REPLACED_BY_LOGIN}, which keeps a
     *                session and is {@link #revokeOtherSessionsAfterCommit}'s
     * @param actorId who caused it — the administrator forcing a password change — or
     *                {@code null} when nobody recorded as an actor did: the dormancy job, a Login,
     *                the User's own self-service change
     */
    public void revokeAllAfterCommit(UUID userId, SessionRevocationCause cause, UUID actorId) {
        if (cause == SessionRevocationCause.REPLACED_BY_LOGIN) {
            throw new IllegalArgumentException("one session per User keeps the Login's session;"
                    + " it is revokeOtherSessionsAfterCommit's");
        }
        schedule(actorId, userId, EnumSet.of(cause), store -> store.revokeAll(userId));
    }

    /**
     * One session per User: once the accepted Login's transaction commits, ends every session the
     * User holds but the one the Login continues in. Recorded with no actor.
     *
     * @param retainedSessionId the id the Login's own session is stored under, or {@code null}
     *                          when it holds none yet, in which case every session ends
     */
    public void revokeOtherSessionsAfterCommit(UUID userId, String retainedSessionId) {
        schedule(null, userId, EnumSet.of(SessionRevocationCause.REPLACED_BY_LOGIN),
                store -> store.revokeAllExcept(userId, retainedSessionId));
    }

    /**
     * The directory's trigger: a SCIM write changed something the User's sessions were issued
     * against.
     *
     * @param connectorId the connector whose write caused it, recorded as the actor
     */
    @Override
    public void revokeAfterCommit(UUID connectorId, UUID userId, Set<Cause> causes) {
        if (causes.isEmpty()) {
            throw new IllegalArgumentException("a revocation has a cause");
        }
        Set<SessionRevocationCause> named = EnumSet.noneOf(SessionRevocationCause.class);
        for (Cause cause : causes) {
            named.add(SessionRevocationCause.valueOf(cause.name()));
        }
        schedule(connectorId, userId, named, store -> store.revokeAll(userId));
    }

    private void schedule(UUID actorId, UUID userId, Set<SessionRevocationCause> causes,
            ToIntFunction<AccountSessions> end) {
        Set<SessionRevocationCause> causesAtCall = Set.copyOf(causes);
        afterCommit.run(() -> revoke(actorId, userId, causesAtCall, end));
    }

    private void revoke(UUID actorId, UUID userId, Set<SessionRevocationCause> causes,
            ToIntFunction<AccountSessions> end) {
        int ended;
        try {
            ended = end.applyAsInt(sessions);
        } catch (RuntimeException storeFailed) {
            audit.recordUserSessionsRevoked(
                    actorId, userId, paths(causes), auditCauses(causes), false);
            alerts.sessionRevocationFailed(storeFailed.getClass());
            if (imposedByRefusal(causes)) {
                return;
            }
            throw storeFailed;
        }
        if (ended == 0) {
            return;
        }
        audit.recordUserSessionsRevoked(actorId, userId, paths(causes), auditCauses(causes), true);
        logEnded(userId, causes, ended);
    }

    /**
     * Whether the revocation follows a request that is itself being refused, whose answer a store
     * failure must not change (ADR 0004). A failure-run lockout is the only such cause, and it is
     * never combined with another.
     */
    private static boolean imposedByRefusal(Set<SessionRevocationCause> causes) {
        return causes.contains(SessionRevocationCause.FAILURE_RUN_LOCKOUT);
    }

    /**
     * The attribute whose change each cause is, as the event's changed paths. A deletion, a
     * lockout, a forced change and a Login changed no attribute a connector reads, so none
     * contributes one; the reason names them instead.
     */
    private static Set<AuditUserAttribute> paths(Set<SessionRevocationCause> causes) {
        Set<AuditUserAttribute> paths = EnumSet.noneOf(AuditUserAttribute.class);
        for (SessionRevocationCause cause : causes) {
            switch (cause) {
                case DEACTIVATED -> paths.add(AuditUserAttribute.ACTIVE);
                case PASSWORD_CHANGED -> paths.add(AuditUserAttribute.PASSWORD);
                case USER_NAME_CHANGED -> paths.add(AuditUserAttribute.USER_NAME);
                case ROLE_REVOKED -> paths.add(AuditUserAttribute.GROUPS);
                case DELETED, DORMANCY_LOCKOUT, FAILURE_RUN_LOCKOUT, FORCED_PASSWORD_CHANGE,
                        REPLACED_BY_LOGIN -> { }
            }
        }
        return paths;
    }

    /**
     * Each cause as the audit trail's own name for it, which is the same name: audit cannot see
     * this enum (the slices depend the other way), so it mirrors it. A cause added here without
     * its mirror fails {@code SessionRevocationServiceTests}' every-cause test.
     */
    private static Set<AuditSessionRevocationCause> auditCauses(
            Set<SessionRevocationCause> causes) {
        Set<AuditSessionRevocationCause> audited = EnumSet.noneOf(AuditSessionRevocationCause.class);
        for (SessionRevocationCause cause : causes) {
            audited.add(AuditSessionRevocationCause.valueOf(cause.name()));
        }
        return audited;
    }

    /**
     * One {@code session-end} record per revocation that ended anything, its reason the causes'
     * names in declaration order. The User whose sessions ended is {@code user.target.id}:
     * {@code user.id} stays the actor the request already carries — the administrator, or nobody
     * for a connector or a scheduled job. No session id is named; an id is the session's bearer
     * credential.
     */
    private static void logEnded(UUID userId, Set<SessionRevocationCause> causes, int ended) {
        String reason = causes.stream()
                .sorted()
                .map(SessionRevocationCause::name)
                .collect(Collectors.joining(","));
        LogEvent.success(log, Operation.SESSION_END, Category.PROCESS, Type.END)
                .addKeyValue(LogEvent.REASON, reason)
                .addKeyValue(LogEvent.USER_TARGET_ID, userId.toString())
                .addKeyValue(LogEvent.SESSIONS_ENDED, ended)
                .log();
    }
}

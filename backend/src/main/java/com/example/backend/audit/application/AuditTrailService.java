package com.example.backend.audit.application;

import com.example.backend.audit.domain.AuditEvent;
import com.example.backend.audit.domain.AuditEventRepository;
import com.example.backend.audit.domain.AuditRequestContext;
import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.audit.domain.Audited;
import com.example.backend.audit.domain.IsolatedAuditEventRepository;
import com.example.backend.audit.domain.OperationalAlerts;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit trail: stamps each {@link Audited} event with its identity, its instant and the
 * request being served, and appends it under the one rule
 * {@code /docs/adr/0004-audit-append-failure-semantics.md} decides.
 *
 * <h2>Two failure semantics, deliberately, applied once</h2>
 *
 * <p>Which one an append gets follows from what the triggering request was going to return, and
 * the event's kind says which that is:
 *
 * <ul>
 *   <li><strong>Fail-closed</strong> for an {@link Audited.Accepted} event — a request that would
 *       otherwise succeed: an administrator's unlock, an accepted login, a logout, a SCIM write or
 *       bulk read. The append joins the caller's transaction ({@code PROPAGATION_REQUIRED}, opening
 *       one where there is none), so an event that cannot be written rolls the mutation it was
 *       recording back. A change this service cannot account for does not happen.
 *   <li><strong>Fail-open with an alert</strong> for an {@link Audited.Refused} event — a request
 *       already being refused, such as a rejected login and the lockout it may impose — and for an
 *       {@link Audited.Committed} one, recorded after its change committed. The original outcome
 *       must stand: turning a refused login's bare {@code 401} into a {@code 500} because the
 *       trail is unavailable would tell an attacker something about the state of the service. The
 *       append commits in a transaction of its own, on a connection of its own from the dedicated
 *       audit append pool ({@link IsolatedAuditEventRepository}), so its rollback cannot take the
 *       caller's with it, the caller's cannot take it, and it never waits for a connection of the
 *       main pool a refusing caller may be holding. Its failure raises an operational alert
 *       instead of propagating.
 * </ul>
 *
 * <p>The fail-closed one is a {@link TransactionTemplate} rather than a {@code @Transactional}
 * method: a self-invocation does not pass through the proxy, so an annotation on a helper here
 * would be silently ignored — and one on {@link #append} could not say both things at once.
 */
@Service
public class AuditTrailService implements AuditTrail {

    private final AuditEventRepository events;
    private final IsolatedAuditEventRepository isolatedEvents;
    private final AuditRequestContext requests;
    private final OperationalAlerts alerts;
    private final Clock clock;

    /**
     * Runs a fail-closed append in the caller's transaction, or in a new one when there is none:
     * the template's default propagation, {@code PROPAGATION_REQUIRED}, which is what the
     * {@code @Transactional} of each former fail-closed recorder asked for.
     */
    private final TransactionTemplate joinedAppend;

    public AuditTrailService(
            AuditEventRepository events,
            IsolatedAuditEventRepository isolatedEvents,
            AuditRequestContext requests,
            OperationalAlerts alerts,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.events = events;
        this.isolatedEvents = isolatedEvents;
        this.requests = requests;
        this.alerts = alerts;
        this.clock = clock;
        this.joinedAppend = new TransactionTemplate(transactionManager);
    }

    @Override
    public void append(Audited event) {
        AuditEvent row = event.toEvent(
                new Audited.Stamp(UUID.randomUUID(), clock.instant(), requests));
        if (event.failsClosed()) {
            joinedAppend.executeWithoutResult(status -> events.append(row));
        } else {
            appendRaisingAlertOnFailure(row);
        }
    }

    /**
     * Appends in a transaction of its own, on the audit append pool, and swallows a failure,
     * having raised an operational alert for it — a pool that cannot hand out a connection
     * within its short timeout included.
     *
     * <p>{@link RuntimeException} and not {@link Throwable}: a persistence failure arrives as one,
     * and an {@link Error} means the process is in a state where continuing to serve the request
     * is not the right call either.
     */
    private void appendRaisingAlertOnFailure(AuditEvent row) {
        try {
            isolatedEvents.append(row);
        } catch (RuntimeException appendFailed) {
            alerts.auditAppendFailed(row.operation(), appendFailed.getClass());
        }
    }
}

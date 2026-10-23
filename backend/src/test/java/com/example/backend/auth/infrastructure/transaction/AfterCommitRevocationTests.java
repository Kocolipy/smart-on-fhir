package com.example.backend.auth.infrastructure.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.InMemoryAccountSessions;
import com.example.backend.auth.application.SessionRevocationService;
import com.example.backend.auth.domain.SessionRevocationCause;
import com.example.backend.scheduling.RecordingTransactionManager;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Several revocations scheduled in one transaction, through the real adapter and a real
 * transaction manager's commit: a session store failing on one leaves the others to run, each
 * audited as it ended (ADR 0002, ADR 0004), and the commit then reports the failure.
 *
 * <p>Spring stops running a transaction's synchronizations at the first that throws, which
 * {@code PendingCommit} only models: this is where the real machinery is held to it.
 */
class AfterCommitRevocationTests {

    private final InMemoryAccountSessions sessions = new InMemoryAccountSessions();

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final RecordingOperationalAlerts alerts = new RecordingOperationalAlerts();

    private final SessionRevocationService revocation = new SessionRevocationService(
            sessions, new AfterCommitAdapter(), audit, alerts);

    private final TransactionTemplate transaction =
            new TransactionTemplate(new RecordingTransactionManager());

    private final IllegalStateException storeDown = new IllegalStateException("store timed out");

    private final UUID first = UUID.randomUUID();

    private final UUID second = UUID.randomUUID();

    @Test
    void aRevocationAfterAFailedOneStillEndsItsSessionsAndIsAudited() {
        sessions.open(first, "s-first");
        sessions.open(second, "s-second");
        sessions.failFor(first, storeDown);

        catchThrowable(() -> transaction.executeWithoutResult(status -> {
            revocation.revokeAllAfterCommit(
                    first, SessionRevocationCause.GROUP_PERMISSIONS_REDUCED, null);
            revocation.revokeAllAfterCommit(
                    second, SessionRevocationCause.GROUP_PERMISSIONS_REDUCED, null);
        }));

        assertThat(audit.events(Audited.UserSessionsRevoked.class))
                .extracting(Audited.UserSessionsRevoked::userId,
                        Audited.UserSessionsRevoked::succeeded)
                .containsExactly(tuple(first, false), tuple(second, true));
    }

    @Test
    void theCommitReportsTheFailureOnceEveryRevocationWasAttempted() {
        sessions.failFor(first, storeDown);

        Throwable reported = catchThrowable(() -> transaction.executeWithoutResult(status -> {
            revocation.revokeAllAfterCommit(first, SessionRevocationCause.DORMANCY_LOCKOUT, null);
            revocation.revokeAllAfterCommit(second, SessionRevocationCause.DORMANCY_LOCKOUT, null);
        }));

        assertThat(reported).isSameAs(storeDown);
    }

    /**
     * A failure-run lockout's own failure is still swallowed (ADR 0004): the refused request's
     * commit reports nothing, and the failure is alerted all the same.
     */
    @Test
    void aLockoutsFailedRevocationIsStillSwallowedAndAlerted() {
        sessions.failFor(first, storeDown);

        Throwable reported = catchThrowable(() -> transaction.executeWithoutResult(status ->
                revocation.revokeAllAfterCommit(
                        first, SessionRevocationCause.FAILURE_RUN_LOCKOUT, null)));

        assertThat(Arrays.asList(reported, alerts.revocationFailures()))
                .containsExactly(null, List.of(IllegalStateException.class));
    }
}

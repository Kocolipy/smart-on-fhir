package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.application.EpicLoginOutcome.FailedCall;
import com.example.backend.auth.application.EpicLoginOutcome.Refused;
import com.example.backend.auth.application.EpicLoginOutcome.SignedIn;
import com.example.backend.auth.application.EpicLoginOutcome.Unavailable;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.observability.SessionHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Service;

/**
 * Records how an Epic Login ended (ADR 0013, flow steps 6–8): the one place each ending's audit
 * record, log line and counts are written, so no two endings can disagree about what an ending
 * emits. Where the browser goes next is the web adapter's, and a function of the outcome alone.
 *
 * <ul>
 *   <li><b>Signed in.</b> The {@code user-authentication} record at {@code INFO}, naming the
 *       User, method {@code sso} (D15) and the MFA factor (D17), and {@code epic.login}
 *       {@code outcome=success} — recorded by the web adapter once the session is signed in, so
 *       after the login decision's transaction commits, and naming that session. The
 *       {@code LOGIN_SUCCESS} is not here: it is fail-closed, and so written in the login
 *       decision's own transaction ({@link LoginAttemptService}).
 *   <li><b>Refused.</b> A {@code LOGIN_FAILURE} under method {@code sso} with its reason and the
 *       refused User, if the login decision found one, counted toward no failure run (D12);
 *       one {@code WARN} saying only "Epic sign-in refused", with the field and rule of a refused
 *       input but never the reason, which is the audit trail's alone ("the account reasons are
 *       audit-only"); and {@code epic.login} {@code outcome=refused} with the reason. A refusal
 *       that was an Epic call answering with something unusable is also that call's
 *       {@code ERROR}, followed up: a key or a registration is likely wrong.
 *   <li><b>Unavailable.</b> A {@code LOGIN_FAILURE} with {@code EPIC_UNAVAILABLE} and no subject,
 *       and {@code epic.login} {@code outcome=unavailable}. The call's {@code ERROR}, with no
 *       follow-up, is written here for a {@code 5xx} only: a call that got no answer was already
 *       logged at {@code ERROR}, with its stack, by the outbound interceptor that saw it fail, and
 *       one event is logged once.
 * </ul>
 *
 * <p>Every ending's records carry {@code session.hash} — for the session the Login ran in, or for
 * a success the session it signed in — and no user field but a success's: the session the browser held is not whom the launch was for. Every
 * failed call is also counted by its call and category, so a refused credential
 * ({@code cert/auth}) can be alerted on. Nothing Epic sent, and never the Practitioner ID.
 */
@Service
public class EpicLoginOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(EpicLoginOutcomeService.class);

    private final LoginAttemptService attempts;

    private final EpicLoginCounts counts;

    public EpicLoginOutcomeService(LoginAttemptService attempts, EpicLoginCounts counts) {
        this.attempts = attempts;
        this.counts = counts;
    }

    /**
     * Records {@code outcome}.
     *
     * @param sessionId the id of the session the Login ran in — for a success, the one it signed
     *                  in — which its records name by hash only; {@code null} when the browser
     *                  held none
     */
    public void record(EpicLoginOutcome outcome, String sessionId) {
        String sessionHash = SessionHash.of(sessionId);
        switch (outcome) {
            case SignedIn signedIn -> signedIn(signedIn, sessionHash);
            case Refused refused -> refused(refused, sessionHash);
            case Unavailable unavailable -> unavailable(unavailable, sessionHash);
        }
    }

    private void signedIn(SignedIn signedIn, String sessionHash) {
        try (LogContext.Scope resolved = LogContext.userId(signedIn.userId())) {
            inSession(LogEvent.success(
                            log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.ALLOWED)
                    .addKeyValue(LogEvent.LOGIN_METHOD, AuditLoginMethod.SSO.value())
                    .addKeyValue(LogEvent.MFA_FACTOR, signedIn.mfaFactor().value()), sessionHash)
                    .log();
        }
        counts.signedIn();
    }

    private void refused(Refused refused, String sessionHash) {
        if (refused.failedCall() != null) {
            logFailedCall(refused.failedCall(), sessionHash);
            counts.failedCall(refused.failedCall().call(), refused.failedCall().category());
        }
        attempts.recordRefusal(refused.subjectId(), audited(refused.reason()), AuditLoginMethod.SSO);
        try (LogContext.Scope unresolved = LogContext.userId(null)) {
            LoggingEventBuilder warning = inSession(LogEvent.refused(
                            log, Operation.EPIC_LOGIN, Category.PROCESS, Type.USER, Type.DENIED)
                    .addKeyValue(LogEvent.LOGIN_METHOD, AuditLoginMethod.SSO.value()), sessionHash);
            if (refused.field() != null) {
                warning.addKeyValue(LogEvent.EPIC_INPUT_FIELD, refused.field().value());
            }
            if (refused.rule() != null) {
                warning.addKeyValue(LogEvent.EPIC_INPUT_RULE, refused.rule().value());
            }
            warning.log();
        }
        counts.refused(refused.reason());
    }

    private void unavailable(Unavailable unavailable, String sessionHash) {
        FailedCall call = unavailable.failedCall();
        if (call.category() != ErrorCategory.NETWORK) {
            logFailedCall(call, sessionHash);
        }
        counts.failedCall(call.call(), call.category());
        attempts.recordRefusal(null, AuditRefusalReason.EPIC_UNAVAILABLE, AuditLoginMethod.SSO);
        counts.unavailable();
    }

    /**
     * The one {@code ERROR} of a failed Epic call: which call, and its {@code error.category}
     * (ADR 0013, "Log") and code, followed up unless Epic was merely unavailable.
     */
    private static void logFailedCall(FailedCall failed, String sessionHash) {
        try (LogContext.Scope unresolved = LogContext.userId(null)) {
            // be-log-sensitive-value matches any value named "code", for Epic's authorization
            // code (ADR 0013, D22). This one is the failed call's error.code -- the HTTP status
            // the failure maps to, never a value Epic sent -- so it is suppressed on this one
            // record and nowhere else.
            inSession(LogEvent.error(log, Operation.EPIC_LOGIN, failed.code(), failed.category(), // nosemgrep: be-log-sensitive-value
                            !failed.unavailable(), Category.NETWORK, Type.ERROR)
                    .addKeyValue(LogEvent.EPIC_CALL, failed.call())
                    .addKeyValue(LogEvent.LOGIN_METHOD, AuditLoginMethod.SSO.value()), sessionHash)
                    .log();
        }
    }

    /**
     * {@code record}, naming the session the Login ran in by its hash — or by nothing, when the
     * browser held no session.
     */
    private static LoggingEventBuilder inSession(LoggingEventBuilder record, String sessionHash) {
        if (sessionHash != null) {
            // Not a secret: a truncated SHA-256 of a random session id (SessionHash), which cannot
            // be turned back into the id. be-log-sensitive-value, which keeps password and bearer
            // hashes out of logs, does not match this call, so nothing here is suppressed.
            record.addKeyValue(LogEvent.SESSION_HASH, sessionHash);
        }
        return record;
    }

    /**
     * An Epic refusal as the audit trail's own vocabulary, which password Login's refusals share.
     * Exhaustive, so a reason added to the list cannot reach the trail unmapped.
     */
    private static AuditRefusalReason audited(EpicLoginFailureReason reason) {
        return switch (reason) {
            case INVALID_LAUNCH -> AuditRefusalReason.INVALID_LAUNCH;
            case ISS_MISMATCH -> AuditRefusalReason.ISS_MISMATCH;
            case INVALID_STATE -> AuditRefusalReason.INVALID_STATE;
            case INVALID_CODE -> AuditRefusalReason.INVALID_CODE;
            case IDP_ERROR -> AuditRefusalReason.IDP_ERROR;
            case TOKEN_EXCHANGE_FAILED -> AuditRefusalReason.TOKEN_EXCHANGE_FAILED;
            case INVALID_SIGNATURE -> AuditRefusalReason.INVALID_SIGNATURE;
            case INVALID_CLAIMS -> AuditRefusalReason.INVALID_CLAIMS;
            case INVALID_FHIR_USER -> AuditRefusalReason.INVALID_FHIR_USER;
            case EPIC_UNAVAILABLE -> AuditRefusalReason.EPIC_UNAVAILABLE;
            case UNKNOWN_ACCOUNT -> AuditRefusalReason.UNKNOWN_ACCOUNT;
            case ACCOUNT_DISABLED -> AuditRefusalReason.ACCOUNT_DISABLED;
            case ACCOUNT_LOCKED -> AuditRefusalReason.ACCOUNT_LOCKED;
        };
    }
}

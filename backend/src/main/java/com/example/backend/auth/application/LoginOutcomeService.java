package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.application.LoginOutcome.EpicRefused;
import com.example.backend.auth.application.LoginOutcome.FailedCall;
import com.example.backend.auth.application.LoginOutcome.PasswordRefused;
import com.example.backend.auth.application.LoginOutcome.SignedIn;
import com.example.backend.auth.application.LoginOutcome.Unavailable;
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
 * Records how a Login ended, by either login method (ADR 0013, flow steps 6–8, and its addendum
 * of 2026-10-09 for password Login): the one place each ending's audit record, log line and
 * counts are written, so no two endings — and no two login methods — can disagree about what an
 * ending emits. Where the browser goes next is the web adapter's, and a function of the outcome
 * alone: a password Login's bare {@code 401}, an Epic Login's redirect.
 *
 * <ul>
 *   <li><b>Signed in.</b> The {@code user-authentication} record at {@code INFO}, naming the
 *       User and the login method (D15) — and an Epic Login's MFA factor (D17) — and
 *       {@code login} {@code outcome=success}. The {@code LOGIN_SUCCESS} is not here: it is
 *       fail-closed, and so written in the login decision's own transaction
 *       ({@link LoginAttemptService}).
 *   <li><b>Refused.</b> A {@code LOGIN_FAILURE} under the login method with its reason; one
 *       {@code WARN} saying only that the Login was refused — "Login refused", or "Epic sign-in
 *       refused" with the field and rule of a refused input — but never the reason, which is
 *       the audit trail's alone (Logging §2.2; ADR 0013, "the account reasons are audit-only");
 *       and {@code login} {@code outcome=refused} with the reason. A password Login's refusal
 *       is counted toward the failure run of the User it named, and its {@code LOGIN_FAILURE}
 *       names that User, or nobody, as {@code UNKNOWN_ACCOUNT}, when the name matched none. An
 *       Epic Login's names the refused User, if the login decision found one, and counts toward
 *       no failure run (D12). A refusal that was an Epic call answering with something unusable
 *       is also that call's {@code ERROR}, followed up: a key or a registration is likely wrong.
 *   <li><b>Unavailable</b>, an Epic Login only. A {@code LOGIN_FAILURE} with
 *       {@code EPIC_UNAVAILABLE} and no subject, and {@code login} {@code outcome=unavailable}.
 *       The call's {@code ERROR}, with no follow-up, is written here for a {@code 5xx} only: a
 *       call that got no answer was already logged at {@code ERROR}, with its stack, by the
 *       outbound interceptor that saw it fail, and one event is logged once.
 * </ul>
 *
 * <p>Every ending's records carry {@code session.hash} when a session is named — for a refusal the
 * session the Login ran in, for an Epic success the session it signed in — and no user field but
 * a success's: the session the browser held is not whom the attempt was for. No record carries a
 * password Login's submitted username, nor anything Epic sent, nor the Practitioner ID. Every
 * failed Epic call is also counted by its call and category, so a refused credential
 * ({@code cert/auth}) can be alerted on.
 */
@Service
public class LoginOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(LoginOutcomeService.class);

    private final LoginAttemptService attempts;

    private final LoginCounts counts;

    private final EpicCallCounts epicCalls;

    public LoginOutcomeService(
            LoginAttemptService attempts, LoginCounts counts, EpicCallCounts epicCalls) {
        this.attempts = attempts;
        this.counts = counts;
        this.epicCalls = epicCalls;
    }

    /**
     * Records {@code outcome}.
     *
     * @param sessionId the id of the session the Login ran in — for an Epic success, the one it
     *                  signed in — which its records name by hash only; {@code null} when the
     *                  browser held none, or for a password success, whose records name none
     */
    public void record(LoginOutcome outcome, String sessionId) {
        String sessionHash = SessionHash.of(sessionId);
        switch (outcome) {
            case SignedIn signedIn -> signedIn(signedIn, sessionHash);
            case PasswordRefused refused -> passwordRefused(refused, sessionHash);
            case EpicRefused refused -> epicRefused(refused, sessionHash);
            case Unavailable unavailable -> unavailable(unavailable, sessionHash);
        }
    }

    private void signedIn(SignedIn signedIn, String sessionHash) {
        try (LogContext.Scope resolved = LogContext.userId(signedIn.userId())) {
            LoggingEventBuilder record = inSession(LogEvent.success(
                            log, Operation.LOGIN, Category.PROCESS, Type.USER, Type.ALLOWED)
                    .addKeyValue(LogEvent.LOGIN_METHOD, signedIn.method().value()), sessionHash);
            if (signedIn.mfaFactor() != null) {
                record.addKeyValue(LogEvent.MFA_FACTOR, signedIn.mfaFactor().value());
            }
            record.log();
        }
        counts.signedIn(signedIn.method());
    }

    private void passwordRefused(PasswordRefused refused, String sessionHash) {
        AuditRefusalReason recorded = attempts.recordFailure(refused.username(), refused.reason());
        try (LogContext.Scope unresolved = LogContext.userId(null)) {
            refusedWarning(Operation.LOGIN, refused, sessionHash).log();
        }
        counts.refused(refused.method(), recorded);
    }

    private void epicRefused(EpicRefused refused, String sessionHash) {
        if (refused.failedCall() != null) {
            logFailedCall(refused.failedCall(), sessionHash);
            epicCalls.failedCall(refused.failedCall().call(), refused.failedCall().category());
        }
        attempts.recordRefusal(
                refused.subjectId(), refused.reason().audited(), refused.method());
        try (LogContext.Scope unresolved = LogContext.userId(null)) {
            LoggingEventBuilder warning =
                    refusedWarning(Operation.EPIC_LOGIN, refused, sessionHash);
            if (refused.field() != null) {
                warning.addKeyValue(LogEvent.EPIC_INPUT_FIELD, refused.field().value());
            }
            if (refused.rule() != null) {
                warning.addKeyValue(LogEvent.EPIC_INPUT_RULE, refused.rule().value());
            }
            warning.log();
        }
        counts.refused(refused.method(), refused.reason().audited());
    }

    private void unavailable(Unavailable unavailable, String sessionHash) {
        FailedCall call = unavailable.failedCall();
        if (call.category() != ErrorCategory.NETWORK) {
            logFailedCall(call, sessionHash);
        }
        epicCalls.failedCall(call.call(), call.category());
        attempts.recordRefusal(
                null, EpicLoginFailureReason.EPIC_UNAVAILABLE.audited(), AuditLoginMethod.SSO);
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
     * The one {@code WARN} of a refused Login, under {@code operation}: that it was refused, by
     * which login method, in which session — and never why (Logging §2.2).
     */
    private static LoggingEventBuilder refusedWarning(
            Operation operation, LoginOutcome.Refused refused, String sessionHash) {
        return inSession(LogEvent.refused(
                        log, operation, Category.PROCESS, Type.USER, Type.DENIED)
                .addKeyValue(LogEvent.LOGIN_METHOD, refused.method().value()), sessionHash);
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
}

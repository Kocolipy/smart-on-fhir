package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.audit.domain.AuditRefusalReason;
import com.example.backend.auth.domain.EpicInputField;
import com.example.backend.auth.domain.EpicInputRule;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.observability.LogEvent.ErrorCategory;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * How a Login ended, by either login method: signed in, refused, or — for an Epic Login only —
 * unable to complete because Epic was unavailable (ADR 0013, D23). {@link LoginOutcomeService}
 * records each one; this is the whole of what it needs, and nothing Epic sent.
 */
public sealed interface LoginOutcome {

    /**
     * The Login signed {@code userId} in by {@code method}: a password Login with no MFA factor, or
     * an Epic Login with the one it was made with (ADR 0013, D17).
     */
    record SignedIn(UUID userId, AuditLoginMethod method, AuditMfaFactor mfaFactor)
            implements LoginOutcome {

        public SignedIn {
            Objects.requireNonNull(userId, "a signed-in Login names its User");
            Objects.requireNonNull(method, "a signed-in Login has a login method");
            if (method == AuditLoginMethod.SSO) {
                Objects.requireNonNull(mfaFactor, "an Epic Login always carries an MFA factor (D17)");
            } else if (mfaFactor != null) {
                throw new IllegalArgumentException("a password Login carries no MFA factor");
            }
        }

        /** A password Login signed {@code userId} in. */
        public static SignedIn password(UUID userId) {
            return new SignedIn(userId, AuditLoginMethod.PASSWORD, null);
        }

        /** An Epic Login signed {@code userId} in, made with {@code mfaFactor} (D17). */
        public static SignedIn epic(UUID userId, AuditMfaFactor mfaFactor) {
            return new SignedIn(userId, AuditLoginMethod.SSO, mfaFactor);
        }
    }

    /**
     * The Login was refused. The browser is told only that, by the web adapter of its method — a
     * password Login's bare {@code 401}, an Epic Login's {@code /?signin=refused} — and the
     * reason is the audit trail's and the {@code login} counter's alone.
     */
    sealed interface Refused extends LoginOutcome {

        /** The login method the refused Login was made by. */
        AuditLoginMethod method();
    }

    /**
     * A password Login was refused for {@code reason}, as Spring Security reported it.
     *
     * <p>Carries the submitted {@code username} because the refusal is counted against the User it
     * names, and only {@link LoginOutcomeService} may look that User up. It is half a credential, and
     * on a failed attempt very often a mistyped password, so {@link #toString()} leaves it out and
     * no record names it.
     *
     * @param reason the refusal as Spring Security reported it — never {@code UNKNOWN_ACCOUNT},
     *     which Spring Security hides behind a wrong password, and which recording the refusal
     *     settles by looking the name up
     */
    record PasswordRefused(String username, AuditRefusalReason reason) implements Refused {

        /** Every reason Spring Security reports a password Login's refusal under. */
        private static final Set<AuditRefusalReason> REPORTED = Set.of(
                AuditRefusalReason.BAD_CREDENTIALS,
                AuditRefusalReason.ACCOUNT_LOCKED,
                AuditRefusalReason.ACCOUNT_DISABLED,
                AuditRefusalReason.OTHER);

        public PasswordRefused {
            Objects.requireNonNull(reason, "a refusal has a reason");
            if (!REPORTED.contains(reason)) {
                throw new IllegalArgumentException("not a password Login's refusal: " + reason);
            }
        }

        @Override
        public AuditLoginMethod method() {
            return AuditLoginMethod.PASSWORD;
        }

        @Override
        public String toString() {
            return "PasswordRefused[reason=" + reason + "]";
        }
    }

    /**
     * An Epic Login was refused for {@code reason}.
     *
     * @param subjectId  the refused User's stable id, when the login decision found one
     * @param field      the input refused for D18 or D10, if the refusal was of one
     * @param rule       the rule that input broke
     * @param failedCall the Epic call whose unusable answer was the refusal, if it was one
     */
    record EpicRefused(EpicLoginFailureReason reason, UUID subjectId, EpicInputField field,
            EpicInputRule rule, FailedCall failedCall) implements Refused {

        public EpicRefused {
            Objects.requireNonNull(reason, "a refusal has a reason");
            if (reason == EpicLoginFailureReason.EPIC_UNAVAILABLE) {
                throw new IllegalArgumentException("Epic being unavailable is not a refusal (D23)");
            }
            if (failedCall != null && failedCall.unavailable()) {
                throw new IllegalArgumentException("a call Epic did not answer is not a refusal");
            }
        }

        @Override
        public AuditLoginMethod method() {
            return AuditLoginMethod.SSO;
        }

        /** A refusal for {@code reason} that names no input, User or call. */
        public static EpicRefused because(EpicLoginFailureReason reason) {
            return new EpicRefused(reason, null, null, null, null);
        }

        /** The login decision refused the User {@code subjectId}, or nobody when it is null. */
        public static EpicRefused account(UUID subjectId, EpicLoginFailureReason reason) {
            return new EpicRefused(reason, subjectId, null, null, null);
        }

        /** The input {@code field} was refused for breaking {@code rule}. */
        public static EpicRefused input(
                EpicLoginFailureReason reason, EpicInputField field, EpicInputRule rule) {
            return new EpicRefused(reason, null, field, rule, null);
        }

        /** {@code failedCall} answered with something unusable, refused for {@code reason}. */
        public static EpicRefused call(EpicLoginFailureReason reason, FailedCall failedCall) {
            return new EpicRefused(reason, null, null, null, Objects.requireNonNull(failedCall));
        }
    }

    /** Epic did not answer {@code failedCall}, or answered {@code 5xx} (D23). */
    record Unavailable(FailedCall failedCall) implements LoginOutcome {

        public Unavailable {
            if (!Objects.requireNonNull(failedCall, "Epic was unavailable to a call").unavailable()) {
                throw new IllegalArgumentException("a call Epic answered is not Epic unavailable");
            }
        }
    }

    /**
     * An Epic call that failed (ADR 0013, "Log", error categories): which call — {@code discovery},
     * {@code jwks} or {@code token} — its {@code error.category}, and its {@code error.code}.
     */
    record FailedCall(String call, ErrorCategory category, int code) {

        public FailedCall {
            Objects.requireNonNull(call, "a failed call is named");
            Objects.requireNonNull(category, "a failed call has an error category");
        }

        /** Whether Epic was unavailable (D23): no answer, or a {@code 5xx}. */
        public boolean unavailable() {
            return category == ErrorCategory.NETWORK || category == ErrorCategory.SERVER;
        }
    }
}

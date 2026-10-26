package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditLoginMethod;
import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.auth.domain.EpicInputField;
import com.example.backend.auth.domain.EpicInputRule;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.observability.LogEvent.ErrorCategory;
import java.util.Objects;
import java.util.UUID;

/**
 * How a Login ended, by either login method: signed in, refused, or — for an Epic Login only —
 * unable to complete because Epic was unavailable (ADR 0012, D23). {@link LoginOutcomeService}
 * records each one; this is the whole of what it needs, and nothing Epic sent.
 */
public sealed interface LoginOutcome {

    /**
     * The Login signed {@code userId} in by {@code method}: a password Login with no MFA factor, or
     * an Epic Login with the one it was made with (ADR 0012, D17).
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
     * password Login's bare {@code 401}, an Epic Login's {@code /login?signin=refused} — and the
     * reason is the audit trail's and the {@code login} counter's alone.
     */
    sealed interface Refused extends LoginOutcome {

        /** The login method the refused Login was made by. */
        AuditLoginMethod method();
    }

    /**
     * A password Login was refused for {@code reason}, as the login decision reported it.
     *
     * <p>It names the User the authentication's one read resolved, by stable id, whether the
     * authentication refused it or the decision under the User's lock did — and so whom the
     * refusal is counted and audited against. Never the submitted name: looking that up again
     * would follow whichever User holds it by then, and a directory rename can land in between.
     *
     * @param subjectId the stable id of the User the authentication's read resolved; {@code null}
     *     when it found none, which recording the refusal settles as {@code UNKNOWN_ACCOUNT}
     * @param reason    the refusal as reported — never {@code UNKNOWN_ACCOUNT}, which Spring
     *     Security hides behind a wrong password, and which recording the refusal settles
     *     ({@link PasswordRefusalReason#reported})
     */
    record PasswordRefused(UUID subjectId, PasswordRefusalReason reason) implements Refused {

        public PasswordRefused {
            Objects.requireNonNull(reason, "a refusal has a reason");
            if (!reason.reported()) {
                throw new IllegalArgumentException("not a password Login's refusal: " + reason);
            }
        }

        @Override
        public AuditLoginMethod method() {
            return AuditLoginMethod.PASSWORD;
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
     * An Epic call that failed (ADR 0012, "Log", error categories): which call — {@code discovery},
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

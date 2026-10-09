package com.example.backend.auth.application;

import com.example.backend.audit.domain.AuditMfaFactor;
import com.example.backend.auth.domain.EpicInputField;
import com.example.backend.auth.domain.EpicInputRule;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import com.example.backend.observability.LogEvent.ErrorCategory;
import java.util.Objects;
import java.util.UUID;

/**
 * How an Epic Login ended (ADR 0013, flow steps 6–8): signed in, refused, or unable to complete
 * because Epic was unavailable (D23). {@link EpicLoginOutcomeService} records each one; this is
 * the whole of what it needs, and nothing Epic sent.
 */
public sealed interface EpicLoginOutcome {

    /** The Login signed {@code userId} in, made with {@code mfaFactor} (D17). */
    record SignedIn(UUID userId, AuditMfaFactor mfaFactor) implements EpicLoginOutcome {

        public SignedIn {
            Objects.requireNonNull(userId, "a signed-in Login names its User");
            Objects.requireNonNull(mfaFactor, "an Epic Login always carries an MFA factor (D17)");
        }
    }

    /**
     * The Login was refused for {@code reason}.
     *
     * @param subjectId  the refused User's stable id, when the login decision found one
     * @param field      the input refused for D18 or D10, if the refusal was of one
     * @param rule       the rule that input broke
     * @param failedCall the Epic call whose unusable answer was the refusal, if it was one
     */
    record Refused(EpicLoginFailureReason reason, UUID subjectId, EpicInputField field,
            EpicInputRule rule, FailedCall failedCall) implements EpicLoginOutcome {

        public Refused {
            Objects.requireNonNull(reason, "a refusal has a reason");
            if (reason == EpicLoginFailureReason.EPIC_UNAVAILABLE) {
                throw new IllegalArgumentException("Epic being unavailable is not a refusal (D23)");
            }
            if (failedCall != null && failedCall.unavailable()) {
                throw new IllegalArgumentException("a call Epic did not answer is not a refusal");
            }
        }

        /** A refusal for {@code reason} that names no input, User or call. */
        public static Refused because(EpicLoginFailureReason reason) {
            return new Refused(reason, null, null, null, null);
        }

        /** The login decision refused the User {@code subjectId}, or nobody when it is null. */
        public static Refused account(UUID subjectId, EpicLoginFailureReason reason) {
            return new Refused(reason, subjectId, null, null, null);
        }

        /** The input {@code field} was refused for breaking {@code rule}. */
        public static Refused input(
                EpicLoginFailureReason reason, EpicInputField field, EpicInputRule rule) {
            return new Refused(reason, null, field, rule, null);
        }

        /** {@code failedCall} answered with something unusable, refused for {@code reason}. */
        public static Refused call(EpicLoginFailureReason reason, FailedCall failedCall) {
            return new Refused(reason, null, null, null, Objects.requireNonNull(failedCall));
        }
    }

    /** Epic did not answer {@code failedCall}, or answered {@code 5xx} (D23). */
    record Unavailable(FailedCall failedCall) implements EpicLoginOutcome {

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

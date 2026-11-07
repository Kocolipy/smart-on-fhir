package com.example.backend.patient.infrastructure.http;

import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.OutboundCallFailure;
import com.example.backend.observability.OutboundCallFailure.Reason;
import com.example.backend.observability.UpstreamFaultException;
import java.util.stream.Stream;

/**
 * The in-house patient source could not be read: it gave no answer, an answer other than a
 * Patient or a {@code 404}, or a Patient the service cannot hold. A fault of the source, answered
 * {@code 502} with the {@code exception} outcome (/docs/domain/patient.md, "Errors"), its one
 * fault record naming the {@link Failure} as its {@code event.reason} ({@link #reason()}) under
 * the {@code error.category} ADR 0012 gives an outbound call's failure ({@link #category()}):
 *
 * <ul>
 *   <li>{@code network}: no answer — {@code timed-out} (the connect or the read timeout ran out)
 *       or {@code unreachable} (no connection otherwise: refused, an unknown host, a call
 *       dropped);
 *   <li>{@code server}: the source answered {@code 5xx}, {@code server-error};
 *   <li>{@code data}: the source answered with something the service cannot use — another status
 *       ({@code unexpected-status}), a body that is not JSON or not a Patient
 *       ({@code unreadable}), or a Patient breaking one of the domain's rules.
 * </ul>
 *
 * <p>Its message is fixed text, naming at most a status or an element, and its cause, when it has
 * one, is the original failure redacted to its type: a client's or a parser's message can quote
 * the URL, and so the patient's id, or the body, and so the patient (/docs/domain/patient.md,
 * "Logging").
 */
public final class PatientSourceException extends UpstreamFaultException {

    /**
     * Every way a read of the source fails: its {@code event.reason}, its fixed message, and the
     * {@code error.category} it is reported under. A failure of the call itself is one of
     * {@link OutboundCallFailure}'s reasons, under the category ADR 0012 gives it there; a Patient
     * breaking a domain rule is {@code data}.
     */
    enum Failure {
        /** No connection to the source, other than by a timeout: refused, unknown, or dropped. */
        UNREACHABLE(Reason.UNREACHABLE, "unreachable", "The patient source could not be reached"),
        /** No connection within the connect timeout, or no answer within the read timeout. */
        TIMED_OUT(Reason.TIMED_OUT, "timed-out", "The patient source did not answer in time"),
        /** The source answered {@code 5xx}; the message is completed by the status. */
        SERVER_ERROR(Reason.SERVER_ERROR, "server-error", "The patient source answered"),
        /** Another status, neither a {@code 200}, a {@code 404} nor a {@code 5xx}. */
        UNEXPECTED_STATUS(Reason.UNEXPECTED_STATUS, "unexpected-status",
                "The patient source answered"),
        /** A {@code 200} whose body could not be read as a Patient at all. */
        UNREADABLE(Reason.UNREADABLE, "unreadable", "The patient source could not be read"),
        ANOTHER_RESOURCE("another-resource", "The patient source answered with another resource",
                ErrorCategory.DATA),
        NO_NRIC("no-nric", "The patient source's Patient has no NRIC", ErrorCategory.DATA),
        NO_OFFICIAL_NAME("no-official-name", "The patient source's Patient has no official name",
                ErrorCategory.DATA),
        NO_NAME_TEXT("no-name-text", "The patient source's Patient's official name has no text",
                ErrorCategory.DATA),
        NO_GENDER("no-gender", "The patient source's Patient has no gender", ErrorCategory.DATA),
        NO_BIRTH_DATE("no-birth-date", "The patient source's Patient has no birth date",
                ErrorCategory.DATA),
        BIRTH_DATE_NOT_TO_THE_DAY("birth-date-not-to-the-day",
                "The patient source's Patient's birth date is not to the day", ErrorCategory.DATA);

        /** The call's own failure this is, or {@code null} for a broken domain rule. */
        private final Reason outbound;

        private final String reason;

        private final String message;

        private final ErrorCategory category;

        Failure(Reason outbound, String reason, String message) {
            this(outbound, reason, message, outbound.category());
        }

        Failure(String reason, String message, ErrorCategory category) {
            this(null, reason, message, category);
        }

        private Failure(Reason outbound, String reason, String message, ErrorCategory category) {
            this.outbound = outbound;
            this.reason = reason;
            this.message = message;
            this.category = category;
        }

        /**
         * The failure the call's own {@code outbound} failure is. The source is sent no
         * credential, so {@link Reason#CREDENTIAL_REFUSED} is none of them: a {@code 401} is
         * answered as the status it is, and reaches here only by a defect, which this fails on.
         */
        static Failure of(Reason outbound) {
            return Stream.of(values()).filter(failure -> failure.outbound == outbound)
                    .findFirst().orElseThrow();
        }
    }

    private PatientSourceException(Failure failure, String message, Throwable redactedCause) {
        super(message, failure.category, failure.reason, redactedCause);
    }

    /** The read failed for {@code failure}, with no cause worth keeping. */
    static PatientSourceException failed(Failure failure) {
        return failed(failure, null);
    }

    /** The read failed for {@code failure}, on {@code redactedCause}. */
    static PatientSourceException failed(Failure failure, Throwable redactedCause) {
        return new PatientSourceException(failure, failure.message, redactedCause);
    }

    /** The source answered {@code status}, neither a Patient's {@code 200} nor a {@code 404}. */
    static PatientSourceException answered(int status) {
        Failure failure = status >= 500 ? Failure.SERVER_ERROR : Failure.UNEXPECTED_STATUS;
        return new PatientSourceException(failure, failure.message + " " + status, null);
    }
}

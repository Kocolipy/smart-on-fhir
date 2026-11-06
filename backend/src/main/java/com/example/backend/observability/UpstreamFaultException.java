package com.example.backend.observability;

import com.example.backend.observability.LogEvent.ErrorCategory;

/**
 * A service this one calls failed it, so the request it was serving cannot be answered: the
 * request is answered {@code 502 Bad Gateway}, and the request's one fault record is reported
 * under {@link #category()} with {@link #reason()} as its {@code event.reason}.
 *
 * <p>Here, in the logging base every module reaches, so that a module calling out — the
 * {@code patient} module's patient source adapter — can say what failed, and the app-wide error
 * handler in {@code web} can answer it, with neither knowing the other (ADR 0014, "The module's
 * edges").
 *
 * <p>Like every fault, its message is fixed text and its cause, when it has one, is redacted to
 * its type ({@link RedactedFaultException}): neither may carry a value the call sent or received.
 */
public abstract class UpstreamFaultException extends RuntimeException {

    private final ErrorCategory category;

    private final String reason;

    /**
     * @param message       fixed text saying what failed, naming at most a status or an element
     * @param category      the {@code error.category} it is reported under
     * @param reason        the failure's name, as its record's {@code event.reason}: fixed
     *                      lower-case words, never a value
     * @param redactedCause the original failure redacted to its type, or {@code null}
     */
    protected UpstreamFaultException(
            String message, ErrorCategory category, String reason, Throwable redactedCause) {
        super(message, redactedCause);
        this.category = category;
        this.reason = reason;
    }

    /** The {@code error.category} the fault record is reported under. */
    public ErrorCategory category() {
        return category;
    }

    /** The failure's name, the fault record's {@code event.reason}. */
    public String reason() {
        return reason;
    }

    /**
     * Whether a person must follow the fault up: its {@code error.follow_up_action}, unless the
     * service called was unavailable ({@link ErrorCategory#unavailable()}). An answer the service
     * cannot use ({@code data}) above all needs a person.
     */
    public boolean followUp() {
        return !category.unavailable();
    }
}

package com.example.backend.observability;

import com.example.backend.observability.LogEvent.ErrorCategory;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * A call to another service that failed, as ADR 0012's table for an outbound call reports it:
 * its {@link Reason}, the {@code error.category} that follows from it ({@link #category()}), and
 * its {@code error.code} — the other service's status when it answered one, {@code 502} when it
 * gave none worth keeping. The one classifier every outbound adapter uses, the Epic calls and the
 * patient source's alike. It names no HTTP client's type, which only those adapters may
 * (ADR 0012, D25; ADR 0014): each hands it what its client reported in neutral terms — the status
 * it answered, or the failure it raised, the type of its client's own timeout failure and, when
 * the adapter times its calls, how long it had waited against its read timeout.
 *
 * <ul>
 *   <li><b>A status</b> ({@link #answered}): a {@code 5xx} is {@code server}, a {@code 401} is our
 *       own credential refused ({@code cert/auth}), and any other is an answer the service cannot
 *       use ({@code data}).
 *   <li><b>No answer</b> ({@link #failed}), {@code network}: an I/O failure anywhere in the
 *       failure's cause chain. Not only the client's own "no answer" failure, raised before the
 *       headers arrive, but a read of the body after them that stalled or whose connection reset
 *       or dropped, which the client raises as a generic failure to extract the response with
 *       the I/O failure beneath, often under the JSON parser's wrapper of it. It
 *       {@link Reason#TIMED_OUT timed out} when the client's timeout is in the chain or the call
 *       had waited its read timeout out, and otherwise found the other service
 *       {@link Reason#UNREACHABLE unreachable}: refused, unknown, reset or dropped.
 *   <li><b>Anything else</b>, {@code data}: the answer arrived and could not be read. A failure
 *       to parse the message is no I/O failure: Jackson 3's parse failures are unchecked, and the
 *       message converters wrap them with no I/O failure beneath, so a body that arrived whole
 *       but is not JSON, or not the shape asked for, stays {@code data}.
 * </ul>
 *
 * @param reason the failure, for an {@code event.reason}
 * @param code   the {@code error.code}
 */
public record OutboundCallFailure(Reason reason, int code) {

    /** Every way an outbound call fails, each under the {@code error.category} ADR 0012 gives it. */
    public enum Reason {
        /** No answer within a timeout: the connect timeout, or the read timeout. */
        TIMED_OUT(ErrorCategory.NETWORK),
        /** No answer otherwise: no connection, or one reset or dropped before the answer was in. */
        UNREACHABLE(ErrorCategory.NETWORK),
        /** The other service answered {@code 5xx}. */
        SERVER_ERROR(ErrorCategory.SERVER),
        /** The other service answered {@code 401}: it refused this one's own credential. */
        CREDENTIAL_REFUSED(ErrorCategory.CERT_AUTH),
        /** Another error status. */
        UNEXPECTED_STATUS(ErrorCategory.DATA),
        /** An answer that arrived and could not be read as what was asked for. */
        UNREADABLE(ErrorCategory.DATA);

        private final ErrorCategory category;

        Reason(ErrorCategory category) {
            this.category = category;
        }

        /** The {@code error.category} ADR 0012 gives this reason. */
        public ErrorCategory category() {
            return category;
        }
    }

    /** The {@code error.category} ADR 0012 gives the {@link #reason()}. */
    public ErrorCategory category() {
        return reason.category();
    }

    /** The other service answered {@code status}, an error status. */
    public static OutboundCallFailure answered(int status) {
        if (status >= 500) {
            return new OutboundCallFailure(Reason.SERVER_ERROR, status);
        }
        if (status == 401) {
            return new OutboundCallFailure(Reason.CREDENTIAL_REFUSED, status);
        }
        return new OutboundCallFailure(Reason.UNEXPECTED_STATUS, status);
    }

    /**
     * A call that times itself raised {@code failure} with no status, after {@code waited}: no
     * answer when an I/O failure is anywhere in its cause chain, and an answer that could not be
     * read when none is. No answer timed out when the client's {@code clientTimeout} is in the
     * chain — the connect timeout, or the read timeout before the headers — or when the call had
     * waited its {@code readTimeout} out: a client that enforces the read timeout on the body by
     * closing the body's stream, as Spring's JDK client does, fails the cut-off read as a bare
     * I/O failure, as a reset's or a dropped connection's is, and only the time tells them apart.
     *
     * @param clientTimeout the type of the client's own timeout failure, named by the adapter:
     *                      only it may name an HTTP client's types (ADR 0012, D25)
     */
    public static OutboundCallFailure failed(Throwable failure,
            Class<? extends Throwable> clientTimeout, Duration waited, Duration readTimeout) {
        return failed(failure, clientTimeout, waited.compareTo(readTimeout) >= 0);
    }

    /**
     * A call that does not time itself raised {@code failure} with no status: as
     * {@link #failed(Throwable, Class, Duration, Duration)} has it, but timed out only when the
     * client's {@code clientTimeout} is in the chain. A body the read timeout cut off is then
     * unreachable rather than timed out: no answer, {@code network}, all the same — for a caller
     * that keeps the category and not the reason.
     */
    public static OutboundCallFailure failed(Throwable failure,
            Class<? extends Throwable> clientTimeout) {
        return failed(failure, clientTimeout, false);
    }

    private static OutboundCallFailure failed(Throwable failure,
            Class<? extends Throwable> clientTimeout, boolean waitedOut) {
        List<Throwable> chain = CauseChain.of(failure);
        if (chain.stream().noneMatch(IOException.class::isInstance)) {
            return withNoStatus(Reason.UNREADABLE);
        }
        boolean timedOut = waitedOut || chain.stream().anyMatch(clientTimeout::isInstance);
        return withNoStatus(timedOut ? Reason.TIMED_OUT : Reason.UNREACHABLE);
    }

    /** A failure with no status worth keeping, its {@code error.code} the {@code 502}. */
    private static OutboundCallFailure withNoStatus(Reason reason) {
        return new OutboundCallFailure(reason, LogEvent.BAD_GATEWAY_ERROR_CODE);
    }
}

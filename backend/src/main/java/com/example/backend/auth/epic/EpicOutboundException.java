package com.example.backend.auth.epic;

import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.OutboundCallFailure;
import java.net.http.HttpTimeoutException;
import java.util.Optional;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * An Epic call that failed for a reason an operator has to see (ADR 0012, "Log",
 * error categories): which call, the {@code error.category} it is reported under, and the
 * {@code error.code}.
 *
 * <ul>
 *   <li>{@code network}: no answer — a connect or read timeout, or no connection at all, or a
 *       body that stopped arriving or whose connection dropped once the headers were in;
 *   <li>{@code server}: Epic answered {@code 5xx};
 *   <li>{@code cert/auth}: Epic refused our own credential, {@code invalid_client} or a
 *       {@code 401} at the token endpoint — likely a key or a registration problem;
 *   <li>{@code data}: Epic answered, with something that is not what was asked for.
 * </ul>
 *
 * <p>The first two are Epic being unavailable (D23): the clinician is told to try again rather
 * than that they were refused. The other two are refusals, which an operator must still follow
 * up.
 *
 * <p>An authentication failure, so wherever it is thrown on the Epic Login path — at the
 * authorize hop, at the callback, or deep inside the token call or the {@code id_token}
 * decoder, as some other failure's cause — Spring Security hands it to the Epic failure handler,
 * which finds it with {@link #in}. It carries no cause and no text from Epic: an Epic error
 * body, or a URL's query, belongs in no log and no trace.
 */
public final class EpicOutboundException extends AuthenticationException {

    private final EpicOutboundCall call;

    private final ErrorCategory category;

    private final int code;

    EpicOutboundException(EpicOutboundCall call, ErrorCategory category, int code) {
        super("Epic outbound call failed");
        this.call = call;
        this.category = category;
        this.code = code;
    }

    /**
     * {@code failure}, from {@code call}, as ADR 0012's error categories have it
     * ({@link OutboundCallFailure}): no answer — before the headers, or while reading the body —
     * is {@code network}, a {@code 5xx} is {@code server}, a {@code 401} is {@code cert/auth},
     * and anything else Epic answered — another {@code 4xx}, or a body that arrived and could not
     * be read — is {@code data}. Only the category and the code are kept, so the call is not
     * timed: a body the read timeout cut off, which only the time would tell from a dropped
     * connection, is {@code network} either way.
     */
    public static EpicOutboundException of(EpicOutboundCall call, RestClientException failure) {
        OutboundCallFailure failed = failure instanceof RestClientResponseException answered
                ? OutboundCallFailure.answered(answered.getStatusCode().value())
                : OutboundCallFailure.failed(failure, HttpTimeoutException.class);
        return new EpicOutboundException(call, failed.category(), failed.code());
    }

    /** {@code call} answered {@code 2xx} with something that is not what was asked for. */
    public static EpicOutboundException malformed(EpicOutboundCall call) {
        return new EpicOutboundException(
                call, ErrorCategory.DATA, LogEvent.BAD_GATEWAY_ERROR_CODE);
    }

    /** The token endpoint refused our client assertion, with {@code status}. */
    public static EpicOutboundException credentialRefused(int status) {
        return new EpicOutboundException(EpicOutboundCall.TOKEN, ErrorCategory.CERT_AUTH, status);
    }

    /** The first Epic call failure in {@code failure}'s cause chain, if there is one. */
    public static Optional<EpicOutboundException> in(Throwable failure) {
        return CauseChain.firstOf(failure, EpicOutboundException.class);
    }

    /** Whether Epic was unavailable (D23): no answer, or a {@code 5xx}. */
    public boolean unavailable() {
        return category.unavailable();
    }

    /** Which call failed. */
    public EpicOutboundCall call() {
        return call;
    }

    /** The {@code error.category} (ADR 0012, "Log"). */
    public ErrorCategory category() {
        return category;
    }

    /** The {@code error.code}: Epic's status, or {@code 502} when it gave none worth keeping. */
    public int code() {
        return code;
    }
}

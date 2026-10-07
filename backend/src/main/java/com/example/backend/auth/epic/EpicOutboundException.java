package com.example.backend.auth.epic;

import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.ErrorCategory;
import java.util.Optional;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * An Epic call that failed for a reason an operator has to see (spec section 5, "Error
 * categories"): which call, the {@code error.category} it is reported under, and the
 * {@code error.code}.
 *
 * <ul>
 *   <li>{@code network}: no answer — a connect or read timeout, or no connection at all;
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
     * {@code failure}, from {@code call}, as section 5 categorizes it: no answer is
     * {@code network}, a {@code 5xx} is {@code server}, a {@code 401} is {@code cert/auth}, and
     * anything else Epic answered — another {@code 4xx}, or a body that could not be read — is
     * {@code data}.
     */
    public static EpicOutboundException of(EpicOutboundCall call, RestClientException failure) {
        if (failure instanceof ResourceAccessException) {
            return new EpicOutboundException(
                    call, ErrorCategory.NETWORK, LogEvent.BAD_GATEWAY_ERROR_CODE);
        }
        if (failure instanceof HttpServerErrorException server) {
            return new EpicOutboundException(
                    call, ErrorCategory.SERVER, server.getStatusCode().value());
        }
        if (failure instanceof HttpClientErrorException.Unauthorized unauthorized) {
            return new EpicOutboundException(
                    call, ErrorCategory.CERT_AUTH, unauthorized.getStatusCode().value());
        }
        if (failure instanceof HttpClientErrorException client) {
            return new EpicOutboundException(
                    call, ErrorCategory.DATA, client.getStatusCode().value());
        }
        return malformed(call);
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
        return firstInChain(failure, EpicOutboundException.class);
    }

    /**
     * The first {@code type} in {@code failure}'s cause chain, {@code failure} itself included,
     * if there is one. A cause that points to itself ends the walk.
     */
    public static <T extends Throwable> Optional<T> firstInChain(
            Throwable failure, Class<T> type) {
        for (Throwable link = failure; link != null; link = link.getCause()) {
            if (type.isInstance(link)) {
                return Optional.of(type.cast(link));
            }
            if (link.getCause() == link) {
                break;
            }
        }
        return Optional.empty();
    }

    /** Whether Epic was unavailable (D23): no answer, or a {@code 5xx}. */
    public boolean unavailable() {
        return category == ErrorCategory.NETWORK || category == ErrorCategory.SERVER;
    }

    /** Which call failed. */
    public EpicOutboundCall call() {
        return call;
    }

    /** The section 5 {@code error.category}. */
    public ErrorCategory category() {
        return category;
    }

    /** The {@code error.code}: Epic's status, or {@code 502} when it gave none worth keeping. */
    public int code() {
        return code;
    }
}

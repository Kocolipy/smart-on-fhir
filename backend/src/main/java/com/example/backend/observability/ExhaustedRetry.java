package com.example.backend.observability;

import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Whether the current request's write was the database's deadlock victim again on its retry —
 * ADR 0013's as-13 second deadlock, the one failure answered {@code 503 Service Unavailable} with
 * {@code Retry-After: 1} rather than the generic {@code 500}.
 *
 * <p>The deadlock retry ({@code DeadlockRetryAdapter}) marks the request as the second deadlock
 * propagates, and the request's fault handler reads the mark with the failure it is answering.
 * A deadlock nothing retried — on a path ADR 0013 does not retry, or a write that joined a
 * transaction already open — carries no mark and stays the generic {@code 500}, so the
 * {@code 503} is exactly as-13's and only a retried write documents it.
 *
 * <p>Kept on the request, as {@link RequestFault} is: the mark is set deep in the call and read
 * by a handler after it has unwound, and it is never written into a record.
 */
public final class ExhaustedRetry {

    /** The {@code Retry-After} the {@code 503} carries: the write may be sent again in a second. */
    public static final String RETRY_AFTER_SECONDS = "1";

    static final String EXHAUSTED_ATTRIBUTE = ExhaustedRetry.class.getName() + ".exhausted";

    private ExhaustedRetry() {
    }

    /**
     * The headers the {@code 503} carries, whichever chain answers it: {@code Retry-After} set to
     * {@link #RETRY_AFTER_SECONDS}. A new, writable instance on every call.
     */
    public static HttpHeaders retryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        return headers;
    }

    /** Marks the request being served on this thread, if there is one. */
    public static void recorded() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes current) {
            current.getRequest().setAttribute(EXHAUSTED_ATTRIBUTE, Boolean.TRUE);
        }
    }

    /**
     * Whether {@code fault} is the deadlock the current request's retried write met again on its
     * retry, and so is answered {@code 503} with {@link #RETRY_AFTER_SECONDS}.
     */
    public static boolean answers(Throwable fault) {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes current
                && Boolean.TRUE.equals(current.getRequest().getAttribute(EXHAUSTED_ATTRIBUTE))
                && DatabaseFailureCategory.deadlock(fault);
    }
}

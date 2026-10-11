package com.example.backend.observability;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Whether the session a request brought was ended during that request, carried from the filter
 * that ended it to the refusal record written for the request.
 *
 * <p>The absolute session lifetime is enforced by a filter inside the security chain, which ends
 * an expired session before anything reads it, so by the time the chain's entry point refuses
 * the request nothing about the request says a session was there. The filter marks the request
 * here, and the entry point reads the mark to record {@code session-expired} rather than
 * {@code no-session}. The filter is in the web layer and the entry point in the security
 * configuration, and one adapter layer does not depend on another, so the mark lives here,
 * beside {@link RequestActor}, the other mark a request carries out of the chain.
 */
public final class EndedSession {

    static final String ATTRIBUTE = EndedSession.class.getName() + ".ended";

    private EndedSession() {
    }

    /** The session {@code request} brought has been ended by this request. */
    public static void mark(HttpServletRequest request) {
        request.setAttribute(ATTRIBUTE, Boolean.TRUE);
    }

    /** Whether {@link #mark} was called for {@code request}. */
    public static boolean markedOn(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) != null;
    }
}

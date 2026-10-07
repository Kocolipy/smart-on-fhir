package com.example.backend.auth.epic;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;

/**
 * The {@code launch} value Epic opened the launch URL with, held in the HTTP session for the next
 * step only (flow step 1): the authorize hop takes it, and it is gone from the session once it
 * has been sent on to Epic's authorization endpoint.
 *
 * <p>It is opaque to us — it carries the clinician's Hyperspace context, which is why no login
 * hint is sent — and it is never logged or audited (D22).
 */
public final class EpicLaunchContext {

    /** The session attribute the value is held under between the launch and the authorize hop. */
    static final String ATTRIBUTE = EpicLaunchContext.class.getName() + ".LAUNCH";

    private EpicLaunchContext() {
    }

    /** Holds {@code launch} in {@code session} for the authorize hop. */
    public static void hold(HttpSession session, String launch) {
        session.setAttribute(ATTRIBUTE, launch);
    }

    /** Takes the held value out of the request's session, or empty when none is held. */
    public static Optional<String> take(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(ATTRIBUTE) instanceof String launch)) {
            return Optional.empty();
        }
        session.removeAttribute(ATTRIBUTE);
        return Optional.of(launch);
    }
}

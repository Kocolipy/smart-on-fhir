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
 *
 * <p>Unlike the pending authorization request, which waits under its own Redis key and is taken
 * atomically (D27), this lives in the session and its take is not atomic: two concurrent
 * authorize hops in one session could each send it on. That is deliberate, not an oversight. The
 * launch is no credential of ours — Epic alone decides what it is worth — and each such hop holds
 * its pending request under the same session-keyed entry, the later replacing the earlier, so
 * still only one callback can complete. A refused or unavailable Login ends the session (D24),
 * which takes this value with it and leaves the pending entry unreachable until it expires.
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

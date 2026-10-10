package com.example.backend.auth.config;

import com.example.backend.auth.domain.SignedInSession;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;

/**
 * The servlet container's session as {@link SignedInSession} reads and writes it, for the
 * security chain's own filters ({@link AbsoluteSessionLifetimeFilter},
 * {@link SessionUserLogContextFilter}).
 *
 * <p>Delegation only; the shape is the module's. The web adapters hold a copy of their own
 * ({@code auth.controller.HttpSessionAttributes}), because one adapter layer does not depend on
 * another.
 *
 * @param session the session to read and write
 */
record HttpSessionAttributes(HttpSession session) implements SignedInSession.Attributes {

    /** {@code session} as the signed-in session module sees it. */
    static SignedInSession signedIn(HttpSession session) {
        return SignedInSession.of(new HttpSessionAttributes(session));
    }

    @Override
    public Object attribute(String name) {
        return session.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        session.setAttribute(name, value);
    }

    @Override
    public Instant createdAt() {
        return Instant.ofEpochMilli(session.getCreationTime());
    }

    @Override
    public Duration idleBound() {
        return Duration.ofSeconds(session.getMaxInactiveInterval());
    }

    /** In whole seconds, the servlet API's unit. */
    @Override
    public void setIdleBound(Duration idleBound) {
        session.setMaxInactiveInterval((int) idleBound.toSeconds());
    }
}

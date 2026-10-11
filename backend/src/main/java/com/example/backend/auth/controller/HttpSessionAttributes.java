package com.example.backend.auth.controller;

import com.example.backend.auth.domain.SignedInSession;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;

/**
 * The servlet container's session as {@link SignedInSession} reads and writes it, for the web
 * adapters: every handler here and in {@code auth.epic.controller}, and the application chain's
 * session filters ({@link AbsoluteSessionLifetimeFilter}, {@link SessionUserLogContextFilter}),
 * asks the signed-in session its questions through this, and none reads the attribute map itself.
 *
 * <p>Delegation only; the shape is the module's. It is the one servlet adapter: the filters that
 * need it are the web layer's own beans, which the security configuration places by name, so no
 * other adapter layer needs a copy. Spring Session's stored session has the other adapter,
 * {@code auth.infrastructure.session.SpringSessionAttributes}.
 *
 * @param session the session to read and write
 */
public record HttpSessionAttributes(HttpSession session) implements SignedInSession.Attributes {

    /** {@code session} as the signed-in session module sees it. */
    public static SignedInSession signedIn(HttpSession session) {
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

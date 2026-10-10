package com.example.backend.auth.infrastructure.session;

import com.example.backend.auth.domain.SignedInSession;
import java.time.Duration;
import java.time.Instant;
import org.springframework.session.Session;

/**
 * A Spring Session {@link Session}, found in the store by id, as {@link SignedInSession} reads
 * and writes it: how the store adapters here ({@link EpicTokensAdapter},
 * {@link RoleMappingSessionsAdapter}) ask a session they did not receive with a request its
 * questions.
 *
 * <p>Delegation only; the shape is the module's. A read-only view: reading an attribute touches
 * neither the session's last-access time nor the store, so asking never keeps a session alive,
 * and a write is refused rather than made to a copy the store would never see.
 *
 * @param session the stored session to read
 */
record SpringSessionAttributes(Session session) implements SignedInSession.Attributes {

    private static final String READ_ONLY = "a stored session is read, never written, here";

    /** {@code session} as the signed-in session module sees it. */
    static SignedInSession signedIn(Session session) {
        return SignedInSession.of(new SpringSessionAttributes(session));
    }

    @Override
    public Object attribute(String name) {
        return session.getAttribute(name);
    }

    /** Refused: a stored session is only read here, and a change would never reach the store. */
    @Override
    public void setAttribute(String name, Object value) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public Instant createdAt() {
        return session.getCreationTime();
    }

    @Override
    public Duration idleBound() {
        return session.getMaxInactiveInterval();
    }

    /** Refused: a stored session is only read here, and a change would never reach the store. */
    @Override
    public void setIdleBound(Duration idleBound) {
        throw new UnsupportedOperationException(READ_ONLY);
    }
}

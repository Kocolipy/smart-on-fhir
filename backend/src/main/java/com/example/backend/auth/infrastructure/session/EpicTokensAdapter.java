package com.example.backend.auth.infrastructure.session;

import com.example.backend.auth.domain.AbsoluteSessionLifetimePolicy;
import com.example.backend.auth.domain.EpicTokenSet;
import com.example.backend.auth.domain.EpicTokens;
import java.time.Clock;
import java.util.Optional;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

/**
 * Outbound adapter for {@link EpicTokens}, over Spring Session's repository: the tokens are the
 * session's own attribute, read from the session the id names.
 *
 * <p>The repository already answers nothing for a session that is gone — deleted by logout or a
 * revocation, invalidated by the next launch, or past its idle timeout. The absolute session
 * lifetime is the one end it does not know: that bound is enforced on a session's next request
 * ({@code AbsoluteSessionLifetimeFilter}), and a session holding Epic tokens is stored no longer
 * than it, but the store measures that on its own time and keeps an expired session's key a
 * grace period past it. The same policy, on the same injected clock, is applied here, so a
 * session that has ended by its age hands out no tokens whatever the store still holds.
 *
 * <p>A read only: it neither touches the session's last-access time nor saves it, so asking for
 * the tokens never keeps a session alive.
 */
@Component
public class EpicTokensAdapter implements EpicTokens {

    private final SessionRepository<? extends Session> sessions;

    private final AbsoluteSessionLifetimePolicy absoluteLifetime;

    private final Clock clock;

    public EpicTokensAdapter(SessionRepository<? extends Session> sessions,
            AbsoluteSessionLifetimePolicy absoluteLifetime, Clock clock) {
        this.sessions = sessions;
        this.absoluteLifetime = absoluteLifetime;
        this.clock = clock;
    }

    @Override
    public Optional<EpicTokenSet> forSession(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        Session session = sessions.findById(sessionId);
        if (session == null
                || absoluteLifetime.isExpired(session.getCreationTime(), clock.instant())) {
            return Optional.empty();
        }
        return session.getAttribute(SESSION_ATTRIBUTE) instanceof EpicTokenSet tokens
                ? Optional.of(tokens)
                : Optional.empty();
    }
}

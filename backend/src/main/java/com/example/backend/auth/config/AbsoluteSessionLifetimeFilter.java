package com.example.backend.auth.config;

import com.example.backend.auth.domain.AbsoluteSessionLifetimePolicy;
import com.example.backend.auth.domain.EpicTokens;
import com.example.backend.auth.domain.SignedInSession;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces {@link AbsoluteSessionLifetimePolicy} on every request that carries
 * an existing session.
 *
 * <p>Runs before the security context is loaded from the session (see its
 * placement in {@link SecurityConfig#securityFilterChain}), so an expired
 * session is invalidated and its authentication cleared before anything later
 * in the chain reads either — the rest of the chain sees exactly what it would
 * see if the browser had presented no session cookie at all, and a protected
 * path answers with the ordinary 401 rather than a distinct "session too old"
 * response.
 *
 * <p>Does nothing when there is no existing session ({@code getSession(false)})
 * or the session has not outlived the policy: no session is created here, and
 * an unexpired one is left alone, idle-timeout renewal included — with one
 * exception. A session holding Epic tokens ({@link EpicTokens}) has its idle
 * bound cut to what remains of its lifetime once that is the shorter
 * ({@link SignedInSession#boundByLifetime}, the same cut the Epic sign-in made),
 * so the renewal this request makes cannot keep the session, and the tokens on
 * it, in the store past the lifetime's end (ADR 0013, D29). Far from the end the
 * idle bound is untouched, and a session without Epic tokens is never touched.
 *
 * <p>Both questions — has the session outlived the policy, and whose was it — are
 * asked of {@link SignedInSession}, so the age and the owner are read here exactly
 * as everywhere else.
 */
public class AbsoluteSessionLifetimeFilter extends OncePerRequestFilter {

    /**
     * Set on a request whose session this filter ended, so the entry point that answers it can
     * say the session expired rather than that there never was one.
     */
    static final String ENDED_ATTRIBUTE = AbsoluteSessionLifetimeFilter.class.getName() + ".ended";

    /** The cause a session-end record names for a session ended here. */
    static final String CAUSE = "absolute-lifetime";

    private static final Logger log = LoggerFactory.getLogger(AbsoluteSessionLifetimeFilter.class);

    private final AbsoluteSessionLifetimePolicy policy;
    private final Clock clock;

    public AbsoluteSessionLifetimeFilter(AbsoluteSessionLifetimePolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            SignedInSession signedIn = HttpSessionAttributes.signedIn(session);
            Instant now = clock.instant();
            if (signedIn.hasOutlived(policy, now)) {
                // Read before the session is gone: the principal index is the only place the
                // account's stable id is, and the logging context does not have it yet — this
                // filter runs ahead of the one that puts it there.
                UUID owner = signedIn.owner().orElse(null);
                session.invalidate();
                SecurityContextHolder.clearContext();
                request.setAttribute(ENDED_ATTRIBUTE, Boolean.TRUE);
                recordEnded(owner);
            } else {
                // This request renews the session for its idle bound again, so near the
                // lifetime's end that bound would keep a session holding Epic's tokens, and the
                // tokens with it, stored past the end. Cut it back to what remains (ADR 0013, D29).
                signedIn.boundByLifetime(policy, now);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * One {@code session-end} record, naming whose session it was by stable id — absent for a
     * session that was never signed in to, which belonged to nobody.
     */
    private static void recordEnded(UUID owner) {
        try (LogContext.Scope scope = LogContext.userId(owner)) {
            LogEvent.success(log, Operation.SESSION_END, Category.PROCESS, Type.END)
                    .addKeyValue(LogEvent.REASON, CAUSE)
                    .log();
        }
    }
}

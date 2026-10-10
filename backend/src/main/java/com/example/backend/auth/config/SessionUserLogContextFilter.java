package com.example.backend.auth.config;

import com.example.backend.auth.domain.SignedInSession;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.RequestActor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the authenticated caller's stable id in the logging context as
 * {@code user.id} for the rest of the request, so every record the request emits
 * says whose request it was.
 *
 * <p>The id is the one the login wrote into the session's principal index — the
 * SCIM resource id, which survives a later userName change and names nobody to a
 * reader without database access. It is never the {@code Authentication}'s name,
 * which is the userName and is exactly what must not be logged.
 *
 * <p>Placed after the security context is loaded from the session (see
 * {@link SecurityConfig#securityFilterChain}), and it requires both halves: an
 * authenticated context AND a session that identifies its owner
 * ({@link SignedInSession#owner}). A session that carries only one of them — or an
 * index value that is not a UUID — puts nothing in the context, which is the same
 * answer an anonymous request gets.
 *
 * <p>The id is also marked on the request ({@link RequestActor}), so the request record
 * {@code RequestIdFilter} writes outside the security chain names the same caller.
 */
public class SessionUserLogContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID userId = sessionUserId(request);
        // The request record is written outside the chain, after this scope has closed.
        RequestActor.user(request, userId);
        // A null id leaves user.id absent for the chain, the same answer an anonymous
        // request gets; one scope covers both cases.
        try (LogContext.Scope scope = LogContext.userId(userId)) {
            chain.doFilter(request, response);
        }
    }

    private static UUID sessionUserId(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        HttpSession session = request.getSession(false);
        return session == null
                ? null
                : HttpSessionAttributes.signedIn(session).owner().orElse(null);
    }
}

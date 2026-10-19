package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.EpicRoutes;
import com.example.backend.web.ErrorNamespaces;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes the Epic Login routes unreachable while {@code APP_EPIC_ENABLED} is off, mirroring the
 * SCIM release gate: the whole of what {@link EpicLoginOff} adds to the application chain.
 *
 * <p>Ordered first in the application chain, ahead of the session, CSRF and authorization
 * filters, so a closed gate is the answer to every request under {@code /api/auth/epic} whatever
 * it carries. Without it an unknown route there would reach the chain's deny-by-default rule and
 * be answered {@code 401} or {@code 403}, which says "this exists and you may not have it"; a
 * {@code 404} says the path is not served here, which is the truth while the switch is off.
 *
 * <p>Every other request passes straight through. While the switch is on there is no gate at
 * all: {@link EpicLoginOn} adds none, so nothing here asks whether Epic Login is on.
 *
 * <p>The body is the namespace's error document for a {@code 404} ({@link ErrorNamespaces}) — the
 * application API's generic {@code ApiError} — written here because no controller and no error
 * page runs while the gate is closed, and so the answer cannot be mistaken for the single-page
 * application's HTML shell.
 */
final class EpicReleaseGateFilter extends OncePerRequestFilter {

    /** The namespace this gate owns: the exact path and everything beneath it. */
    private static final RequestMatcher NAMESPACE =
            PathPatternRequestMatcher.withDefaults().matcher(EpicRoutes.NAMESPACE + "/**");

    private final ErrorNamespaces errorNamespaces;

    EpicReleaseGateFilter(ErrorNamespaces errorNamespaces) {
        this.errorNamespaces = errorNamespaces;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!NAMESPACE.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        errorNamespaces.refuse(request, response, HttpServletResponse.SC_NOT_FOUND);
    }
}

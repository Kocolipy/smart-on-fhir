package com.example.backend.auth.epic.config;

import com.example.backend.auth.epic.EpicReleaseGate;
import com.example.backend.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes the Epic Login routes unreachable while {@code APP_EPIC_ENABLED} is off, mirroring the
 * SCIM release gate.
 *
 * <p>Ordered first in the application chain, ahead of the session, CSRF and authorization
 * filters, so a closed gate is the answer to every request under {@code /api/auth/epic} whatever
 * it carries. Without it an unknown route there would reach the chain's deny-by-default rule and
 * be answered {@code 401} or {@code 403}, which says "this exists and you may not have it"; a
 * {@code 404} says the path is not served here, which is the truth while the switch is off.
 *
 * <p>Every other request passes straight through, as does every request while the gate is
 * open.
 *
 * <p>The body is the application API's generic refusal, {@link ApiError#of} for a {@code 404},
 * serialized here because no controller and no error page runs while the gate is closed — and
 * so the answer cannot be mistaken for the single-page application's HTML shell.
 */
public class EpicReleaseGateFilter extends OncePerRequestFilter {

    /** The namespace this gate owns: the exact path and everything beneath it. */
    static final String EPIC_NAMESPACE = "/api/auth/epic/**";

    private static final String GATE_CLOSED_BODY =
            JsonMapper.builder().build().writeValueAsString(ApiError.of(HttpStatus.NOT_FOUND));

    private static final RequestMatcher NAMESPACE =
            PathPatternRequestMatcher.withDefaults().matcher(EPIC_NAMESPACE);

    private final EpicReleaseGate gate;

    public EpicReleaseGateFilter(EpicReleaseGate gate) {
        this.gate = gate;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (gate.open() || !NAMESPACE.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(GATE_CLOSED_BODY);
    }
}

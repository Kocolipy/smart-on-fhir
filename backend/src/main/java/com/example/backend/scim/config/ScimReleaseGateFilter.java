package com.example.backend.scim.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes the SCIM namespace unreachable while the release gate is closed.
 *
 * <p>Ordered first in the SCIM chain, ahead of authentication, so a closed gate is the
 * answer to every request whatever credential it carries. That ordering is what makes the
 * gate a statement about the namespace rather than about authorisation: a valid token, an
 * expired one and none at all all get the same answer, so the gate cannot be probed for
 * whether the interface exists behind it.
 *
 * <p><strong>{@code 404}, not {@code 403} or {@code 503}.</strong> A {@code 403} would say
 * "this exists and you may not have it" and a {@code 503} would say "come back later"; both
 * disclose a surface that is not being offered. A {@code 404} says the path is not served
 * here, which is the truth while the gate is closed.
 *
 * <p>The body is a SCIM error document rather than empty, for one reason that is worth the
 * few bytes: the gated answer must be impossible to confuse with the SPA index, which is what
 * a reserved-path mistake would produce with a {@code 200}. A caller can tell the two apart from the body alone.
 */
class ScimReleaseGateFilter extends OncePerRequestFilter {

    /**
     * The refusal, written out rather than rendered through the SCIM error mapper: that
     * mapper is a controller advice, and no controller runs while the gate is closed.
     */
    private static final String GATE_CLOSED_BODY = """
            {"schemas":["urn:ietf:params:scim:api:messages:2.0:Error"],\
            "status":"404",\
            "detail":"This deployment does not serve a SCIM interface."}""";

    private final ScimReleaseGate gate;

    ScimReleaseGateFilter(ScimReleaseGate gate) {
        this.gate = gate;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (gate.open()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/scim+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(GATE_CLOSED_BODY);
    }
}

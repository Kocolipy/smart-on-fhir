package com.example.backend.scim.config;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.observability.AccessRefusalLog;
import com.example.backend.observability.RouteTemplates;
import com.example.backend.scim.application.ConnectorAuthenticationService;
import com.example.backend.web.ErrorDocumentFilter;
import com.example.backend.web.ErrorNamespace;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;

/**
 * The SCIM namespace's own security chain: stateless, bearer-authenticated, and
 * ordered ahead of the application chain.
 *
 * <p>A second chain rather than more rules in the first one, because the two
 * namespaces disagree about nearly everything a chain configures. The application
 * chain is session-backed with a session-bound CSRF synchronizer token and an SPA
 * fallback; the SCIM chain
 * is a stateless API authenticated by a bearer credential on every request. Folding
 * the second into the first would mean every rule carrying a path condition, and the
 * SPA's session behaviour reaching an endpoint no browser calls.
 *
 * <p><strong>Ordering is load-bearing.</strong> This chain declares a
 * {@code securityMatcher}; the application chain matches everything and must
 * therefore be last, or it would answer SCIM requests with the SPA's rules — and
 * {@code SpaRoutes} would treat an unknown SCIM path as a client-side route and
 * return the HTML shell with a {@code 200}. {@code /scim} is a reserved server path
 * for that reason, so the two mechanisms agree even if the ordering is ever changed.
 */
@Configuration
public class ScimSecurityConfig {

    /** Ahead of the application chain, which matches every remaining request. */
    public static final int SCIM_CHAIN_ORDER = 1;

    /** The namespace this chain owns, exact path and everything beneath it. */
    private static final String SCIM_NAMESPACE = ScimErrorDocument.PATH + "/**";

    /**
     * The one source of random material for connector tokens.
     *
     * <p>The no-argument constructor, which yields the platform's strongest seeded
     * generator and reseeds itself; {@code SecureRandom.getInstanceStrong()} is
     * deliberately not used, because on Linux it can block on entropy and would turn
     * minting a token into an unbounded wait on a request thread.
     */
    @Bean
    public SecureRandom connectorTokenRandom() {
        return new SecureRandom();
    }

    /**
     * The SCIM namespace's error document, contributed to the one selection every refusing layer
     * asks ({@code ErrorNamespaces}): the {@code sendError} adapter renders the dispatcher's
     * refusals in the namespace with it.
     */
    @Bean
    public ErrorNamespace scimErrorNamespace() {
        return new ScimErrorDocument();
    }

    /**
     * Whether this deployment serves SCIM at all, from external configuration.
     *
     * <p>Defaulted here rather than in {@code application.yaml}, so the default is a fact about
     * the code rather than about a file a deployment may replace wholesale.
     *
     * <p><strong>The default is {@code true}.</strong> An operator who wants the SCIM interface
     * off — because this deployment authenticates by password only and has no connectors — sets
     * {@code APP_SCIM_ENABLED=false}, and the filter that enforces it answers {@code 404} to every request in the namespace whatever credential it carries.
     */
    @Bean
    public ScimReleaseGate scimReleaseGate(
            @Value("${app.scim.enabled:true}") boolean enabled) {
        return new ScimReleaseGate(enabled);
    }

    /**
     * Ahead of the application chain, which matches every remaining request.
     *
     * <p>The annotation goes on the {@code @Bean} METHOD and not on this class:
     * Spring Security orders {@code SecurityFilterChain} beans by their own
     * {@code @Order}, and a class-level one leaves the bean at lowest precedence —
     * which registers the catch-all chain first and makes this one unreachable. That
     * is a startup failure rather than a silent misconfiguration, and
     * {@code ScimSecurityChainOrderTests} pins the resulting order so a future edit
     * cannot invert it back.
     */
    @Bean
    @Order(ScimSecurityConfig.SCIM_CHAIN_ORDER)
    public SecurityFilterChain scimSecurityFilterChain(
            HttpSecurity http,
            ConnectorAuthenticationService connectors,
            ScimReleaseGate releaseGate,
            AccessRefusalLog accessRefusalLog,
            AuditTrail audit,
            RouteTemplates routeTemplates,
            ErrorDocumentFilter errorDocuments) {
        AuthenticationEntryPoint challenge = new ScimBearerEntryPoint(accessRefusalLog);
        // One answer to a token lacking a Permission, whichever layer refused it: the bearer
        // filter's backstop and the chain's access-denied handler share this instance.
        ScimInsufficientPermissions insufficientPermissions =
                new ScimInsufficientPermissions(accessRefusalLog, audit, routeTemplates);

        // A stateless bearer API has no CSRF exposure to protect, because the
        // credential is not ambient. A browser cannot make an authenticated SCIM
        // request on a user's behalf: there is no cookie or session this chain
        // accepts, and the token has to be placed in a header by the caller, which a
        // cross-site form cannot do. The application chain's synchronizer token is
        // untouched and still guards every cookie-authenticated path — which is what
        // be-csrf-disabled is about, and why this is the one place it does not apply.
        // Written as its own statement so the suppression sits on the flagged line
        // rather than several lines above a builder chain.
        http.csrf(csrf -> csrf.disable()); // nosemgrep: be-csrf-disabled

        return http
                .securityMatcher(SCIM_NAMESPACE)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .anonymous(anonymous -> anonymous.disable())
                // Request-scoped rather than session-backed: an authenticated SCIM
                // request leaves nothing behind, so a token cannot be exchanged for a
                // longer-lived session by presenting it once.
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Ordered ahead of every other filter in this chain, including
                // authentication: while the gate is closed the namespace answers 404 to
                // every request whatever credential it carries, so the surface cannot be
                // probed for existence. WebAsyncManagerIntegrationFilter is the first
                // filter of a standard chain that reads the request, so naming it is how
                // "first" is stated without depending on the order these builder calls
                // happen to be in. Spring Security places DisableEncodeUrlFilter ahead of
                // it, which only suppresses session ids in response URLs and so decides
                // nothing before the gate; ScimSecurityChainOrderTests pins that position.
                .addFilterBefore(
                        new ScimReleaseGateFilter(releaseGate),
                        WebAsyncManagerIntegrationFilter.class)
                // Next, ahead of authentication and of the header writer: an oversized body is
                // refused on its declared length before anything reads it, and the
                // dispatcher's own refusals (404, 405, 406, 415) are rendered as SCIM error
                // documents by the one sendError adapter every namespace shares. The container
                // runs the same instance ahead of both chains, and it runs once per request; here
                // it keeps this chain complete on its own. Ahead of HeaderWriterFilter so its
                // response wrapper sits inside the adapter's and still writes the security
                // headers when the dispatcher sends an error. ScimSecurityChainOrderTests pins
                // both positions.
                .addFilterBefore(new ScimRequestBodyLimitFilter(), HeaderWriterFilter.class)
                .addFilterBefore(errorDocuments, HeaderWriterFilter.class)
                .addFilterBefore(
                        new ScimBearerAuthenticationFilter(
                                connectors, accessRefusalLog, insufficientPermissions),
                        AuthorizationFilter.class)
                // A handler's own Permission declaration refusing a token propagates out of the
                // dispatcher to here, and is answered exactly as the bearer filter's backstop
                // refusal is: the same 403 challenge, refusal record and audit row.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(challenge)
                        .accessDeniedHandler(insufficientPermissions))
                // Every request needs a valid token, discovery included (ADR 0015). What a
                // request needs BEYOND a token is declared by the handler that serves it, with
                // method security; the bearer filter's rule is the backstop. No route is public.
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().authenticated())
                .build();
    }
}

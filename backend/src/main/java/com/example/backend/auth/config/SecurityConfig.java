package com.example.backend.auth.config;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.auth.domain.AbsoluteSessionLifetimePolicy;
import com.example.backend.auth.epic.EpicReleaseGate;
import com.example.backend.auth.epic.config.EpicReleaseGateFilter;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.AccessRefusalLog;
import com.example.backend.observability.RouteTemplates;
import com.example.backend.scim.domain.PasswordNormalization;
import com.example.backend.web.SpaRoutes;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Last, because this chain has no {@code securityMatcher} and therefore matches
     * whatever an earlier chain did not. The SCIM chain is ordered ahead of it — see
     * {@code ScimSecurityConfig}.
     */
    public static final int APPLICATION_CHAIN_ORDER = 2;

    /** Baseline access: active, and no password change required (see LoginIdentityService). */
    private static final String USER_ROLE = "USER";

    /**
     * The single-page application loads only same-origin module scripts and a
     * same-origin stylesheet, so everything can be locked to {@code 'self'}.
     * Inline styles stay allowed because component libraries inject style
     * elements at runtime; inline script is not allowed at all.
     */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    private static final String PERMISSIONS_POLICY = String.join(", ",
            "geolocation=()",
            "camera=()",
            "microphone=()",
            "payment=()",
            "usb=()");

    /**
     * Argon2id as the only encoder, at the parameters this ticket specifies
     * ({@code m=19456} KiB, {@code t=2}, {@code p=1}) — none of Spring
     * Security's named defaults match that memory cost, so the parameters are
     * given explicitly rather than through
     * {@code Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()} (which uses
     * {@code m=16384}). Salt and hash length (16 and 32 bytes) are the same
     * values every {@code defaultsForSpringSecurity_*} factory uses; the ticket
     * does not name them, so there is nothing to deviate from.
     *
     * <p>Wrapped in a single-entry {@link DelegatingPasswordEncoder} — the
     * bracketed {@code {argon2id}} prefix on a stored hash is that wrapper's
     * behaviour, not the raw {@link Argon2PasswordEncoder}'s, so it is what
     * this ticket's acceptance criterion actually needs. No BCrypt — or any
     * other — decoder shares the registry: this service ships no user data yet,
     * so there is no previously-issued hash needing one, and the acceptance
     * criteria for this ticket says none may remain registered.
     *
     * <p>Normalized first: every password is put through {@link PasswordNormalization} before it
     * is hashed or compared, so the stored credential, a login, and a password-history check all
     * see the same form of the same password. Done here, in the one encoder every path uses,
     * rather than at each caller — a caller that forgot would store a credential its own login
     * path could not match.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        PasswordEncoder argon2id = new DelegatingPasswordEncoder(
                "argon2id", Map.of("argon2id", new Argon2PasswordEncoder(16, 32, 1, 19456, 2)));
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return argon2id.encode(PasswordNormalization.normalize(rawPassword));
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return argon2id.matches(
                        PasswordNormalization.normalize(rawPassword), encodedPassword);
            }

        };
    }

    @Bean
    public AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Session fixation protection for the login path. The filter chain cannot
     * apply this itself: nothing in the chain authenticates, so no chain-level
     * {@code sessionFixation()} setting would ever run. SessionEstablishment, the
     * step every Login ends in, invokes this strategy instead, which rotates the
     * id of a session the caller already held before authenticating.
     */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    /**
     * The Synchronizer Token Pattern: the token lives in the HTTP session and
     * nowhere else, so it is bound to that session and ends with it. The SPA
     * obtains it from {@code GET /api/auth/csrf} — never from a cookie, which the
     * standard prohibits outright (see {@code /docs/adr/0009-csrf-synchronizer-token.md}).
     *
     * <p>Shared with SessionEstablishment for the same reason as the security context
     * repository: the chain validates the token on every unsafe request and the
     * login path discards the pre-login one, so both halves must read and write
     * the same session attribute through the same configuration.
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    /**
     * The fixed ceiling on a session's life, independent of how recently it was
     * used. Configured separately from {@code server.servlet.session.timeout}
     * (the idle timeout), which this does not replace or interact with.
     */
    @Bean
    public AbsoluteSessionLifetimePolicy absoluteSessionLifetimePolicy(
            @Value("${app.session.absolute-lifetime}") Duration maxLifetime) {
        return new AbsoluteSessionLifetimePolicy(maxLifetime);
    }

    @Bean
    public AbsoluteSessionLifetimeFilter absoluteSessionLifetimeFilter(
            AbsoluteSessionLifetimePolicy absoluteSessionLifetimePolicy, Clock clock) {
        return new AbsoluteSessionLifetimeFilter(absoluteSessionLifetimePolicy, clock);
    }

    /**
     * The application chain, ordered after the SCIM chain because it matches every
     * request that chain did not. Spring requires the catch-all chain to be last;
     * without the order, a SCIM request could be answered by the SPA's session and
     * CSRF rules instead of by bearer authentication.
     */
    @Bean
    @Order(SecurityConfig.APPLICATION_CHAIN_ORDER)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            AbsoluteSessionLifetimeFilter absoluteSessionLifetimeFilter,
            AccessRefusalLog accessRefusalLog,
            AuditTrail auditTrail,
            RouteTemplates routeTemplates,
            EpicReleaseGate epicReleaseGate) {
        // Both refusals record themselves before answering. The access-denied handler is the
        // chain's one handler, and the CSRF filter answers through the same one, so a missing
        // token and a missing Permission are each recorded once, under their own reason — and
        // only the latter, an authorization decision, is audited.
        AuthenticationEntryPoint unauthorized = new SessionAuthenticationEntryPoint(accessRefusalLog);
        AccessDeniedHandler forbidden = new RefusalLoggingAccessDeniedHandler(
                accessRefusalLog, auditTrail, routeTemplates);
        return http
                // The default request handler: it XOR-masks the token it exposes
                // (so GET /api/auth/csrf never returns the same bytes twice, which
                // defeats BREACH) and unmasks the header value before comparing it
                // with the session's. Deliberately not spa(), which swaps in a
                // cookie repository and a handler that accepts the raw token.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                // Share one repository with SessionEstablishment: the login path writes the
                // authentication here and every later request reads it back from the
                // same place. Left implicit, the chain builds its own repository.
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // First, as the SCIM release gate is in its chain: while APP_EPIC_ENABLED is
                // off, every request under /api/auth/epic is answered 404 before any session,
                // CSRF or authorization filter decides anything, so the routes cannot be
                // probed for existence. Every other request passes straight through.
                .addFilterBefore(
                        new EpicReleaseGateFilter(epicReleaseGate),
                        WebAsyncManagerIntegrationFilter.class)
                // Before the context is loaded from the session, so an expired
                // session presents to the rest of the chain — including the
                // context-loading filter itself — as if no session existed.
                .addFilterBefore(
                        absoluteSessionLifetimeFilter, SecurityContextHolderFilter.class)
                // After the context is loaded, so the caller's stable id is in the logging
                // context for every record the rest of the request emits. Constructed here
                // rather than declared as a bean: a Filter bean is also registered with the
                // servlet container, where it would run first — ahead of any security context
                // — and its once-per-request marker would then skip it in this chain.
                .addFilterAfter(
                        new SessionUserLogContextFilter(), SecurityContextHolderFilter.class)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions
                                .policy(PERMISSIONS_POLICY)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                // Deny-by-default as a whole: every route is listed below with what it needs,
                // and the final rule refuses whatever no earlier rule named. The Permission
                // rules are the BACKSTOP — each handler declares the same Permission with
                // method security, which is the authority (ADR 0010) — so a route someone
                // forgot to list here is refused rather than open, and a handler someone
                // forgot to annotate is still held by its rule here. There is deliberately no
                // rule for /scim/**: a session can never reach the directory protocol, which
                // the SCIM chain serves to bearer tokens alone.
                .authorizeHttpRequests(authorize -> authorize
                        // ---- public ----
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Public because the login form needs a token before there is anyone
                        // to authenticate: a guest's call creates the session the token is
                        // bound to, and login then carries that session forward.
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        // Our public JWKS: Epic fetches it, with no session, to verify our client
                        // assertions (D14). Public keys only. While APP_EPIC_ENABLED is off the
                        // release gate above answers 404 before this rule is consulted.
                        .requestMatchers(read("/api/auth/epic/jwks.json")).permitAll()
                        // ---- self-service: authenticated, no Permission ----
                        // The whole of what a session confined by a required password change may
                        // do: read its own standing, submit the change, and log out (the token
                        // the latter two need comes from the public endpoint above). Any
                        // authenticated session, flagged or not, may reach these three.
                        .requestMatchers(read("/api/auth/me")).authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/change-password")
                                .authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/auth/logout").authenticated()
                        // ROLE_USER, not merely authenticated: a session issued while a password
                        // change was required does not hold it, so it is refused here with 403,
                        // as it is everywhere but the three routes above. Every active User
                        // without the flag holds ROLE_USER, whatever Roles it has or lacks, so
                        // a misconfigured mapping can never take these away.
                        .requestMatchers(read("/api/self")).hasRole(USER_ROLE)
                        .requestMatchers(read("/api/session")).hasRole(USER_ROLE)
                        .requestMatchers(HttpMethod.PUT, "/api/session").hasRole(USER_ROLE)
                        .requestMatchers(HttpMethod.DELETE, "/api/session").hasRole(USER_ROLE)
                        // ---- Permission per operation (backstop; see above) ----
                        .requestMatchers(read("/api/admin/accounts"))
                                .hasAuthority(Permission.USER_READ.value())
                        .requestMatchers(HttpMethod.POST,
                                        "/api/admin/accounts/*/unlock",
                                        "/api/admin/accounts/*/force-password-change")
                                .hasAuthority(Permission.USER_WRITE.value())
                        .requestMatchers(read("/api/admin/groups"), read("/api/admin/roles"))
                                .hasAuthority(Permission.GROUP_READ.value())
                        .requestMatchers(read("/api/admin/audit-events"))
                                .hasAuthority(Permission.AUDIT_READ.value())
                        .requestMatchers(read("/api/admin/connectors"))
                                .hasAuthority(Permission.CONNECTOR_READ.value())
                        .requestMatchers(HttpMethod.POST, "/api/admin/connectors")
                                .hasAuthority(Permission.CONNECTOR_WRITE.value())
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/connectors/*")
                                .hasAuthority(Permission.CONNECTOR_WRITE.value())
                        .requestMatchers(HttpMethod.POST,
                                        "/api/admin/connectors/*/tokens",
                                        "/api/admin/connectors/*/tokens/*/rotate",
                                        "/api/admin/connectors/*/tokens/*/revoke")
                                .hasAuthority(Permission.CONNECTOR_TOKEN.value())
                        .requestMatchers(read("/api/count"))
                                .hasAuthority(Permission.COUNTER_READ.value())
                        .requestMatchers(HttpMethod.POST, "/api/count/increment", "/api/count/reset")
                                .hasAuthority(Permission.COUNTER_WRITE.value())
                        // Operational telemetry (the Prometheus scrape) and every other
                        // actuator endpoint but health, on whichever port actuator is served.
                        // A scrape describes the whole service's traffic, so it needs ops:read.
                        // A connector token is not a credential here at all: the SCIM chain
                        // matches /scim/v2/** only, so a bearer header on this path reaches
                        // THIS chain, which has no bearer authentication and answers 401.
                        // Actuator endpoints are not controller methods, so this rule is their
                        // only declaration.
                        .requestMatchers("/actuator", "/actuator/**")
                                .hasAuthority(Permission.OPS_READ.value())
                        .requestMatchers(this::isFrontendGet).permitAll()
                        // The error page a refusal or a missing route is rendered through. As
                        // reachable as it was before this chain denied by default — by a session
                        // holding baseline access — so every answer keeps the body it had.
                        .requestMatchers("/error").hasRole(USER_ROLE)
                        // Everything no rule above names: an undeclared /api/ route, a method a
                        // listed route does not serve, /scim/** when the SCIM chain is off.
                        .anyRequest().denyAll())
                .build();
    }

    /**
     * A read of {@code path}: {@code GET}, and the {@code HEAD} the dispatcher answers with the
     * same handler. A rule for {@code GET} alone would leave {@code HEAD} to the deny-all rule,
     * refusing a read the operation itself serves.
     */
    private static RequestMatcher read(String path) {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(
                paths.matcher(HttpMethod.GET, path), paths.matcher(HttpMethod.HEAD, path));
    }

    /**
     * Which paths the frontend owns is SpaRoutes' knowledge; that only a GET of
     * one may skip authentication is this chain's.
     */
    private boolean isFrontendGet(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return HttpMethod.GET.matches(request.getMethod())
                && !SpaRoutes.isReservedServerPath(path);
    }
}

package com.example.backend.auth.config;

import static com.example.backend.web.ReadRequests.read;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.auth.domain.AbsoluteSessionLifetimePolicy;
import com.example.backend.auth.epic.EpicLogin;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.observability.AccessRefusalLog;
import com.example.backend.observability.RouteTemplates;
import com.example.backend.scim.domain.PasswordNormalization;
import com.example.backend.web.ErrorNamespaces;
import com.example.backend.web.SpaRoutes;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

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

    /** The Superuser authority: {@code ROLE_SUPERUSER}, held by a direct member of the Admin group. */
    private static final String SUPERUSER_ROLE = "SUPERUSER";

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

    /**
     * Password Login's authentication: one {@link DaoAuthenticationProvider} over the
     * {@code UserDetailsService}, {@code LoginIdentityService}.
     *
     * <p>A locked or deactivated User is refused by the provider's pre-authentication checks,
     * which run before the password is compared. With
     * {@code alwaysPerformAdditionalChecksOnUser} on, the provider compares the submitted password
     * anyway before rethrowing the check's refusal, so a locked or deactivated User costs the
     * same one Argon2id verification as a wrong password, an unknown name and a credentialless
     * User do (ADR 0007, uniform refusal timing), and is still refused with the right password.
     * It is Spring Security's default since 7.0, and set here regardless: a default that changed
     * back would otherwise make those refusals measurably faster with no line of ours changing.
     * {@code RefusalTimingEquivalenceTests} counts the verifications.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setAlwaysPerformAdditionalChecksOnUser(true);
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

    /**
     * The bean name of the web layer's absolute-lifetime filter. The chain places it by name, as a
     * plain {@link Filter}, so this configuration never names the type: one adapter layer does not
     * depend on another. A name that matched no bean would fail startup rather than leave the
     * chain without the filter.
     */
    static final String ABSOLUTE_SESSION_LIFETIME_FILTER = "absoluteSessionLifetimeFilter";

    /** The bean name of the web layer's logging-context filter; see the one above. */
    static final String SESSION_USER_LOG_CONTEXT_FILTER = "sessionUserLogContextFilter";

    /**
     * Keeps the absolute-lifetime filter out of the servlet container. Boot registers every
     * {@link Filter} bean there unless a registration says otherwise, and there it would run
     * outside this chain, so its once-per-request marker would then skip it here. The chain is
     * the only place it runs.
     */
    @Bean
    public FilterRegistrationBean<Filter> absoluteSessionLifetimeFilterRegistration(
            @Qualifier(ABSOLUTE_SESSION_LIFETIME_FILTER) Filter filter) {
        return chainOnly(filter);
    }

    /** Keeps the logging-context filter out of the servlet container, for the reason above. */
    @Bean
    public FilterRegistrationBean<Filter> sessionUserLogContextFilterRegistration(
            @Qualifier(SESSION_USER_LOG_CONTEXT_FILTER) Filter filter) {
        return chainOnly(filter);
    }

    private static FilterRegistrationBean<Filter> chainOnly(Filter filter) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
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
            @Qualifier(ABSOLUTE_SESSION_LIFETIME_FILTER) Filter absoluteSessionLifetimeFilter,
            @Qualifier(SESSION_USER_LOG_CONTEXT_FILTER) Filter sessionUserLogContextFilter,
            AccessRefusalLog accessRefusalLog,
            AuditTrail auditTrail,
            RouteTemplates routeTemplates,
            ErrorNamespaces errorNamespaces,
            EpicLogin epicLogin) {
        // Both refusals record themselves before answering, in the request's namespace's error
        // document. The access-denied handler is the chain's one handler, and the CSRF filter
        // answers through the same one, so a missing token and a missing Permission are each
        // recorded once, under their own reason — and only the latter, an authorization
        // decision, is audited.
        AuthenticationEntryPoint unauthorized =
                new SessionAuthenticationEntryPoint(accessRefusalLog, errorNamespaces);
        AccessDeniedHandler forbidden = new RefusalLoggingAccessDeniedHandler(
                accessRefusalLog, auditTrail, routeTemplates, errorNamespaces);
        // Epic Login (ADR 0012), whichever state APP_EPIC_ENABLED puts it in, and everything it
        // adds to this chain. Off, its release gate is ordered first, as the SCIM one is in its
        // chain, so every request under /api/auth/epic is answered 404 before any session, CSRF
        // or authorization filter decides anything. On, its filters serve the authorize hop and
        // the callback, and its four routes are public, by rules ahead of every rule below —
        // none of which names an Epic route.
        epicLogin.applyTo(http);
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
                // Before the context is loaded from the session, so an expired
                // session presents to the rest of the chain — including the
                // context-loading filter itself — as if no session existed.
                .addFilterBefore(
                        absoluteSessionLifetimeFilter, SecurityContextHolderFilter.class)
                // After the context is loaded, so the caller's stable id is in the logging
                // context for every record the rest of the request emits. Both filters are the
                // web layer's beans, kept out of the servlet container by their disabled
                // registrations above, so each runs here and only here.
                .addFilterAfter(sessionUserLogContextFilter, SecurityContextHolderFilter.class)
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
                        // without the flag holds ROLE_USER, whatever its Groups carry, so no
                        // Group can take it away.
                        .requestMatchers(read("/api/self")).hasRole(USER_ROLE)
                        // ---- Permission per operation (backstop; see above) ----
                        .requestMatchers(read("/api/admin/accounts"))
                                .hasAuthority(Permission.USER_READ.value())
                        .requestMatchers(HttpMethod.POST,
                                        "/api/admin/accounts/*/unlock",
                                        "/api/admin/accounts/*/force-password-change")
                                .hasAuthority(Permission.ACCOUNT_UNLOCK.value())
                        .requestMatchers(read("/api/admin/groups"), read("/api/admin/permissions"))
                                .hasAuthority(Permission.GROUP_READ.value())
                        .requestMatchers(read("/api/admin/audit-events"))
                                .hasAuthority(Permission.AUDIT_READ.value())
                        .requestMatchers(read("/api/admin/connectors"))
                                .hasAuthority(Permission.CONNECTOR_READ.value())
                        // ---- Superuser only (backstop; see above) ----
                        // Connector existence and credentials — create and delete, and issue,
                        // rotate and revoke a token — and a Group's Permissions. Not a Permission,
                        // so no Group can carry it and no number of Permissions stands in for it
                        // (ADR 0015).
                        .requestMatchers(HttpMethod.POST, "/api/admin/connectors")
                                .hasRole(SUPERUSER_ROLE)
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/connectors/*")
                                .hasRole(SUPERUSER_ROLE)
                        .requestMatchers(HttpMethod.POST,
                                        "/api/admin/connectors/*/tokens",
                                        "/api/admin/connectors/*/tokens/*/rotate",
                                        "/api/admin/connectors/*/tokens/*/revoke")
                                .hasRole(SUPERUSER_ROLE)
                        // A Group's Permissions: what it confers is set by the Superuser alone,
                        // and never over SCIM (ADR 0015).
                        .requestMatchers(HttpMethod.PUT, "/api/admin/groups/*/permissions")
                                .hasRole(SUPERUSER_ROLE)
                        .requestMatchers(read("/api/patient/*"))
                                .hasAuthority(Permission.PATIENT_READ.value())
                        // Not a backstop: no operation serves any other method on a patient, and
                        // the rule above matches a read only. It lets one through, for a session
                        // holding baseline access as /error is, so the dispatcher answers it 405 —
                        // the resource takes no write, as FHIR's not-supported says — rather than
                        // the final rule 403, a refusal of the caller. A carve-out from deny-by-
                        // default (ADR 0010, addendum of 2026-10-18): a patient write, when one
                        // exists, must put its own Permission rule ahead of this one.
                        .requestMatchers("/api/patient/*").hasRole(USER_ROLE)
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
     * Which paths the frontend owns is SpaRoutes' knowledge; that only a GET of
     * one may skip authentication is this chain's.
     */
    private boolean isFrontendGet(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return HttpMethod.GET.matches(request.getMethod())
                && !SpaRoutes.isReservedServerPath(path);
    }
}

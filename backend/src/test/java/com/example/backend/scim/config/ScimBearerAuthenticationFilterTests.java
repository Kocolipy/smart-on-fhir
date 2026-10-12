package com.example.backend.scim.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.TokenPermissions;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.authorization.domain.Permission;
import com.example.backend.auth.MutableClock;
import com.example.backend.observability.AccessRefusalLog;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.MetricTag;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.observability.RouteTemplates;
import com.example.backend.scim.InMemoryScimConnectorRepository;
import com.example.backend.scim.InMemoryScimConnectorTokenRepository;
import com.example.backend.scim.application.ConnectorAuthenticationService;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.ConnectorTokenSecret;
import com.example.backend.scim.domain.ScimConnector;
import com.example.backend.scim.domain.ScimConnectorToken;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * The SCIM bearer filter, driven with servlet mocks.
 *
 * <p>Two families of claim. What a credential gets — accepted, {@code invalid_token},
 * {@code insufficient_scope}, or passed along as absent — and, more importantly, that a
 * token placed anywhere other than the {@code Authorization} header is not found at
 * all. The second family is asserted by presenting a genuinely valid token through a
 * query string, a form body and a cookie in turn: each one would authenticate if the
 * filter looked there, so a passing test is evidence the filter does not.
 */
class ScimBearerAuthenticationFilterTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryScimConnectorRepository connectors =
            new InMemoryScimConnectorRepository();

    private final InMemoryScimConnectorTokenRepository tokens =
            new InMemoryScimConnectorTokenRepository();

    private final MutableClock clock = new MutableClock(NOW);

    private ScimBearerAuthenticationFilter filter;

    private ScimConnector connector;

    private MockHttpServletResponse response;

    private MockFilterChain chain;

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    @BeforeEach
    void setUp() {
        RouteTemplates routes = new RouteTemplates(() -> null);
        AccessRefusalLog refusals = new AccessRefusalLog(routes);
        filter = new ScimBearerAuthenticationFilter(
                new ConnectorAuthenticationService(connectors, tokens, clock),
                refusals,
                new ScimInsufficientPermissions(refusals, audit, routes));
        connector = connectors.seed("Okta", NOW);
        response = new MockHttpServletResponse();
        chain = new MockFilterChain();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void a_valid_write_token_authenticates_and_the_request_proceeds() throws Exception {
        String value = mint(TokenPermissions.ALL);
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        Authentication authentication = authenticationSeenBy(request);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal())
                .isEqualTo(new AuthenticatedConnector(
                        connector.id(), tokens.all().getFirst().id(),
                        TokenPermissions.of(TokenPermissions.ALL)));
        assertThat(authorities(authentication))
                .containsExactly("group:read", "group:write", "user:read", "user:write");
    }

    @Test
    void a_read_only_token_authenticates_with_its_read_permissions_alone() throws Exception {
        String value = mint(TokenPermissions.READ);
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        assertThat(authorities(authenticationSeenBy(request)))
                .containsExactly("group:read", "user:read");
    }

    /** The presented value must not be carried past its verification. */
    @Test
    void the_authentication_carries_no_credentials() throws Exception {
        String value = mint(TokenPermissions.ALL);
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        assertThat(authenticationSeenBy(request).getCredentials()).isNull();
    }

    /** The thread-local must not survive, or a pooled thread would carry the authority on. */
    @Test
    void the_security_context_is_cleared_once_the_request_is_served() throws Exception {
        String value = mint(TokenPermissions.ALL);
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void no_authorization_header_is_passed_along_for_the_chain_to_challenge() throws Exception {
        filter.doFilter(scimRequest("GET", "/scim/v2/Users"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    @Test
    void another_authentication_scheme_is_treated_as_no_credential() throws Exception {
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void an_empty_bearer_value_is_treated_as_no_credential() throws Exception {
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer    ");

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void a_malformed_bearer_value_is_an_invalid_token() throws Exception {
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer not-a-token-at-all");

        filter.doFilter(request, response, chain);

        assertInvalidToken();
    }

    @Test
    void an_expired_token_is_an_invalid_token() throws Exception {
        String value = mint(TokenPermissions.ALL, NOW.plus(Duration.ofDays(1)));
        clock.advanceBy(Duration.ofDays(2));
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        filter.doFilter(request, response, chain);

        assertInvalidToken();
    }

    @Test
    void a_revoked_token_is_an_invalid_token() throws Exception {
        String value = mint(TokenPermissions.ALL);
        tokens.save(tokens.all().getFirst().revoked(NOW));
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        filter.doFilter(request, response, chain);

        assertInvalidToken();
    }

    /** Both refusals carry the same header, so nothing distinguishes the reasons. */
    @Test
    void every_rejected_token_gets_the_same_refusal() throws Exception {
        String revokedValue = mint(TokenPermissions.ALL);
        tokens.save(tokens.all().getFirst().revoked(NOW));

        MockHttpServletResponse toRevoked = new MockHttpServletResponse();
        MockHttpServletRequest revoked = scimRequest("GET", "/scim/v2/Users");
        revoked.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + revokedValue);
        filter.doFilter(revoked, toRevoked, new MockFilterChain());

        MockHttpServletResponse toUnknown = new MockHttpServletResponse();
        MockHttpServletRequest unknown = scimRequest("GET", "/scim/v2/Users");
        unknown.addHeader(HttpHeaders.AUTHORIZATION, "Bearer nosuch.lookup");
        filter.doFilter(unknown, toUnknown, new MockFilterChain());

        assertThat(toRevoked.getStatus()).isEqualTo(toUnknown.getStatus());
        assertThat(toRevoked.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo(toUnknown.getHeader(HttpHeaders.WWW_AUTHENTICATE));
    }

    @Test
    void a_read_only_token_mutating_is_refused_for_insufficient_permissions() throws Exception {
        String value = mint(TokenPermissions.READ);
        MockHttpServletRequest request = scimRequest("POST", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        // Inside the request-id filter, as in the running chain, so the refusal's correlation id
        // is the request's own.
        new RequestIdFilter().doFilter(request, response,
                (req, res) -> filter.doFilter(req, res, chain));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"insufficient_scope\"");
        assertThat(chain.getRequest()).as("the handler must not run").isNull();
        assertThat(audit.recorded()).singleElement().satisfies(event -> {
            assertThat(event.operation()).isEqualTo(AuditOperation.ACCESS_DENIED);
            assertThat(event.actorId()).isEqualTo(connector.id());
            assertThat(event.subjectId()).isEqualTo(connector.id());
        });
        assertThat(audit.accessDeniedRequests()).singleElement().satisfies(operation -> {
            assertThat(operation.method()).isEqualTo("POST");
            // No handler mapping in this unit, so the template is the unmatched marker — but the
            // refusal carries the route the log record names, never a null.
            assertThat(operation.pathTemplate())
                    .isEqualTo(new RouteTemplates(() -> null).of(request));
            assertThat(operation.requestId()).isEqualTo(RequestIdFilter.requestId(request))
                    .isNotNull();
        });
    }

    /**
     * A Groups-only token: Group writes proceed, a User write and a Group read — which it was
     * not given — are refused before any handler runs.
     */
    @Test
    void a_group_write_only_token_writes_groups_and_nothing_else() throws Exception {
        String value = mint(Set.of(Permission.GROUP_WRITE));
        for (String[] admitted : List.of(
                new String[] {"POST", "/scim/v2/Groups"},
                new String[] {"PATCH", "/scim/v2/Groups/" + UUID.randomUUID()})) {
            MockHttpServletResponse answered = new MockHttpServletResponse();
            MockFilterChain proceeded = new MockFilterChain();
            MockHttpServletRequest request = scimRequest(admitted[0], admitted[1]);
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);
            filter.doFilter(request, answered, proceeded);
            assertThat(proceeded.getRequest()).as("%s %s", admitted[0], admitted[1]).isNotNull();
        }
        for (String[] refused : List.of(
                new String[] {"POST", "/scim/v2/Users"},
                new String[] {"GET", "/scim/v2/Groups"})) {
            MockHttpServletResponse answered = new MockHttpServletResponse();
            MockFilterChain proceeded = new MockFilterChain();
            MockHttpServletRequest request = scimRequest(refused[0], refused[1]);
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);
            filter.doFilter(request, answered, proceeded);
            assertThat(answered.getStatus()).as("%s %s", refused[0], refused[1]).isEqualTo(403);
            assertThat(proceeded.getRequest()).isNull();
        }
    }

    /**
     * A refusal is recorded once per exchange: an error dispatch of the same request refused
     * again gets neither a second log record nor a second audit event.
     */
    @Test
    void a_refusal_already_recorded_for_the_exchange_is_not_audited_again() throws Exception {
        String value = mint(TokenPermissions.READ);
        MockHttpServletRequest request = scimRequest("POST", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        filter.doFilter(request, response, chain);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(audit.of(AuditOperation.ACCESS_DENIED)).hasSize(1);
    }

    @Test
    void a_read_only_token_may_call_a_search_post() throws Exception {
        String value = mint(TokenPermissions.READ);
        MockHttpServletRequest request = scimRequest("POST", "/scim/v2/Users/.search");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void a_write_token_may_mutate() throws Exception {
        String value = mint(TokenPermissions.ALL);
        MockHttpServletRequest request = scimRequest("POST", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * A query-string token is not merely rejected, it is not looked for. Each of the
     * three cases below presents a token that authenticates perfectly well in the
     * {@code Authorization} header, so the request arriving unauthenticated is evidence
     * the filter never read that location.
     */
    @Test
    void a_token_in_the_query_string_is_not_a_credential() throws Exception {
        String value = mint(TokenPermissions.ALL);
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.setQueryString("access_token=" + value);
        request.setParameter("access_token", value);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void a_token_in_a_form_body_is_not_a_credential() throws Exception {
        String value = mint(TokenPermissions.ALL);
        MockHttpServletRequest request = scimRequest("POST", "/scim/v2/Users");
        request.setContentType("application/x-www-form-urlencoded");
        request.setParameter("access_token", value);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void a_token_in_a_cookie_is_not_a_credential() throws Exception {
        String value = mint(TokenPermissions.ALL);
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.setCookies(new Cookie("access_token", value));

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    /**
     * The scope rule sees the path WITHIN the application, not the deployed URI.
     *
     * <p>Every other test here runs at the container root, where stripping the context
     * path is the identity function — so without this case the strip could be deleted
     * and nothing would notice. Deployed under a context path it matters: the rule
     * decides "is this a `.search`" from a path suffix, and a raw
     * {@code /app/scim/v2/Users/.search} still ends in the suffix, but a raw
     * {@code /app} prefix would reach any future rule that reasons about the path's
     * START.
     */
    @Test
    void the_scope_rule_sees_the_path_within_the_application_not_the_deployed_uri()
            throws Exception {
        String value = mint(TokenPermissions.READ);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/scim/v2/Users");
        request.setContextPath("/app");
        request.setRequestURI("/app/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();

        MockHttpServletResponse searchResponse = new MockHttpServletResponse();
        MockFilterChain searchChain = new MockFilterChain();
        MockHttpServletRequest search =
                new MockHttpServletRequest("POST", "/app/scim/v2/Users/.search");
        search.setContextPath("/app");
        search.setRequestURI("/app/scim/v2/Users/.search");
        search.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        filter.doFilter(search, searchResponse, searchChain);

        assertThat(searchResponse.getStatus()).isEqualTo(200);
        assertThat(searchChain.getRequest()).isNotNull();
    }

    /** Each challenge writes exactly the header RFC 6750 names, and no body. */
    @Test
    void the_three_challenges_are_the_headers_rfc_6750_names() throws IOException {
        MockHttpServletResponse missing = new MockHttpServletResponse();
        ScimBearerChallenge.missingCredential(missing);
        assertThat(missing.getStatus()).isEqualTo(401);
        assertThat(missing.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(missing.getContentAsString()).isEmpty();

        MockHttpServletResponse invalid = new MockHttpServletResponse();
        ScimBearerChallenge.invalidToken(invalid);
        assertThat(invalid.getStatus()).isEqualTo(401);
        assertThat(invalid.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");

        MockHttpServletResponse scope = new MockHttpServletResponse();
        ScimBearerChallenge.insufficientPermissions(scope);
        assertThat(scope.getStatus()).isEqualTo(403);
        assertThat(scope.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"insufficient_scope\"");
    }

    // ---- the request record's actor (#95) -----------------------------------------------------

    /**
     * The request record, written outside the security chain after this filter's scope has
     * closed, names the connector whose token authenticated the request: on an accepted
     * request, and on one refused for scope, which did authenticate.
     */
    @Test
    void the_request_record_names_the_authenticated_connector() throws Exception {
        String write = mint(TokenPermissions.ALL);
        String readOnly = mint(TokenPermissions.READ);

        assertThat(requestRecordContext("GET", write))
                .containsEntry(LogContext.CONNECTOR_ID, connector.id().toString());
        assertThat(requestRecordContext("POST", readOnly))
                .containsEntry(LogContext.CONNECTOR_ID, connector.id().toString());
    }

    /** A token that was not accepted authenticated nobody, and its record names no connector. */
    @Test
    void the_request_record_of_a_refused_token_names_no_connector() throws Exception {
        String value = mint(TokenPermissions.ALL);
        tokens.save(tokens.all().getFirst().revoked(NOW));

        assertThat(requestRecordContext("GET", value)).doesNotContainKey(LogContext.CONNECTOR_ID);
    }

    /** The request record's logging context, with this filter running inside the request's. */
    private Map<String, String> requestRecordContext(String method, String token)
            throws Exception {
        MockHttpServletRequest request = scimRequest(method, "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        try (CapturedLog captured = CapturedLog.attach()) {
            new RequestIdFilter().doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> filter.doFilter(req, res, new MockFilterChain()));
            List<ILoggingEvent> records =
                    captured.withAction(Level.TRACE, LogEvent.LOCAL_ACTION, "http.request");
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().getMDCPropertyMap().toString()).doesNotContain(token);
            return records.getFirst().getMDCPropertyMap();
        }
    }

    // ---- refusal records (#69) ---------------------------------------------------------------

    /**
     * A refused credential is one WARN record whose reason is a fixed word, and no record
     * carries the presented value or any prefix of it — the token's lookup id included.
     */
    @Test
    void an_invalid_token_is_one_warn_record_carrying_nothing_of_the_value() throws Exception {
        String value = mint(TokenPermissions.ALL);
        tokens.save(tokens.all().getFirst().revoked(NOW));
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        List<ILoggingEvent> records;
        try (CapturedLog captured = CapturedLog.attach()) {
            filter.doFilter(request, response, chain);
            records = captured.withAction(Level.TRACE, LogEvent.KIND, "event");
        }

        assertInvalidToken();
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARN);
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.REASON, "bearer-invalid")
                    .containsEntry(LogEvent.HTTP_STATUS_CODE, 401)
                    .containsEntry(LogEvent.OUTCOME, LogEvent.FAILURE);
            assertThat(record.getMDCPropertyMap()).doesNotContainKey(LogContext.CONNECTOR_ID);
            assertThat(record.toString() + CapturedLog.fields(record) + record.getMDCPropertyMap())
                    .doesNotContain(value)
                    .doesNotContain(value.substring(0, 8));
        });
    }

    /** A read-only token's refused write names the connector — by id — and its reason. */
    @Test
    void insufficient_permissions_is_one_warn_record_naming_the_connector() throws Exception {
        String value = mint(TokenPermissions.READ);
        MockHttpServletRequest request = scimRequest("POST", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);

        List<ILoggingEvent> records;
        try (CapturedLog captured = CapturedLog.attach()) {
            filter.doFilter(request, response, chain);
            records = captured.withAction(Level.TRACE, LogEvent.KIND, "event");
        }

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARN);
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.REASON, "insufficient-permissions")
                    .containsEntry(LogEvent.HTTP_STATUS_CODE, 403);
            assertThat(record.getMDCPropertyMap())
                    .containsEntry(LogContext.CONNECTOR_ID, connector.id().toString());
            assertThat(record.getFormattedMessage()).doesNotContain(value);
        });
        assertThat(MDC.get(LogContext.CONNECTOR_ID)).as("the scope closes").isNull();
    }

    /**
     * An accepted request runs with its connector's id in the logging context, so every record
     * the handler writes names the connector; the scope closes with the request. Nothing is
     * recorded for an accepted credential.
     */
    @Test
    void an_accepted_request_carries_its_connector_id_and_is_not_recorded() throws Exception {
        String value = mint(TokenPermissions.ALL);
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);
        String[] seen = new String[1];
        chain = new MockFilterChain() {
            @Override
            public void doFilter(
                    jakarta.servlet.ServletRequest servletRequest,
                    jakarta.servlet.ServletResponse servletResponse)
                    throws IOException, ServletException {
                seen[0] = MDC.get(LogContext.CONNECTOR_ID);
                super.doFilter(servletRequest, servletResponse);
            }
        };

        List<ILoggingEvent> records;
        try (CapturedLog captured = CapturedLog.attach()) {
            filter.doFilter(request, response, chain);
            records = captured.withAction(Level.TRACE, LogEvent.KIND, "event");
        }

        assertThat(seen[0]).isEqualTo(connector.id().toString());
        assertThat(MDC.get(LogContext.CONNECTOR_ID)).isNull();
        assertThat(records).isEmpty();
    }

    /**
     * An accepted request is tagged on the request metric with its connector's id — the one
     * place that knows it, since the security context is cleared before the metric is taken.
     */
    @Test
    void an_accepted_request_tags_the_request_metric_with_its_connector() throws Exception {
        String value = mint(TokenPermissions.ALL);
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);
        ServerRequestObservationContext observation =
                new ServerRequestObservationContext(request, response);
        request.setAttribute(
                ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, observation);

        filter.doFilter(request, response, chain);

        assertThat(observation.getLowCardinalityKeyValue(MetricTag.SCIM_CONNECTOR))
                .isNotNull();
        assertThat(observation.getLowCardinalityKeyValue(MetricTag.SCIM_CONNECTOR).getValue())
                .isEqualTo(connector.id().toString());
    }

    /** A request with no credential is the entry point's to record, not this filter's. */
    @Test
    void a_missing_credential_is_not_recorded_by_the_filter() throws Exception {
        List<ILoggingEvent> records;
        try (CapturedLog captured = CapturedLog.attach()) {
            filter.doFilter(scimRequest("GET", "/scim/v2/Users"), response, chain);
            records = captured.withAction(Level.TRACE, LogEvent.KIND, "event");
        }

        assertThat(chain.getRequest()).isNotNull();
        assertThat(records).isEmpty();
    }

    /** The entry point: the bare challenge, and one WARN {@code bearer-missing} record. */
    @Test
    void the_entry_point_challenges_and_records_a_missing_credential() throws Exception {
        MockHttpServletRequest request = scimRequest("GET", "/scim/v2/Users");

        List<ILoggingEvent> records;
        try (CapturedLog captured = CapturedLog.attach()) {
            new ScimBearerEntryPoint(new AccessRefusalLog(new RouteTemplates(() -> null)))
                    .commence(request, response, null);
            records = captured.withAction(Level.TRACE, LogEvent.KIND, "event");
        }

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARN);
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.REASON, "bearer-missing");
        });
    }

    private void assertInvalidToken() {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(chain.getRequest()).as("the handler must not run").isNull();
    }

    /**
     * The authentication as the FILTERED request saw it, captured from inside the
     * chain. Read there rather than after {@code doFilter} returns, because the filter
     * clears the thread-local on its way out — asserting afterwards would see null and
     * prove nothing.
     */
    private Authentication authenticationSeenBy(MockHttpServletRequest request)
            throws ServletException, IOException {
        Authentication[] captured = new Authentication[1];
        chain = new MockFilterChain() {
            @Override
            public void doFilter(
                    jakarta.servlet.ServletRequest servletRequest,
                    jakarta.servlet.ServletResponse servletResponse)
                    throws IOException, ServletException {
                captured[0] = SecurityContextHolder.getContext().getAuthentication();
                super.doFilter(servletRequest, servletResponse);
            }
        };
        filter.doFilter(request, response, chain);
        return captured[0];
    }

    private static Iterable<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private static MockHttpServletRequest scimRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    private String mint(Set<Permission> scope) {
        return mint(scope, NOW.plus(Duration.ofDays(365)));
    }

    private String mint(Set<Permission> scope, Instant expiresAt) {
        ConnectorTokenSecret.Minted minted =
                ConnectorTokenSecret.mint(new SecureRandom());
        tokens.save(ScimConnectorToken.issue(
                UUID.randomUUID(),
                connector.id(),
                minted.lookupId(),
                minted.digest(),
                TokenPermissions.of(scope),
                NOW,
                expiresAt));
        return minted.presentedValue();
    }
}

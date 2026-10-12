package com.example.backend.scim.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.observability.AccessRefusalLog;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.RequestActor;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.observability.RouteTemplates;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;

/**
 * The SCIM chain's access-denied handler, driven with servlet mocks: what a handler's own
 * Permission declaration refusing a connector's request turns into once it reaches the chain.
 *
 * <p>The bearer filter's backstop refusal goes through the same instance and is asserted in
 * {@link ScimBearerAuthenticationFilterTests}; this class covers the path the filter never takes,
 * where the connector is known only from the request mark.
 */
class ScimInsufficientPermissionsTests {

    private static final UUID CONNECTOR = UUID.fromString("7b0e6a52-3c1f-4d8e-9a10-2f5b6c7d8e9f");

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final RouteTemplates routes = new RouteTemplates(() -> null);

    private final ScimInsufficientPermissions handler =
            new ScimInsufficientPermissions(new AccessRefusalLog(routes), audit, routes);

    @Test
    void a_handler_refusal_is_the_insufficient_scope_challenge_with_no_body() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(markedRequest(), response, refused());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"insufficient_scope\"");
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void a_handler_refusal_is_one_warn_record_naming_the_marked_connector() {
        List<ILoggingEvent> records;
        try (CapturedLog captured = CapturedLog.attach()) {
            handler.handle(markedRequest(), new MockHttpServletResponse(), refused());
            records = captured.withAction(Level.TRACE, LogEvent.KIND, "event");
        }

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARN);
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.REASON, "insufficient-permissions")
                    .containsEntry(LogEvent.HTTP_STATUS_CODE, 403);
            assertThat(record.getMDCPropertyMap())
                    .containsEntry(LogContext.CONNECTOR_ID, CONNECTOR.toString());
        });
    }

    @Test
    void the_connectors_logging_context_closes_with_the_refusal() {
        handler.handle(markedRequest(), new MockHttpServletResponse(), refused());

        assertThat(MDC.get(LogContext.CONNECTOR_ID)).isNull();
    }

    @Test
    void a_handler_refusal_is_audited_as_the_marked_connectors_access_denied() throws Exception {
        MockHttpServletRequest request = markedRequest();
        new RequestIdFilter().doFilter(request, new MockHttpServletResponse(),
                (req, res) -> handler.handle(request, (HttpServletResponse) res, refused()));

        assertThat(audit.recorded()).singleElement().satisfies(event -> {
            assertThat(event.operation()).isEqualTo(AuditOperation.ACCESS_DENIED);
            assertThat(event.actorId()).isEqualTo(CONNECTOR);
        });
        assertThat(audit.accessDeniedRequests()).singleElement().satisfies(operation -> {
            assertThat(operation.method()).isEqualTo("DELETE");
            // No handler mapping in this unit, so the template is the unmatched marker.
            assertThat(operation.pathTemplate()).isEqualTo(routes.of(request));
            assertThat(operation.requestId()).isEqualTo(RequestIdFilter.requestId(request))
                    .isNotNull();
        });
    }

    @Test
    void a_refusal_already_recorded_for_the_exchange_is_not_audited_again() {
        MockHttpServletRequest request = markedRequest();

        handler.handle(request, new MockHttpServletResponse(), refused());
        handler.handle(request, new MockHttpServletResponse(), refused());

        assertThat(audit.of(AuditOperation.ACCESS_DENIED)).hasSize(1);
    }

    /** A request the bearer filter authenticated: it carries the connector's mark. */
    private static MockHttpServletRequest markedRequest() {
        String path = "/scim/v2/Groups/" + UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", path);
        request.setRequestURI(path);
        RequestActor.connector(request, CONNECTOR);
        return request;
    }

    private static AuthorizationDeniedException refused() {
        return new AuthorizationDeniedException("Access Denied", new AuthorizationDecision(false));
    }
}

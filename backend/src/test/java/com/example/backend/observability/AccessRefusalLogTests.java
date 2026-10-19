package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.observability.AccessRefusalLog.Refusal;
import jakarta.servlet.DispatcherType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The refusal record, and the route template it names, against a real request mapping built
 * over a fixture controller — so "the template a refused request would have matched" is
 * Spring's own matching rather than a copy of it.
 */
class AccessRefusalLogTests {

    private static RequestMappingHandlerMapping mapping;

    @BeforeAll
    static void buildMapping() {
        StaticWebApplicationContext context = new StaticWebApplicationContext();
        context.registerSingleton("fixture", FixtureController.class);
        context.refresh();
        mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
    }

    // ---- the route ---------------------------------------------------------------------------

    @Test
    void a_refused_request_is_named_by_the_template_it_would_have_matched() {
        RouteTemplates routes = new RouteTemplates(() -> mapping);

        assertThat(routes.of(request("GET", "/api/things"))).isEqualTo("/api/things");
        assertThat(routes.of(request("GET", "/api/things/" + UUID.randomUUID())))
                .isEqualTo("/api/things/{id}");
    }

    /** The more specific mapping wins, as it would at dispatch, against every rival. */
    @Test
    void the_most_specific_template_wins() {
        RouteTemplates routes = new RouteTemplates(() -> mapping);

        assertThat(routes.of(request("GET", "/api/things/special")))
                .isEqualTo("/api/things/special");
        assertThat(routes.of(request("GET", "/api/widgets/special")))
                .isEqualTo("/api/{kind}/special");
        assertThat(routes.of(request("GET", "/api/widgets/7"))).isEqualTo("/api/{a}/{b}");
        assertThat(routes.of(request("GET", "/api/things/a/b"))).isEqualTo("/api/things/**");
    }

    @Test
    void a_request_no_mapping_serves_is_unmatched() {
        RouteTemplates routes = new RouteTemplates(() -> mapping);

        assertThat(routes.of(request("GET", "/api/nothing-here"))).isEqualTo("unmatched");
        assertThat(routes.of(request("DELETE", "/api/things"))).as("wrong method")
                .isEqualTo("unmatched");
        assertThat(new RouteTemplates(() -> null).of(request("GET", "/api/things")))
                .as("no mapping at all").isEqualTo("unmatched");
    }

    /** Once the dispatcher has matched, its answer is the answer — nothing is looked up. */
    @Test
    void a_template_the_dispatcher_already_matched_is_used_unchanged() {
        MockHttpServletRequest request = request("GET", "/api/things");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/dispatched/{x}");

        assertThat(new RouteTemplates(() -> null).of(request)).isEqualTo("/dispatched/{x}");
    }

    /**
     * The lookup leaves the request as it found it: no matched-pattern attribute for the
     * request record to pick up, and no parsed path it did not have before — while a parsed
     * path that WAS there is left alone.
     */
    @Test
    void the_lookup_leaves_nothing_behind_on_the_request() {
        RouteTemplates routes = new RouteTemplates(() -> mapping);
        MockHttpServletRequest bare = request("GET", "/api/things/" + UUID.randomUUID());

        routes.of(bare);

        assertThat(bare.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).isNull();
        assertThat(bare.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)).isNull();
        assertThat(ServletRequestPathUtils.hasParsedRequestPath(bare)).isFalse();

        MockHttpServletRequest parsed = request("GET", "/api/things");
        ServletRequestPathUtils.parseAndCache(parsed);
        assertThat(routes.of(parsed)).isEqualTo("/api/things");
        assertThat(ServletRequestPathUtils.hasParsedRequestPath(parsed)).isTrue();
    }

    // ---- the record --------------------------------------------------------------------------

    @Test
    void a_denial_is_one_warn_access_control_record_with_method_route_and_status() {
        MockHttpServletRequest request = request("GET", "/api/things/" + UUID.randomUUID());

        ILoggingEvent record = onlyRecord(request, Refusal.INSUFFICIENT_PERMISSIONS);

        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(record.getFormattedMessage()).isEqualTo("Request refused: access denied");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.KIND, "event")
                .containsEntry(LogEvent.CATEGORY, List.of("process"))
                .containsEntry(LogEvent.TYPE, List.of("access", "denied"))
                .containsEntry(LogEvent.ACTION, "access-control")
                .containsEntry(LogEvent.LOCAL_ACTION, "access.denied")
                .containsEntry(LogEvent.OUTCOME, "failure")
                .containsEntry(LogEvent.REASON, "insufficient-permissions")
                .containsEntry(LogEvent.HTTP_METHOD, "GET")
                .containsEntry(LogEvent.HTTP_ROUTE, "/api/things/{id}")
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 403);
    }

    @Test
    void an_unauthenticated_refusal_is_its_own_operation_and_message() {
        ILoggingEvent record = onlyRecord(request("POST", "/api/things"), Refusal.NO_SESSION);

        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(record.getFormattedMessage())
                .isEqualTo("Request refused: authentication required");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ACTION, "access-control")
                .containsEntry(LogEvent.LOCAL_ACTION, "access.unauthenticated")
                .containsEntry(LogEvent.REASON, "no-session")
                .containsEntry(LogEvent.HTTP_METHOD, "POST")
                .containsEntry(LogEvent.HTTP_ROUTE, "/api/things")
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 401);
    }

    /** Each refusal's reason and status are the fixed pair the contract names. */
    @ParameterizedTest
    @EnumSource(Refusal.class)
    void every_refusal_records_its_own_reason_and_status(Refusal refusal) {
        ILoggingEvent record = onlyRecord(request("GET", "/api/things"), refusal);

        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.REASON, refusal.reason())
                .containsEntry(LogEvent.HTTP_STATUS_CODE, refusal.status())
                .containsEntry(LogEvent.LOCAL_ACTION,
                        refusal.status() == 401 ? "access.unauthenticated" : "access.denied");
    }

    /** A 403 is the caller's authorization error: classified cert/auth, needing no follow-up. */
    @ParameterizedTest
    @EnumSource(value = Refusal.class, names = {"INSUFFICIENT_PERMISSIONS", "CSRF"})
    void a_forbidden_refusal_is_classified_as_a_cert_auth_error(Refusal refusal) {
        ILoggingEvent record = onlyRecord(request("GET", "/api/things"), refusal);

        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, 403)
                .containsEntry(LogEvent.ERROR_CATEGORY, "cert/auth")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false);
    }

    @ParameterizedTest
    @EnumSource(value = Refusal.class, names = {"NO_SESSION", "SESSION_EXPIRED", "BEARER_MISSING",
        "BEARER_INVALID"})
    void an_unauthenticated_refusal_carries_no_error_classification(Refusal refusal) {
        ILoggingEvent record = onlyRecord(request("GET", "/api/things"), refusal);

        assertThat(CapturedLog.fields(record)).doesNotContainKeys(
                LogEvent.ERROR_CODE, LogEvent.ERROR_CATEGORY, LogEvent.ERROR_FOLLOW_UP_ACTION);
    }

    @Test
    void the_reasons_and_statuses_are_the_tickets() {
        assertThat(List.of(Refusal.values()))
                .extracting(Refusal::reason, Refusal::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("no-session", 401),
                        org.assertj.core.groups.Tuple.tuple("session-expired", 401),
                        org.assertj.core.groups.Tuple.tuple("bearer-missing", 401),
                        org.assertj.core.groups.Tuple.tuple("bearer-invalid", 401),
                        org.assertj.core.groups.Tuple.tuple("insufficient-permissions", 403),
                        org.assertj.core.groups.Tuple.tuple("csrf", 403));
    }

    /**
     * The error dispatch that follows a {@code sendError} passes through the chain again and
     * may be refused again; it is the same exchange, so it writes nothing. Neither does a
     * second refusal on the same dispatch.
     */
    @Test
    void one_exchange_gets_one_record() {
        AccessRefusalLog refusals = new AccessRefusalLog(new RouteTemplates(() -> mapping));
        MockHttpServletRequest request = request("GET", "/api/things");

        try (CapturedLog captured = CapturedLog.attach()) {
            assertThat(refusals.record(request, Refusal.INSUFFICIENT_PERMISSIONS)).isTrue();
            assertThat(refusals.record(request, Refusal.INSUFFICIENT_PERMISSIONS)).isFalse();
            request.setDispatcherType(DispatcherType.ERROR);
            assertThat(refusals.record(request, Refusal.NO_SESSION)).isFalse();

            assertThat(captured.withAction(Level.TRACE, LogEvent.KIND, "event")).hasSize(1);
        }
    }

    @Test
    void an_error_dispatch_alone_writes_nothing() {
        AccessRefusalLog refusals = new AccessRefusalLog(new RouteTemplates(() -> mapping));
        MockHttpServletRequest request = request("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);

        try (CapturedLog captured = CapturedLog.attach()) {
            refusals.record(request, Refusal.NO_SESSION);

            assertThat(captured.withAction(Level.TRACE, LogEvent.KIND, "event")).isEmpty();
        }
    }

    private static ILoggingEvent onlyRecord(MockHttpServletRequest request, Refusal refusal) {
        AccessRefusalLog refusals = new AccessRefusalLog(new RouteTemplates(() -> mapping));
        try (CapturedLog captured = CapturedLog.attach()) {
            refusals.record(request, refusal);
            List<ILoggingEvent> records = captured.withAction(Level.TRACE, LogEvent.KIND, "event");
            assertThat(records).hasSize(1);
            return records.getFirst();
        }
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    @RestController
    static class FixtureController {

        @GetMapping("/api/things")
        String list() {
            return "";
        }

        @PostMapping("/api/things")
        String create() {
            return "";
        }

        @GetMapping("/api/things/{id}")
        String one(@PathVariable String id) {
            return id;
        }

        @GetMapping("/api/things/special")
        String special() {
            return "";
        }

        // Further candidates for /api/things/special, so the most specific one has rivals in
        // whatever order the mapping registry yields them.
        @GetMapping("/api/{kind}/special")
        String anySpecial(@PathVariable String kind) {
            return kind;
        }

        @GetMapping("/api/{a}/{b}")
        String anyPair(@PathVariable String a, @PathVariable String b) {
            return a + b;
        }

        @GetMapping("/api/things/**")
        String anyThing() {
            return "";
        }
    }
}

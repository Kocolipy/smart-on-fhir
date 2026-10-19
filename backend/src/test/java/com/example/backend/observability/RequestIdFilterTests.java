package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.WebUtils;

/**
 * What the filter has to get right: an id is in scope for the handler, it is the
 * service's own, it does not survive the request, and the error dispatch reports
 * the same exchange rather than a second one — and each request ends in exactly
 * one record that names its route by template and nothing the caller wrote.
 */
class RequestIdFilterTests {

    private final RequestIdFilter filter = new RequestIdFilter();

    private CapturedLog logs;

    @BeforeEach
    void captureLogs() {
        logs = CapturedLog.attach();
    }

    @AfterEach
    void clearContext() {
        logs.close();
        MDC.clear();
    }

    @Test
    void theHandlerRunsWithARequestIdInScope() throws Exception {
        String observed = idSeenDuringChain(new MockHttpServletRequest("GET", "/api/auth/me"));

        assertThat(observed).isNotBlank();
    }

    /**
     * The context is thread-local and the thread is returned to a pool, so an id
     * left behind would be attributed to whatever request is served next.
     */
    @Test
    void theRequestIdDoesNotOutliveTheRequest() throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/api/auth/me"));

        assertThat(MDC.get(LogContext.REQUEST_ID)).isNull();
    }

    @Test
    void eachRequestGetsItsOwnId() throws Exception {
        String first = idSeenDuringChain(new MockHttpServletRequest("GET", "/api/auth/me"));
        String second = idSeenDuringChain(new MockHttpServletRequest("GET", "/api/auth/me"));

        assertThat(first).isNotEqualTo(second);
    }

    /**
     * A caller-supplied correlation header is ignored. Honouring one would let a
     * client merge unrelated requests in a log search by repeating a value, or
     * inject one of its own choosing into this service's records.
     */
    @Test
    void anIdOfferedByTheCallerIsNotUsed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("X-Request-Id", "chosen-by-the-caller");
        request.addHeader("X-Correlation-Id", "chosen-by-the-caller");
        request.addHeader("traceparent", "chosen-by-the-caller");

        assertThat(idSeenDuringChain(request)).isNotEqualTo("chosen-by-the-caller");
    }

    /**
     * The error dispatch is a second pass over the same exchange. Minting a fresh
     * id there would file the response the client actually received under an id
     * that appears nowhere else.
     */
    @Test
    void theErrorDispatchReportsTheSameIdAsTheRequestThatFailed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        String duringRequest = idSeenDuringChain(request);

        // Both are what the container sets on an error dispatch, and both are what
        // OncePerRequestFilter consults before deciding to skip one: without the
        // error-dispatch override this filter carries, the chain below would run
        // with no id in scope at all.
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, "/api/auth/login");
        String duringErrorDispatch = idSeenDuringChain(request);

        assertThat(duringErrorDispatch).isEqualTo(duringRequest);
    }

    /**
     * The stashed value is type-checked, not cast. The attribute name is this
     * filter's own, but a request attribute namespace is shared with every filter
     * and framework in the container, so a collision must degrade to minting a
     * fresh id rather than failing the request with a cast error.
     */
    @Test
    void anAttributeOfTheWrongTypeIsIgnoredRatherThanCast() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, 42);

        assertThat(idSeenDuringChain(request)).isNotBlank();
    }

    // ---- the request record ----------------------------------------------------------------

    /**
     * One record, classified, naming the method, the route template the handler mapping
     * matched, the status, a duration and the outcome — and written while the request's id
     * is still in scope, so it files under the same id as the request's other records.
     */
    @Test
    void aRequestEndsInOneRecordNamingItsMethodRouteStatusDurationAndOutcome() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/self");
        String id = idSeenDuringChain(request, matched("/api/self", 200));

        ILoggingEvent record = onlyRequestRecord();
        Map<String, Object> fields = CapturedLog.fields(record);
        assertThat(record.getLevel()).isEqualTo(Level.INFO);
        assertThat(record.getFormattedMessage()).isEqualTo("HTTP request completed");
        assertThat(record.getLoggerName()).isEqualTo(RequestIdFilter.class.getName());
        assertThat(fields)
                .containsEntry(LogEvent.KIND, "event")
                .containsEntry(LogEvent.CATEGORY, List.of("network"))
                .containsEntry(LogEvent.TYPE, List.of("access", "end"))
                .containsEntry(LogEvent.LOCAL_ACTION, "http.request")
                .doesNotContainKey(LogEvent.ACTION)
                .containsEntry(LogEvent.HTTP_METHOD, "GET")
                .containsEntry(LogEvent.HTTP_ROUTE, "/api/self")
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 200)
                .containsEntry(LogEvent.OUTCOME, "success")
                .containsKey(LogEvent.DURATION_MS);
        assertThat(fields.get(LogEvent.DURATION_MS)).isInstanceOf(Long.class);
        assertThat(record.getMDCPropertyMap()).containsEntry(LogContext.REQUEST_ID, id);
    }

    /** The duration is the chain's, measured around it, in milliseconds. */
    @Test
    void theDurationIsTheTimeTheChainTook() throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/api/self"), (request, response) -> {
            matched("/api/self", 200).handle(request, response);
            Thread.sleep(60);
        });

        long duration = (Long) CapturedLog.fields(onlyRequestRecord()).get(LogEvent.DURATION_MS);
        assertThat(duration).isBetween(60L, 10_000L);
    }

    /**
     * {@code INFO} below {@code 400}, {@code WARN} for a {@code 4xx}, {@code ERROR} for a
     * {@code 5xx}, and the outcome flips at {@code 400}.
     */
    @ParameterizedTest
    @CsvSource({
        "200, INFO, success", "302, INFO, success", "399, INFO, success",
        "400, WARN, failure", "401, WARN, failure", "499, WARN, failure",
        "500, ERROR, failure", "503, ERROR, failure"})
    void theLevelAndOutcomeFollowTheStatus(int status, String level, String outcome)
            throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/api/self"),
                matched("/api/self", status));

        ILoggingEvent record = onlyRequestRecord();
        assertThat(record.getLevel()).isEqualTo(Level.toLevel(level));
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.HTTP_STATUS_CODE, status)
                .containsEntry(LogEvent.OUTCOME, outcome);
    }

    /**
     * A {@code 5xx} record is an {@code ERROR}, so it carries the {@code Log_Schema.md} §Error
     * classification: the status as the code, an {@code application} error, needing follow-up.
     */
    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void aFiveHundredRecordCarriesTheErrorClassification(int status) throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/api/self"),
                matched("/api/self", status));

        assertThat(CapturedLog.fields(onlyRequestRecord()))
                .containsEntry(LogEvent.ERROR_CODE, status)
                .containsEntry(LogEvent.ERROR_CATEGORY, "application")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true);
    }

    /** Below {@code 500}, unclassified by its handler, the request record classifies no error. */
    @ParameterizedTest
    @ValueSource(ints = {200, 400, 404, 499})
    void aRecordBelowFiveHundredCarriesNoErrorClassification(int status) throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/api/self"),
                matched("/api/self", status));

        assertThat(CapturedLog.fields(onlyRequestRecord()))
                .doesNotContainKeys(LogEvent.ERROR_CODE, LogEvent.ERROR_CATEGORY,
                        LogEvent.ERROR_FOLLOW_UP_ACTION);
    }

    /**
     * A {@code 5xx} whose handler already wrote the fault's {@code ERROR} is not reported a second
     * time: the request record is {@code WARN}, still a failure, and classifies no error.
     */
    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void aFaultTheHandlerRecordedEndsInAWarnRequestRecord(int status) throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/api/self"), (request, response) -> {
            matched("/api/self", status).handle(request, response);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                RequestFault.recorded();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        });

        ILoggingEvent record = onlyRequestRecord();
        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.HTTP_STATUS_CODE, status)
                .containsEntry(LogEvent.OUTCOME, "failure")
                .doesNotContainKeys(LogEvent.ERROR_CODE, LogEvent.ERROR_CATEGORY,
                        LogEvent.ERROR_FOLLOW_UP_ACTION);
    }

    /**
     * A refusal its handler classified rather than recorded — the FHIR path's {@code 400} and
     * {@code 404} — is classified on the request record itself, which stays its only record: the
     * caller's error, needing no follow-up, at {@code WARN}.
     */
    @ParameterizedTest
    @ValueSource(ints = {400, 404})
    void aRefusalTheHandlerClassifiedIsClassifiedOnTheRequestRecord(int status) throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/api/patient/x"),
                (request, response) -> {
                    matched("/api/patient/{id}", status).handle(request, response);
                    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                    try {
                        RequestRefusal.classified(LogEvent.ErrorCategory.DATA);
                    } finally {
                        RequestContextHolder.resetRequestAttributes();
                    }
                });

        ILoggingEvent record = onlyRequestRecord();
        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, status)
                .containsEntry(LogEvent.ERROR_CATEGORY, "data")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false);
    }

    /** Only the mark itself classifies: an attribute of another type under its name does not. */
    @Test
    void aRefusalMarkOfTheWrongTypeIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/patient/x");
        request.setAttribute(RequestRefusal.CATEGORY_ATTRIBUTE, "data");

        idSeenDuringChain(request, matched("/api/patient/{id}", 404));

        assertThat(CapturedLog.fields(onlyRequestRecord()))
                .doesNotContainKeys(LogEvent.ERROR_CODE, LogEvent.ERROR_CATEGORY);
    }

    /** Off any request — a scheduled job's thread — classifying is a no-op, not a failure. */
    @Test
    void classifyingWithNoRequestInScopeDoesNothing() {
        RequestContextHolder.resetRequestAttributes();

        RequestRefusal.classified(LogEvent.ErrorCategory.DATA);

        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }

    @Test
    void aRequestWithNoMarkIsNotRecordedAsHandled() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(RequestFault.isRecorded(request)).isFalse();
        request.setAttribute(RequestFault.RECORDED_ATTRIBUTE, "true");
        assertThat(RequestFault.isRecorded(request)).as("only the mark itself counts").isFalse();
        request.setAttribute(RequestFault.RECORDED_ATTRIBUTE, Boolean.TRUE);
        assertThat(RequestFault.isRecorded(request)).isTrue();
    }

    /** Off any request — a scheduled job's thread — marking is a no-op rather than a failure. */
    @Test
    void markingWithNoRequestInScopeDoesNothing() {
        RequestContextHolder.resetRequestAttributes();

        RequestFault.recorded();

        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }

    /**
     * An exception escaping the chain has set no status yet — the container answers
     * {@code 500} after this filter returns — so the record says {@code 500} at
     * {@code ERROR}, and the exception still propagates.
     */
    @Test
    void anExceptionEscapingTheChainIsRecordedAsTheFiveHundredTheClientReceives() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalStateException failure = new IllegalStateException("handler failed");

        assertThatThrownBy(() -> filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
                throw failure;
            }
        })).isSameAs(failure);

        ILoggingEvent record = onlyRequestRecord();
        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 500)
                .containsEntry(LogEvent.OUTCOME, "failure")
                .containsEntry(LogEvent.HTTP_METHOD, "POST")
                .containsEntry(LogEvent.ERROR_CODE, 500)
                .containsEntry(LogEvent.ERROR_CATEGORY, "application")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true);
    }

    /**
     * A request no handler mapping matched — the security chain's refusals, above all —
     * names a fixed bucket rather than its path, and so does a pattern attribute that is
     * not a string.
     */
    @Test
    void aRequestNoHandlerMatchedIsFiledUnderTheUnmatchedBucket() throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/api/self/12345?filter=x"));
        MockHttpServletRequest odd = new MockHttpServletRequest("GET", "/api/self");
        odd.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, 42);
        idSeenDuringChain(odd);

        assertThat(requestRecords())
                .extracting(record -> CapturedLog.fields(record).get(LogEvent.HTTP_ROUTE))
                .containsExactly("unmatched", "unmatched");
    }

    /** A method is a client's choice, so only the standard ones are written as sent. */
    @Test
    void aMethodOutsideTheStandardSetIsNotWrittenAsSent() throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("PATCH", "/api/self"));
        idSeenDuringChain(new MockHttpServletRequest("BREW\nFORGED", "/api/self"));

        assertThat(requestRecords())
                .extracting(record -> CapturedLog.fields(record).get(LogEvent.HTTP_METHOD))
                .containsExactly("PATCH", "_OTHER");
    }

    /** The error dispatch is the same exchange; recording it would count one request twice. */
    @Test
    void theErrorDispatchWritesNoSecondRecord() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/self");
        idSeenDuringChain(request, matched("/api/self", 404));
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, "/api/self");
        idSeenDuringChain(request);

        assertThat(requestRecords()).hasSize(1);
    }

    /** The probes and the scrape are infrastructure polling, not traffic. */
    @ParameterizedTest
    @ValueSource(strings = {
        "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
        "/actuator/prometheus"})
    void theProbesAndTheScrapeAreNotRecorded(String path) throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", path));

        assertThat(requestRecords()).isEmpty();
    }

    /** The exclusion is by whole path segment, and is read beneath the context path. */
    @Test
    void theExclusionIsExactlyTheProbesAndTheScrape() throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/actuator/healthz"));
        idSeenDuringChain(new MockHttpServletRequest("GET", "/actuator/prometheus-ish"));
        idSeenDuringChain(new MockHttpServletRequest("GET", "/actuator/info"));
        MockHttpServletRequest underContext =
                new MockHttpServletRequest("GET", "/backend/actuator/health");
        underContext.setContextPath("/backend");
        idSeenDuringChain(underContext);

        assertThat(requestRecords()).hasSize(3);
    }

    // ---- the request's actor (#95) -----------------------------------------------------------

    private static final UUID ADA = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private static final UUID OKTA = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    /**
     * A caller the chain authenticated by session is named on the request record, though the
     * inner filter's {@code user.id} scope closed before the record was written.
     */
    @Test
    void aRequestTheChainAuthenticatedBySessionIsRecordedUnderItsUserId() throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/api/self"), (request, response) -> {
            RequestActor.user(request, ADA);
            matched("/api/self", 200).handle(request, response);
        });

        assertThat(onlyRequestRecord().getMDCPropertyMap())
                .containsEntry(LogContext.USER_ID, ADA.toString())
                .doesNotContainKey(LogContext.CONNECTOR_ID);
    }

    @Test
    void aRequestTheChainAuthenticatedByBearerIsRecordedUnderItsConnectorId() throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/scim/v2/Users"), (request, response) -> {
            RequestActor.connector(request, OKTA);
            matched("/scim/v2/Users", 200).handle(request, response);
        });

        assertThat(onlyRequestRecord().getMDCPropertyMap())
                .containsEntry(LogContext.CONNECTOR_ID, OKTA.toString())
                .doesNotContainKey(LogContext.USER_ID);
    }

    /** No mark is no field: an anonymous request's record names nobody. */
    @Test
    void anAnonymousRequestIsRecordedUnderNoActor() throws Exception {
        idSeenDuringChain(new MockHttpServletRequest("GET", "/api/self"), matched("/api/self", 401));

        assertThat(onlyRequestRecord().getMDCPropertyMap())
                .doesNotContainKeys(LogContext.USER_ID, LogContext.CONNECTOR_ID);
    }

    /** A null id leaves no mark — and clears one, so a request resolved to nobody names nobody. */
    @Test
    void aNullIdLeavesNoMark() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestActor.user(request, ADA);
        RequestActor.connector(request, OKTA);

        RequestActor.user(request, null);
        RequestActor.connector(request, null);

        assertThat(request.getAttributeNames().hasMoreElements()).isFalse();
    }

    /** A mark of any other type is ignored rather than cast, as the request id's is. */
    @Test
    void anActorMarkOfTheWrongTypeIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/self");
        request.setAttribute(RequestActor.USER_ATTRIBUTE, ADA.toString());
        request.setAttribute(RequestActor.CONNECTOR_ATTRIBUTE, OKTA.toString());

        idSeenDuringChain(request, matched("/api/self", 200));

        assertThat(onlyRequestRecord().getMDCPropertyMap())
                .doesNotContainKeys(LogContext.USER_ID, LogContext.CONNECTOR_ID);
    }

    /**
     * The actor is in scope for the record alone: once the filter returns, neither id is
     * left in the pooled thread's context, and an id the thread already held is restored.
     */
    @Test
    void theActorDoesNotOutliveTheRecord() throws Exception {
        UUID outer = UUID.fromString("00000000-0000-4000-8000-000000000001");
        try (LogContext.Scope held = LogContext.userId(outer)) {
            idSeenDuringChain(new MockHttpServletRequest("GET", "/api/self"),
                    (request, response) -> {
                        RequestActor.user(request, ADA);
                        RequestActor.connector(request, OKTA);
                        matched("/api/self", 200).handle(request, response);
                    });

            assertThat(MDC.get(LogContext.USER_ID)).isEqualTo(outer.toString());
            assertThat(MDC.get(LogContext.CONNECTOR_ID)).isNull();
        }
    }

    // ---- harness -----------------------------------------------------------------------------

    /** What the chain does in place of a handler. */
    @FunctionalInterface
    private interface Handler {
        void handle(MockHttpServletRequest request, MockHttpServletResponse response)
                throws Exception;
    }

    /** A handler mapping matching {@code template}, then a handler answering {@code status}. */
    private static Handler matched(String template, int status) {
        return (request, response) -> {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
            response.setStatus(status);
        };
    }

    private String idSeenDuringChain(MockHttpServletRequest request) throws Exception {
        return idSeenDuringChain(request, (servletRequest, servletResponse) -> { });
    }

    /**
     * Runs the filter over one request and reports the id that was in scope while
     * the chain was executing — which is the only moment a handler could log
     * under it.
     */
    private String idSeenDuringChain(MockHttpServletRequest request, Handler handler)
            throws Exception {
        List<String> seen = new ArrayList<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
                seen.add(MDC.get(LogContext.REQUEST_ID));
                try {
                    handler.handle((MockHttpServletRequest) servletRequest,
                            (MockHttpServletResponse) servletResponse);
                } catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen).hasSize(1);
        return seen.getFirst();
    }

    private List<ILoggingEvent> requestRecords() {
        return logs.withAction(Level.TRACE, LogEvent.LOCAL_ACTION, "http.request");
    }

    private ILoggingEvent onlyRequestRecord() {
        List<ILoggingEvent> records = requestRecords();
        assertThat(records).hasSize(1);
        return records.getFirst();
    }
}

package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.MetricTag;
import com.example.backend.observability.RedactedFaultException;
import com.example.backend.scim.application.ScimLogFields;
import com.example.backend.scim.domain.DuplicateDisplayNameException;
import com.example.backend.scim.domain.DuplicateUserNameException;
import com.example.backend.scim.domain.InvalidPreconditionException;
import com.example.backend.scim.domain.InvalidScimFilterException;
import com.example.backend.scim.domain.InvalidScimQueryException;
import com.example.backend.scim.domain.PasswordPolicy;
import com.example.backend.scim.domain.PasswordPolicyRefusedException;
import com.example.backend.scim.domain.PasswordReusedException;
import com.example.backend.scim.domain.PreconditionFailedException;
import com.example.backend.scim.domain.ProtectedResourceException;
import com.example.backend.scim.domain.ReservedResourceName;
import com.example.backend.scim.domain.ScimPatchRefusedException;
import com.example.backend.scim.domain.ScimRequestBodyTooLargeException;
import com.example.backend.scim.domain.ScimValueControlCharacterException;
import com.example.backend.scim.domain.ScimValueEdgeWhitespaceException;
import com.example.backend.scim.domain.ScimValueTooLongException;
import com.example.backend.scim.domain.UnknownGroupMemberException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One record per refusal, from every handler: the level the status calls for and the
 * {@code event.reason} the refusal is — its {@code scimType}, or its own name where SCIM has
 * none. Driven directly, so mutation testing sees each handler's record every time.
 *
 * <p>The same direct drive holds the other two things a refusal leaves behind: its
 * {@code scimType} on the request metric ({@link RequestMetric}), and the error classification of
 * an {@code ERROR} record ({@link ErrorFields}).
 */
class ScimExceptionHandlerLogTests {

    private final ScimExceptionHandler handler = new ScimExceptionHandler(new RecordingAuditTrail());

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---- one row per handler ----------------------------------------------------------------

    /** Each handler's refusal, recorded once at WARN under the reason it is rendered with. */
    static Stream<Arguments> refusals() {
        return Stream.of(
                refusal("a deliberate refusal, as its scim type",
                        h -> h.handle(ScimErrorException.invalidPath("members")), "invalidPath", 400),
                refusal("a refusal without a scim type, by its own name",
                        h -> h.handle(ScimErrorException.notFound("No User has that id.")),
                        "notFound", 404),
                refusal("an unsupported query, by its own name",
                        h -> h.handle(ScimErrorException.unsupportedQuery("no filter")),
                        "unsupportedQuery", 403),
                refusal("an unreadable body",
                        h -> h.handle(new HttpMessageNotReadableException("x", null, null)),
                        "invalidSyntax", 400),
                refusal("a body that ran past the bound",
                        h -> h.handle(new HttpMessageNotReadableException(
                                "x", new ScimRequestBodyTooLargeException(), null)),
                        "payloadTooLarge", 413),
                refusal("a duplicate user name",
                        h -> h.handle(new DuplicateUserNameException(null)), "uniqueness", 409),
                refusal("a duplicate display name",
                        h -> h.handle(new DuplicateDisplayNameException(null)), "uniqueness", 409),
                refusal("an unknown member",
                        h -> h.handle(new UnknownGroupMemberException(null)), "invalidValue", 400),
                refusal("an invalid precondition",
                        h -> h.handle(new InvalidPreconditionException()), "invalidValue", 400),
                refusal("a failed precondition",
                        h -> h.handle(new PreconditionFailedException()), "preconditionFailed", 412),
                refusal("a password policy refusal",
                        h -> h.handle(new PasswordPolicyRefusedException(PasswordPolicy.Rule.TOO_SHORT)),
                        "invalidValue", 400),
                refusal("a reused password",
                        h -> h.handle(new PasswordReusedException()), "invalidValue", 400),
                refusal("an over-length value",
                        h -> h.handle(new ScimValueTooLongException("userName", 256)),
                        "invalidValue", 400),
                refusal("a control character",
                        h -> h.handle(new ScimValueControlCharacterException(
                                "userName", ScimValueControlCharacterException.Forbidden.NUL)),
                        "invalidValue", 400),
                refusal("edge whitespace in a key",
                        h -> h.handle(new ScimValueEdgeWhitespaceException("externalId")),
                        "invalidValue", 400),
                refusal("a patch refused for mutability",
                        h -> h.handle(new ScimPatchRefusedException(
                                ScimPatchRefusedException.Reason.MUTABILITY, "id is read-only")),
                        "mutability", 400),
                refusal("a patch refused for no target",
                        h -> h.handle(new ScimPatchRefusedException(
                                ScimPatchRefusedException.Reason.NO_TARGET, "nothing matched")),
                        "noTarget", 400),
                refusal("an invalid filter",
                        h -> h.handle(new InvalidScimFilterException("unsupported")),
                        "invalidFilter", 400),
                refusal("an invalid query",
                        h -> h.handle(new InvalidScimQueryException("bad count")),
                        "invalidValue", 400),
                refusal("the protected Bootstrap Admin",
                        h -> h.handle(new ProtectedResourceException(
                                ReservedResourceName.BOOTSTRAP_ADMIN)),
                        "mutability", 400),
                refusal("the protected Admin group",
                        h -> h.handle(new ProtectedResourceException(ReservedResourceName.ADMIN_GROUP)),
                        "mutability", 400));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void each_handlers_refusal_is_recorded_under_its_reason(String name,
            Function<ScimExceptionHandler, ResponseEntity<Map<String, Object>>> raise,
            String reason, int status) {
        assertRefusal(() -> raise.apply(handler), Level.WARN, reason, status);
    }

    private static Arguments refusal(String name,
            Function<ScimExceptionHandler, ResponseEntity<Map<String, Object>>> raise,
            String reason, int status) {
        return Arguments.arguments(name, raise, reason, status);
    }

    /** A 5xx refusal is one ERROR record with the exception — safe by construction — attached. */
    @Test
    void a_server_side_refusal_is_one_error_record_with_the_exception_attached() {
        ScimErrorException refusal = ScimErrorException.notImplemented("/Me is not implemented.");

        ILoggingEvent record = assertRefusal(() -> handler.handle(refusal),
                Level.ERROR, "notImplemented", 501);

        assertThat(CapturedLog.fields(record)).containsEntry(LogEvent.TYPE, List.of("error"));
        assertThat(record.getThrowableProxy()).isNotNull();
        assertThat(record.getThrowableProxy().getClassName())
                .isEqualTo(ScimErrorException.class.getName());
    }

    // ---- the record's shape -------------------------------------------------------------------

    /**
     * The handlers whose refusal carries a message written by this service pass it through as
     * the detail — the record does not, which the shape tests below hold.
     */
    @Test
    void the_service_written_messages_are_the_rendered_detail() {
        assertThat(detail(() -> handler.handle(new InvalidScimFilterException("no such op"))))
                .isEqualTo("no such op");
        assertThat(detail(() -> handler.handle(new InvalidScimQueryException("bad count"))))
                .isEqualTo("bad count");
        assertThat(detail(() -> handler.handle(new ScimPatchRefusedException(
                        ScimPatchRefusedException.Reason.MUTABILITY, "id is read-only"))))
                .isEqualTo("id is read-only");
        assertThat(detail(() -> handler.handle(new ScimPatchRefusedException(
                        ScimPatchRefusedException.Reason.NO_TARGET, "nothing matched"))))
                .isEqualTo("nothing matched");
        ScimValueControlCharacterException control = new ScimValueControlCharacterException(
                "userName", ScimValueControlCharacterException.Forbidden.NUL);
        assertThat(detail(() -> handler.handle(control))).isEqualTo(control.getMessage());
        assertThat(detail(() -> handler.handle(new ScimValueEdgeWhitespaceException("userName"))))
                .isEqualTo("userName must not begin or end with whitespace.");
        assertThat(detail(() -> handler.handle(
                        new ProtectedResourceException(ReservedResourceName.BOOTSTRAP_ADMIN))))
                .asString().startsWith("This User");
        assertThat(detail(() -> handler.handle(
                        new ProtectedResourceException(ReservedResourceName.ADMIN_GROUP))))
                .asString().startsWith("This Group");
    }

    /** A request the dispatcher matched to no route addresses no resource type. */
    @Test
    void a_request_without_a_matched_route_names_no_resource() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ILoggingEvent record = assertRefusal(
                () -> handler.handle(ScimErrorException.notFound("none")),
                Level.WARN, "notFound", 404);

        assertThat(CapturedLog.fields(record)).doesNotContainKey(ScimLogFields.SCIM_RESOURCE_TYPE);
        assertThat(record.getMDCPropertyMap()).doesNotContainKey(LogContext.RESOURCE_ID);
    }

    @Test
    void a_client_refusal_is_classified_as_a_denied_scim_refusal_with_no_exception() {
        ILoggingEvent record = assertRefusal(
                () -> handler.handle(new InvalidScimFilterException("unsupported")),
                Level.WARN, "invalidFilter", 400);

        assertThat(record.getFormattedMessage()).isEqualTo("SCIM request refused");
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.KIND, "event")
                .containsEntry(LogEvent.CATEGORY, List.of("process"))
                .containsEntry(LogEvent.TYPE, List.of("denied"))
                .containsEntry(LogEvent.ACTION, "user-provisioning")
                .containsEntry(LogEvent.LOCAL_ACTION, "scim.refusal")
                .containsEntry(LogEvent.OUTCOME, "failure");
        assertThat(record.getThrowableProxy()).isNull();
    }

    /**
     * The addressed resource — type from the matched route, id from its {@code {id}} — and the
     * connector already in the context; never the detail, which names an attribute.
     */
    @Test
    void the_record_names_the_addressed_resource_and_the_connector() {
        UUID id = UUID.randomUUID();
        inRequest("/scim/v2/Users/{id}", Map.of("id", id.toString()));

        ILoggingEvent record;
        try (LogContext.Scope connector = LogContext.connectorId("connector-1")) {
            record = assertRefusal(() -> handler.handle(new PreconditionFailedException()),
                    Level.WARN, "preconditionFailed", 412);
        }

        assertThat(CapturedLog.fields(record))
                .containsEntry(ScimLogFields.SCIM_RESOURCE_TYPE, "User")
                .doesNotContainValue("The resource has changed since that ETag was issued; GET"
                        + " it and retry.");
        assertThat(record.getMDCPropertyMap())
                .containsEntry(LogContext.RESOURCE_ID, id.toString())
                .containsEntry(LogContext.CONNECTOR_ID, "connector-1");
        assertThat(MDC.get(LogContext.RESOURCE_ID)).as("the scope closes").isNull();
    }

    @Test
    void a_group_route_names_a_group_and_the_collection_route_has_no_id() {
        inRequest("/scim/v2/Groups", Map.of());

        ILoggingEvent record = assertRefusal(
                () -> handler.handle(new DuplicateDisplayNameException(null)),
                Level.WARN, "uniqueness", 409);

        assertThat(CapturedLog.fields(record))
                .containsEntry(ScimLogFields.SCIM_RESOURCE_TYPE, "Group");
        assertThat(record.getMDCPropertyMap()).doesNotContainKey(LogContext.RESOURCE_ID);
    }

    /** A path segment that is not an id this service issues is whatever the caller typed. */
    @Test
    void an_id_that_is_not_a_uuid_is_not_recorded() {
        inRequest("/scim/v2/Users/{id}", Map.of("id", "jane.doe@example.com"));

        ILoggingEvent record = assertRefusal(
                () -> handler.handle(ScimErrorException.notFound("No User has that id.")),
                Level.WARN, "notFound", 404);

        assertThat(record.getMDCPropertyMap()).doesNotContainKey(LogContext.RESOURCE_ID);
        assertThat(CapturedLog.fields(record).toString()).doesNotContain("jane.doe");
    }

    @Test
    void a_route_outside_users_and_groups_names_no_resource_type() {
        inRequest("/scim/v2/Schemas/{id}", Map.of("id", UUID.randomUUID().toString()));
        assertThat(CapturedLog.fields(assertRefusal(
                        () -> handler.handle(ScimErrorException.notFound("none")),
                        Level.WARN, "notFound", 404)))
                .doesNotContainKey(ScimLogFields.SCIM_RESOURCE_TYPE);

        inRequest("/scim/v2/UsersX", Map.of());
        assertThat(CapturedLog.fields(assertRefusal(
                        () -> handler.handle(ScimErrorException.notFound("none")),
                        Level.WARN, "notFound", 404)))
                .as("a prefix that is not the collection")
                .doesNotContainKey(ScimLogFields.SCIM_RESOURCE_TYPE);
    }

    @Test
    void the_integrity_record_names_the_addressed_resource_too() {
        UUID id = UUID.randomUUID();
        inRequest("/scim/v2/Groups/{id}", Map.of("id", id.toString()));

        List<ILoggingEvent> records = records(() -> handler.handle(
                new DataIntegrityViolationException("x", new java.sql.SQLException("y"))));

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(CapturedLog.fields(record))
                    .containsEntry(ScimLogFields.SCIM_RESOURCE_TYPE, "Group");
            assertThat(record.getMDCPropertyMap())
                    .containsEntry(LogContext.RESOURCE_ID, id.toString());
        });
    }

    // ---- harness -----------------------------------------------------------------------------

    private static ILoggingEvent assertRefusal(
            Supplier<ResponseEntity<Map<String, Object>>> refusal,
            Level level, String reason, int status) {
        List<ILoggingEvent> records = new ArrayList<>();
        ResponseEntity<Map<String, Object>> rendered = rendered(refusal, records);
        // The refusal is rendered as well as recorded: the status it records is the one sent.
        assertThat(rendered).isNotNull();
        assertThat(rendered.getStatusCode().value()).isEqualTo(status);
        assertThat(rendered.getBody()).isNotNull().containsKey("detail");
        assertThat(rendered.getBody().get("detail")).isInstanceOf(String.class);
        assertThat(records).as("records for %s", reason).hasSize(1);
        ILoggingEvent record = records.getFirst();
        assertThat(record.getLevel()).isEqualTo(level);
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.REASON, reason)
                .containsEntry(LogEvent.HTTP_STATUS_CODE, status);
        return record;
    }

    /** The detail a handler rendered, for the handlers that pass a safe message through. */
    private static Object detail(Supplier<ResponseEntity<Map<String, Object>>> refusal) {
        return rendered(refusal, new ArrayList<>()).getBody().get("detail");
    }

    private static ResponseEntity<Map<String, Object>> rendered(
            Supplier<ResponseEntity<Map<String, Object>>> refusal, List<ILoggingEvent> records) {
        try (CapturedLog captured = CapturedLog.attach()) {
            ResponseEntity<Map<String, Object>> rendered = refusal.get();
            records.addAll(captured.withAction(Level.TRACE, LogEvent.KIND, "event"));
            return rendered;
        }
    }

    private static List<ILoggingEvent> records(Supplier<?> refusal) {
        try (CapturedLog captured = CapturedLog.attach()) {
            refusal.get();
            return captured.withAction(Level.TRACE, LogEvent.KIND, "event");
        }
    }

    private static void inRequest(String route, Map<String, String> variables) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /**
     * A SCIM refusal names its {@code scimType} on the request metric, so errors can be
     * counted "4xx by scimType".
     *
     * <p>{@code OperationalTelemetryIntegrationTests} proves the same thing end to end from a
     * real scrape, but it sends its traffic in {@code @BeforeAll}. PIT does not reliably
     * credit that code to any test method, so a mutant that removes the recording survived
     * one survey and was killed in another. This case calls the handler directly so
     * mutation testing sees the recording every time.
     */
    @Nested
    class RequestMetric {

        @Test
        void a_refusal_records_its_scim_type_on_the_request_metric() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            ServerRequestObservationContext context =
                    new ServerRequestObservationContext(request, new MockHttpServletResponse());
            request.setAttribute(
                    ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, context);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            handler.handle(new InvalidScimFilterException("unparseable"));

            assertThat(context.getLowCardinalityKeyValue(MetricTag.SCIM_TYPE))
                    .as("the scimType tag recorded on the in-flight observation")
                    .isNotNull();
            assertThat(context.getLowCardinalityKeyValue(MetricTag.SCIM_TYPE).getValue())
                    .isEqualTo("invalidFilter");
        }
    }

    /**
     * Every {@code ERROR} the SCIM advice writes carries the {@code Log_Schema.md} §Error
     * classification and marks its request's fault as recorded, so the request record does not
     * report it a second time; a {@code WARN} refusal does neither. The responses themselves are
     * the other handler tests' concern, and are unchanged.
     */
    @Nested
    class ErrorFields {

        private final MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/scim/v2/Me");

        private CapturedLog logs;

        @BeforeEach
        void setUp() {
            logs = CapturedLog.attach();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        }

        @AfterEach
        void closeLogs() {
            logs.close();
        }

        @Test
        void aServerSideRefusalIsAClassifiedApplicationErrorAndMarksTheFault() {
            handler.handle(ScimErrorException.notImplemented("/Me is not implemented."));

            ILoggingEvent record = onlyRecord();
            assertThat(record.getLevel()).isEqualTo(Level.ERROR);
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.ERROR_CODE, 501)
                    .containsEntry(LogEvent.ERROR_CATEGORY, "application")
                    .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                    .containsEntry(LogEvent.HTTP_STATUS_CODE, 501);
            assertThat(faultRecorded()).isTrue();
        }

        @Test
        void anIntegrityViolationIsAClassifiedDatabaseErrorAndMarksTheFault() {
            DataIntegrityViolationException violation =
                    new DataIntegrityViolationException("x", new java.sql.SQLException("y"));

            handler.handle(violation);

            ILoggingEvent record = onlyRecord();
            assertThat(record.getLevel()).isEqualTo(Level.ERROR);
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.ERROR_CODE, 500)
                    .containsEntry(LogEvent.ERROR_CATEGORY, "database")
                    .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                    .containsEntry(LogEvent.REASON, "SQLException");
            assertThat(record.getThrowableProxy().getClassName())
                    .isEqualTo(RedactedFaultException.class.getName());
            assertThat(record.getThrowableProxy().getMessage()).isEqualTo("SQLException");
            assertThat(record.getThrowableProxy().getStackTraceElementProxyArray())
                    .hasSize(violation.getStackTrace().length);
            assertThat(faultRecorded()).isTrue();
        }

        @Test
        void aCallersRefusalIsUnclassifiedAndLeavesTheRequestUnmarked() {
            handler.handle(ScimErrorException.notFound("No User has that id."));

            ILoggingEvent record = onlyRecord();
            assertThat(record.getLevel()).isEqualTo(Level.WARN);
            assertThat(CapturedLog.fields(record)).doesNotContainKeys(
                    LogEvent.ERROR_CODE, LogEvent.ERROR_CATEGORY, LogEvent.ERROR_FOLLOW_UP_ACTION);
            assertThat(faultRecorded()).isFalse();
        }

        private ILoggingEvent onlyRecord() {
            List<ILoggingEvent> records = logs.withAction(Level.TRACE, LogEvent.KIND, "event");
            assertThat(records).hasSize(1);
            return records.getFirst();
        }

        private boolean faultRecorded() {
            return Boolean.TRUE.equals(
                    request.getAttribute("com.example.backend.observability.RequestFault.recorded"));
        }
    }
}

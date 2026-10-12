package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
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
import com.example.backend.scim.domain.ScimValueTooLongException;
import com.example.backend.scim.domain.UnknownGroupMemberException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One record per refusal, from every handler: the level the status calls for and the
 * {@code event.reason} the refusal is — its {@code scimType}, or its own name where SCIM has
 * none. Driven directly, so mutation testing sees each handler's record every time.
 */
class ScimExceptionHandlerLogTests {

    private final ScimExceptionHandler handler = new ScimExceptionHandler();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ---- one test per handler ----------------------------------------------------------------

    @Test
    void a_deliberate_refusal_is_recorded_as_its_scim_type() {
        assertRefusal(() -> handler.handle(ScimErrorException.invalidPath("members")),
                Level.WARN, "invalidPath", 400);
    }

    @Test
    void a_refusal_without_a_scim_type_is_recorded_by_its_own_name() {
        assertRefusal(() -> handler.handle(ScimErrorException.notFound("No User has that id.")),
                Level.WARN, "notFound", 404);
        assertRefusal(() -> handler.handle(ScimErrorException.unsupportedQuery("no filter")),
                Level.WARN, "unsupportedQuery", 403);
    }

    @Test
    void an_unreadable_body_is_invalid_syntax() {
        assertRefusal(() -> handler.handle(new HttpMessageNotReadableException("x", null, null)),
                Level.WARN, "invalidSyntax", 400);
    }

    @Test
    void a_body_that_ran_past_the_bound_is_payload_too_large() {
        assertRefusal(() -> handler.handle(new HttpMessageNotReadableException(
                        "x", new ScimRequestBodyTooLargeException(), null)),
                Level.WARN, "payloadTooLarge", 413);
    }

    @Test
    void a_duplicate_user_name_is_uniqueness() {
        assertRefusal(() -> handler.handle(new DuplicateUserNameException(null)),
                Level.WARN, "uniqueness", 409);
    }

    @Test
    void a_duplicate_display_name_is_uniqueness() {
        assertRefusal(() -> handler.handle(new DuplicateDisplayNameException(null)),
                Level.WARN, "uniqueness", 409);
    }

    @Test
    void an_unknown_member_is_invalid_value() {
        assertRefusal(() -> handler.handle(new UnknownGroupMemberException(null)),
                Level.WARN, "invalidValue", 400);
    }

    @Test
    void an_invalid_precondition_is_invalid_value() {
        assertRefusal(() -> handler.handle(new InvalidPreconditionException()),
                Level.WARN, "invalidValue", 400);
    }

    @Test
    void a_failed_precondition_is_precondition_failed() {
        assertRefusal(() -> handler.handle(new PreconditionFailedException()),
                Level.WARN, "preconditionFailed", 412);
    }

    @Test
    void a_password_policy_refusal_is_invalid_value() {
        assertRefusal(() -> handler.handle(
                        new PasswordPolicyRefusedException(PasswordPolicy.Rule.TOO_SHORT)),
                Level.WARN, "invalidValue", 400);
    }

    @Test
    void a_reused_password_is_invalid_value() {
        assertRefusal(() -> handler.handle(new PasswordReusedException()),
                Level.WARN, "invalidValue", 400);
    }

    @Test
    void an_over_length_value_is_invalid_value() {
        assertRefusal(() -> handler.handle(new ScimValueTooLongException("userName", 256)),
                Level.WARN, "invalidValue", 400);
    }

    @Test
    void a_control_character_is_invalid_value() {
        assertRefusal(() -> handler.handle(new ScimValueControlCharacterException(
                        "userName", ScimValueControlCharacterException.Forbidden.NUL)),
                Level.WARN, "invalidValue", 400);
    }

    @Test
    void a_refused_patch_is_mutability_or_no_target() {
        assertRefusal(() -> handler.handle(new ScimPatchRefusedException(
                        ScimPatchRefusedException.Reason.MUTABILITY, "id is read-only")),
                Level.WARN, "mutability", 400);
        assertRefusal(() -> handler.handle(new ScimPatchRefusedException(
                        ScimPatchRefusedException.Reason.NO_TARGET, "nothing matched")),
                Level.WARN, "noTarget", 400);
    }

    @Test
    void an_invalid_filter_is_invalid_filter() {
        assertRefusal(() -> handler.handle(new InvalidScimFilterException("unsupported")),
                Level.WARN, "invalidFilter", 400);
    }

    @Test
    void an_invalid_query_is_invalid_value() {
        assertRefusal(() -> handler.handle(new InvalidScimQueryException("bad count")),
                Level.WARN, "invalidValue", 400);
    }

    @Test
    void a_protected_resource_is_mutability() {
        assertRefusal(() -> handler.handle(
                        new ProtectedResourceException(ReservedResourceName.BOOTSTRAP_ADMIN)),
                Level.WARN, "mutability", 400);
        assertRefusal(() -> handler.handle(
                        new ProtectedResourceException(ReservedResourceName.ADMIN_GROUP)),
                Level.WARN, "mutability", 400);
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

    /** The integrity fault writes its own record and render writes none: one, not two. */
    @Test
    void an_integrity_violation_is_exactly_one_error_record() {
        List<ILoggingEvent> records = records(() -> handler.handle(
                new DataIntegrityViolationException("x", new java.sql.SQLException("y"))));

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.ERROR);
            assertThat(record.getFormattedMessage())
                    .isEqualTo("SCIM write refused by an unmapped integrity violation");
        });
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
}

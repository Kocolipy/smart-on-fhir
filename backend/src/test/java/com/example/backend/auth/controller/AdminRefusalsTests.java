package com.example.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.domain.AuditAdministrativeRefusal;
import com.example.backend.audit.domain.Audited;
import com.example.backend.auth.application.AdministrativeRefusalException;
import com.example.backend.auth.application.GroupPermissionsRefusedException;
import com.example.backend.auth.application.UnlockRefusedException;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.application.ConnectorAdministrationRefusedException;
import com.example.backend.scim.application.ConnectorTokenNotRotatableException;
import com.example.backend.scim.application.UnknownConnectorException;
import com.example.backend.scim.controller.AdminConnectorController;
import com.example.backend.scim.domain.ConnectorTokenRotationRefusal;
import com.example.backend.scim.domain.InvalidConnectorTokenLifetimeException;
import com.example.backend.scim.domain.InvalidConnectorTokenPermissionsException;
import com.example.backend.web.ApiError;
import com.example.backend.web.ApiExceptionHandler;
import com.example.backend.web.ErrorNamespaces;
import com.example.backend.web.NamespaceExceptionResolver;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * An administrative refusal is answered, logged and audited by what it is, once, at the edge of
 * {@code /api/admin} — never by the use case that refused.
 *
 * <p>The table below is the whole policy: for each refusal an administrator can meet under
 * {@code /api/admin}, the status and body the caller receives, the {@code WARN} record the refusal
 * writes of its own (or none), and the audit row it appends (or none). Driven through the advice
 * as the dispatcher calls it once a handler threw; the last tests drive Spring's exception
 * resolution itself, so the advice is proven to be the one that answers each controller.
 */
class AdminRefusalsTests {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-00000000a0a0");

    private static final UUID TARGET = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private static final UUID CONNECTOR = UUID.fromString("0b6a3c1e-5d1f-4c38-9a1e-6f2f0c9d7a11");

    private static final UUID ADDRESSED = UUID.fromString("3f1d2c4b-8a7e-4d6f-9b0a-1c2d3e4f5a6b");

    private static final ApiError INVALID_REQUEST = new ApiError(400, "invalid-request",
            "The request could not be read or did not pass validation.");

    private static final ApiError NOT_FOUND = new ApiError(404, "request-refused",
            "The request was refused.");

    private static final ApiError CONFLICT = new ApiError(409, "request-refused",
            "The request was refused.");

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final AdminExceptionHandler edge = new AdminExceptionHandler(audit);

    private CapturedLog logs;

    @BeforeEach
    void captureLogs() {
        logs = CapturedLog.attach();
    }

    @AfterEach
    void release() {
        logs.close();
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * What the caller was told, the refusal's own record — its level and fields, {@code null} for
     * none — and what the trail recorded.
     */
    private record Outcome(
            int status, ApiError body, Level level, Map<String, Object> logged,
            List<Audited> audited) {
    }

    // ---- the table -------------------------------------------------------------------------

    static Stream<Arguments> refusals() {
        return Stream.of(
                // Unlock
                refusal("unlock of an unknown User", edge -> edge.handle(new UnlockRefusedException(
                                UnlockRefusedException.Reason.UNKNOWN_IDENTITY, ACTOR, TARGET)),
                        new Outcome(404, null, null, null, List.of(new Audited.UnlockRefused(
                                ACTOR, TARGET, AuditAdministrativeRefusal.UNKNOWN_RESOURCE)))),
                refusal("unlock of oneself", edge -> edge.handle(new UnlockRefusedException(
                                UnlockRefusedException.Reason.SELF_TARGET, ACTOR, TARGET)),
                        new Outcome(403, null, Level.WARN, unlockRecord("SELF_TARGET"),
                                List.of(new Audited.UnlockRefused(
                                        ACTOR, TARGET, AuditAdministrativeRefusal.SELF_TARGET)))),
                refusal("unlock of a User serving no lockout", edge -> edge.handle(
                                new UnlockRefusedException(
                                        UnlockRefusedException.Reason.NOT_LOCKED, ACTOR, TARGET)),
                        new Outcome(409, CONFLICT, Level.WARN, unlockRecord("NOT_LOCKED"),
                                List.of(new Audited.UnlockRefused(
                                        ACTOR, TARGET, AuditAdministrativeRefusal.NOT_LOCKED)))),
                // A Group's Permissions
                refusal("a body that is no set of delegatable Permissions", edge -> edge.handle(
                                new GroupPermissionsRefusedException(
                                        GroupPermissionsRefusedException.Reason.INVALID_PERMISSIONS,
                                        ACTOR, TARGET)),
                        new Outcome(400, INVALID_REQUEST, Level.WARN,
                                groupRecord("INVALID_PERMISSIONS"),
                                List.of(groupRefused(AuditAdministrativeRefusal.INVALID_PERMISSIONS)))),
                refusal("an unknown Group", edge -> edge.handle(
                                new GroupPermissionsRefusedException(
                                        GroupPermissionsRefusedException.Reason.UNKNOWN_GROUP,
                                        ACTOR, TARGET)),
                        new Outcome(404, NOT_FOUND, Level.WARN, groupRecord("UNKNOWN_RESOURCE"),
                                List.of(groupRefused(AuditAdministrativeRefusal.UNKNOWN_RESOURCE)))),
                refusal("the Admin group", edge -> edge.handle(
                                new GroupPermissionsRefusedException(
                                        GroupPermissionsRefusedException.Reason.ADMIN_GROUP,
                                        ACTOR, TARGET)),
                        new Outcome(409, CONFLICT, Level.WARN, groupRecord("PROTECTED_RESOURCE"),
                                List.of(groupRefused(
                                        AuditAdministrativeRefusal.PROTECTED_RESOURCE)))),
                // Connectors and their tokens
                refusal("delete of an unknown connector", edge -> edge.handle(
                                new UnknownConnectorException(UnknownConnectorException.Operation
                                        .DELETE, ACTOR, CONNECTOR, null)),
                        new Outcome(404, null, null, null, List.of(new Audited.ConnectorDeleteRefused(
                                ACTOR, CONNECTOR, AuditAdministrativeRefusal.UNKNOWN_RESOURCE)))),
                refusal("a token for an unknown connector", edge -> edge.handle(
                                new UnknownConnectorException(UnknownConnectorException.Operation
                                        .TOKEN_ISSUE, ACTOR, CONNECTOR, null)),
                        new Outcome(404, null, null, null, List.of(
                                new Audited.ConnectorTokenIssueRefused(ACTOR, CONNECTOR,
                                        AuditAdministrativeRefusal.UNKNOWN_RESOURCE)))),
                refusal("rotation of an unknown token", edge -> edge.handle(
                                new UnknownConnectorException(UnknownConnectorException.Operation
                                        .TOKEN_ROTATE, ACTOR, null, ADDRESSED)),
                        new Outcome(404, null, null, null, List.of(
                                new Audited.ConnectorTokenNotRotatable(ACTOR, null, ADDRESSED,
                                        AuditAdministrativeRefusal.UNKNOWN_RESOURCE)))),
                refusal("rotation of a deleted connector's token", edge -> edge.handle(
                                new UnknownConnectorException(UnknownConnectorException.Operation
                                        .TOKEN_ROTATE, ACTOR, CONNECTOR, ADDRESSED)),
                        new Outcome(404, null, null, null, List.of(
                                new Audited.ConnectorTokenNotRotatable(ACTOR, CONNECTOR, ADDRESSED,
                                        AuditAdministrativeRefusal.UNKNOWN_RESOURCE)))),
                refusal("revocation of an unknown token", edge -> edge.handle(
                                new UnknownConnectorException(UnknownConnectorException.Operation
                                        .TOKEN_REVOKE, ACTOR, null, ADDRESSED)),
                        new Outcome(404, null, null, null, List.of(
                                new Audited.ConnectorTokenRevokeRefused(ACTOR, null, ADDRESSED,
                                        AuditAdministrativeRefusal.UNKNOWN_RESOURCE)))),
                refusal("revocation naming the connector it addressed", edge -> edge.handle(
                                new UnknownConnectorException(UnknownConnectorException.Operation
                                        .TOKEN_REVOKE, ACTOR, CONNECTOR, ADDRESSED)),
                        new Outcome(404, null, null, null, List.of(
                                new Audited.ConnectorTokenRevokeRefused(ACTOR, CONNECTOR, ADDRESSED,
                                        AuditAdministrativeRefusal.UNKNOWN_RESOURCE)))),
                refusal("rotation of a revoked token", edge -> edge.handle(
                                new ConnectorTokenNotRotatableException(ACTOR, CONNECTOR, ADDRESSED,
                                        ConnectorTokenRotationRefusal.REVOKED)),
                        new Outcome(409, null, Level.WARN, rotationRecord(), List.of(
                                notRotatable(AuditAdministrativeRefusal.TOKEN_REVOKED)))),
                refusal("rotation of a token rotated already", edge -> edge.handle(
                                new ConnectorTokenNotRotatableException(ACTOR, CONNECTOR, ADDRESSED,
                                        ConnectorTokenRotationRefusal.ALREADY_ROTATED)),
                        new Outcome(409, null, Level.WARN, rotationRecord(), List.of(
                                notRotatable(AuditAdministrativeRefusal.TOKEN_ALREADY_ROTATED)))),
                refusal("rotation of an expired token", edge -> edge.handle(
                                new ConnectorTokenNotRotatableException(ACTOR, CONNECTOR, ADDRESSED,
                                        ConnectorTokenRotationRefusal.EXPIRED)),
                        new Outcome(409, null, Level.WARN, rotationRecord(), List.of(
                                notRotatable(AuditAdministrativeRefusal.TOKEN_EXPIRED)))),
                // A token request of a shape no token may have: answered, never audited.
                refusal("a lifetime past the ceiling", edge -> edge.handle(
                                new InvalidConnectorTokenLifetimeException("400 days")),
                        new Outcome(400, null, null, null, List.of())),
                refusal("a Permission no token may carry", edge -> edge.handle(
                                new InvalidConnectorTokenPermissionsException("Not a Permission")),
                        new Outcome(400, null, null, null, List.of())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void each_refusal_is_answered_logged_and_audited_by_what_it_is(
            String refusal, Function<AdminExceptionHandler, ResponseEntity<ApiError>> raise,
            Outcome expected) {
        assertThat(outcome(raise.apply(edge))).isEqualTo(expected);
    }

    /**
     * The connector refusals of a session that names nobody are still recorded, with no actor: an
     * event with no actor is more use than no event.
     */
    @Test
    void a_connector_refusal_with_no_actor_is_recorded_with_none() {
        edge.handle(new UnknownConnectorException(
                UnknownConnectorException.Operation.DELETE, null, CONNECTOR, null));

        assertThat(audit.events()).containsExactly(new Audited.ConnectorDeleteRefused(
                null, CONNECTOR, AuditAdministrativeRefusal.UNKNOWN_RESOURCE));
    }

    /**
     * The edge answers a refusal it was handed, never an absent one: a missing identity or Group
     * refusal is a defect in the caller, refused as a null rather than mistaken for an unmatched
     * kind of refusal.
     */
    @Test
    void an_absent_identity_or_group_refusal_is_rejected_as_null() {
        assertThatNullPointerException().isThrownBy(
                () -> edge.handle((AdministrativeRefusalException) null));
    }

    /** As above, for a connector or token refusal. */
    @Test
    void an_absent_connector_refusal_is_rejected_as_null() {
        assertThatNullPointerException().isThrownBy(
                () -> edge.handle((ConnectorAdministrationRefusedException) null));
    }

    /**
     * A body that is not JSON at all never reached the use case: the binder refused it. The edge
     * records it as the body that is no set of Permissions it is, against the Group the path
     * addressed and the Superuser the session names.
     */
    @Test
    void an_unreadable_group_permissions_body_is_refused_as_invalid_permissions() {
        MockHttpServletRequest request = signedIn(new MockHttpServletRequest(
                "PUT", "/api/admin/groups/" + TARGET + "/permissions"));
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("id", TARGET.toString()));

        ResponseEntity<ApiError> answered =
                new AdminExceptionHandler.UnreadableGroupPermissionsBody(edge).handle(request);

        assertThat(outcome(answered)).isEqualTo(new Outcome(400, INVALID_REQUEST, Level.WARN,
                groupRecord("INVALID_PERMISSIONS"),
                List.of(groupRefused(AuditAdministrativeRefusal.INVALID_PERMISSIONS))));
    }

    // ---- through Spring's exception resolution -----------------------------------------------

    /**
     * Each administrative controller's refusal is answered by the edge, ahead of the application
     * API's advice — whose catch-all would otherwise make it a {@code 500} — and in the
     * {@code /api} namespace, whichever module the controller is in.
     */
    @Test
    void the_edge_answers_each_administrative_controller_ahead_of_the_api_advice()
            throws Exception {
        NamespaceExceptionResolver resolver = resolver();

        assertThat(List.of(
                        resolve(resolver, handler(AdminAccountController.class, "listAccounts"),
                                new UnlockRefusedException(
                                        UnlockRefusedException.Reason.NOT_LOCKED, ACTOR, TARGET)),
                        resolve(resolver, handler(AdminGroupController.class, "listGroups"),
                                new GroupPermissionsRefusedException(
                                        GroupPermissionsRefusedException.Reason.ADMIN_GROUP,
                                        ACTOR, TARGET)),
                        resolve(resolver,
                                handler(AdminConnectorController.class, "listConnectors"),
                                new ConnectorTokenNotRotatableException(ACTOR, CONNECTOR, ADDRESSED,
                                        ConnectorTokenRotationRefusal.EXPIRED))))
                .containsExactly(409, 409, 409);
        assertThat(audit.events()).hasSize(3).allMatch(Audited.Refused.class::isInstance);
    }

    /**
     * The binder's refusal of a body is the edge's only on the Group Permissions route, whose body
     * it is: a connector request's unreadable body stays the application API's plain
     * {@code 400}, logged as a request refusal and audited nowhere.
     */
    @Test
    void only_the_group_permissions_route_records_an_unreadable_body() throws Exception {
        NamespaceExceptionResolver resolver = resolver();
        MockHttpServletRequest group = signedIn(new MockHttpServletRequest(
                "PUT", "/api/admin/groups/" + TARGET + "/permissions"));
        group.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("id", TARGET.toString()));

        int groupStatus = resolve(resolver, group,
                handler(AdminGroupController.class, "listGroups"), unreadable());
        int connectorStatus = resolve(resolver,
                new MockHttpServletRequest("POST", "/api/admin/connectors"),
                handler(AdminConnectorController.class, "listConnectors"), unreadable());

        assertThat(List.of(groupStatus, connectorStatus)).containsExactly(400, 400);
        assertThat(audit.events()).containsExactly(
                groupRefused(AuditAdministrativeRefusal.INVALID_PERMISSIONS));
        assertThat(logs.withAction(Level.TRACE, LogEvent.REASON,
                        "HttpMessageNotReadableException"))
                .as("the connector's is the application API's request refusal").hasSize(1);
    }

    // ---- expectations ------------------------------------------------------------------------

    private static Arguments refusal(String name,
            Function<AdminExceptionHandler, ResponseEntity<ApiError>> raise, Outcome outcome) {
        return arguments(name, raise, outcome);
    }

    /** An Unlock's refusal record: the action, a failure, the User by id, and the reason. */
    private static Map<String, Object> unlockRecord(String reason) {
        Map<String, Object> fields = refusalRecord(
                "identity.unlock", "access-control", "process", List.of("admin", "user", "denied"));
        fields.put(LogEvent.USER_TARGET_ID, TARGET.toString());
        fields.put(LogEvent.REASON, reason);
        return fields;
    }

    /** A Group Permissions refusal record: the action, a failure, the Group by id, the reason. */
    private static Map<String, Object> groupRecord(String reason) {
        Map<String, Object> fields = refusalRecord("authorization.group_permissions_change",
                "user-administration", "process", List.of("admin", "group", "denied"));
        fields.put(LogEvent.GROUP_ID, TARGET.toString());
        fields.put(LogEvent.REASON, reason);
        return fields;
    }

    /** A refused rotation's record: the action and a failure, naming neither token nor why. */
    private static Map<String, Object> rotationRecord() {
        return refusalRecord("scim.connector.token.rotate", "user-administration",
                "configuration", List.of("admin", "denied"));
    }

    private static Map<String, Object> refusalRecord(
            String localAction, String action, String category, List<String> types) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(LogEvent.KIND, "event");
        fields.put(LogEvent.CATEGORY, List.of(category));
        fields.put(LogEvent.TYPE, types);
        fields.put(LogEvent.ACTION, action);
        fields.put(LogEvent.LOCAL_ACTION, localAction);
        fields.put(LogEvent.OUTCOME, LogEvent.FAILURE);
        return fields;
    }

    private static Audited groupRefused(AuditAdministrativeRefusal reason) {
        return new Audited.GroupPermissionsChangeRefused(ACTOR, TARGET, reason);
    }

    private static Audited notRotatable(AuditAdministrativeRefusal reason) {
        return new Audited.ConnectorTokenNotRotatable(ACTOR, CONNECTOR, ADDRESSED, reason);
    }

    // ---- harness -----------------------------------------------------------------------------

    private Outcome outcome(ResponseEntity<ApiError> answered) {
        List<ILoggingEvent> records = logs.withAction(Level.TRACE, LogEvent.KIND, "event");
        assertThat(records).as("at most one record of the refusal's own").hasSizeLessThan(2);
        if (answered.getBody() != null) {
            assertThat(answered.getHeaders().getContentType())
                    .isEqualTo(MediaType.APPLICATION_JSON);
        }
        ILoggingEvent record = records.isEmpty() ? null : records.getFirst();
        return new Outcome(answered.getStatusCode().value(), answered.getBody(),
                record == null ? null : record.getLevel(),
                record == null ? null : new LinkedHashMap<>(CapturedLog.fields(record)),
                new ArrayList<>(audit.events()));
    }

    private NamespaceExceptionResolver resolver() {
        // The application API's namespaces alone: no request here is a SCIM one.
        ErrorNamespaces namespaces = new ErrorNamespaces(List.of());
        StaticWebApplicationContext context = new StaticWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.registerBean(AdminExceptionHandler.class, () -> edge);
        context.registerBean(AdminExceptionHandler.UnreadableGroupPermissionsBody.class,
                () -> new AdminExceptionHandler.UnreadableGroupPermissionsBody(edge));
        context.registerBean(ApiExceptionHandler.class, () -> new ApiExceptionHandler(namespaces));
        context.refresh();
        NamespaceExceptionResolver resolver = new NamespaceExceptionResolver(namespaces);
        resolver.setApplicationContext(context);
        resolver.setMessageConverters(List.of(new JacksonJsonHttpMessageConverter()));
        resolver.afterPropertiesSet();
        return resolver;
    }

    private static int resolve(NamespaceExceptionResolver resolver, HandlerMethod handler,
            Exception failure) {
        return resolve(resolver, new MockHttpServletRequest("POST", "/api/admin/anything"),
                handler, failure);
    }

    private static int resolve(NamespaceExceptionResolver resolver,
            MockHttpServletRequest request, HandlerMethod handler, Exception failure) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(resolver.resolveException(request, response, handler, failure))
                .as("resolved by an exception handler").isNotNull();
        return response.getStatus();
    }

    private static HandlerMethod handler(Class<?> controller, String name) throws Exception {
        Method method = controller.getMethod(name);
        return new HandlerMethod(controller.getDeclaredConstructors()[0]
                .newInstance(new Object[controller.getDeclaredConstructors()[0]
                        .getParameterCount()]), method);
    }

    private static HttpMessageNotReadableException unreadable() {
        return new HttpMessageNotReadableException("Unexpected character ('S1234567A')",
                new MockHttpInputMessage(new byte[0]));
    }

    private static MockHttpServletRequest signedIn(MockHttpServletRequest request) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, ACTOR.toString());
        request.setSession(session);
        return request;
    }
}

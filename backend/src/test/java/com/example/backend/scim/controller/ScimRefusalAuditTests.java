package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.audit.RecordingOperationalAlerts;
import com.example.backend.audit.application.AuditTrailService;
import com.example.backend.audit.domain.AuditOperation;
import com.example.backend.audit.domain.AuditRequest;
import com.example.backend.audit.domain.AuditScimRefusal;
import com.example.backend.audit.domain.Audited;
import com.example.backend.observability.RequestActor;
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
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * A SCIM refusal is audited by what it is, not by which module raised it.
 *
 * <p>The advice is the one place a refused write is recorded: whether a reader, the directory or
 * a use case raised the refusal, its kind decides the answer and the audit reason, and the route
 * it refused decides the event. Driven directly through the advice, as the dispatcher would call
 * it once the handler threw, with the request marks the dispatcher and the bearer filter leave.
 */
class ScimRefusalAuditTests {

    private static final UUID CONNECTOR = UUID.fromString("0b6a3c1e-5d1f-4c38-9a1e-6f2f0c9d7a11");

    private static final UUID TARGET = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private static final String USERS = "/scim/v2/Users";

    private static final String USER = "/scim/v2/Users/{id}";

    private static final String GROUPS = "/scim/v2/Groups";

    private static final String GROUP = "/scim/v2/Groups/{id}";

    private final RecordingAuditTrail audit = new RecordingAuditTrail();

    private final ScimExceptionHandler handler = new ScimExceptionHandler(audit);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** What the caller was told, and what the trail recorded beside it. */
    private record Outcome(int status, String scimType, List<Audited> audited) {
    }

    // ---- the kinds -----------------------------------------------------------------------------

    /**
     * Every refusal kind, raised on a User replacement: its status, its {@code scimType}, and the
     * audit reason it is recorded with — {@code null} for a kind no audit reason names. A reader's
     * {@code mutability} and the directory's are the same kind and are recorded alike. A refused
     * precondition is deliberately not audited, though its {@code scimType} is
     * {@code invalidValue}: two writers colliding is ordinary traffic, not an attempt.
     */
    static Stream<Arguments> kinds() {
        return Stream.of(
                // raised by a reader or an adapter
                kind("reader invalidSyntax", h -> h.handle(
                        ScimErrorException.invalidSyntax("A required attribute is missing: userName")),
                        400, "invalidSyntax", null),
                kind("reader invalidValue", h -> h.handle(
                        ScimErrorException.invalidValue("name must be a complex value.")),
                        400, "invalidValue", AuditScimRefusal.INVALID_VALUE),
                kind("reader mutability", h -> h.handle(
                        ScimErrorException.mutability("id is read-only.")),
                        400, "mutability", AuditScimRefusal.MUTABILITY),
                kind("reader noTarget", h -> h.handle(
                        ScimErrorException.noTarget("A remove operation requires a path.")),
                        400, "noTarget", AuditScimRefusal.NO_TARGET),
                kind("reader invalidPath", h -> h.handle(
                        ScimErrorException.invalidPath("name has no such sub-attribute.")),
                        400, "invalidPath", null),
                kind("reader invalidFilter", h -> h.handle(
                        ScimErrorException.invalidFilter("primary compares with true or false.")),
                        400, "invalidFilter", null),
                kind("reader uniqueness", h -> h.handle(
                        ScimErrorException.uniqueness("taken")),
                        409, "uniqueness", AuditScimRefusal.UNIQUENESS),
                kind("controller notFound", h -> h.handle(
                        ScimErrorException.notFound("No User has that id.")),
                        404, null, null),
                kind("unreadable body", h -> h.handle(
                        new HttpMessageNotReadableException("x", null, null)),
                        400, "invalidSyntax", null),
                kind("body past the bound", h -> h.handle(new HttpMessageNotReadableException(
                                "x", new ScimRequestBodyTooLargeException(), null)),
                        413, null, null),
                // raised by the directory or a use case
                kind("duplicate userName", h -> h.handle(new DuplicateUserNameException(null)),
                        409, "uniqueness", AuditScimRefusal.UNIQUENESS),
                kind("duplicate displayName", h -> h.handle(new DuplicateDisplayNameException(null)),
                        409, "uniqueness", AuditScimRefusal.UNIQUENESS),
                kind("unknown member", h -> h.handle(new UnknownGroupMemberException(null)),
                        400, "invalidValue", AuditScimRefusal.INVALID_VALUE),
                kind("password policy", h -> h.handle(
                                new PasswordPolicyRefusedException(PasswordPolicy.Rule.TOO_SHORT)),
                        400, "invalidValue", AuditScimRefusal.INVALID_VALUE),
                kind("reused password", h -> h.handle(new PasswordReusedException()),
                        400, "invalidValue", AuditScimRefusal.INVALID_VALUE),
                kind("too long", h -> h.handle(new ScimValueTooLongException("userName", 256)),
                        400, "invalidValue", AuditScimRefusal.INVALID_VALUE),
                kind("control character", h -> h.handle(new ScimValueControlCharacterException(
                                "userName", ScimValueControlCharacterException.Forbidden.NUL)),
                        400, "invalidValue", AuditScimRefusal.INVALID_VALUE),
                kind("edge whitespace", h -> h.handle(
                                new ScimValueEdgeWhitespaceException("externalId")),
                        400, "invalidValue", AuditScimRefusal.INVALID_VALUE),
                kind("patch mutability", h -> h.handle(new ScimPatchRefusedException(
                                ScimPatchRefusedException.Reason.MUTABILITY, "userName is required")),
                        400, "mutability", AuditScimRefusal.MUTABILITY),
                kind("patch noTarget", h -> h.handle(new ScimPatchRefusedException(
                                ScimPatchRefusedException.Reason.NO_TARGET, "nothing matched")),
                        400, "noTarget", AuditScimRefusal.NO_TARGET),
                kind("protected Bootstrap Admin", h -> h.handle(
                                new ProtectedResourceException(ReservedResourceName.BOOTSTRAP_ADMIN)),
                        400, "mutability", AuditScimRefusal.MUTABILITY),
                kind("protected Admin group", h -> h.handle(
                                new ProtectedResourceException(ReservedResourceName.ADMIN_GROUP)),
                        400, "mutability", AuditScimRefusal.MUTABILITY),
                kind("invalid filter", h -> h.handle(new InvalidScimFilterException("unsupported")),
                        400, "invalidFilter", null),
                kind("invalid query", h -> h.handle(new InvalidScimQueryException("bad count")),
                        400, "invalidValue", AuditScimRefusal.INVALID_VALUE),
                kind("malformed If-Match", h -> h.handle(new InvalidPreconditionException()),
                        400, "invalidValue", null),
                kind("stale If-Match", h -> h.handle(new PreconditionFailedException()),
                        412, null, null),
                // faults on this side
                kind("server error", h -> h.handle(ScimErrorException.serverError("fault")),
                        500, null, null),
                kind("unmapped integrity violation", h -> h.handle(
                                new DataIntegrityViolationException("x", new java.sql.SQLException("y"))),
                        500, null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kinds")
    void each_refusal_kind_answers_and_is_audited_by_what_it_is(
            String kind,
            Function<ScimExceptionHandler, ResponseEntity<Map<String, Object>>> raise,
            int status, String scimType, AuditScimRefusal reason) {
        inRequest("PUT", USER, TARGET.toString());

        Outcome outcome = outcome(raise.apply(handler));

        assertThat(outcome).isEqualTo(new Outcome(status, scimType, reason == null
                ? List.of()
                : List.of(new Audited.ScimUserWriteRejected(CONNECTOR, TARGET, reason))));
    }

    // ---- the routes ----------------------------------------------------------------------------

    /**
     * The event a refused request records is the one its route's write names. A read has no
     * refused-read event, so its refusals are answered and logged, never audited; a Group
     * deletion is recorded as the refused Group write it always was.
     */
    static Stream<Arguments> routes() {
        return Stream.of(
                arguments("POST", USERS, null,
                        new Audited.ScimUserCreateRejected(CONNECTOR, AuditScimRefusal.MUTABILITY)),
                arguments("PUT", USER, TARGET, new Audited.ScimUserWriteRejected(
                        CONNECTOR, TARGET, AuditScimRefusal.MUTABILITY)),
                arguments("PATCH", USER, TARGET, new Audited.ScimUserWriteRejected(
                        CONNECTOR, TARGET, AuditScimRefusal.MUTABILITY)),
                arguments("DELETE", USER, TARGET, new Audited.ScimUserDeleteRejected(
                        CONNECTOR, TARGET, AuditScimRefusal.MUTABILITY)),
                arguments("POST", GROUPS, null,
                        new Audited.ScimGroupCreateRejected(CONNECTOR, AuditScimRefusal.MUTABILITY)),
                arguments("PUT", GROUP, TARGET, new Audited.ScimGroupWriteRejected(
                        CONNECTOR, TARGET, AuditScimRefusal.MUTABILITY)),
                arguments("PATCH", GROUP, TARGET, new Audited.ScimGroupWriteRejected(
                        CONNECTOR, TARGET, AuditScimRefusal.MUTABILITY)),
                arguments("DELETE", GROUP, TARGET, new Audited.ScimGroupWriteRejected(
                        CONNECTOR, TARGET, AuditScimRefusal.MUTABILITY)),
                arguments("GET", USERS, null, null),
                arguments("GET", USER, TARGET, null),
                arguments("POST", USERS + "/.search", null, null),
                arguments("GET", GROUPS, null, null),
                arguments("GET", GROUP, TARGET, null),
                arguments("POST", GROUPS + "/.search", null, null),
                arguments("GET", "/scim/v2/Me", null, null));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("routes")
    void the_refused_route_names_the_event(
            String method, String route, UUID id, Audited expected) {
        inRequest(method, route, id == null ? null : id.toString());

        handler.handle(ScimErrorException.mutability("id is read-only."));

        assertThat(audit.events()).isEqualTo(expected == null ? List.of() : List.of(expected));
    }

    /**
     * Every write the SCIM controllers map — read from their own mappings, not copied — records
     * its refusal under the event its route names. A route the table did not recognise would be
     * answered and silently not audited, so a changed mapping or a new write route fails here
     * until the table covers it. {@code /.search} is a read and is left out.
     */
    @Test
    void every_scim_write_route_records_its_refusal() {
        Map<String, String> recorded = new TreeMap<>();
        for (Class<?> controller : List.of(ScimUserController.class, ScimGroupController.class)) {
            String base = AnnotatedElementUtils
                    .findMergedAnnotation(controller, RequestMapping.class).path()[0];
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String route = base + (mapping.path().length == 0 ? "" : mapping.path()[0]);
                for (RequestMethod verb : mapping.method()) {
                    if (verb == RequestMethod.GET || route.endsWith("/.search")) {
                        continue;
                    }
                    RecordingAuditTrail trail = new RecordingAuditTrail();
                    inRequest(verb.name(), route, route.contains("{id}") ? TARGET.toString() : null);
                    new ScimExceptionHandler(trail)
                            .handle(ScimErrorException.mutability("id is read-only."));
                    recorded.put(verb + " " + route, trail.events().stream()
                            .map(event -> event.getClass().getSimpleName())
                            .findFirst().orElse("nothing"));
                }
            }
        }

        assertThat(recorded).isEqualTo(Map.of(
                "POST /scim/v2/Users", "ScimUserCreateRejected",
                "PUT /scim/v2/Users/{id}", "ScimUserWriteRejected",
                "PATCH /scim/v2/Users/{id}", "ScimUserWriteRejected",
                "DELETE /scim/v2/Users/{id}", "ScimUserDeleteRejected",
                "POST /scim/v2/Groups", "ScimGroupCreateRejected",
                "PUT /scim/v2/Groups/{id}", "ScimGroupWriteRejected",
                "PATCH /scim/v2/Groups/{id}", "ScimGroupWriteRejected",
                "DELETE /scim/v2/Groups/{id}", "ScimGroupWriteRejected"));
    }

    /**
     * A path segment that is not an id this service issues names no resource, so a write refused
     * there has no target to record.
     */
    @Test
    void a_write_whose_id_names_no_resource_is_not_audited() {
        inRequest("PUT", GROUP, "jane.doe@example.com");

        handler.handle(ScimErrorException.invalidValue("members must be an array."));

        assertThat(audit.events()).isEmpty();
    }

    /** A request the dispatcher matched to no route names no write to record. */
    @Test
    void a_refusal_on_no_matched_route_is_not_audited() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", USERS);
        RequestActor.connector(request, CONNECTOR);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        handler.handle(new DuplicateUserNameException(null));

        assertThat(audit.events()).isEmpty();
    }

    /** Every request a handler serves was authenticated by a connector; without one, no actor. */
    @Test
    void a_refusal_with_no_authenticated_connector_is_not_audited() {
        RequestActor.connector(request("POST", USERS, null), null);

        handler.handle(new DuplicateUserNameException(null));

        assertThat(audit.events()).isEmpty();
    }

    /** The edge records the refusal once, however the handler is reached. */
    @Test
    void a_refusal_is_audited_exactly_once() {
        inRequest("POST", GROUPS, null);

        handler.handle(new DuplicateDisplayNameException(null));

        assertThat(audit.events()).containsExactly(
                new Audited.ScimGroupCreateRejected(CONNECTOR, AuditScimRefusal.UNIQUENESS));
    }

    // ---- fail-open (ADR 0004) ------------------------------------------------------------------

    /**
     * A trail that cannot append changes nothing about the answer: the same status, the same body,
     * the same headers as with a trail that can.
     */
    @Test
    void a_refusal_whose_audit_cannot_be_appended_answers_exactly_as_it_would_have() {
        inRequest("POST", USERS, null);
        ResponseEntity<Map<String, Object>> recorded =
                handler.handle(new DuplicateUserNameException(null));

        ResponseEntity<Map<String, Object>> unrecorded =
                new ScimExceptionHandler(failingTrail(new RecordingOperationalAlerts()))
                        .handle(new DuplicateUserNameException(null));

        assertThat(unrecorded).isEqualTo(recorded);
    }

    /** The lost event is not silent: the alert names the operation it was going to record. */
    @Test
    void a_refusal_whose_audit_cannot_be_appended_raises_the_alert() {
        inRequest("PATCH", USER, TARGET.toString());
        RecordingOperationalAlerts alerts = new RecordingOperationalAlerts();

        new ScimExceptionHandler(failingTrail(alerts))
                .handle(ScimErrorException.mutability("id is read-only."));

        assertThat(alerts.appendFailures()).containsExactly(AuditOperation.SCIM_USER_REPLACE);
    }

    // ---- harness -------------------------------------------------------------------------------

    private static Arguments kind(
            String name,
            Function<ScimExceptionHandler, ResponseEntity<Map<String, Object>>> raise,
            int status, String scimType, AuditScimRefusal reason) {
        return arguments(name, raise, status, scimType, reason);
    }

    private Outcome outcome(ResponseEntity<Map<String, Object>> answer) {
        return new Outcome(answer.getStatusCode().value(),
                (String) answer.getBody().get("scimType"), audit.events());
    }

    /**
     * The real trail over an audit append pool that refuses every connection — the boundary a
     * fail-open append crosses — so the semantics under test are the trail's own.
     */
    private static AuditTrailService failingTrail(RecordingOperationalAlerts alerts) {
        return new AuditTrailService(
                null,
                event -> {
                    throw new IllegalStateException("the audit append pool is unavailable");
                },
                () -> AuditRequest.NONE,
                alerts,
                Clock.systemUTC(),
                null);
    }

    private static void inRequest(String method, String route, String id) {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request(method, route, id)));
    }

    private static MockHttpServletRequest request(String method, String route, String id) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, route);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                id == null ? Map.of() : Map.of("id", id));
        RequestActor.connector(request, CONNECTOR);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }
}

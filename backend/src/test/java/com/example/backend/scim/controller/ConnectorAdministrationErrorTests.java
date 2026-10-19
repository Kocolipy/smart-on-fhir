package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.observability.ExhaustedRetry;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.RedactedFaultException;
import com.example.backend.observability.SqlFailures;
import com.example.backend.scim.application.UnknownConnectorException;
import com.example.backend.web.ApiExceptionHandler;
import com.example.backend.web.ErrorNamespaces;
import com.example.backend.web.NamespaceExceptionResolver;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;
import tools.jackson.databind.json.JsonMapper;

/**
 * A failure of connector administration's handlers, as Spring MVC resolves it with this service's
 * advices: connector administration lives in this package, beside the SCIM namespace's advice, but
 * serves {@code /api}, so the application API's advice answers it — {@code ApiError}, one
 * {@code WARN} refusal or one redacted {@code ERROR} fault, and nothing logged as a SCIM refusal.
 * The same handler's failure on a SCIM path is the SCIM advice's: the request's namespace decides,
 * not the handler.
 */
class ConnectorAdministrationErrorTests {

    /** What the driver's message quotes, which must reach neither the body nor any record. */
    private static final String STATEMENT = "INSERT INTO scim_connectors VALUES ('Okta secret')";

    private static final String SCIM_REFUSAL = "scim.refusal";

    private final JsonMapper json = JsonMapper.builder().build();

    private final NamespaceExceptionResolver resolver = resolver();

    private MockHttpServletRequest request;

    private MockHttpServletResponse response;

    private CapturedLog logs;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("POST", "/api/admin/connectors");
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        logs = CapturedLog.attach();
    }

    @AfterEach
    void tearDown() {
        logs.close();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void an_unreadable_body_is_an_invalid_request_with_one_warn_refusal() throws Exception {
        resolve(new HttpMessageNotReadableException("Unexpected character ('S1234567A')",
                unreadable()));

        assertApiError(400, "invalid-request");
        assertThat(onlyRecord().getLevel()).isEqualTo(Level.WARN);
        assertThat(onlyRecord().getFormattedMessage()).isEqualTo("Request refused");
        assertThat(CapturedLog.fields(onlyRecord()))
                .containsEntry(LogEvent.REASON, "HttpMessageNotReadableException")
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 400);
        assertNothingScim();
    }

    @Test
    void a_body_failing_validation_is_an_invalid_request_with_one_warn_refusal() throws Exception {
        Method create = AdminConnectorController.class.getMethod("create",
                AdminConnectorController.CreateConnectorRequest.class,
                jakarta.servlet.http.HttpServletRequest.class);
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(
                new AdminConnectorController.CreateConnectorRequest(""), "request");
        result.rejectValue("displayName", "NotBlank", "must not be blank");

        resolve(new MethodArgumentNotValidException(new MethodParameter(create, 0), result));

        assertApiError(400, "invalid-request");
        assertThat(onlyRecord().getLevel()).isEqualTo(Level.WARN);
        assertThat(CapturedLog.fields(onlyRecord()))
                .containsEntry(LogEvent.REASON, "MethodArgumentNotValidException");
        assertNothingScim();
    }

    @Test
    void an_integrity_violation_is_a_server_error_with_one_redacted_fault() throws Exception {
        resolve(new DataIntegrityViolationException(STATEMENT, new SQLException(STATEMENT)));

        assertApiError(500, "server-error");
        assertRedactedFault("SQLException");
        assertNothingScim();
    }

    @Test
    void any_other_database_fault_is_a_server_error_with_one_redacted_fault() throws Exception {
        resolve(new QueryTimeoutException(STATEMENT, new SQLException(STATEMENT)));

        assertApiError(500, "server-error");
        assertRedactedFault("SQLException");
        assertNothingScim();
    }

    @Test
    void the_exhausted_deadlock_retry_keeps_its_503_and_retry_after() throws Exception {
        ExhaustedRetry.recorded();

        resolve(new CannotAcquireLockException(STATEMENT,
                SqlFailures.DEADLOCK_VICTIM.raised(STATEMENT)));

        assertApiError(503, "server-error");
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(onlyRecord().getLevel()).isEqualTo(Level.ERROR);
        assertNothingScim();
    }

    /** The controller's own refusals answer first, unchanged: here the bodiless 404. */
    @Test
    void the_controllers_own_refusal_is_unchanged() throws Exception {
        resolve(new UnknownConnectorException("No such connector"));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertNothingScim();
    }

    /** The same handler, the same failure, on a SCIM path: the SCIM namespace's advice answers. */
    @Test
    void the_requests_namespace_not_the_handler_chooses_the_advice() throws Exception {
        request.setRequestURI("/scim/v2/Users");

        resolve(new DataIntegrityViolationException(STATEMENT, new SQLException(STATEMENT)));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("application/scim+json");
        assertThat(json.readTree(response.getContentAsString()).get("schemas").get(0).asText())
                .isEqualTo(ScimSchemas.ERROR);
    }

    /**
     * A refusal made before any handler is chosen is no handler's failure: no advice answers it,
     * and the dispatcher's {@code sendError} is left to the one adapter to render.
     */
    @Test
    void a_failure_with_no_handler_is_left_unresolved() {
        ModelAndView answer = resolver.resolveException(request, response, null,
                new HttpMessageNotReadableException("x", unreadable()));

        assertThat(answer).isNull();
        assertThat(logs.withAction(Level.TRACE, LogEvent.KIND, "event")).isEmpty();
    }

    @Test
    void a_failure_on_a_path_in_no_namespace_is_left_unresolved() throws Exception {
        request.setRequestURI("/accounts");

        assertThat(resolver.resolveException(request, response, handler(),
                new IllegalStateException("x"))).isNull();
    }

    // ---- harness ------------------------------------------------------------------------------

    private static NamespaceExceptionResolver resolver() {
        ErrorNamespaces namespaces = new ErrorNamespaces(
                List.of(new com.example.backend.scim.config.ScimSecurityConfig().scimErrorNamespace()));
        StaticWebApplicationContext context = new StaticWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.registerBean(ApiExceptionHandler.class, () -> new ApiExceptionHandler(namespaces));
        context.registerBean(ScimExceptionHandler.class,
                () -> new ScimExceptionHandler(new RecordingAuditTrail()));
        context.refresh();
        NamespaceExceptionResolver resolver = new NamespaceExceptionResolver(namespaces);
        resolver.setApplicationContext(context);
        resolver.setMessageConverters(List.of(new JacksonJsonHttpMessageConverter()));
        resolver.afterPropertiesSet();
        return resolver;
    }

    private static HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(new AdminConnectorController(null),
                AdminConnectorController.class.getMethod("listConnectors"));
    }

    private static HttpInputMessage unreadable() {
        return new MockHttpInputMessage(new byte[0]);
    }

    private void resolve(Exception failure) throws Exception {
        assertThat(resolver.resolveException(request, response, handler(), failure))
                .as("resolved by an exception handler").isNotNull();
    }

    private void assertApiError(int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(json.readTree(response.getContentAsString()).get("code").asText())
                .isEqualTo(code);
        assertThat(response.getContentAsString())
                .doesNotContain("S1234567A", "secret", "connectors", "displayName");
    }

    private void assertRedactedFault(String causeType) {
        ILoggingEvent record = onlyRecord();
        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(record.getThrowableProxy().getClassName())
                .isEqualTo(RedactedFaultException.class.getName());
        assertThat(record.getThrowableProxy().getMessage()).isEqualTo(causeType);
        assertThat(record.getFormattedMessage() + CapturedLog.fields(record)).doesNotContain("secret");
    }

    private ILoggingEvent onlyRecord() {
        List<ILoggingEvent> records = logs.withAction(Level.TRACE, LogEvent.KIND, "event");
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    /**
     * Nothing logged or answered as a SCIM refusal — the SCIM advice, the only writer of its
     * record, its audit and its {@code scimType} tag, never ran.
     */
    private void assertNothingScim() {
        assertThat(logs.withAction(Level.TRACE, LogEvent.LOCAL_ACTION, SCIM_REFUSAL)).isEmpty();
        assertThat(String.valueOf(response.getContentType())).doesNotContain("scim");
    }
}

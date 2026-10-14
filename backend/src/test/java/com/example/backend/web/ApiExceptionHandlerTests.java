package com.example.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.counter.controller.UserCounterController;
import com.example.backend.observability.ExhaustedRetry;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.RedactedFaultException;
import com.example.backend.observability.SqlFailures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

/**
 * What the handler answers and what it records, for each kind of failure, read off the records
 * the logger actually received.
 */
class ApiExceptionHandlerTests {

    /** A value a caller submitted; it must reach neither a record nor a body. */
    private static final String SUBMITTED = "submitted-marker-7f3a";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x");

    private CapturedLog logs;

    @BeforeEach
    void setUp() {
        logs = CapturedLog.attach();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        logs.close();
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * A handler's own Permission declaration refusing the caller is NOT this advice's to answer:
     * run through Spring MVC's real exception resolution, against a handler in a package the
     * advice covers, the refusal is left unresolved — no response written, no fault record — so
     * it propagates to the security chain, which answers it {@code 403} and records it as the
     * refusal it is. Answered here, the catch-all would have made it a {@code 500}.
     */
    @Test
    void aMethodSecurityRefusalIsLeftForTheSecurityChainToAnswer() throws Exception {
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerSingleton("apiExceptionHandler", ApiExceptionHandler.class);
        context.refresh();
        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();
        resolver.setApplicationContext(context);
        resolver.afterPropertiesSet();
        HandlerMethod handled = new HandlerMethod(
                new UserCounterController(null),
                UserCounterController.class.getMethod("getCount", java.security.Principal.class));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AccessDeniedException refused = new AuthorizationDeniedException("Access Denied");

        assertThat(resolver.resolveException(request, response, handled, refused)).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.isCommitted()).isFalse();
        assertThat(logs.withAction(Level.ERROR, LogEvent.KIND, "event")).isEmpty();
        // And the advice IS consulted for this handler, so the null above is its answer: anything
        // else reaching the catch-all is recorded as the fault it is.
        resolver.resolveException(
                request, new MockHttpServletResponse(), handled, new IllegalStateException());
        assertThat(logs.withAction(Level.ERROR, LogEvent.KIND, "event"))
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Request failed with an unexpected exception");
        context.close();
    }

    @Test
    void handingARefusalBackRethrowsTheVeryException() {
        AccessDeniedException refused = new AccessDeniedException("Access Denied");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handBackToTheChain(refused))
                .isSameAs(refused);
    }

    @Test
    void anUnexpectedExceptionIsOneClassifiedErrorWithTheExceptionAttachedWhole() {
        IllegalStateException failure = new IllegalStateException("internal detail " + SUBMITTED);

        ResponseEntity<Object> response = handler.handleUnexpected(failure);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(new ApiError(500, ApiError.SERVER_ERROR,
                "The request could not be completed because of a server-side failure."));
        assertThat(response.getBody().toString())
                .doesNotContain(SUBMITTED, "IllegalStateException", "internal detail");

        ILoggingEvent record = onlyRecord();
        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(record.getFormattedMessage())
                .isEqualTo("Request failed with an unexpected exception");
        assertThat(record.getThrowableProxy().getClassName())
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(record.getThrowableProxy().getMessage()).isEqualTo(failure.getMessage());
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, 500)
                .containsEntry(LogEvent.ERROR_CATEGORY, "application")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                .containsEntry(LogEvent.OUTCOME, "failure")
                .containsEntry(LogEvent.REASON, "IllegalStateException")
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 500)
                .containsEntry(LogEvent.LOCAL_ACTION, "http.request.fault")
                .containsEntry(LogEvent.CATEGORY, List.of("process"))
                .containsEntry(LogEvent.TYPE, List.of("error"));
        assertThat(faultRecorded()).isTrue();
    }

    /**
     * A data-access failure's message quotes the statement and the refused row, so the record
     * carries a redacted copy: the original's stack, named by its most specific cause.
     */
    @Test
    void aDataAccessFailureIsAttachedRedacted() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException(
                "duplicate key (user_name)=(" + SUBMITTED + ")",
                new java.sql.SQLException("Key (user_name)=(" + SUBMITTED + ") already exists"));

        handler.handleUnexpected(violation);

        ILoggingEvent record = onlyRecord();
        assertThat(record.getThrowableProxy().getClassName()).isEqualTo(RedactedFaultException.class.getName());
        assertThat(record.getThrowableProxy().getMessage()).isEqualTo("SQLException");
        assertThat(record.getThrowableProxy().getCause()).isNull();
        assertThat(record.getThrowableProxy().getStackTraceElementProxyArray())
                .hasSize(violation.getStackTrace().length);
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.REASON, "DataIntegrityViolationException");
        assertThat(ApiExceptionHandler.attached(violation)).isInstanceOf(RedactedFaultException.class);
        assertThat(ApiExceptionHandler.attached(violation).getStackTrace())
                .isEqualTo(violation.getStackTrace());
    }

    /**
     * A data-access failure is the database's, so its {@code error.category} says so, while
     * {@code error.code} stays the status the request was answered with (ADR 0015's mapping).
     */
    @Test
    void aDataAccessFailureIsCategorisedAsADatabaseErrorUnderTheAnsweredStatus() {
        handler.handleUnexpected(new DataIntegrityViolationException("refused",
                SqlFailures.VALUE_TOO_LONG.raised()));

        assertThat(CapturedLog.fields(onlyRecord()))
                .containsEntry(LogEvent.ERROR_CODE, 500)
                .containsEntry(LogEvent.ERROR_CATEGORY, "database");
    }

    /** The database refusing this service's own rights needs its configuration checked. */
    @Test
    void aPermissionRefusalIsCategorisedAsCertAuth() {
        handler.handleUnexpected(new DataIntegrityViolationException("refused",
                SqlFailures.PERMISSION_DENIED.raised()));

        assertThat(CapturedLog.fields(onlyRecord()))
                .containsEntry(LogEvent.ERROR_CATEGORY, "cert/auth");
    }

    /**
     * ADR 0015's as-13: a retried write the database chose as the deadlock's victim again on its
     * retry answers {@code 503} with {@code Retry-After: 1} and the stable body, and is still the
     * one {@code database} {@code ERROR}, needing investigation, that the 500 would have been.
     */
    @Test
    void aDeadlockOnARetriedWritesRetryIsA503WithRetryAfter() {
        ExhaustedRetry.recorded();
        CannotAcquireLockException deadlock = deadlock();

        ResponseEntity<Object> response = handler.handleUnexpected(deadlock);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(new ApiError(503, ApiError.SERVER_ERROR,
                "The request could not be completed because of a server-side failure."));
        ILoggingEvent record = onlyRecord();
        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(record.getThrowableProxy().getClassName())
                .isEqualTo(RedactedFaultException.class.getName());
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, 503)
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 503)
                .containsEntry(LogEvent.ERROR_CATEGORY, "database")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true);
        assertThat(faultRecorded()).isTrue();
    }

    /** A deadlock nothing retried is not as-13's second one: the generic 500, no Retry-After. */
    @Test
    void aDeadlockNoRetryMetIsTheGeneric500() {
        ResponseEntity<Object> response = handler.handleUnexpected(deadlock());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
        assertThat(CapturedLog.fields(onlyRecord())).containsEntry(LogEvent.ERROR_CODE, 500);
    }

    /** Only the deadlock is answered 503: any other failure of a retried request stays a 500. */
    @Test
    void anotherFailureOfARequestWhoseRetryWasExhaustedIsThe500() {
        ExhaustedRetry.recorded();

        ResponseEntity<Object> response = handler.handleUnexpected(
                new DataIntegrityViolationException("refused", SqlFailures.VALUE_TOO_LONG.raised()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
    }

    @Test
    void anyOtherExceptionIsAttachedAsItself() {
        IllegalArgumentException failure = new IllegalArgumentException("x");

        assertThat(ApiExceptionHandler.attached(failure)).isSameAs(failure);
    }

    /**
     * A body failing validation: {@code 400} with the stable body, one {@code WARN} classified as
     * the caller's {@code data} error needing no follow-up, and the rejected value nowhere.
     */
    @Test
    void aValidationFailureIsA400WithTheStableBodyAndOneWarnNamingNoValue() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Body(SUBMITTED), "body");
        binding.rejectValue("username", "Size", new Object[] {SUBMITTED}, "too long: " + SUBMITTED);
        MethodArgumentNotValidException invalid = new MethodArgumentNotValidException(
                new MethodParameter(Body.class.getDeclaredMethod("accept", Body.class), 0), binding);

        ResponseEntity<Object> response = handle(invalid);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(new ApiError(400, ApiError.INVALID_REQUEST,
                "The request could not be read or did not pass validation."));

        ILoggingEvent record = onlyRecord();
        assertThat(record.getLevel()).isEqualTo(Level.WARN);
        assertThat(record.getFormattedMessage()).isEqualTo("Request refused");
        assertThat(record.getThrowableProxy()).isNull();
        assertThat(CapturedLog.fields(record))
                .containsEntry(LogEvent.ERROR_CODE, 400)
                .containsEntry(LogEvent.ERROR_CATEGORY, "data")
                .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, false)
                .containsEntry(LogEvent.OUTCOME, "failure")
                .containsEntry(LogEvent.REASON, "MethodArgumentNotValidException")
                .containsEntry(LogEvent.HTTP_STATUS_CODE, 400)
                .containsEntry(LogEvent.LOCAL_ACTION, "http.request.refusal")
                .containsEntry(LogEvent.CATEGORY, List.of("process"))
                .containsEntry(LogEvent.TYPE, List.of("denied"));
        assertThat(recordText(record)).doesNotContain(SUBMITTED);
        assertThat(faultRecorded()).as("a refusal is not a fault").isFalse();
    }

    @Test
    void anUnreadableBodyIsA400NamingNothingItQuoted() throws Exception {
        HttpMessageNotReadableException unreadable = new HttpMessageNotReadableException(
                "JSON parse error near '" + SUBMITTED + "'",
                new MockHttpInputMessage(SUBMITTED.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<Object> response = handle(unreadable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ApiError) response.getBody()).code()).isEqualTo(ApiError.INVALID_REQUEST);
        assertThat(recordText(onlyRecord())).doesNotContain(SUBMITTED);
        assertThat(CapturedLog.fields(onlyRecord()))
                .containsEntry(LogEvent.REASON, "HttpMessageNotReadableException");
    }

    @Test
    void aMissingParameterIsA400() throws Exception {
        ResponseEntity<Object> response = handle(
                new MissingServletRequestParameterException("page", "int"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(onlyRecord().getLevel()).isEqualTo(Level.WARN);
    }

    /** Another {@code 4xx} keeps its status and the headers Spring set, under the generic code. */
    @Test
    void anotherClientErrorKeepsItsStatusAndHeaders() throws Exception {
        ResponseEntity<Object> response = handle(
                new HttpRequestMethodNotSupportedException("PATCH", List.of("GET", "POST")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).isNotEmpty();
        assertThat(response.getBody()).isEqualTo(
                new ApiError(405, ApiError.REQUEST_REFUSED, "The request was refused."));
        assertThat(CapturedLog.fields(onlyRecord())).containsEntry(LogEvent.ERROR_CODE, 405);
    }

    /** A {@code 5xx} Spring MVC itself assigns is a fault, recorded as one. */
    @Test
    void aServerErrorSpringAssignsIsAFault() throws Exception {
        ResponseEntity<Object> response = handle(new AsyncRequestTimeoutException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(new ApiError(503, ApiError.SERVER_ERROR,
                "The request could not be completed because of a server-side failure."));
        ILoggingEvent record = onlyRecord();
        assertThat(record.getLevel()).isEqualTo(Level.ERROR);
        assertThat(record.getThrowableProxy()).isNotNull();
        assertThat(CapturedLog.fields(record)).containsEntry(LogEvent.ERROR_CODE, 503);
        assertThat(faultRecorded()).isTrue();
    }

    /** A refusal a controller translated is recorded by the type it translated. */
    @Test
    void aTranslatedRefusalIsRecordedByTheTypeItTranslated() throws Exception {
        ResponseEntity<Object> response = handle(new ResponseStatusException(
                HttpStatus.BAD_REQUEST, null, new IllegalArgumentException("page " + SUBMITTED)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ApiError) response.getBody()).code()).isEqualTo(ApiError.INVALID_REQUEST);
        assertThat(CapturedLog.fields(onlyRecord()))
                .containsEntry(LogEvent.REASON, "IllegalArgumentException");
        assertThat(recordText(onlyRecord())).doesNotContain(SUBMITTED);
        assertThat(ApiExceptionHandler.reason(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .isEqualTo("ResponseStatusException");
    }

    @Test
    void theErrorCodesAreStable() {
        assertThat(ApiError.INVALID_REQUEST).isEqualTo("invalid-request");
        assertThat(ApiError.REQUEST_REFUSED).isEqualTo("request-refused");
        assertThat(ApiError.SERVER_ERROR).isEqualTo("server-error");
        assertThat(ApiError.of(HttpStatus.valueOf(500)).code()).isEqualTo(ApiError.SERVER_ERROR);
        assertThat(ApiError.of(HttpStatus.valueOf(400)).code()).isEqualTo(ApiError.INVALID_REQUEST);
        assertThat(ApiError.of(HttpStatus.valueOf(401)).code()).isEqualTo(ApiError.REQUEST_REFUSED);
        assertThat(ApiError.of(org.springframework.http.HttpStatusCode.valueOf(499)).code()).isEqualTo(ApiError.REQUEST_REFUSED);
    }

    // ---- harness -----------------------------------------------------------------------------

    private ResponseEntity<Object> handle(Exception failure) throws Exception {
        return handler.handleException(
                failure, new ServletWebRequest(request, new MockHttpServletResponse()));
    }

    private static CannotAcquireLockException deadlock() {
        return new CannotAcquireLockException("could not execute statement",
                SqlFailures.DEADLOCK_VICTIM.raised("ERROR: deadlock detected"));
    }

    private ILoggingEvent onlyRecord() {
        List<ILoggingEvent> records = logs.withAction(
                Level.TRACE, LogEvent.KIND, "event");
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    private boolean faultRecorded() {
        return Boolean.TRUE.equals(
                request.getAttribute("com.example.backend.observability.RequestFault.recorded"));
    }

    /** Everything a record says: its message, every field, and its context. */
    private static String recordText(ILoggingEvent record) {
        Map<String, Object> fields = CapturedLog.fields(record);
        return record.getFormattedMessage() + fields + record.getMDCPropertyMap()
                + (record.getThrowableProxy() == null ? "" : record.getThrowableProxy().getMessage());
    }

    /** A request body as a controller would bind it. */
    record Body(String username) {
        @SuppressWarnings("unused")
        void accept(Body body) {
        }
    }
}

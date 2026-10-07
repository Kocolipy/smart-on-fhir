package com.example.backend.web;

import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.observability.RedactedFaultException;
import com.example.backend.observability.RequestFault;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Answers every failure the application API's handlers leave unanswered with one stable JSON
 * body, {@link ApiError}, and gives it its one record.
 *
 * <p>Two kinds of failure, told apart by the status Spring MVC assigns:
 *
 * <ul>
 *   <li><b>The caller's error</b> — a body that fails validation or cannot be read, a parameter
 *       that cannot be bound, and the rest of Spring MVC's own {@code 4xx} refusals, which
 *       {@link ResponseEntityExceptionHandler} maps to their statuses. One {@code WARN}
 *       {@code http.request.refusal} record classified as {@code data}, needing no follow-up,
 *       whose {@code event.reason} is the exception's type. Never its message, and never
 *       anything the caller sent: a validation message quotes the rejected value.
 *   <li><b>A fault on this side</b> — any other exception, and a {@code 5xx} Spring MVC
 *       assigns. One {@code ERROR} {@code http.request.fault} record with the exception
 *       attached — redacted, for a data-access failure, whose message quotes the statement and
 *       the refused row — answered with a generic {@code 500} that says nothing about it. The
 *       request record that follows is then written at {@code WARN} ({@link RequestFault}), so
 *       the fault is reported once.
 * </ul>
 *
 * <h2>Scope</h2>
 *
 * <p>The application chain's controller packages, named in {@code basePackages}; never the
 * SCIM namespace's, whose own advice renders every refusal as a SCIM error document. Naming the
 * packages rather than leaving this advice global is what keeps the two apart: a global advice
 * would also answer a SCIM handler's unmapped exception, in a body no SCIM client can parse.
 * {@code ArchitectureTest.every_application_controller_is_covered_by_the_api_exception_handler}
 * fails a new controller package this list does not name.
 *
 * <p>A controller's own {@code @ExceptionHandler} still answers first — Spring consults the
 * controller before any advice — so every refusal a controller already renders is unchanged.
 * A refusal made before a handler is chosen (no route, a method or content type no route
 * accepts) has no controller to scope by and keeps the container's handling.
 */
@RestControllerAdvice(basePackages = {
    ApiExceptionHandler.AUDIT_CONTROLLERS,
    ApiExceptionHandler.AUTH_CONTROLLERS,
    ApiExceptionHandler.COUNTER_CONTROLLERS,
    ApiExceptionHandler.EPIC_CONTROLLERS,
    ApiExceptionHandler.SESSION_CONTROLLERS})
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String AUDIT_CONTROLLERS = "com.example.backend.audit.controller";

    public static final String AUTH_CONTROLLERS = "com.example.backend.auth.controller";

    public static final String COUNTER_CONTROLLERS = "com.example.backend.counter.controller";

    public static final String EPIC_CONTROLLERS = "com.example.backend.auth.epic.controller";

    public static final String SESSION_CONTROLLERS = "com.example.backend.session.controller";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Any exception no controller and no Spring MVC mapping answers: a fault. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception fault) {
        return fault(fault, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * A handler's own Permission declaration refused the caller: not answered here but handed
     * back, unchanged, to the security chain. Rethrowing the very exception leaves it unresolved,
     * so it propagates out of the dispatcher to the chain's exception translation, which answers
     * it as the {@code 403} — and writes the one refusal record and audit row — a URL rule's
     * refusal gets. Without this the catch-all above would turn every such refusal into a
     * {@code 500}.
     */
    @ExceptionHandler(AccessDeniedException.class)
    void handBackToTheChain(AccessDeniedException refused) {
        throw refused;
    }

    /**
     * Every exception {@link ResponseEntityExceptionHandler} maps comes through here, with the
     * status it maps it to. Its own body — a problem document whose detail may quote the
     * request — is discarded for {@link ApiError}.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception failure,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        if (statusCode.is5xxServerError()) {
            return fault(failure, statusCode);
        }
        refusal(failure, statusCode);
        return respond(ApiError.of(statusCode), headers);
    }

    private static ResponseEntity<Object> fault(Exception fault, HttpStatusCode statusCode) {
        int status = statusCode.value();
        LogEvent.error(log, Operation.HTTP_REQUEST_FAULT, status, ErrorCategory.APPLICATION,
                        Category.PROCESS, Type.ERROR)
                .setCause(attached(fault))
                .addKeyValue(LogEvent.REASON, fault.getClass().getSimpleName())
                .addKeyValue(LogEvent.HTTP_STATUS_CODE, status)
                .log();
        RequestFault.recorded();
        return respond(ApiError.of(statusCode), HttpHeaders.EMPTY);
    }

    private static void refusal(Exception refused, HttpStatusCode statusCode) {
        int status = statusCode.value();
        LogEvent.withError(LogEvent.refused(log, Operation.HTTP_REQUEST_REFUSAL,
                                Category.PROCESS, Type.DENIED),
                        status, ErrorCategory.DATA, false)
                .addKeyValue(LogEvent.REASON, reason(refused))
                .addKeyValue(LogEvent.HTTP_STATUS_CODE, status)
                .log();
    }

    /**
     * The refusal's type name — or, for a {@code ResponseStatusException} a controller raised to
     * translate a refusal of its own, the type it translated, which is the one that says why.
     */
    static String reason(Exception refused) {
        return refused instanceof ResponseStatusException translated && translated.getCause() != null
                ? translated.getCause().getClass().getSimpleName()
                : refused.getClass().getSimpleName();
    }

    /**
     * The exception as the record carries it: whole, so {@code error.type} is its real class —
     * unless it is a data-access failure, whose message quotes the statement and the refused
     * values, and which is then a {@link RedactedFaultException} naming its most specific cause's type.
     */
    static Throwable attached(Exception fault) {
        return fault instanceof DataAccessException dataAccess
                ? RedactedFaultException.of(fault,
                        dataAccess.getMostSpecificCause().getClass().getSimpleName())
                : fault;
    }

    private static ResponseEntity<Object> respond(ApiError body, HttpHeaders headers) {
        return ResponseEntity.status(body.status())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}

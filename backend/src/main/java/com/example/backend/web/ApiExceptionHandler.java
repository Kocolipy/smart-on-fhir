package com.example.backend.web;

import com.example.backend.observability.DatabaseFailureCategory;
import com.example.backend.observability.ExhaustedRetry;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.observability.RedactedFaultException;
import com.example.backend.observability.RequestFault;
import com.example.backend.observability.RequestRefusal;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.context.request.ServletWebRequest;
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
 *       the refused row — answered with a generic {@code 500} that says nothing about it, or, for
 *       a retried write the database chose as the deadlock's victim again on its retry, with
 *       {@code 503} and {@code Retry-After: 1} ({@link ExhaustedRetry}). The request record that
 *       follows is then written at {@code WARN} ({@link RequestFault}), so the fault is reported
 *       once.
 * </ul>
 *
 * <p>In the FHIR namespace ({@link ErrorNamespaces#FHIR}) the body is a
 * {@link FhirOperationOutcome} instead, as FHIR JSON, with the same fixed content chosen by the
 * status alone. A fault there has the same record. The caller's error has none of its own: a
 * read of a patient adds no record to the request's, so it is classified as {@code data} on the
 * request record itself ({@link RequestRefusal}) — the one record a {@code 400} or {@code 404}
 * there has (/docs/domain/patient.md, "Logging").
 *
 * <h2>Scope</h2>
 *
 * <p>The advice of both of the application API's namespaces, {@code /api} and
 * {@code /api/patient}: it answers a failure of any handler serving a request under them,
 * whichever module the handler lives in — connector administration, in {@code scim}, included —
 * and never one under another namespace, which {@link NamespaceExceptionResolver} hands to that
 * namespace's own advice. Its declared scope is this application's handlers, the scope every
 * namespace advice shares, so that a framework handler (the actuator's) is never answered here.
 *
 * <p>A controller's own {@code @ExceptionHandler} still answers first — Spring consults the
 * controller before any advice — and so does an advice scoped to named handlers and ordered ahead
 * of this one, as the administrative edge of {@code /api/admin} is for its refusals; everything
 * else those handlers raise is still answered here.
 * A refusal made before a handler is chosen (no route, a method or content type no route
 * accepts) is not answered here but by the dispatcher's {@code sendError}, which
 * {@link ErrorDocumentFilter} renders in the namespace's document.
 */
@RestControllerAdvice(basePackages = NamespaceAdvice.APPLICATION_HANDLERS)
@NamespaceAdvice({ErrorNamespaces.API_PATH, ErrorNamespaces.FHIR_PATH})
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ErrorNamespaces namespaces;

    public ApiExceptionHandler(ErrorNamespaces namespaces) {
        this.namespaces = namespaces;
    }

    /**
     * Any exception no controller and no Spring MVC mapping answers: a fault. Its one record and
     * its answer are the generic {@code 500} — or, for a retried write the database chose as the
     * deadlock's victim again on its retry, {@code 503} with {@code Retry-After: 1} (ADR 0013's
     * as-13; {@link ExhaustedRetry}), the same record under that status.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception fault, HttpServletRequest request) {
        Body body = body(request);
        if (ExhaustedRetry.answers(fault)) {
            return fault(fault, HttpStatus.SERVICE_UNAVAILABLE, ExhaustedRetry.retryAfter(), body);
        }
        return fault(fault, HttpStatus.INTERNAL_SERVER_ERROR, HttpHeaders.EMPTY, body);
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
     * request — is discarded for {@link ApiError}, or under the FHIR path for a
     * {@link FhirOperationOutcome}.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception failure,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        Body shape = request instanceof ServletWebRequest servlet
                ? body(servlet.getRequest())
                : Body.API_ERROR;
        if (statusCode.is5xxServerError()) {
            return fault(failure, statusCode, HttpHeaders.EMPTY, shape);
        }
        if (shape == Body.FHIR_OUTCOME) {
            // A read of a patient adds no record to the request's own: its refusal is classified
            // there instead (/docs/domain/patient.md, "Logging").
            RequestRefusal.classified(ErrorCategory.DATA);
        } else {
            refusal(failure, statusCode);
        }
        return respond(statusCode, headers, shape);
    }

    private static ResponseEntity<Object> fault(
            Exception fault, HttpStatusCode statusCode, HttpHeaders headers, Body body) {
        int status = statusCode.value();
        LogEvent.error(log, Operation.HTTP_REQUEST_FAULT, status,
                        DatabaseFailureCategory.of(fault),
                        Category.PROCESS, Type.ERROR)
                .setCause(attached(fault))
                .addKeyValue(LogEvent.REASON, fault.getClass().getSimpleName())
                .addKeyValue(LogEvent.HTTP_STATUS_CODE, status)
                .log();
        RequestFault.recorded();
        return respond(statusCode, headers, body);
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

    private static ResponseEntity<Object> respond(
            HttpStatusCode statusCode, HttpHeaders headers, Body body) {
        ResponseEntity.BodyBuilder answer = ResponseEntity.status(statusCode).headers(headers);
        if (body == Body.FHIR_OUTCOME) {
            return answer.contentType(FhirOperationOutcome.FHIR_JSON_R5)
                    .body(FhirOperationOutcome.of(statusCode.value()));
        }
        return answer.contentType(MediaType.APPLICATION_JSON).body(ApiError.of(statusCode));
    }

    /** Which of this advice's two namespaces' bodies {@code request} is answered with. */
    private Body body(HttpServletRequest request) {
        return namespaces.of(request).orElse(ErrorNamespaces.API) == ErrorNamespaces.FHIR
                ? Body.FHIR_OUTCOME
                : Body.API_ERROR;
    }

    /** Which error body a request is answered with: the FHIR namespace's, or the API's. */
    private enum Body {
        API_ERROR,
        FHIR_OUTCOME
    }
}

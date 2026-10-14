package com.example.backend.scim.controller;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.observability.DatabaseFailureCategory;
import com.example.backend.observability.ExhaustedRetry;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.ErrorCategory;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.observability.MetricTag;
import com.example.backend.observability.RedactedFaultException;
import com.example.backend.observability.RequestFault;
import com.example.backend.scim.application.ScimLogFields;
import com.example.backend.scim.domain.ScimRequestBodyTooLargeException;
import com.example.backend.scim.domain.ScimRequestRefusedException;
import com.example.backend.web.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Renders every refusal this slice's handlers produce as the one SCIM error document.
 *
 * <p>Scoped to this package by {@code basePackages}, deliberately. The application
 * chain's errors keep their existing shape — a SCIM error body on a browser request
 * would be a change to the SPA's contract — so this advice must not be global. What
 * that scoping costs is that a refusal raised BEFORE a handler is selected (an
 * unsupported method on a mapped path, an unsupported content type, a path nothing
 * serves) is not rendered here; {@code ScimDispatcherErrorFilter} in the namespace's
 * security chain renders those as the same document, and {@code ScimRequestBodyLimitFilter}
 * refuses a body declaring more than the size bound before any of this runs.
 *
 * <p>It is also where a refused write is audited — once, at the edge of the request, whichever
 * module raised the refusal. What each kind of refusal answers and is audited as is
 * {@link ScimRefusals}' table; this advice adapts the exceptions to it, records, audits and
 * renders. The audit is a refused event, so it is fail-open (ADR 0004): a trail that cannot
 * record it leaves the answer as it was.
 *
 * <p>The body is built as an ordered map rather than a record so the field order is
 * {@code schemas}, {@code status}, {@code scimType}, {@code detail} as RFC 7644's
 * examples have it, and so {@code scimType} can be omitted entirely — rather than
 * rendered as null — where the RFC defines none for the condition.
 */
@RestControllerAdvice(basePackages = "com.example.backend.scim.controller")
class ScimExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ScimExceptionHandler.class);

    private final AuditTrail audit;

    ScimExceptionHandler(AuditTrail audit) {
        this.audit = audit;
    }

    /** Every refusal this slice raises deliberately. */
    @ExceptionHandler(ScimErrorException.class)
    ResponseEntity<Map<String, Object>> handle(ScimErrorException refusal) {
        return render(refusal);
    }

    /**
     * A body the JSON codec could not read: absent, not JSON, or one that ran past the size bound
     * while being read.
     *
     * <p>The codec raises all three as one exception, so the size case is found in the cause
     * chain — a body that declared its length was already refused by the namespace's limit filter,
     * and this is the chunked one that could only be measured as it streamed. The codec's own
     * message is not rendered: it quotes the offending input.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> handle(HttpMessageNotReadableException unreadable) {
        for (Throwable cause = unreadable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ScimRequestBodyTooLargeException) {
                return render(ScimErrorException.payloadTooLarge(
                        "The request body exceeds the 1 MiB limit."));
            }
        }
        return render(ScimErrorException.invalidSyntax(
                "The request body must be a single JSON object."));
    }

    /**
     * A refusal the directory raised — every kind of it, through the one table that says what
     * each is answered and audited as ({@link ScimRefusals#answer}).
     */
    @ExceptionHandler(ScimRequestRefusedException.class)
    ResponseEntity<Map<String, Object>> handle(ScimRequestRefusedException refused) {
        return render(ScimRefusals.answer(refused));
    }

    /**
     * An integrity violation no adapter translated into a refusal — the database turned down a
     * write that the checks above it accepted.
     *
     * <p>That is a fault on this side, not the caller's, so it is a {@code 500} — but in the SCIM
     * error document rather than the servlet container's own error body, which a connector cannot
     * parse. The detail says nothing about the cause: the violation's message quotes the
     * statement and, for a unique key, the conflicting value.
     *
     * <p>Handling it here takes it away from the container's own error logging, so it is logged
     * here instead, as the cause's type alone for the same reason the message is not rendered.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> handle(DataIntegrityViolationException violation) {
        String causeType = violation.getMostSpecificCause().getClass().getSimpleName();
        ScimErrorException refusal = ScimErrorException.serverError(
                "The write could not be completed because of a server-side failure; it was not"
                        + " applied.");
        databaseFault(violation, causeType, Operation.SCIM_WRITE, refusal.status().value(),
                ErrorCategory.DATABASE, record -> record.addKeyValue(LogEvent.REASON, causeType));
        return body(refusal);
    }

    /**
     * A retried write the database chose as the deadlock's victim again on its retry (ADR 0015's
     * as-13, {@link ExhaustedRetry}): {@code 503} with {@code Retry-After: 1}, a fault on this side
     * the connector may simply send again.
     *
     * <p>A deadlock arrives as Spring's data-access or transaction failure, so those are the types
     * this handler is consulted for; every one of them that is not the exhausted retry's deadlock
     * is rethrown as it is, which leaves it unresolved — exactly as it was before this handler
     * existed — and the integrity violation keeps its own, more specific, handler above.
     *
     * <p>The fault's one record is the {@code database} {@code ERROR}, needing investigation, that
     * {@link com.example.backend.observability.DatabaseFailureCategory} makes of it, with a
     * redacted copy attached: the driver's message quotes the statement. The connector
     * administration adapter is in this package but on the application chain, so its deadlocked
     * writes are answered in that chain's shape, by the application API's own fault handler.
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<?> handle(RuntimeException failure, HandlerMethod handler) {
        if (!ExhaustedRetry.answers(failure)) {
            throw failure;
        }
        if (AdminConnectorController.class.isAssignableFrom(handler.getBeanType())) {
            return ApiExceptionHandler.answerUnexpected(failure);
        }
        ScimErrorException unavailable = ScimErrorException.serviceUnavailable(
                "The write conflicted with a concurrent write and was not applied; send it again.");
        String type = failure.getClass().getSimpleName();
        String causeType = failure instanceof DataAccessException dataAccess
                ? dataAccess.getMostSpecificCause().getClass().getSimpleName()
                : type;
        int status = unavailable.status().value();
        databaseFault(failure, causeType, Operation.SCIM_REFUSAL, status,
                DatabaseFailureCategory.of(failure), record -> record
                        .addKeyValue(LogEvent.REASON, type)
                        .addKeyValue(LogEvent.HTTP_STATUS_CODE, status));
        ResponseEntity<Map<String, Object>> body = body(unavailable);
        return ResponseEntity.status(body.getStatusCode())
                .headers(body.getHeaders())
                .headers(ExhaustedRetry.retryAfter())
                .body(body.getBody());
    }

    /**
     * A database fault's one record, in the addressed resource's context, and the request marked
     * as having had it ({@link RequestFault}). The failure is attached as a redacted copy named
     * {@code causeType}: the stack says where it failed, and the message — which quotes the
     * statement and, for a unique key, the conflicting value — never reaches the record.
     * {@code fields} adds what the handler's record carries beyond that.
     */
    private static void databaseFault(
            RuntimeException failure, String causeType, Operation operation, int status,
            ErrorCategory errorCategory, UnaryOperator<LoggingEventBuilder> fields) {
        inResourceContext(resourceType -> withResourceType(
                        fields.apply(LogEvent.error(log, operation, status, errorCategory,
                                        Category.DATABASE, Type.ERROR)
                                .setCause(RedactedFaultException.of(failure, causeType))),
                        resourceType)
                .log());
        RequestFault.recorded();
    }

    /**
     * Records the refusal, audits it, then renders it. Every handler but the integrity-violation
     * one comes through here, so each refusal gets exactly one record: {@code WARN} for the
     * caller's error, {@code ERROR} with the exception attached for a fault on this side. The
     * record carries the refusal's {@link ScimErrorException#reason() reason}, its status, and
     * the resource the request addressed where the route names one — never the detail, which
     * may name an attribute, and never anything the caller submitted.
     *
     * <p>The audit is the refused-write event the table names for this kind on this route
     * ({@link ScimRefusals#audited}), when it names one, so each refused write is audited exactly
     * once however deep the refusal was raised.
     */
    private ResponseEntity<Map<String, Object>> render(ScimErrorException refusal) {
        boolean fault = refusal.status().is5xxServerError();
        int status = refusal.status().value();
        inResourceContext(resourceType -> withResourceType(
                        (fault
                                ? LogEvent.error(log, Operation.SCIM_REFUSAL, status,
                                                ErrorCategory.APPLICATION, Category.PROCESS,
                                                Type.ERROR)
                                        .setCause(refusal)
                                : LogEvent.refused(log, Operation.SCIM_REFUSAL, Category.PROCESS,
                                        Type.DENIED))
                                .addKeyValue(LogEvent.REASON, refusal.reason())
                                .addKeyValue(LogEvent.HTTP_STATUS_CODE, status),
                        resourceType)
                .log());
        if (fault) {
            RequestFault.recorded();
        }
        HttpServletRequest request = currentRequest();
        if (request != null) {
            ScimRefusals.audited(refusal, request).ifPresent(audit::append);
        }
        return body(refusal);
    }

    /**
     * Runs {@code write} with the addressed resource's id in the logging context, handing it
     * the resource's type. Both come from the route the dispatcher matched — the template
     * and its {@code {id}} variable — and the id only when it is one this service could have
     * issued: a path segment that is not a UUID names no resource and is whatever the caller
     * typed, so it is not recorded.
     */
    private static void inResourceContext(Consumer<String> write) {
        HttpServletRequest request = currentRequest();
        String resourceId = request == null
                ? null
                : Objects.toString(ScimRefusals.resourceId(request), null);
        try (LogContext.Scope scope = LogContext.resourceId(resourceId)) {
            write.accept(request == null ? null : resourceType(request));
        }
    }

    private static LoggingEventBuilder withResourceType(
            LoggingEventBuilder record, String resourceType) {
        return resourceType == null
                ? record
                : record.addKeyValue(ScimLogFields.SCIM_RESOURCE_TYPE, resourceType);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes current
                ? current.getRequest()
                : null;
    }

    /** {@code User} or {@code Group}, from the matched template; {@code null} for any other. */
    private static String resourceType(HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
                instanceof String route)) {
            return null;
        }
        if (isUnder(route, ScimSchemas.USERS_PATH)) {
            return "User";
        }
        return isUnder(route, ScimSchemas.GROUPS_PATH) ? "Group" : null;
    }

    private static boolean isUnder(String route, String prefix) {
        return route.equals(prefix) || route.startsWith(prefix + "/");
    }

    private static ResponseEntity<Map<String, Object>> body(ScimErrorException refusal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(ScimSchemas.ERROR));
        // A string, not a number: RFC 7644 §3.12 defines status as a string, and a
        // conformance client that parses it as one fails on an integer.
        body.put("status", String.valueOf(refusal.status().value()));
        if (refusal.scimType() != null) {
            body.put("scimType", refusal.scimType());
            // The error-class dimension of the request metric: "4xx by scimType".
            MetricTag.recordOnCurrentRequest(MetricTag.SCIM_TYPE, refusal.scimType());
        }
        body.put("detail", refusal.detail());
        return ResponseEntity.status(refusal.status())
                .header("Content-Type", ScimSchemas.MEDIA_TYPE)
                .body(body);
    }
}

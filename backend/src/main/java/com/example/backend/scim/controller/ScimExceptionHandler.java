package com.example.backend.scim.controller;

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
import com.example.backend.scim.domain.DuplicateDisplayNameException;
import com.example.backend.scim.domain.DuplicateUserNameException;
import com.example.backend.scim.domain.InvalidPreconditionException;
import com.example.backend.scim.domain.InvalidScimFilterException;
import com.example.backend.scim.domain.InvalidScimQueryException;
import com.example.backend.scim.domain.PasswordHistoryPolicy;
import com.example.backend.scim.domain.PasswordPolicyRefusedException;
import com.example.backend.scim.domain.PasswordReusedException;
import com.example.backend.scim.domain.PreconditionFailedException;
import com.example.backend.scim.domain.ProtectedResourceException;
import com.example.backend.scim.domain.ScimPatchRefusedException;
import com.example.backend.scim.domain.ScimRequestBodyTooLargeException;
import com.example.backend.scim.domain.ScimValueControlCharacterException;
import com.example.backend.scim.domain.ScimValueTooLongException;
import com.example.backend.scim.domain.UnknownGroupMemberException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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
 * <p>The body is built as an ordered map rather than a record so the field order is
 * {@code schemas}, {@code status}, {@code scimType}, {@code detail} as RFC 7644's
 * examples have it, and so {@code scimType} can be omitted entirely — rather than
 * rendered as null — where the RFC defines none for the condition.
 */
@RestControllerAdvice(basePackages = "com.example.backend.scim.controller")
class ScimExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ScimExceptionHandler.class);

    private static final String USERS_ROUTE = "/scim/v2/Users";

    private static final String GROUPS_ROUTE = "/scim/v2/Groups";

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
     * A {@code userName} already taken, translated at the boundary.
     *
     * <p>The domain exception carries no detail naming the value, and neither does this:
     * the caller knows which {@code userName} it sent, and echoing it back would put it
     * in a response body and in every log that records one.
     */
    @ExceptionHandler(DuplicateUserNameException.class)
    ResponseEntity<Map<String, Object>> handle(DuplicateUserNameException duplicate) {
        return render(ScimErrorException.uniqueness(
                "A User with the requested userName already exists."));
    }

    /**
     * A Group {@code displayName} already taken.
     *
     * <p>RFC 7643 does not make {@code displayName} unique; this directory does, because a Group's
     * membership confers authority and two Groups an administrator reads as the same name is how
     * membership of the wrong one gets granted. Advertised in the Group schema as
     * {@code uniqueness=server}, so a connector is told rather than discovering it here.
     */
    @ExceptionHandler(DuplicateDisplayNameException.class)
    ResponseEntity<Map<String, Object>> handle(DuplicateDisplayNameException duplicate) {
        return render(ScimErrorException.uniqueness(
                "A Group with the requested displayName already exists."));
    }

    /**
     * A member that is not a live User.
     *
     * <p>One message for three causes — an id naming a Group, a deleted User, or nothing at all —
     * because distinguishing them would disclose the existence and the type of resources the caller
     * has not been shown. The id is not echoed: the caller sent it, and an error body is also a log
     * line.
     */
    @ExceptionHandler(UnknownGroupMemberException.class)
    ResponseEntity<Map<String, Object>> handle(UnknownGroupMemberException unknownMember) {
        return render(ScimErrorException.invalidValue(
                "Every Group member must reference a live User."));
    }

    /** An {@code If-Match} that is a wildcard, a list, repeated, or not an entity tag. */
    @ExceptionHandler(InvalidPreconditionException.class)
    ResponseEntity<Map<String, Object>> handle(InvalidPreconditionException invalid) {
        return render(ScimErrorException.invalidValue(
                "If-Match must carry exactly one strong ETag; '*' and lists are not accepted."));
    }

    /** An {@code If-Match} naming a version another write has already replaced. */
    @ExceptionHandler(PreconditionFailedException.class)
    ResponseEntity<Map<String, Object>> handle(PreconditionFailedException stale) {
        return render(ScimErrorException.preconditionFailed(
                "The resource has changed since that ETag was issued; GET it and retry."));
    }

    /** A password breaking an intrinsic policy rule. Names the rule, never the value. */
    @ExceptionHandler(PasswordPolicyRefusedException.class)
    ResponseEntity<Map<String, Object>> handle(PasswordPolicyRefusedException refused) {
        return render(ScimErrorException.invalidValue(
                "The password does not satisfy the password policy (" + refused.rule().name()
                        + "). " + refused.rule().message() + "."));
    }

    /** A password the User has used recently. Names the rule, never the value. */
    @ExceptionHandler(PasswordReusedException.class)
    ResponseEntity<Map<String, Object>> handle(PasswordReusedException reused) {
        return render(ScimErrorException.invalidValue(
                "The password matches one of the User's " + PasswordHistoryPolicy.RETAINED
                        + " most recent passwords; a new value is required."));
    }

    /** A value longer than its stored limit. Names the attribute and the limit, never the value. */
    @ExceptionHandler(ScimValueTooLongException.class)
    ResponseEntity<Map<String, Object>> handle(ScimValueTooLongException tooLong) {
        return render(ScimErrorException.invalidValue(
                tooLong.attribute() + " must be at most " + tooLong.limit()
                        + " characters long."));
    }

    /**
     * A value carrying a character its attribute refuses — U+0000 anywhere, or a control in a
     * name. Names the attribute and the characters, never the value nor where in it they were.
     */
    @ExceptionHandler(ScimValueControlCharacterException.class)
    ResponseEntity<Map<String, Object>> handle(ScimValueControlCharacterException refused) {
        return render(ScimErrorException.invalidValue(refused.getMessage()));
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
        // The fault's one record. Attached as a redacted copy: the stack says where it failed,
        // and the message — which quotes the statement and the conflicting value — is replaced
        // by the cause's type, so no part of the refused row reaches the record.
        inResourceContext(resourceType -> withResourceType(
                        LogEvent.error(log, Operation.SCIM_WRITE, refusal.status().value(),
                                        ErrorCategory.DATABASE, Category.DATABASE, Type.ERROR)
                                .setCause(RedactedFaultException.of(violation, causeType))
                                .addKeyValue(LogEvent.REASON, causeType), resourceType)
                .log());
        RequestFault.recorded();
        return body(refusal);
    }

    /** A PATCH operation the stored resource cannot accept. */
    @ExceptionHandler(ScimPatchRefusedException.class)
    ResponseEntity<Map<String, Object>> handle(ScimPatchRefusedException refused) {
        return render(switch (refused.reason()) {
            case MUTABILITY -> ScimErrorException.mutability(refused.getMessage());
            case NO_TARGET -> ScimErrorException.noTarget(refused.getMessage());
        });
    }

    /**
     * A write aimed at a resource reserved for deployment recovery.
     *
     * <p>{@code mutability}, which is SCIM's {@code scimType} for an attempt to change something
     * that cannot be changed, with a {@code 400}. Deliberately NOT a {@code 403}: the caller's token
     * may hold every Permission the write needs, and the refusal is about the target rather than
     * about the credential — answering 403 would send an integrator to re-check its token's
     * Permissions (ADR 0006).
     *
     * <p>The detail says which kind of resource was protected but not which resource, and nothing
     * about why this deployment reserves it.
     */
    /** A filter this service does not evaluate. The message quotes no literal from it. */
    @ExceptionHandler(InvalidScimFilterException.class)
    ResponseEntity<Map<String, Object>> handle(InvalidScimFilterException invalid) {
        return render(ScimErrorException.invalidFilter(invalid.getMessage()));
    }

    /** A sort, paging value or search body this service cannot honour. */
    @ExceptionHandler(InvalidScimQueryException.class)
    ResponseEntity<Map<String, Object>> handle(InvalidScimQueryException invalid) {
        return render(ScimErrorException.invalidValue(invalid.getMessage()));
    }

    @ExceptionHandler(ProtectedResourceException.class)
    ResponseEntity<Map<String, Object>> handle(ProtectedResourceException protectedResource) {
        return render(ScimErrorException.mutability(
                switch (protectedResource.reservedName()) {
                    case BOOTSTRAP_ADMIN ->
                            "This User is reserved for deployment recovery; it cannot be deleted,"
                                    + " and its attributes and its Group membership cannot be"
                                    + " changed.";
                    case ADMIN_GROUP ->
                            "This Group is reserved for deployment recovery; it cannot be renamed"
                                    + " or deleted, though its ordinary membership may change.";
                }));
    }

    /**
     * Records the refusal, then renders it. Every handler but the integrity-violation one comes
     * through here, so each refusal gets exactly one record: {@code WARN} for the caller's
     * error, {@code ERROR} with the exception attached for a fault on this side. The record
     * carries the refusal's {@link ScimErrorException#reason() reason}, its status, and the
     * resource the request addressed where the route names one — never the detail, which
     * may name an attribute, and never anything the caller submitted.
     */
    private static ResponseEntity<Map<String, Object>> render(ScimErrorException refusal) {
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
        String resourceId = request == null ? null : resourceId(request);
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
    static String resourceType(HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)
                instanceof String route)) {
            return null;
        }
        if (isUnder(route, USERS_ROUTE)) {
            return "User";
        }
        return isUnder(route, GROUPS_ROUTE) ? "Group" : null;
    }

    static String resourceId(HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
                instanceof Map<?, ?> variables)
                || !(variables.get("id") instanceof String id)) {
            return null;
        }
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
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

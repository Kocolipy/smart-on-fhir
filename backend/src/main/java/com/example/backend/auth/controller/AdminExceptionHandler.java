package com.example.backend.auth.controller;

import com.example.backend.audit.domain.AuditTrail;
import com.example.backend.auth.application.AdministrativeRefusalException;
import com.example.backend.auth.application.GroupPermissionsChangeRefusedException;
import com.example.backend.scim.application.ConnectorAdministrationRefusedException;
import com.example.backend.scim.domain.InvalidConnectorTokenLifetimeException;
import com.example.backend.scim.domain.InvalidConnectorTokenPermissionsException;
import com.example.backend.web.AdminApiHandler;
import com.example.backend.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The edge of {@code /api/admin}: where every administrative refusal is answered, logged and
 * audited, once, from the one table that says how ({@link AdminRefusals}).
 *
 * <p>Scoped to the administrative controllers by their {@link AdminApiHandler} mark, whichever
 * module each lives in — connector administration is {@code scim}'s, served under {@code /api} —
 * and ordered ahead of the application API's advice, whose catch-all would otherwise answer a
 * refusal as a {@code 500}.
 * Everything else those controllers raise — a body failing validation, a fault — is still that
 * advice's, unchanged. The use cases throw and never record; this advice adapts each refusal to
 * its row, writes the row's record, appends its refused event — fail-open, after the use case's
 * transaction rolled back (ADR 0004) — and renders the answer.
 */
@RestControllerAdvice(annotations = AdminApiHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminExceptionHandler {

    private final AuditTrail audit;

    public AdminExceptionHandler(AuditTrail audit) {
        this.audit = audit;
    }

    /** A refused Unlock, or a refused change of a Group's Permissions. */
    @ExceptionHandler(AdministrativeRefusalException.class)
    ResponseEntity<ApiError> handle(AdministrativeRefusalException refused) {
        return refuse(AdminRefusals.of(refused));
    }

    /** A connector or token operation refused: an unknown target, or a token not rotatable. */
    @ExceptionHandler(ConnectorAdministrationRefusedException.class)
    ResponseEntity<ApiError> handle(ConnectorAdministrationRefusedException refused) {
        return refuse(AdminRefusals.of(refused));
    }

    /**
     * A lifetime past the ceiling is a bad request rather than a conflict: the request itself is
     * out of range, and reporting it lets the Admin see that the token they would have received
     * is not the one they asked for.
     */
    @ExceptionHandler(InvalidConnectorTokenLifetimeException.class)
    ResponseEntity<ApiError> handle(InvalidConnectorTokenLifetimeException refused) {
        return refuse(AdminRefusals.ofMalformedTokenRequest());
    }

    /**
     * No Permission, a name that is no Permission, or one a token cannot carry: the request itself
     * cannot be granted, whoever makes it.
     */
    @ExceptionHandler(InvalidConnectorTokenPermissionsException.class)
    ResponseEntity<ApiError> handle(InvalidConnectorTokenPermissionsException refused) {
        return refuse(AdminRefusals.ofMalformedTokenRequest());
    }

    /** Writes the row's record, appends its event, and answers its status and body. */
    private ResponseEntity<ApiError> refuse(AdminRefusals.Entry entry) {
        if (entry.logged() != null) {
            entry.logged().log();
        }
        if (entry.audited() != null) {
            audit.append(entry.audited());
        }
        ResponseEntity.BodyBuilder answer = ResponseEntity.status(entry.status());
        return switch (entry.body()) {
            case API_ERROR -> answer.contentType(MediaType.APPLICATION_JSON)
                    .body(ApiError.of(entry.status()));
            case NONE -> answer.build();
        };
    }

    /**
     * A Group Permissions body that is not JSON at all, which the binder refuses before the handler
     * runs, so the use case never sees it: refused here as the body that is no set of Permissions
     * it is ({@code INVALID_PERMISSIONS}), against the Group the path addressed.
     *
     * <p>An advice of its own because its scope is narrower than the edge's: of the administrative
     * controllers, only the Group Permissions route reads a body the binder can refuse unrecorded.
     * A connector request's unreadable body stays the application API's plain {@code 400}.
     */
    @RestControllerAdvice(assignableTypes = AdminGroupController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class UnreadableGroupPermissionsBody {

        private final AdminExceptionHandler edge;

        public UnreadableGroupPermissionsBody(AdminExceptionHandler edge) {
            this.edge = edge;
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        ResponseEntity<ApiError> handle(HttpServletRequest request) {
            return edge.handle(new GroupPermissionsChangeRefusedException(
                    GroupPermissionsChangeRefusedException.Reason.INVALID_PERMISSIONS,
                    HttpSessionAttributes.ownerOf(request), addressedGroup(request)));
        }

        /**
         * The Group the request's path names. The binder reads the path's id before the body, so
         * by the time the body is refused it is a well-formed UUID.
         */
        @SuppressWarnings("unchecked")
        private static UUID addressedGroup(HttpServletRequest request) {
            Map<String, String> variables = (Map<String, String>) request.getAttribute(
                    HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            return UUID.fromString(variables.get("id"));
        }
    }
}

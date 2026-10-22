package com.example.backend.auth.controller;

import com.example.backend.auth.application.GroupPermissionAdministrationService;
import com.example.backend.auth.application.GroupSummary;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.auth.application.InvalidGroupPermissionsRequestException;
import com.example.backend.auth.application.ProtectedGroupException;
import com.example.backend.auth.application.UnknownGroupException;
import com.example.backend.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * Inbound HTTP adapter for the Groups projection of the Accounts page, and the Superuser's one
 * write on a Group: its Permissions (ADR 0015).
 *
 * <p>Groups and their membership belong to the directory, written by a connector over SCIM, so
 * this adapter declares no write of either: a {@code POST}, {@code PUT}, {@code PATCH} or
 * {@code DELETE} of a Group itself is refused by the chain, which denies by default. What a Group
 * confers is the application's, and only the Superuser sets it, here and never over SCIM.
 *
 * <p>The listing requires {@code group:read}; setting a Group's Permissions requires the
 * Superuser. Each is declared on the handler and repeated by the chain as a backstop, for the
 * reason {@link AdminAccountController} gives.
 */
@RestController
@RequestMapping("/api/admin/groups")
public class AdminGroupController {

    private final IdentityAdministrationService identities;
    private final GroupPermissionAdministrationService groupPermissions;

    public AdminGroupController(
            IdentityAdministrationService identities,
            GroupPermissionAdministrationService groupPermissions) {
        this.identities = identities;
        this.groupPermissions = groupPermissions;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('group:read')")
    public List<GroupSummary> listGroups() {
        return identities.listGroups();
    }

    /**
     * Replaces the Group's Permissions with the body: the complete set, as an array of Permission
     * names, an empty one clearing it. No precondition: the last writer wins. Answers the Group's
     * row as it now stands.
     *
     * <p>The body is read as a JSON tree rather than bound to a list, so that a missing body, one
     * that is not an array, and an element that is not a name all reach the use case — which
     * audits each as the refusal it is — rather than being refused by the binder unrecorded.
     */
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasRole('SUPERUSER')")
    public GroupSummary setPermissions(@PathVariable UUID id,
            @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        return groupPermissions.setPermissions(
                id, names(body), HttpSessionAttributes.ownerOf(request));
    }

    /**
     * A body that is not JSON at all. Audited as the use case audits a body that is not a set of
     * names, then answered as one: the binder raised this before the handler ran.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadableBody(HttpServletRequest request) {
        groupPermissions.refuseUnreadableBody(
                addressedGroup(request), HttpSessionAttributes.ownerOf(request));
        return answer(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(InvalidGroupPermissionsRequestException.class)
    public ResponseEntity<ApiError> invalidPermissions() {
        return answer(HttpStatus.BAD_REQUEST);
    }

    /**
     * The caller is the Superuser, so naming what is missing reveals nothing the listing would
     * not.
     */
    @ExceptionHandler(UnknownGroupException.class)
    public ResponseEntity<ApiError> unknownGroup() {
        return answer(HttpStatus.NOT_FOUND);
    }

    /**
     * The Admin group: the request is well formed and the caller may make it, but the Group's
     * members' power is the Superuser, which is not a Permission.
     */
    @ExceptionHandler(ProtectedGroupException.class)
    public ResponseEntity<ApiError> protectedGroup() {
        return answer(HttpStatus.CONFLICT);
    }

    /**
     * The names a body that is an array of strings carries, in order and duplicates kept — the use
     * case collapses them — or {@code null} for a body that is missing, is not an array, or holds
     * anything but strings.
     */
    private static List<String> names(JsonNode body) {
        if (body == null || !body.isArray()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode element : body) {
            if (!element.isString()) {
                return null;
            }
            names.add(element.asString());
        }
        return names;
    }

    /**
     * The Group the request's path names. Only an unreadable body asks, and the binder reads the
     * path's id before the body, so by then it is a well-formed UUID.
     */
    @SuppressWarnings("unchecked")
    private static UUID addressedGroup(HttpServletRequest request) {
        Map<String, String> variables = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return UUID.fromString(variables.get("id"));
    }

    private static ResponseEntity<ApiError> answer(HttpStatus status) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(status));
    }
}

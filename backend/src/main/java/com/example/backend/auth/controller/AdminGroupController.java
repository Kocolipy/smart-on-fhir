package com.example.backend.auth.controller;

import com.example.backend.auth.application.GroupPermissionAdministrationService;
import com.example.backend.auth.application.GroupSummary;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.web.AdminApiHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
@AdminApiHandler
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
     * refuses each as the refusal it is — rather than being refused by the binder. A body that is
     * not JSON at all is still the binder's to refuse, and the administrative edge records it as
     * the same refusal ({@link AdminExceptionHandler.UnreadableGroupPermissionsBody}). Every
     * refusal is answered, logged and audited there, from the one table of administrative
     * refusals.
     */
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasRole('SUPERUSER')")
    public GroupSummary setPermissions(@PathVariable UUID id,
            @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        return groupPermissions.setPermissions(
                id, names(body), HttpSessionAttributes.ownerOf(request));
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
}

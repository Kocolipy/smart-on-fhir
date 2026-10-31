package com.example.backend.auth.controller;

import com.example.backend.auth.application.GroupMemberPage;
import com.example.backend.auth.application.GroupPage;
import com.example.backend.auth.application.GroupPermissionAdministrationService;
import com.example.backend.auth.application.GroupSummary;
import com.example.backend.auth.application.IdentityAdministrationService;
import com.example.backend.paging.domain.PageRequest;
import com.example.backend.web.AdminApiHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
 * <p>The listing requires {@code group:read}; reading a Group's direct members requires
 * {@code user:read} as well, since who they are is Users-projection data; setting a Group's
 * Permissions requires the Superuser. Each is declared on the handler and repeated by the chain
 * as a backstop, for the reason {@link AdminAccountController} gives.
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

    /**
     * One page of the Groups projection: the Groups whose display name contains {@code search},
     * ignoring case — every Group without one — sorted by {@code sortBy} and {@code sortOrder} as
     * SCIM sorts, or in normalized display-name order without a {@code sortBy}, with how many
     * matched; paged exactly as {@link #members} is. A sort SCIM refuses is a {@code 400}, as the
     * Users projection's is.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('group:read')")
    public GroupPage listGroups(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(required = false) Integer startIndex,
            @RequestParam(required = false) Integer count) {
        return InvalidProjectionSortException.translating(() -> identities.listGroups(
                search, sortBy, sortOrder, PageRequest.of(startIndex, count)));
    }

    /**
     * One page of the Group's direct members, paged as a SCIM collection is: {@code startIndex}
     * one-based, {@code count} defaulted and capped, each out-of-range value coerced rather than
     * refused ({@link PageRequest#of}). Requires both {@code group:read} and
     * {@code user:read}: who a Group's members are is Users-projection data, and
     * {@code group:read} alone discloses only how many there are (ADR 0015). No Group by that id
     * is a {@code 404} naming nothing.
     */
    @GetMapping("/{id}/members")
    @PreAuthorize("hasAuthority('group:read') and hasAuthority('user:read')")
    public GroupMemberPage members(@PathVariable UUID id,
            @RequestParam(required = false) Integer startIndex,
            @RequestParam(required = false) Integer count) {
        return identities.groupMembers(id, PageRequest.of(startIndex, count))
                .orElseThrow(NoSuchGroupException::new);
    }

    /** No Group is held under the id asked for. It carries no message and no id. */
    static final class NoSuchGroupException extends ResponseStatusException {

        NoSuchGroupException() {
            super(HttpStatus.NOT_FOUND);
        }
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

package com.example.backend.scim.controller;

import com.example.backend.scim.application.ScimSearchListing;
import com.example.backend.scim.application.ScimSearchService;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /scim/v2/.search}: one {@code SearchRequest} over Users and Groups together, as
 * RFC 7644 §3.4.3 defines a search at the service's root.
 *
 * <p>A path valid for either type is valid here; where one type lacks it, the filter treats it
 * as having no value and the projection does not apply to that type. A token needs at least one
 * read Permission — {@code user:read} or {@code group:read}, declared on the handler (ADR 0010) —
 * and gets only the types it may read, which the search itself narrows to; the request is audited
 * as one bulk read.
 */
@RestController
class ScimSearchController {

    /** Both resource types, which is what makes this the base search. */
    private static final Set<ScimResourceType> TYPES =
            Set.of(ScimResourceType.USER, ScimResourceType.GROUP);

    private final ScimSearchService search;

    ScimSearchController(ScimSearchService search) {
        this.search = search;
    }

    @PreAuthorize("hasAnyAuthority('user:read', 'group:read')")
    @PostMapping(
            path = ScimSchemas.BASE_PATH + "/.search",
            consumes = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> search(
            @AuthenticationPrincipal AuthenticatedConnector connector,
            @RequestBody JsonNode body) {
        ScimQueryRequest request = ScimQueryRequest.fromSearchRequest(TYPES, body);
        ScimAttributeProjection.Search projection = ScimAttributeProjection.forSearch(
                request.attributes(), request.excludedAttributes());
        String baseUri = ScimBaseUri.current();
        ScimSearchListing listing = search.search(connector, request.query(), baseUri);
        return ResponseEntity.ok(ScimSearchRenderer.renderList(listing, baseUri, projection));
    }
}

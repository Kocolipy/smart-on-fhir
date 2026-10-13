package com.example.backend.scim.controller;

import com.example.backend.scim.application.NewScimUser;
import com.example.backend.scim.application.ScimUserListing;
import com.example.backend.scim.application.ScimUserReplacement;
import com.example.backend.scim.application.ScimUserResource;
import com.example.backend.scim.application.ScimUserService;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.ScimResourceType;
import com.example.backend.scim.domain.ScimUserPatchOperation;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The SCIM User endpoints a connector calls: create, retrieve one, retrieve a page, replace,
 * patch.
 *
 * <p>Each handler declares the Permission it needs with method security, as the application
 * chain's handlers do (ADR 0010): {@code user:read} to read or search, {@code user:write} to
 * create, replace, patch or delete. The declaration is the authority, because it sits on the
 * operation the dispatcher actually selected; the namespace's bearer filter, which judges the
 * request line before dispatch, is the backstop. A handler added without a declaration fails
 * {@code ArchitectureTest.every_protected_handler_declares_a_permission}.
 *
 * <p>The connector arrives as the authenticated principal and is passed on to the use case
 * rather than read from a static context there, because an {@code externalId} is
 * connector-scoped and an audit event names the actor. Both would be silent defects if the
 * identity were optional.
 */
@RestController
@RequestMapping(ScimSchemas.USERS_PATH)
class ScimUserController {

    /** The resource type every query on this endpoint is over. */
    private static final Set<ScimResourceType> TYPES = Set.of(ScimResourceType.USER);

    private final ScimUserService users;

    ScimUserController(ScimUserService users) {
        this.users = users;
    }

    /**
     * Creates a User.
     *
     * <p>{@code 201} with the canonical resource, its {@code Location} and a strong
     * {@code ETag} — the three things RFC 7644 §3.3 requires of a create — and no password,
     * whatever was sent, because the projection this renders has no field for one.
     */
    @PreAuthorize("hasAuthority('user:write')")
    @PostMapping(
            consumes = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal AuthenticatedConnector connector,
            @RequestBody JsonNode body,
            @RequestParam(required = false) String attributes,
            @RequestParam(required = false) String excludedAttributes) {
        ScimAttributeProjection projection =
                ScimAttributeProjection.ofUser(attributes, excludedAttributes);
        NewScimUser command = ScimUserRequestReader.readCreate(body);
        ScimUserResource created = users.create(connector, command);
        String baseUri = baseUri();
        return ResponseEntity.created(java.net.URI.create(
                        ScimUserRenderer.location(baseUri, created)))
                .eTag(ScimUserRenderer.etag(created.version()))
                .body(projection.apply(ScimUserRenderer.render(created, baseUri)));
    }

    /**
     * One User by its id.
     *
     * <p>Not audited, deliberately: a single-resource read is the ordinary unit of
     * provisioning traffic, and recording it would bury the collection reads that indicate
     * an enumeration. The absence of an audit call is in the use case, where the read is.
     */
    @PreAuthorize("hasAuthority('user:read')")
    @GetMapping(
            path = ScimSchemas.ID_PATH,
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> byId(
            @AuthenticationPrincipal AuthenticatedConnector connector,
            @PathVariable String id,
            @RequestParam(required = false) String attributes,
            @RequestParam(required = false) String excludedAttributes) {
        ScimAttributeProjection projection =
                ScimAttributeProjection.ofUser(attributes, excludedAttributes);
        ScimUserResource user = users.findById(connector, resourceId(id))
                .orElseThrow(() -> ScimErrorException.notFound("No User has that id."));
        String baseUri = baseUri();
        return ResponseEntity.ok()
                .eTag(ScimUserRenderer.etag(user.version()))
                .header(HttpHeaders.LOCATION, ScimUserRenderer.location(baseUri, user))
                .body(projection.apply(ScimUserRenderer.render(user, baseUri)));
    }

    /**
     * A query of the User collection — RFC 7644 §3.4.2, written as query parameters.
     *
     * <p>Filtering, sorting, paging and projection all apply. A malformed filter is a
     * {@code 400 invalidFilter} and an unsortable {@code sortBy} a {@code 400 invalidValue}, both
     * refused before anything is read.
     */
    @PreAuthorize("hasAuthority('user:read')")
    @GetMapping(produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> page(
            @AuthenticationPrincipal AuthenticatedConnector connector,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(required = false) String startIndex,
            @RequestParam(required = false) String count,
            @RequestParam(required = false) String attributes,
            @RequestParam(required = false) String excludedAttributes) {
        return respond(connector, ScimQueryRequest.fromParameters(
                TYPES, filter, sortBy, sortOrder, startIndex, count, attributes,
                excludedAttributes));
    }

    /**
     * The same query, written as a {@code SearchRequest} body — RFC 7644 §3.4.3.
     *
     * <p>The path a connector should prefer when its filter carries personal values, since a body
     * stays out of URLs and access logs. A {@code POST} that reads: it needs {@code user:read},
     * not {@code user:write}, and it is audited as the same bulk read the {@code GET} is.
     */
    @PreAuthorize("hasAuthority('user:read')")
    @PostMapping(
            path = "/.search",
            consumes = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> search(
            @AuthenticationPrincipal AuthenticatedConnector connector,
            @RequestBody JsonNode body) {
        return respond(connector, ScimQueryRequest.fromSearchRequest(TYPES, body));
    }

    /**
     * Runs a query and renders its page. The projection is validated before the query runs, so a
     * bad attribute path is refused without a read.
     */
    private ResponseEntity<Map<String, Object>> respond(
            AuthenticatedConnector connector, ScimQueryRequest request) {
        ScimAttributeProjection projection = ScimAttributeProjection.ofUser(
                request.attributes(), request.excludedAttributes());
        String baseUri = baseUri();
        ScimUserListing listing = users.query(connector, request.query(), baseUri);
        return ResponseEntity.ok(ScimUserRenderer.renderList(listing, baseUri, projection));
    }

    /**
     * Replaces a User — PUT.
     *
     * <p>{@code If-Match} is optional; when sent it must be exactly one current ETag (see
     * {@link ScimIfMatch}). The header is captured here and evaluated by the use case once the User is found, so an id that
     * names nothing is a {@code 404} whatever the header says. The body is read BEFORE the User is
     * looked up, so a malformed body is refused without touching the directory.
     *
     * <p>{@code 200} with the canonical resource, its {@code Location} and its new {@code ETag}.
     */
    @PreAuthorize("hasAuthority('user:write')")
    @PutMapping(
            path = ScimSchemas.ID_PATH,
            consumes = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> replace(
            @AuthenticationPrincipal AuthenticatedConnector connector,
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @RequestBody JsonNode body,
            @RequestParam(required = false) String attributes,
            @RequestParam(required = false) String excludedAttributes) {
        ScimAttributeProjection projection =
                ScimAttributeProjection.ofUser(attributes, excludedAttributes);
        UUID userId = resourceId(id);
        ScimUserReplacement replacement = ScimUserRequestReader.readReplace(body);
        ScimUserResource written = users.replace(
                        connector, userId, ScimIfMatch.capture(ifMatch), replacement)
                .orElseThrow(ScimUserController::noSuchUser);
        return ok(written, projection);
    }

    /**
     * Applies PATCH operations to a User, all or nothing — PATCH.
     *
     * <p>{@code 200} with the patched resource rather than a {@code 204}, which RFC 7644 §3.5.2
     * permits either of: a client that received no body would have to re-read the resource to
     * learn the new {@code ETag} before its next conditional write.
     */
    @PreAuthorize("hasAuthority('user:write')")
    @PatchMapping(
            path = ScimSchemas.ID_PATH,
            consumes = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> patch(
            @AuthenticationPrincipal AuthenticatedConnector connector,
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch,
            @RequestBody JsonNode body,
            @RequestParam(required = false) String attributes,
            @RequestParam(required = false) String excludedAttributes) {
        ScimAttributeProjection projection =
                ScimAttributeProjection.ofUser(attributes, excludedAttributes);
        UUID userId = resourceId(id);
        List<ScimUserPatchOperation> operations = ScimUserPatchReader.readPatch(body);
        ScimUserResource written = users.patch(
                        connector, userId, ScimIfMatch.capture(ifMatch), operations)
                .orElseThrow(ScimUserController::noSuchUser);
        return ok(written, projection);
    }

    /**
     * Deletes a User — DELETE.
     *
     * <p>{@code 204} with no body, as RFC 7644 §3.6 requires, under the same {@code If-Match} rules
     * as PUT and PATCH. Every later operation on the id is a {@code 404}, and the former
     * {@code userName} is free for the next create.
     */
    @PreAuthorize("hasAuthority('user:write')")
    @DeleteMapping(path = ScimSchemas.ID_PATH)
    ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedConnector connector,
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch) {
        if (!users.delete(connector, resourceId(id), ScimIfMatch.capture(ifMatch))) {
            throw noSuchUser();
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Map<String, Object>> ok(
            ScimUserResource user, ScimAttributeProjection projection) {
        String baseUri = baseUri();
        return ResponseEntity.ok()
                .eTag(ScimUserRenderer.etag(user.version()))
                .header(HttpHeaders.LOCATION, ScimUserRenderer.location(baseUri, user))
                .body(projection.apply(ScimUserRenderer.render(user, baseUri)));
    }

    /** The one refusal for every "no User here" case, so a caller cannot tell them apart. */
    private static ScimErrorException noSuchUser() {
        return ScimErrorException.notFound("No User has that id.");
    }

    /**
     * The id as a resource id.
     *
     * <p>A malformed id is a {@code 404} rather than a {@code 400}: every SCIM id this
     * service issues is a UUID, so a value that is not one names no resource, and telling a
     * caller that its id was the wrong SHAPE reveals what shape the real ones have. The
     * answer for "this id names nothing" is the same either way.
     */
    private static UUID resourceId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            throw ScimErrorException.notFound("No User has that id.");
        }
    }


    private static String baseUri() {
        return ScimBaseUri.current();
    }
}

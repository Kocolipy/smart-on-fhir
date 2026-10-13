package com.example.backend.scim.controller;

import com.example.backend.scim.application.NewScimGroup;
import com.example.backend.scim.application.ScimGroupListing;
import com.example.backend.scim.application.ScimGroupPatchOperation;
import com.example.backend.scim.application.ScimGroupReplacement;
import com.example.backend.scim.application.ScimGroupResource;
import com.example.backend.scim.application.ScimGroupService;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.ScimResourceType;
import java.net.URI;
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
 * The SCIM Group endpoints a connector calls: create, retrieve one, retrieve a page, replace, patch,
 * delete.
 *
 * <p>Each handler declares the Permission it needs with method security, as the application chain's
 * handlers do (ADR 0010): {@code group:read} to read or search, {@code group:write} to create,
 * replace, patch or delete. The declaration is the authority, because it sits on the operation the
 * dispatcher actually selected; the namespace's bearer filter, which judges the request line before
 * dispatch, is the backstop. A handler added without a declaration fails
 * {@code ArchitectureTest.every_protected_handler_declares_a_permission}.
 *
 * <p>Nor is there a protected-resource check here. Whether the Admin group may be renamed is a rule
 * about the directory, not about HTTP, so it lives in {@link ScimGroupService} where every write path
 * passes through it — including the ones a later ticket adds.
 *
 * <p>The connector arrives as the authenticated principal and is passed on to the use case rather
 * than read from a static context there, because an {@code externalId} is connector-scoped and an
 * audit event names the actor. Both would be silent defects if the identity were optional.
 */
@RestController
@RequestMapping(ScimSchemas.GROUPS_PATH)
class ScimGroupController {

    /** The resource type every query on this endpoint is over. */
    private static final Set<ScimResourceType> TYPES = Set.of(ScimResourceType.GROUP);

    private final ScimGroupService groups;

    ScimGroupController(ScimGroupService groups) {
        this.groups = groups;
    }

    /**
     * Creates a Group.
     *
     * <p>{@code 201} with the canonical resource, its {@code Location} and a strong {@code ETag} —
     * the three things RFC 7644 §3.3 requires of a create.
     */
    @PreAuthorize("hasAuthority('group:write')")
    @PostMapping(
            consumes = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE},
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal AuthenticatedConnector connector,
            @RequestBody JsonNode body,
            @RequestParam(required = false) String attributes,
            @RequestParam(required = false) String excludedAttributes) {
        ScimAttributeProjection projection =
                ScimAttributeProjection.ofGroup(attributes, excludedAttributes);
        NewScimGroup command = ScimGroupRequestReader.readCreate(body);
        ScimGroupResource created = groups.create(connector, command);
        String baseUri = baseUri();
        return ResponseEntity.created(URI.create(ScimGroupRenderer.location(baseUri, created)))
                .eTag(ScimGroupRenderer.etag(created.version()))
                .body(projection.apply(ScimGroupRenderer.render(created, baseUri)));
    }

    /**
     * One Group by its id.
     *
     * <p>Not audited, deliberately: a single-resource read is the ordinary unit of provisioning
     * traffic, and recording it would bury the collection reads that indicate an enumeration. The
     * absence of an audit call is in the use case, where the read is.
     */
    @PreAuthorize("hasAuthority('group:read')")
    @GetMapping(
            path = ScimSchemas.ID_PATH,
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> byId(
            @AuthenticationPrincipal AuthenticatedConnector connector,
            @PathVariable String id,
            @RequestParam(required = false) String attributes,
            @RequestParam(required = false) String excludedAttributes) {
        ScimAttributeProjection projection =
                ScimAttributeProjection.ofGroup(attributes, excludedAttributes);
        ScimGroupResource group = groups.findById(connector, resourceId(id))
                .orElseThrow(ScimGroupController::noSuchGroup);
        String baseUri = baseUri();
        return ResponseEntity.ok()
                .eTag(ScimGroupRenderer.etag(group.version()))
                .header(HttpHeaders.LOCATION, ScimGroupRenderer.location(baseUri, group))
                .body(projection.apply(ScimGroupRenderer.render(group, baseUri)));
    }

    /**
     * A query of the Group collection — RFC 7644 §3.4.2, written as query parameters.
     *
     * <p>Filtering, sorting, paging and projection all apply. A malformed filter is a
     * {@code 400 invalidFilter} and an unsortable {@code sortBy} a {@code 400 invalidValue}, both
     * refused before anything is read.
     */
    @PreAuthorize("hasAuthority('group:read')")
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
     * <p>A {@code POST} that reads: it needs {@code group:read}, not {@code group:write}, and it is
     * audited as the same bulk read the {@code GET} is.
     */
    @PreAuthorize("hasAuthority('group:read')")
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
        ScimAttributeProjection projection = ScimAttributeProjection.ofGroup(
                request.attributes(), request.excludedAttributes());
        String baseUri = baseUri();
        ScimGroupListing listing = groups.query(connector, request.query(), baseUri);
        return ResponseEntity.ok(ScimGroupRenderer.renderList(listing, baseUri, projection));
    }

    /**
     * Replaces a Group's writable attributes.
     *
     * <p>{@code If-Match} is optional; when sent it must be exactly one current ETag, as on every
     * write against an existing resource. The use case evaluates it once the Group is found, so an unknown id is a
     * {@code 404} whatever the header says.
     */
    @PreAuthorize("hasAuthority('group:write')")
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
                ScimAttributeProjection.ofGroup(attributes, excludedAttributes);
        ScimGroupReplacement replacement = ScimGroupRequestReader.readReplace(body);
        ScimGroupResource written = groups.replace(
                        connector, resourceId(id), ScimIfMatch.capture(ifMatch),
                        replacement)
                .orElseThrow(ScimGroupController::noSuchGroup);
        return ok(written, projection);
    }

    /**
     * Applies PATCH operations to a Group.
     *
     * <p>Returns the patched resource with a {@code 200} rather than a {@code 204}, which RFC 7644
     * §3.5.2 permits either of: a membership change advances versions, and a client that received no
     * body would have to re-read the resource to learn its new ETag before it could make a
     * conditional write.
     */
    @PreAuthorize("hasAuthority('group:write')")
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
                ScimAttributeProjection.ofGroup(attributes, excludedAttributes);
        List<ScimGroupPatchOperation> operations = ScimGroupRequestReader.readPatch(body);
        ScimGroupResource written = groups.patch(
                        connector, resourceId(id), ScimIfMatch.capture(ifMatch),
                        operations)
                .orElseThrow(ScimGroupController::noSuchGroup);
        return ok(written, projection);
    }

    /**
     * Deletes a Group.
     *
     * <p>{@code 204} with no body, as RFC 7644 §3.6 requires. A Group that is not there is a
     * {@code 404} rather than a silent success: a connector converging on a desired state needs to
     * know whether the id it holds was ever real.
     */
    @PreAuthorize("hasAuthority('group:write')")
    @DeleteMapping(path = ScimSchemas.ID_PATH)
    ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedConnector connector,
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) List<String> ifMatch) {
        if (!groups.delete(connector, resourceId(id), ScimIfMatch.capture(ifMatch))) {
            throw noSuchGroup();
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Map<String, Object>> ok(
            ScimGroupResource group, ScimAttributeProjection projection) {
        String baseUri = baseUri();
        return ResponseEntity.ok()
                .eTag(ScimGroupRenderer.etag(group.version()))
                .header(HttpHeaders.LOCATION, ScimGroupRenderer.location(baseUri, group))
                .body(projection.apply(ScimGroupRenderer.render(group, baseUri)));
    }

    /**
     * The id as a resource id.
     *
     * <p>A malformed id is a {@code 404} rather than a {@code 400}: every SCIM id this service issues
     * is a UUID, so a value that is not one names no resource, and telling a caller that its id was
     * the wrong SHAPE reveals what shape the real ones have. The answer for "this id names nothing"
     * is the same either way.
     */
    private static UUID resourceId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAnId) {
            throw noSuchGroup();
        }
    }

    /**
     * The one refusal for every "no Group here" case — an unknown id, a malformed id, and an id that
     * names a User.
     *
     * <p>One message for all three so a caller cannot tell them apart. Distinguishing them would
     * disclose which ids exist and what type they are.
     */
    private static ScimErrorException noSuchGroup() {
        return ScimErrorException.notFound("No Group has that id.");
    }

    private static String baseUri() {
        return ScimBaseUri.current();
    }
}

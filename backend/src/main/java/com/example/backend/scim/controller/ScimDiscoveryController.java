package com.example.backend.scim.controller;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The discovery endpoints: what this service can do, and the resources and schemas it serves.
 *
 * <p>Readable with any valid connector token and no Permission (ADR 0010): a connector reads
 * them before it knows which Permissions it was given. They disclose capabilities and schemas
 * and no directory content, and they are GET-only. So no handler here declares a Permission —
 * deliberately, and listed as token-only in
 * {@code ArchitectureTest.every_protected_handler_declares_a_permission} — while the Users and
 * Groups handlers declare theirs.
 *
 * <p>Every handler refuses a {@code filter} parameter. RFC 7644 has a provider IGNORE
 * filtering on discovery endpoints, and ignoring it is the one answer a client cannot tell
 * from a match: a connector that asked for the ServiceProviderConfig matching some
 * predicate and received the whole document would conclude the predicate held. The refusal
 * is a {@code 403}, which is the same answer a filtered resource query gets while filtering
 * is unimplemented, so one rule covers the namespace.
 */
@RestController
@RequestMapping(ScimSchemas.BASE_PATH)
class ScimDiscoveryController {

    /**
     * Rejects a filter on a discovery path.
     *
     * <p>Declared as a parameter on every handler rather than checked in a filter, because
     * a filter would have to know which paths are discovery paths — knowledge that already
     * lives in this class's request mappings and would then live in two places.
     */
    private static void rejectFilter(String filter) {
        if (filter != null) {
            throw ScimErrorException.unsupportedQuery(
                    "Discovery endpoints do not support filtering.");
        }
    }

    @GetMapping(
            path = "/ServiceProviderConfig",
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> serviceProviderConfig(
            @RequestParam(required = false) String filter) {
        rejectFilter(filter);
        return ResponseEntity.ok(ScimDiscovery.serviceProviderConfig(ScimBaseUri.current()));
    }

    @GetMapping(
            path = "/ResourceTypes",
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> resourceTypes(
            @RequestParam(required = false) String filter) {
        rejectFilter(filter);
        return ResponseEntity.ok(
                ScimDiscovery.listResponse(ScimDiscovery.resourceTypes(ScimBaseUri.current())));
    }

    @GetMapping(
            path = "/ResourceTypes/{id}",
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> resourceType(
            @PathVariable String id, @RequestParam(required = false) String filter) {
        rejectFilter(filter);
        return ScimDiscovery.resourceTypes(ScimBaseUri.current()).stream()
                .filter(resourceType -> id.equals(resourceType.get("id")))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElseThrow(() -> ScimErrorException.notFound(
                        "This service serves no such resource type."));
    }

    @GetMapping(
            path = "/Schemas",
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> schemas(
            @RequestParam(required = false) String filter) {
        rejectFilter(filter);
        return ResponseEntity.ok(
                ScimDiscovery.listResponse(ScimDiscovery.schemas(ScimBaseUri.current())));
    }

    /**
     * One schema by its URI.
     *
     * <p>The path variable is the schema URI itself, colons and all, which is how RFC 7644
     * spells this endpoint. It is compared for exact equality because a schema URI is
     * case-sensitive and is an identifier rather than a name.
     */
    @GetMapping(
            path = "/Schemas/{schemaUri}",
            produces = {ScimSchemas.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    ResponseEntity<Map<String, Object>> schema(
            @PathVariable String schemaUri, @RequestParam(required = false) String filter) {
        rejectFilter(filter);
        return ScimDiscovery.schemas(ScimBaseUri.current()).stream()
                .filter(schema -> schemaUri.equals(schema.get("id")))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElseThrow(() -> ScimErrorException.notFound(
                        "This service serves no such schema."));
    }
}

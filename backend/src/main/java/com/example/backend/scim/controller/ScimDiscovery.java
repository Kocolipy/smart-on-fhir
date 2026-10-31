package com.example.backend.scim.controller;

import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.domain.ScimResourceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The discovery documents, built from what this service actually implements.
 *
 * <p>Discovery is a promise a connector configures itself from, so the only failure mode
 * that matters is advertising a capability that is not there: a client that reads
 * {@code filter.supported=true} sends filters, believes the results are filtered, and
 * provisions against a directory it has misread. Each flag below is therefore paired with
 * a test that exercises the corresponding request and asserts the flag agrees with the
 * answer — {@code patch.supported=false} beside a PATCH that is refused,
 * {@code filter.supported=true} beside filtered GETs and searches that are honoured, and
 * {@code etag.supported=true} beside a create whose response carries one.
 *
 * <p>The flags are constants here rather than in the rendered map so those tests can name
 * them, which is what makes the pairing an assertion rather than a comment.
 */
public final class ScimDiscovery {

    /**
     * PATCH is implemented for Groups — which is what makes membership
     * changes usable: a provisioning system that had to PUT a Group to add one member would
     * have to send the whole membership every time, and would overwrite concurrent changes
     * it never read.
     *
     * <p>Advertised as one flag because SCIM has one, even though Users do not accept PATCH
     * until the conditional-write ticket. That is the honest reading of the capability: the
     * service does support PATCH, and which resources accept it is what the ResourceType
     * documents and the endpoints themselves say. The alternative — advertising false while
     * {@code /Groups} answers a PATCH — would be the failure mode this whole document exists
     * to avoid, in reverse.
     */
    public static final boolean PATCH_SUPPORTED = true;

    /**
     * Bulk is not implemented and will not be: no Bulk support at all is a settled
     * decision, so this is a permanent answer rather than a staged one. Its limits
     * are advertised as zero because RFC 7643 requires the sub-attributes to be present,
     * and zero is the honest value for an operation that cannot be performed.
     */
    public static final boolean BULK_SUPPORTED = false;

    public static final int BULK_MAX_OPERATIONS = 0;

    public static final int BULK_MAX_PAYLOAD_SIZE = 0;

    /**
     * The full RFC 7644 filter grammar over every advertised attribute, on {@code GET} and on
     * all three {@code .search} endpoints.
     */
    public static final boolean FILTER_SUPPORTED = true;

    /** Sorting by any advertised simple attribute or sub-attribute. */
    public static final boolean SORT_SUPPORTED = true;

    /**
     * SCIM's {@code changePassword} capability means a client may change a User's password
     * through the protocol, which PUT and PATCH now do — subject to the password history, so a
     * connector re-sending a recent password is refused with {@code invalidValue}.
     */
    public static final boolean CHANGE_PASSWORD_SUPPORTED = true;

    /**
     * ETags are implemented: every User read and create carries a strong
     * validator, and {@code meta.version} carries the same string.
     */
    public static final boolean ETAG_SUPPORTED = true;

    private ScimDiscovery() {
    }

    /**
     * The capability document.
     *
     * @param baseUri absolute URI of this service's SCIM base, with no trailing slash, so
     *                {@code meta.location} is absolute as RFC 7643 §3.1 requires
     */
    public static Map<String, Object> serviceProviderConfig(String baseUri) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemas", List.of(ScimSchemas.SERVICE_PROVIDER_CONFIG));
        document.put("patch", Map.of("supported", PATCH_SUPPORTED));
        document.put("bulk", Map.of(
                "supported", BULK_SUPPORTED,
                "maxOperations", BULK_MAX_OPERATIONS,
                "maxPayloadSize", BULK_MAX_PAYLOAD_SIZE));
        // maxResults is the page ceiling every collection response obeys, and it comes from the
        // same constant the paging rule enforces rather than from a number written twice.
        document.put("filter", Map.of(
                "supported", FILTER_SUPPORTED,
                "maxResults", PageRequest.MAX_COUNT));
        document.put("changePassword", Map.of("supported", CHANGE_PASSWORD_SUPPORTED));
        document.put("sort", Map.of("supported", SORT_SUPPORTED));
        document.put("etag", Map.of("supported", ETAG_SUPPORTED));
        document.put("authenticationSchemes", List.of(bearerScheme()));
        document.put("meta", Map.of(
                "resourceType", "ServiceProviderConfig",
                "location", baseUri + "/ServiceProviderConfig"));
        return document;
    }

    /**
     * The one authentication scheme: a bearer token this deployment issues itself.
     *
     * <p>Typed {@code oauthbearertoken} because that is RFC 7644's name for "a bearer
     * token in the Authorization header", which is what the credential is. It is
     * deliberately not described as OAuth 2.0 in any other sense: there is no
     * authorization server, no grant flow and no refresh token, and a connector that read
     * one into this would look for endpoints that do not exist.
     */
    private static Map<String, Object> bearerScheme() {
        Map<String, Object> scheme = new LinkedHashMap<>();
        scheme.put("type", "oauthbearertoken");
        scheme.put("name", "OAuth Bearer Token");
        scheme.put("description",
                "An opaque bearer token issued by an administrator of this deployment and"
                        + " presented in the Authorization header. There is no authorization"
                        + " server and no grant flow.");
        scheme.put("specUri", "https://www.rfc-editor.org/info/rfc6750");
        scheme.put("primary", true);
        return scheme;
    }

    /**
     * The resource types this service serves.
     *
     * <p>Both of them. A resource type in this list is a claim that its endpoint answers —
     * Users and Groups are one release capability precisely so that this list never
     * advertises half a directory.
     */
    static List<Map<String, Object>> resourceTypes(String baseUri) {
        return List.of(userResourceType(baseUri), groupResourceType(baseUri));
    }

    /** The schema documents this service serves: the core User and Group schemas. */
    static List<Map<String, Object>> schemas(String baseUri) {
        return List.of(
                ScimSchemaDocuments.of(ScimResourceType.USER, baseUri),
                ScimSchemaDocuments.of(ScimResourceType.GROUP, baseUri));
    }

    static Map<String, Object> userResourceType(String baseUri) {
        return resourceType(
                baseUri,
                ScimResourceType.USER.resourceTypeName(),
                "/Users",
                "SCIM core User.",
                ScimSchemas.USER);
    }

    static Map<String, Object> groupResourceType(String baseUri) {
        return resourceType(
                baseUri,
                ScimResourceType.GROUP.resourceTypeName(),
                "/Groups",
                "SCIM core Group. Membership confers application authority.",
                ScimSchemas.GROUP);
    }

    private static Map<String, Object> resourceType(
            String baseUri, String name, String endpoint, String description, String schema) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemas", List.of(ScimSchemas.RESOURCE_TYPE));
        document.put("id", name);
        document.put("name", name);
        document.put("endpoint", endpoint);
        document.put("description", description);
        document.put("schema", schema);
        // No schemaExtensions: this service implements no extension, and an empty array
        // would still be a claim that extensions are a thing here.
        document.put("meta", Map.of(
                "resourceType", "ResourceType",
                "location", baseUri + "/ResourceTypes/" + name));
        return document;
    }

    /**
     * A discovery collection as a {@code ListResponse}.
     *
     * <p>Discovery collections are not paged: RFC 7644 has a service provider ignore
     * paging on them, so {@code startIndex} is always 1 and {@code itemsPerPage} is the
     * whole list.
     */
    static Map<String, Object> listResponse(List<Map<String, Object>> resources) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemas", List.of(ScimSchemas.LIST_RESPONSE));
        document.put("totalResults", resources.size());
        document.put("startIndex", 1);
        document.put("itemsPerPage", resources.size());
        document.put("Resources", resources);
        return document;
    }
}

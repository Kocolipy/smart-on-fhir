package com.example.backend.scim.controller;

/**
 * The SCIM namespace's fixed strings: its base path, its media type and the schema
 * URIs every document declares.
 *
 * <p>One class, because these values appear in a request mapping, in a rendered
 * document, in a {@code Location} header and in a test's expectation, and a typo in a
 * schema URI is the kind of defect a conformance client reports as "not SCIM" rather
 * than as a missing feature. Spelled as RFC 7643 and RFC 7644 spell them, including
 * case: a schema URI is compared exactly.
 */
public final class ScimSchemas {

    /** The Base URI every SCIM endpoint hangs from. */
    public static final String BASE_PATH = "/scim/v2";

    /**
     * The User endpoint. The route its controller maps, and so the template the dispatcher reports
     * as matched, which is how a refusal tells which resource and which write it was aimed at.
     */
    static final String USERS_PATH = BASE_PATH + "/Users";

    /** The Group endpoint, mapped and matched as {@link #USERS_PATH} is. */
    static final String GROUPS_PATH = BASE_PATH + "/Groups";

    /** One resource under an endpoint; {@code id} is the path variable it is read back from. */
    static final String ID_PATH = "/{id}";

    /**
     * SCIM's own media type, which every response defaults to.
     *
     * <p>Spring's Jackson converter already supports {@code application/*+json}, so
     * this type is readable and writable with no converter configuration; what the
     * constant adds is that the handlers declare it FIRST, which is what makes it the
     * default for a client sending {@code Accept: *}{@code /*}.
     */
    public static final String MEDIA_TYPE = "application/scim+json";

    /** The core User schema. */
    public static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User";

    /**
     * The core Group schema — the resource that carries authority in this directory.
     *
     * <p>Users and Groups are one release capability, which is why both schemas are declared
     * together: a directory that advertised Users alone could not express authority, and a
     * connector provisioning against it would build something that means less than it will mean
     * later.
     */
    public static final String GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";

    /** The schema every PATCH request body declares. */
    public static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    /** The error message schema every refusal body declares. */
    public static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";

    /** The schema of any collection response, discovery's included. */
    public static final String LIST_RESPONSE =
            "urn:ietf:params:scim:api:messages:2.0:ListResponse";

    /** The schema of a {@code POST .search} request body. */
    public static final String SEARCH_REQUEST =
            "urn:ietf:params:scim:api:messages:2.0:SearchRequest";

    /** The schema of the capability document. */
    public static final String SERVICE_PROVIDER_CONFIG =
            "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";

    /** The schema of a resource-type document. */
    public static final String RESOURCE_TYPE =
            "urn:ietf:params:scim:schemas:core:2.0:ResourceType";

    /** The schema of a schema document. */
    public static final String SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Schema";

    private ScimSchemas() {
    }
}

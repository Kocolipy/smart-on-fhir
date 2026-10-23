package com.example.backend.scim.domain;

import com.example.backend.authorization.domain.Permission;

/**
 * The kinds of resource that share the SCIM id namespace.
 *
 * <p>A closed set, and the spelling matters: {@link #resourceTypeName()} is what
 * {@code meta.resourceType} renders and what the {@code scim_resources} table
 * stores, so the enum constant and the wire value are related by one method here
 * rather than by a mapping at each use.
 *
 * <p>Each type also names the Permissions a connector token needs to read and to write it
 * (ADR 0015), so a type cannot exist without a Permission guarding each direction.
 */
public enum ScimResourceType {

    /** A person or provisioning identity. */
    USER("User", "Users", "urn:ietf:params:scim:schemas:core:2.0:User",
            Permission.USER_READ, Permission.USER_WRITE),

    /** A collection of Users conferring authority. */
    GROUP("Group", "Groups", "urn:ietf:params:scim:schemas:core:2.0:Group",
            Permission.GROUP_READ, Permission.GROUP_WRITE);

    private final String resourceTypeName;

    private final String endpointName;

    private final String schemaUri;

    private final Permission readPermission;

    private final Permission writePermission;

    ScimResourceType(
            String resourceTypeName,
            String endpointName,
            String schemaUri,
            Permission readPermission,
            Permission writePermission) {
        this.resourceTypeName = resourceTypeName;
        this.endpointName = endpointName;
        this.schemaUri = schemaUri;
        this.readPermission = readPermission;
        this.writePermission = writePermission;
    }

    /** The name RFC 7643 gives this resource type, exactly as it goes on the wire. */
    public String resourceTypeName() {
        return resourceTypeName;
    }

    /** The path segment of this type's endpoint under {@code /scim/v2}: {@code Users}. */
    public String endpointName() {
        return endpointName;
    }

    /**
     * The URI of this type's core schema, which a filter path may be qualified with —
     * {@code urn:ietf:params:scim:schemas:core:2.0:User:userName}. The same string the web
     * adapter renders in {@code schemas}; a test holds the two equal.
     */
    public String schemaUri() {
        return schemaUri;
    }

    /** What a token needs to retrieve, list or search this type. */
    public Permission readPermission() {
        return readPermission;
    }

    /** What a token needs to create, replace, patch or delete this type. */
    public Permission writePermission() {
        return writePermission;
    }
}

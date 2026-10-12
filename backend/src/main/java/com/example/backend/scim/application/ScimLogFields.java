package com.example.backend.scim.application;

/**
 * The log field names only the SCIM namespace's records carry, beside the module that writes
 * them. The records themselves are built by {@link com.example.backend.observability.LogEvent}'s
 * shapes (ADR 0003); these are only the keys SCIM adds to them.
 */
public final class ScimLogFields {

    /**
     * The SCIM resource type a refused request addressed ({@code User}, {@code Group}), read
     * from the route it matched. Never anything from the request body.
     */
    public static final String SCIM_RESOURCE_TYPE = "scim.resource.type";

    private ScimLogFields() {
    }
}

package com.example.backend.audit.domain;

/**
 * Which reserved resource seeding created, as a {@code SCIM_RESOURCE_SEED} event records it: a
 * closed set rather than the resource type's name, so the event that carries it holds no
 * {@code String}.
 */
public enum AuditSeededResource {

    /** The Bootstrap Admin, a SCIM User. */
    USER(AuditEvent.USER_RESOURCE_TYPE),

    /** The Admin group, a SCIM Group. */
    GROUP(AuditEvent.GROUP_RESOURCE_TYPE);

    private final String resourceType;

    AuditSeededResource(String resourceType) {
        this.resourceType = resourceType;
    }

    /** The resource type the seeded resource is recorded under. */
    public String resourceType() {
        return resourceType;
    }
}

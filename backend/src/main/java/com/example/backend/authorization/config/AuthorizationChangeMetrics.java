package com.example.backend.authorization.config;

import com.example.backend.authorization.domain.AuthorizationChangeCounts;
import com.example.backend.authorization.domain.GroupPermissionChangeCounts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The {@code authorization.changes} counter, the adapter of {@link AuthorizationChangeCounts} and
 * {@link GroupPermissionChangeCounts}: one count per change of authorization worth alerting on,
 * tagged by its {@code operation}.
 *
 * <p>{@code superuser-grant} counts a User gaining direct membership of the Admin group, so a
 * Superuser appearing is a spike on a series that exists from the start, at zero: an alert on its
 * increase sees the first one too. {@code group-permissions-change} counts a Superuser setting a
 * Group's Permissions, registered at zero for the same reason. A later kind of change is a tag of
 * its own on the same meter.
 */
public final class AuthorizationChangeMetrics
        implements AuthorizationChangeCounts, GroupPermissionChangeCounts {

    /** The meter's name. */
    static final String CHANGES = "authorization.changes";

    /** The {@code operation} tag of a Superuser grant. */
    static final String SUPERUSER_GRANT = "superuser-grant";

    /** The {@code operation} tag of a Group's Permissions set by a Superuser. */
    static final String GROUP_PERMISSIONS_CHANGE = "group-permissions-change";

    private static final String DESCRIPTION = "Changes of authorization, by operation";

    private final Counter superuserGrants;

    private final Counter groupPermissionsChanges;

    public AuthorizationChangeMetrics(MeterRegistry registry) {
        this.superuserGrants = series(registry, SUPERUSER_GRANT);
        this.groupPermissionsChanges = series(registry, GROUP_PERMISSIONS_CHANGE);
    }

    private static Counter series(MeterRegistry registry, String operation) {
        return Counter.builder(CHANGES)
                .description(DESCRIPTION)
                .tag("operation", operation)
                .register(registry);
    }

    @Override
    public void superuserGranted() {
        superuserGrants.increment();
    }

    @Override
    public void groupPermissionsChanged() {
        groupPermissionsChanges.increment();
    }
}

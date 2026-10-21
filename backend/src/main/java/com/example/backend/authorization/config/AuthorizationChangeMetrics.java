package com.example.backend.authorization.config;

import com.example.backend.authorization.domain.AuthorizationChangeCounts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The {@code authorization.changes} counter, the adapter of {@link AuthorizationChangeCounts}: one
 * count per change of authorization worth alerting on, tagged by its {@code operation}.
 *
 * <p>{@code superuser-grant} counts a User gaining direct membership of the Admin group, so a
 * Superuser appearing is a spike on a series that exists from the start, at zero: an alert on its
 * increase sees the first one too. A later kind of change is a tag of its own on the same meter.
 */
public final class AuthorizationChangeMetrics implements AuthorizationChangeCounts {

    /** The meter's name. */
    static final String CHANGES = "authorization.changes";

    /** The {@code operation} tag of a Superuser grant. */
    static final String SUPERUSER_GRANT = "superuser-grant";

    private final Counter superuserGrants;

    public AuthorizationChangeMetrics(MeterRegistry registry) {
        this.superuserGrants = Counter.builder(CHANGES)
                .description("Changes of authorization, by operation")
                .tag("operation", SUPERUSER_GRANT)
                .register(registry);
    }

    @Override
    public void superuserGranted() {
        superuserGrants.increment();
    }
}

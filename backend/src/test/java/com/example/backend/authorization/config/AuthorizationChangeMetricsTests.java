package com.example.backend.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@code authorization.changes} counter: one count per change of authorization worth alerting
 * on, tagged by the kind of change.
 */
class AuthorizationChangeMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final AuthorizationChangeMetrics metrics = new AuthorizationChangeMetrics(registry);

    private double count(String operation) {
        Counter counter = registry.find("authorization.changes").tag("operation", operation)
                .counter();
        assertThat(counter).as("the %s series", operation).isNotNull();
        return counter.count();
    }

    /** Registered at zero, so an alert on its increase sees the first grant too. */
    @Test
    void theSuperuserGrantSeriesExistsFromTheStartAtZero() {
        assertThat(count("superuser-grant")).isZero();
    }

    @Test
    void eachSuperuserGrantCountsOnce() {
        metrics.superuserGranted();
        metrics.superuserGranted();

        assertThat(count("superuser-grant")).isEqualTo(2.0);
    }

    /** A Group's Permissions set by a Superuser is a series of its own, there from the start. */
    @Test
    void theGroupPermissionsChangeSeriesExistsFromTheStartAtZero() {
        assertThat(count("group-permissions-change")).isZero();
    }

    @Test
    void eachGroupPermissionsChangeCountsOnceOnItsOwnSeries() {
        metrics.groupPermissionsChanged();
        metrics.groupPermissionsChanged();
        metrics.groupPermissionsChanged();

        assertThat(List.of(count("group-permissions-change"), count("superuser-grant")))
                .containsExactly(3.0, 0.0);
    }
}

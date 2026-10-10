package com.example.backend.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A clock the test moves by hand, as the application's notion of "now", for a full-context test
 * of the dormancy jobs: months of inactivity pass in one call instead of in real time.
 *
 * <p>{@code @Primary} over the production {@code Clock} bean rather than replacing it, so every
 * collaborator that injects a {@code Clock} — the login path, the SCIM writes, the audit trail,
 * seeding — reads the same simulated time. Truncated to microseconds, which is what
 * {@code TIMESTAMPTZ} stores, so a timestamp read back compares equal to the instant written.
 *
 * <p>A top-level class, imported, for the reason {@code InMemorySessionRegistryConfiguration} is:
 * a nested configuration would give its test class a context and a Postgres database of its own
 * even when another class imported an identical one.
 */
@TestConfiguration
public class DormancyTestClockConfiguration {

    @Bean
    @Primary
    MutableClock dormancyTestClock() {
        return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}

package com.example.backend.auth.config;

import com.example.backend.scim.domain.LockoutPolicy;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the lockout rule's one number and the application's notion of "now" enter the
 * application. Both are beans so the rule itself stays free of configuration and of the
 * system clock.
 *
 * <p>There is no lockout duration to configure. A lock is lifted by an administrator's
 * Unlock and by nothing else, so a window would express a lift that no code performs.
 *
 * <p>Nor is the lockout-exempt identity named here. Which User must never lock is the
 * reservation marker on the seeded Bootstrap Admin's own resource row, written once by seeding
 * and reachable by no UPDATE, rather than a comparison against
 * {@code app.auth.bootstrap-username}: a rename could silently move a name, and a second
 * identity could acquire the exemption by taking it. {@code ScimSeedConfig} is the one place the
 * configured name becomes a privilege, and it does so by creating the marked row rather than by
 * leaving a name for something downstream to compare.
 */
@Configuration
public class LoginLockoutConfig {

    @Bean
    public LockoutPolicy lockoutPolicy(
            @Value("${app.auth.lockout.max-attempts}") int maxAttempts) {
        return new LockoutPolicy(maxAttempts);
    }

    /**
     * The system clock in UTC, ticking in whole microseconds.
     *
     * <p>Microseconds because that is what a {@code DATETIMEOFFSET(6)} column stores. An instant
     * with nanoseconds is rendered at full precision in the response that wrote it and then rounded
     * by the database, so a SCIM resource's {@code meta.created} and {@code meta.lastModified} would
     * read differently on the next {@code GET} with nothing having changed — and a client that
     * compares them would see a modification that never happened. A clock that never produces
     * more precision than the store keeps makes every persisted instant round-trip exactly.
     */
    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}

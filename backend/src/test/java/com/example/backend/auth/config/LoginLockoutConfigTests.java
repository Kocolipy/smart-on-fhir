package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The beans {@link LoginLockoutConfig} contributes — above all, an application clock that never
 * yields more precision than a {@code DATETIMEOFFSET(6)} column keeps.
 *
 * <p>A SCIM resource's {@code meta.created} and {@code meta.lastModified} are rendered from the
 * instant the write used and then read back from the database on every later {@code GET}. With
 * nanoseconds in that instant the two differ by a rounding the database applied, so a resource
 * would appear modified between its create and its first read.
 */
class LoginLockoutConfigTests {

    private final Clock clock = new LoginLockoutConfig().clock();

    @Test
    void everyInstantIsAWholeMicrosecond() {
        for (int sample = 0; sample < 1_000; sample++) {
            Instant now = clock.instant();
            assertThat(now.getNano() % 1_000).as("sub-microsecond digits of %s", now).isZero();
        }
    }

    @Test
    void theLockoutPolicyCarriesTheConfiguredThreshold() {
        assertThat(new LoginLockoutConfig().lockoutPolicy(7))
                .isEqualTo(new com.example.backend.scim.domain.LockoutPolicy(7));
    }

    @Test
    void theClockIsUtcAndTracksTheSystemClock() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        Instant before = Instant.now().minusMillis(1);
        Instant now = clock.instant();
        Instant after = Instant.now().plusMillis(1);
        assertThat(now).isBetween(before, after);
    }
}

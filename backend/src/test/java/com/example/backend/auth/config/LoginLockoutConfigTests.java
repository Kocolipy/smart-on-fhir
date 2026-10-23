package com.example.backend.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * The deployed configuration is read directly rather than through the environment, because
     * the test resources' own {@code application.yaml} shadows it entirely.
     *
     * <p>The {@code :3} default is pinned here and mirrored by the E2E suite's fallback
     * ({@code frontend/test/e2e/lockoutThreshold.ts}, pinned by
     * {@code frontend/test/lockoutThreshold.test.ts}); changing one without the other fails a
     * test (#98).
     */
    @Test
    void theDeployedThresholdDefaultsToThree() throws IOException {
        assertThat(Files.readString(Path.of("src/main/resources/application.yaml"))
                        .lines().map(String::strip))
                .contains("max-attempts: ${APP_LOCKOUT_MAX_ATTEMPTS:3}");
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

package com.example.backend;

import com.example.backend.scim.application.ScimSeedService;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;

/**
 * Tests start with a non-administrative User, {@code test-user} / {@code test-password}, beside
 * the seeded Bootstrap Admin.
 *
 * <p>Startup seeds none in a deployment: the non-administrative {@code user} is a
 * development fixture, seeded only with {@code app.dev-fixtures.enabled}. Enabling the fixtures
 * in every test context would also register the startup dormancy run, so instead this seeds the
 * one User through the same fixture path — {@link ScimSeedService#seedDevFixtures} with no Groups
 * — which creates it when absent and otherwise leaves it alone, so every context sharing the
 * database sees the same User.
 */
public class SeededTestUserConfiguration {

    /** The non-administrative test User's userName. */
    public static final String TEST_USER = "test-user";

    /** The non-administrative test User's password. */
    public static final String TEST_PASSWORD = "test-password";

    @Bean
    ApplicationRunner seedTestUser(ScimSeedService seeding) {
        return arguments -> seeding.seedDevFixtures(List.of(), TEST_USER, TEST_PASSWORD);
    }
}

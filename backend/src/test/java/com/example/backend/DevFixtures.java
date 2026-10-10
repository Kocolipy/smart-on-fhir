package com.example.backend;

/**
 * The password every test context with {@code app.dev-fixtures.enabled} seeds the development
 * fixture Users under.
 *
 * <p>One value for all of them, because seeding never overwrites: a fixture User that exists keeps
 * the password it was first seeded with. Contexts that each chose their own would sign in only
 * while their fixtures were never seeded by another context first.
 */
public final class DevFixtures {

    /** {@code app.dev-fixtures.password} in every test context that seeds the fixtures. */
    public static final String PASSWORD = "dev-fixture-test-password";

    private DevFixtures() {
    }
}

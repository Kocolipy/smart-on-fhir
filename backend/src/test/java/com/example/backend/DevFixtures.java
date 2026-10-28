package com.example.backend;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The development fixtures as the tests seed them: their password, the Epic clinician, and the
 * one context configuration every class signing in as them shares.
 *
 * <p>One password for all of them, because seeding never overwrites: a fixture User that exists
 * keeps the password it was first seeded with. Contexts that each chose their own would sign in
 * only while their fixtures were never seeded by another context first.
 */
public final class DevFixtures {

    /** {@code app.dev-fixtures.password} in every test context that seeds the fixtures. */
    public static final String PASSWORD = "dev-fixture-test-password";

    /** The local Epic stand-in's clinician, a Practitioner id (compose.yaml, SEED_CLINICIAN_ID). */
    public static final String EPIC_CLINICIAN = "af74a102-ebaf-46ef-b445-4f72157c473f";

    private DevFixtures() {
    }

    /**
     * The application started with {@code authorization.yaml}'s development fixtures enabled under
     * the {@code local} profile, on the containers. Every class declaring it shares one cached
     * context — and so one database — so a class that moves a fixture's membership or Permissions
     * puts it back before its test ends.
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @SpringBootTest
    @Import(ContainerTestConfiguration.class)
    @ActiveProfiles({"dev-fixtures", "local"})
    @TestPropertySource(properties = {
        "app.dev-fixtures.enabled=true",
        "app.dev-fixtures.epic-clinician=" + EPIC_CLINICIAN,
        "app.dev-fixtures.password=" + PASSWORD})
    public @interface Context {
    }
}

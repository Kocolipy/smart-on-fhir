package com.example.backend.profile;

/**
 * The one developer profile: a run on a developer's machine, and never a deployment's.
 *
 * <p>{@code local} migrates in-process, which the deployed service never does, so no deployment
 * runs it, and two things are granted under it alone, each reading the name from here:
 *
 * <ul>
 *   <li>the development fixtures ({@code APP_DEV_FIXTURES_ENABLED}), which {@code
 *       scim.config.ScimSeedConfig} refuses at startup outside it — {@code authorization.yaml}
 *       activates their Group Permissions under the same name, so the two are renamed together;
 *   <li>the local Epic stand-in's relaxations of Epic Login, {@code http} URLs (ADR 0012, D21) and
 *       the relative {@code fhirUser} {@code Practitioner/{id}}, which {@code
 *       auth.epic.config.EpicLoginConfig} grants. They stay inert unless Epic Login is on and its
 *       URLs are actually {@code http}.
 * </ul>
 *
 * <p>A name only: this class knows no bean and no business module.
 */
public final class LocalProfile {

    /** The profile's name, as {@code SPRING_PROFILES_ACTIVE} spells it. */
    public static final String NAME = "local";

    private LocalProfile() {
    }
}

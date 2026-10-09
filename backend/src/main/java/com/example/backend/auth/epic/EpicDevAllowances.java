package com.example.backend.auth.epic;

/**
 * What the local SMART launcher needs that a real Epic never gets: two separate relaxations of
 * {@link EpicLoginProperties#validate}, each its own fact so either can change without the other.
 * Which profile grants them is {@code EpicLoginConfig}'s decision, not this type's.
 *
 * @param httpUrls         whether the three URLs may be {@code http} (D21)
 * @param relativeFhirUser whether {@code fhirUser} may be the relative {@code Practitioner/{id}}
 *                         the local launcher issues
 */
public record EpicDevAllowances(boolean httpUrls, boolean relativeFhirUser) {

    /** Neither relaxation: every deployment but a local one. */
    public static final EpicDevAllowances NONE = new EpicDevAllowances(false, false);

    /** Both relaxations, for the local Docker launcher. */
    public static final EpicDevAllowances LOCAL_LAUNCHER = new EpicDevAllowances(true, true);
}

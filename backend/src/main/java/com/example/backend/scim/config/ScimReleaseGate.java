package com.example.backend.scim.config;

/**
 * Whether this deployment serves the SCIM interface at all.
 *
 * <p>Open by default. The default lives in {@code ScimSecurityConfig}'s {@code @Value} expression
 * rather than here: the release decision belongs to the code, not to a configuration file a
 * deployment may replace.
 *
 * <p>A deployment that authenticates by password only and provisions nothing can turn the
 * namespace off, and while it is off the namespace
 * answers {@code 404} to every request whatever credential it carries — so the surface cannot even
 * be probed for existence.
 *
 * @param open whether the namespace answers
 */
public record ScimReleaseGate(boolean open) {
}

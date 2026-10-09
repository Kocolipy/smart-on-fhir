package com.example.backend.auth.epic;

/**
 * Epic Login's routes, spelled once (ADR 0013): the handlers map them, the OAuth 2.0 filters
 * process them, and the application chain permits them, all from here — so a route renamed in
 * one place cannot be left open, unmapped or unprocessed in another.
 *
 * <p>Every one sits under {@link #NAMESPACE}, which the release gate answers {@code 404} for
 * while {@code APP_EPIC_ENABLED} is off.
 */
public final class EpicRoutes {

    /** The path every Epic Login route sits under, and the one the release gate owns. */
    public static final String NAMESPACE = "/api/auth/epic";

    /** The launch URL registered with Epic, which Epic opens with {@code iss} and {@code launch}. */
    public static final String LAUNCH = NAMESPACE + "/launch";

    /** The internal hop the authorization-request filter answers with the redirect to Epic. */
    public static final String AUTHORIZE = NAMESPACE + "/authorize";

    /** The callback Epic redirects back to, whose absolute URL is {@code APP_EPIC_REDIRECT_URI}. */
    public static final String CALLBACK = NAMESPACE + "/callback";

    /** Our public JWKS, which Epic verifies our client assertions against (D14). */
    public static final String JWKS = NAMESPACE + "/jwks.json";

    private EpicRoutes() {
    }
}

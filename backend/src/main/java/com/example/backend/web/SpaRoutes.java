package com.example.backend.web;

import java.util.List;

/**
 * The single answer to which request paths belong to the single-page
 * application and which the server answers for itself.
 *
 * <p>Two questions, because the callers ask different ones. The filter chain
 * asks whether a path is reserved for the server, so that everything else —
 * the HTML shell and the hashed asset bundle alike — is served without
 * authentication. The error view resolver asks whether a missing path should
 * be handed to the client-side router instead of returning 404, which is only
 * true of paths that do not look like a file request: a missing asset must stay
 * a 404 rather than quietly become HTML.
 *
 * <p>Both questions share one reserved-prefix list. A new reserved prefix is added
 * here, once.
 */
public final class SpaRoutes {

    /**
     * Path prefixes the server answers for itself. Each reserves both the exact
     * path and everything beneath it, so {@code /api} is as reserved as
     * {@code /api/self}.
     *
     * <p>{@code /scim} is here because an unknown path beneath it must stay a
     * {@code 404} and must never be authenticated as a frontend GET. Without the
     * entry, a mistyped SCIM path — {@code /scim/v2/User} for {@code /scim/v2/Users}
     * — would be read as a client-side route and answered with the HTML shell and a
     * {@code 200}, which a provisioning client would parse as a successful empty
     * response. The SCIM chain matches first and so decides these requests today;
     * this entry is what keeps that true if the chain ordering ever changes.
     */
    private static final List<String> RESERVED_PREFIXES =
            List.of("/api", "/actuator", "/scim");

    private SpaRoutes() {
    }

    /**
     * Whether the server owns this path. Reserved paths are authenticated and
     * error-handled by the application itself; everything else is the
     * single-page application's.
     */
    public static boolean isReservedServerPath(String path) {
        return RESERVED_PREFIXES.stream()
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    /**
     * Whether a path with no server-side handler should be forwarded to the
     * HTML shell for the client-side router to resolve. A path whose last
     * segment carries a dot is read as a file request and is left as a 404; a
     * dot in an earlier segment (a versioned route such as
     * {@code /v1.0/settings}) does not make it one.
     */
    public static boolean servesSpaShell(String path) {
        return !isReservedServerPath(path) && !looksLikeFileRequest(path);
    }

    private static boolean looksLikeFileRequest(String path) {
        return path.substring(path.lastIndexOf('/') + 1).contains(".");
    }
}

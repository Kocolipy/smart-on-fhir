package com.example.backend.scim.domain;

import java.util.Locale;
import java.util.Set;

/**
 * The SCIM chain's backstop: whether a connector token may make a SCIM request, judged from the
 * method and the path alone, before dispatch (ADR 0015).
 *
 * <p><strong>The authority is the handler, not this rule.</strong> Every Users and Groups handler,
 * and the base {@code .search}, declares the same Permission with method security, and that
 * declaration is what decides: it sits on the operation the dispatcher actually selected. This
 * rule repeats it as the session chain's URL rules repeat a session handler's, so a request it can
 * already see lacks the Permission is turned away before it reaches the dispatcher. It reads the
 * path as the request line carries it, and the dispatcher matches a decoded one, so the two can
 * disagree about which resource a path names; where they do, the path is one this rule does not
 * recognise, its answer below is "a valid token is enough", and the handler's declaration is what
 * holds it.
 *
 * <ul>
 *   <li>{@code /Users…}: {@code user:read} to read, {@code user:write} to create, replace, patch
 *       or delete; {@code /Groups…} likewise with {@code group:read} / {@code group:write};
 *   <li>the base {@code /.search}: at least one read Permission — what it returns is narrowed to
 *       the types the token may read, by the search itself;
 *   <li>discovery ({@code ServiceProviderConfig}, {@code Schemas}, {@code ResourceTypes}) and
 *       {@code /Me}: a valid token and no Permission;
 *   <li>any other path in the namespace: a valid token and no Permission, here. When nothing serves
 *       the path the answer is the dispatcher's {@code 404} — which a SCIM client reads as "not
 *       offered" — rather than a {@code 403} that would claim the endpoint exists; when the
 *       dispatcher does resolve it to a handler, that handler's declaration decides. A handler
 *       cannot be added without one: {@code ArchitectureTest} fails the build, and the
 *       authorization contract test sends each documented operation a token lacking its
 *       Permission.
 * </ul>
 *
 * <p>Method alone does not decide read from write. SCIM's {@code .search} endpoints are
 * {@code POST} requests that read — RFC 7644 puts a filter carrying personal values in a body
 * rather than a URL — so a {@code POST} whose LAST segment is {@code .search} is a read.
 */
public final class ScimPermissionRule {

    /** The namespace itself. */
    private static final String NAMESPACE = "/scim/v2";

    /** The namespace every path here is under. */
    private static final String BASE = NAMESPACE + "/";

    /** Methods that cannot change a resource whatever the path is. */
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** The last segment that makes a {@code POST} a read. */
    private static final String SEARCH = ".search";

    private ScimPermissionRule() {
    }

    /**
     * Whether a token holding {@code held} may make this request.
     *
     * @param method HTTP method, in any case; {@code null} is treated as a write
     * @param path   request path within the application, {@code /scim/v2/…}
     */
    public static boolean permits(String method, String path, ConnectorTokenPermissions held) {
        if (NAMESPACE.equals(path)) {
            // The namespace root, which nothing serves.
            return true;
        }
        if (path == null || !path.startsWith(BASE)) {
            return false;
        }
        String[] segments = path.substring(BASE.length()).split("/", -1);
        String first = segments[0];
        if (SEARCH.equals(first) && segments.length == 1) {
            return held.permitsAnyRead();
        }
        for (ScimResourceType type : ScimResourceType.values()) {
            if (type.endpointName().equals(first)) {
                boolean read = isRead(method, segments[segments.length - 1]);
                return held.permits(read ? type.readPermission() : type.writePermission());
            }
        }
        // Discovery, /Me, a path nothing serves, or one this rule does not recognise: nothing for
        // the backstop to refuse. Whatever handler the dispatcher selects decides for itself.
        return true;
    }

    private static boolean isRead(String method, String lastSegment) {
        if (method == null) {
            // Unclassifiable, so the stronger Permission is required: the alternative default
            // would let anything this rule cannot read through as a read.
            return false;
        }
        String upper = method.toUpperCase(Locale.ROOT);
        return READ_METHODS.contains(upper) || ("POST".equals(upper) && SEARCH.equals(lastSegment));
    }
}

package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.TokenPermissions;
import com.example.backend.authorization.domain.Permission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which SCIM requests a token may make, by the Permissions it carries (ADR 0015).
 *
 * <p>Every case is asserted both ways: the named Permission alone admits the request, and every
 * other directory Permission together does not — so a rule that admitted on the wrong Permission,
 * or on any Permission, fails here.
 */
class ScimPermissionRuleTests {

    private static final String ID = "/8f14e45f-ceea-467a-9575-28a1b0e0e8a1";

    @ParameterizedTest
    @CsvSource({
        "GET,/scim/v2/Users,user:read",
        "HEAD,/scim/v2/Users,user:read",
        "OPTIONS,/scim/v2/Users,user:read",
        "get,/scim/v2/Users" + ID + ",user:read",
        "POST,/scim/v2/Users/.search,user:read",
        "POST,/scim/v2/Users,user:write",
        "PUT,/scim/v2/Users" + ID + ",user:write",
        "PATCH,/scim/v2/Users" + ID + ",user:write",
        "DELETE,/scim/v2/Users" + ID + ",user:write",
        "GET,/scim/v2/Groups,group:read",
        "GET,/scim/v2/Groups" + ID + ",group:read",
        "POST,/scim/v2/Groups/.search,group:read",
        "POST,/scim/v2/Groups,group:write",
        "PUT,/scim/v2/Groups" + ID + ",group:write",
        "PATCH,/scim/v2/Groups" + ID + ",group:write",
        "DELETE,/scim/v2/Groups" + ID + ",group:write",
        "PUT,/scim/v2/Users/.search,user:write",
        "PATCH,/scim/v2/Groups/.search,group:write",
    })
    void a_resource_request_needs_exactly_its_permission(
            String method, String path, String permission) {
        Permission needed = Permission.fromValue(permission).orElseThrow();
        Set<Permission> others = EnumSet.copyOf(TokenPermissions.ALL);
        others.remove(needed);

        assertThat(ScimPermissionRule.permits(method, path, holding(Set.of(needed)))).isTrue();
        assertThat(ScimPermissionRule.permits(method, path, holding(others))).isFalse();
    }

    /**
     * Only a LAST segment of {@code .search} makes a {@code POST} a read; a resource whose id
     * merely contains the word, or a search on a single resource, is still a write.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/scim/v2/Users/x.search",
        "/scim/v2/Users/.search/x",
        "/scim/v2/Users/.searchx",
    })
    void only_a_trailing_search_segment_makes_a_post_a_read(String path) {
        assertThat(ScimPermissionRule.permits("POST", path, holding(Set.of(Permission.USER_READ))))
                .isFalse();
        assertThat(ScimPermissionRule.permits("POST", path, holding(Set.of(Permission.USER_WRITE))))
                .isTrue();
    }

    /** An unclassifiable method needs the stronger Permission, never the weaker. */
    @Test
    void a_missing_method_is_treated_as_a_write() {
        assertThat(ScimPermissionRule.permits(null, "/scim/v2/Users",
                holding(Set.of(Permission.USER_READ)))).isFalse();
        assertThat(ScimPermissionRule.permits(null, "/scim/v2/Users",
                holding(Set.of(Permission.USER_WRITE)))).isTrue();
        assertThat(ScimPermissionRule.permits(null, "/scim/v2/Users/.search",
                holding(Set.of(Permission.USER_READ)))).isFalse();
    }

    /** The base search needs at least one read Permission; either suffices. */
    @Test
    void the_base_search_needs_any_read_permission() {
        String path = "/scim/v2/.search";
        assertThat(ScimPermissionRule.permits("POST", path, holding(Set.of(Permission.USER_READ))))
                .isTrue();
        assertThat(ScimPermissionRule.permits("POST", path, holding(Set.of(Permission.GROUP_READ))))
                .isTrue();
        assertThat(ScimPermissionRule.permits("POST", path,
                holding(Set.of(Permission.USER_WRITE, Permission.GROUP_WRITE)))).isFalse();
        assertThat(ScimPermissionRule.permits("POST", path, holding(Set.of()))).isFalse();
    }

    /** Discovery and {@code /Me} need a valid token and no Permission. */
    @ParameterizedTest
    @ValueSource(strings = {
        "/scim/v2/ServiceProviderConfig",
        "/scim/v2/ResourceTypes",
        "/scim/v2/ResourceTypes/User",
        "/scim/v2/Schemas",
        "/scim/v2/Schemas/urn:ietf:params:scim:schemas:core:2.0:User",
        "/scim/v2/Me",
    })
    void discovery_and_me_need_no_permission(String path) {
        assertThat(ScimPermissionRule.permits("GET", path, holding(Set.of()))).isTrue();
    }

    /**
     * A path the rule does not name needs a valid token and nothing more: no handler serves it, so
     * the dispatcher answers {@code 404}, which a SCIM client reads as "not offered".
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/scim/v2",
        "/scim/v2/",
        "/scim/v2/Bulk",
        "/scim/v2/users",
        "/scim/v2/Devices/x",
        "/scim/v2/.search/x",
    })
    void an_unnamed_path_needs_only_a_token(String path) {
        assertThat(ScimPermissionRule.permits("POST", path, holding(Set.of()))).isTrue();
    }

    /** A path outside the namespace is never this rule's to admit. */
    @ParameterizedTest
    @ValueSource(strings = {"/scim/v2x/Users", "/scim", "/api/admin/accounts"})
    void a_path_outside_the_namespace_is_refused_whatever_the_token_holds(String path) {
        assertThat(ScimPermissionRule.permits("GET", path,
                holding(TokenPermissions.ALL))).isFalse();
    }

    @Test
    void a_missing_path_is_refused() {
        assertThat(ScimPermissionRule.permits("GET", null,
                holding(TokenPermissions.ALL))).isFalse();
    }

    private static ConnectorTokenPermissions holding(Set<Permission> permissions) {
        return new ConnectorTokenPermissions(permissions);
    }
}

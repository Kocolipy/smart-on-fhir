package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.ScimIdentities;
import com.example.backend.scim.application.ScimUserListing;
import com.example.backend.scim.application.ScimUserResource;
import com.example.backend.scim.domain.ScimGroupReference;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the User renderer derives rather than echoes: the read-only membership view and the
 * {@code ListResponse} a page becomes. Every writable attribute's rendering, and the whole document
 * of one User, are {@code ScimUserAttributeContractTests}'.
 */
class ScimUserRendererTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String BASE_URI = "https://directory.example/scim/v2";

    private static final UUID ENGINEERING = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    private static final UUID UNLABELLED = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

    private static ScimUserResource user(String userName, List<ScimGroupReference> groups) {
        return new ScimUserResource(UUID.randomUUID(), ScimIdentities.profile(userName, true),
                groups, null, 1, ScimIdentities.NOW, ScimIdentities.NOW);
    }

    /** The document as a connector reads it: serialized, then parsed. */
    private static JsonNode wire(Object document) {
        return JSON.readTree(JSON.writeValueAsString(document));
    }

    /**
     * Each Group the User is a direct member of, with a reference resolvable on this deployment;
     * a Group with no label renders without one.
     */
    @Test
    void the_groups_render_as_the_read_only_reverse_membership_view() {
        ScimUserResource ada = user("ada", List.of(
                new ScimGroupReference(ENGINEERING, "Engineering"),
                new ScimGroupReference(UNLABELLED, null)));

        JsonNode rendered = wire(ScimUserRenderer.render(ada, BASE_URI));

        assertThat(rendered.get("groups")).isEqualTo(JSON.readTree("""
                [{"value":"%1$s","display":"Engineering",
                  "$ref":"https://directory.example/scim/v2/Groups/%1$s","type":"direct"},
                 {"value":"%2$s",
                  "$ref":"https://directory.example/scim/v2/Groups/%2$s","type":"direct"}]"""
                .formatted(ENGINEERING, UNLABELLED)));
    }

    @Test
    void a_user_in_no_group_renders_no_groups() {
        JsonNode rendered = wire(ScimUserRenderer.render(user("ada", List.of()), BASE_URI));

        assertThat(rendered.has("groups")).isFalse();
    }

    /**
     * {@code itemsPerPage} counts the page returned, not the page asked for, and each resource is
     * projected as the request asked.
     */
    @Test
    void a_page_renders_as_a_list_response_of_its_projected_resources() {
        ScimUserResource ada = user("ada", List.of());
        ScimUserResource bea = user("bea", List.of());
        ScimUserListing listing = new ScimUserListing(List.of(ada, bea), 5, new PageRequest(3, 4));

        JsonNode rendered = wire(ScimUserRenderer.renderList(
                listing, BASE_URI, ScimAttributeProjection.ofUser("userName", null)));

        assertThat(rendered).isEqualTo(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
                 "totalResults":5,"startIndex":3,"itemsPerPage":2,
                 "Resources":[
                   {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"id":"%s",
                    "userName":"ada"},
                   {"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],"id":"%s",
                    "userName":"bea"}]}""".formatted(ada.id(), bea.id())));
    }

    /** An empty page omits {@code Resources}, as RFC 7644's own examples do. */
    @Test
    void an_empty_page_renders_no_resources() {
        ScimUserListing listing = new ScimUserListing(List.of(), 0, PageRequest.FIRST_PAGE);

        JsonNode rendered = wire(
                ScimUserRenderer.renderList(listing, BASE_URI, ScimAttributeProjection.NONE));

        assertThat(rendered).isEqualTo(JSON.readTree("""
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
                 "totalResults":0,"startIndex":1,"itemsPerPage":0}"""));
    }
}

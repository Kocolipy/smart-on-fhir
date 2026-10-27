package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The discovery documents, asserted whole.
 *
 * <p>Whole rather than key-by-key on purpose. {@link ScimDiscoveryIntegrationTests}
 * asserts the CLAIMS a document makes — that the bulk limits are zero, that the filter
 * flag agrees with what a filtered query does — and those assertions pass while a key
 * is missing entirely. A connector reads the whole document, so a dropped
 * {@code startIndex} or {@code schemas} is a conformance break no claim-level
 * assertion notices. Comparing against the complete expected map is what fails then.
 */
class ScimDiscoveryTests {

    private static final String BASE_URI = ScimTestUris.BASE_URI;

    @Test
    void the_user_resource_type_is_exactly_this_document() {
        assertThat(ScimDiscovery.userResourceType(BASE_URI))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "schemas", List.of(ScimSchemas.RESOURCE_TYPE),
                        "id", "User",
                        "name", "User",
                        "endpoint", "/Users",
                        "description", "SCIM core User.",
                        "schema", ScimSchemas.USER,
                        "meta", Map.of(
                                "resourceType", "ResourceType",
                                "location", BASE_URI + "/ResourceTypes/User")));
    }

    @Test
    void the_user_resource_type_declares_no_schema_extensions() {
        assertThat(ScimDiscovery.userResourceType(BASE_URI)).doesNotContainKey("schemaExtensions");
    }

    @Test
    void a_list_response_reports_the_whole_list_on_one_unpaged_page() {
        Map<String, Object> first = Map.of("id", "one");
        Map<String, Object> second = Map.of("id", "two");

        assertThat(ScimDiscovery.listResponse(List.of(first, second)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "schemas", List.of(ScimSchemas.LIST_RESPONSE),
                        "totalResults", 2,
                        "startIndex", 1,
                        "itemsPerPage", 2,
                        "Resources", List.of(first, second)));
    }

    @Test
    void an_empty_list_response_still_carries_the_envelope() {
        assertThat(ScimDiscovery.listResponse(List.of()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "schemas", List.of(ScimSchemas.LIST_RESPONSE),
                        "totalResults", 0,
                        "startIndex", 1,
                        "itemsPerPage", 0,
                        "Resources", List.of()));
    }

    @Test
    void items_per_page_tracks_the_list_rather_than_being_a_fixed_number() {
        assertThat(ScimDiscovery.listResponse(List.of(Map.of("id", "only"))))
                .containsEntry("itemsPerPage", 1)
                .containsEntry("totalResults", 1);
    }

    @Test
    void the_group_resource_type_is_exactly_this_document() {
        assertThat(ScimDiscovery.groupResourceType(BASE_URI))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "schemas", List.of(ScimSchemas.RESOURCE_TYPE),
                        "id", "Group",
                        "name", "Group",
                        "endpoint", "/Groups",
                        "description", "SCIM core Group. Membership confers application authority.",
                        "schema", ScimSchemas.GROUP,
                        "meta", Map.of(
                                "resourceType", "ResourceType",
                                "location", BASE_URI + "/ResourceTypes/Group")));
    }

    @Test
    void the_group_resource_type_declares_no_schema_extensions() {
        assertThat(ScimDiscovery.groupResourceType(BASE_URI)).doesNotContainKey("schemaExtensions");
    }
}

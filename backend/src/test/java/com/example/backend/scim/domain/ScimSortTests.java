package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.paging.domain.PageRequest;
import com.example.backend.scim.domain.ScimFilter.AttributeRef;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Sort and query construction: what a {@code sortBy}/{@code sortOrder} pair resolves to. */
class ScimSortTests {

    private static final Set<ScimResourceType> USERS = Set.of(ScimResourceType.USER);

    @Test
    void no_sort_by_is_no_sort_whatever_the_order_says() {
        assertThat(ScimSort.of(null, null, USERS)).isNull();
        assertThat(ScimSort.of("  ", "descending", USERS)).isNull();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "-, false", "'', false", "' ', false", "ascending, false", "ASCENDING, false",
            "descending, true", "' Descending ', true"})
    void the_order_defaults_to_ascending_and_is_read_case_insensitively(
            String sortOrder, boolean descending) {
        assertThat(ScimSort.of("userName", sortOrder, USERS))
                .isEqualTo(new ScimSort(
                        new AttributeRef(ScimFilterPath.USER_NAME, null), descending));
    }

    /** An invalid order is refused even with no {@code sortBy}: the request is still wrong. */
    @Test
    void an_unknown_order_is_refused_with_or_without_a_sort_by() {
        assertThatThrownBy(() -> ScimSort.of("userName", "sideways", USERS))
                .isInstanceOf(InvalidScimQueryException.class);
        assertThatThrownBy(() -> ScimSort.of(null, "sideways", USERS))
                .isInstanceOf(InvalidScimQueryException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "emails", "groups", "meta"})
    void a_complex_attribute_needs_a_sub_attribute(String sortBy) {
        assertThatThrownBy(() -> ScimSort.of(sortBy, null, USERS))
                .isInstanceOf(InvalidScimQueryException.class)
                .hasMessage("sortBy on a complex attribute needs a sub-attribute: " + sortBy);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "nickName", "members.value"})
    void a_path_the_type_cannot_sort_by_is_an_invalid_value(String sortBy) {
        assertThatThrownBy(() -> ScimSort.of(sortBy, null, USERS))
                .isInstanceOf(InvalidScimQueryException.class)
                .hasMessageStartingWith("sortBy does not name a sortable attribute");
    }

    /** The refusal carries the parser's own reason after its prefix. */
    @Test
    void an_unsortable_path_says_why_it_is_not_sortable() {
        assertThatThrownBy(() -> ScimSort.of("nickName", null, USERS))
                .isInstanceOf(InvalidScimQueryException.class)
                .hasMessage("sortBy does not name a sortable attribute: "
                        + "The filter names an attribute this service does not support.");
    }

    @Test
    void a_sub_attribute_of_a_multi_valued_attribute_is_sortable() {
        assertThat(ScimSort.of("emails.value", "descending", USERS).attribute().path())
                .isEqualTo(ScimFilterPath.EMAILS_VALUE);
    }

    // ---- ScimQuery -----------------------------------------------------------------------------

    @Test
    void a_query_parses_its_filter_and_sort_and_defaults_its_page() {
        ScimQuery query = new ScimQuery(USERS, null, null, null);
        assertThat(query.page()).isEqualTo(PageRequest.FIRST_PAGE);

        ScimQuery parsed = ScimQuery.of(USERS, "userName pr", "userName", null,
                new PageRequest(3, 7));
        assertThat(parsed.filter()).isInstanceOf(ScimFilter.Presence.class);
        assertThat(parsed.sort()).isNotNull();
        assertThat(parsed.page()).isEqualTo(new PageRequest(3, 7));
        assertThat(ScimQuery.of(USERS, null, null, null, null).filter()).isNull();
    }

    @Test
    void a_query_names_at_least_one_type() {
        assertThatThrownBy(() -> new ScimQuery(Set.of(), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScimQuery(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_result_separates_its_hits_by_type_in_order() {
        UUID u1 = UUID.randomUUID();
        UUID g1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        ScimQuery.Result result = new ScimQuery.Result(9, List.of(
                new ScimQuery.Hit(ScimResourceType.USER, u1),
                new ScimQuery.Hit(ScimResourceType.GROUP, g1),
                new ScimQuery.Hit(ScimResourceType.USER, u2)));

        assertThat(result.idsOf(ScimResourceType.USER)).containsExactly(u1, u2);
        assertThat(result.idsOf(ScimResourceType.GROUP)).containsExactly(g1);
        assertThat(result.totalResults()).isEqualTo(9);
    }

    // ---- ScimFilterPath ------------------------------------------------------------------------

    @Test
    void a_path_resolves_case_insensitively_and_knows_its_place() {
        assertThat(ScimFilterPath.of("EMAILS", "Value")).contains(ScimFilterPath.EMAILS_VALUE);
        assertThat(ScimFilterPath.of("name", null)).contains(ScimFilterPath.NAME);
        assertThat(ScimFilterPath.of("value", null)).as("a sub-attribute is not a top-level name")
                .isEmpty();
        assertThat(ScimFilterPath.of("nickName", "x")).isEmpty();
        assertThat(ScimFilterPath.of("emails", "display")).isEmpty();
        assertThat(ScimFilterPath.GROUPS_REF.canonical()).isEqualTo("groups.$ref");
        assertThat(ScimFilterPath.GROUPS_REF.attributeName()).isEqualTo("$ref");
        assertThat(ScimFilterPath.GROUPS_REF.parent()).isEqualTo(ScimFilterPath.GROUPS);
        assertThat(ScimFilterPath.USER_NAME.parent()).isNull();
        assertThat(ScimFilterPath.EMAILS.subAttributes()).containsExactly(
                ScimFilterPath.EMAILS_VALUE, ScimFilterPath.EMAILS_TYPE, ScimFilterPath.EMAILS_PRIMARY);
        assertThat(ScimFilterPath.USER_NAME.subAttributes()).isEmpty();
    }

    @Test
    void operators_render_as_the_rfc_spells_them_and_know_their_class() {
        assertThat(ScimFilter.Operator.GE.token()).isEqualTo("ge");
        assertThat(ScimFilter.Operator.values()).filteredOn(ScimFilter.Operator::isSubstring)
                .containsExactly(ScimFilter.Operator.CO, ScimFilter.Operator.SW, ScimFilter.Operator.EW);
        assertThat(ScimFilter.Operator.values()).filteredOn(ScimFilter.Operator::isOrdering)
                .containsExactly(ScimFilter.Operator.GT, ScimFilter.Operator.GE,
                        ScimFilter.Operator.LT, ScimFilter.Operator.LE);
    }
}

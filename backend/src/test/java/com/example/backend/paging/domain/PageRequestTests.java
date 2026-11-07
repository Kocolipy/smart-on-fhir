package com.example.backend.paging.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The paging rules a {@link PageRequest} owns, through the paging module's interface: RFC 7644
 * §3.4.2.4's coercions, the pages a {@code PageRequest} can hold, and whether a page is read at
 * all. How a raw value parses is {@link PageParameter}'s, in {@code PageParameterTests}.
 */
class PageRequestTests {

    private static PageRequest page(String startIndex, String count) {
        return PageRequest.of(PageParameter.valueOf(startIndex), PageParameter.valueOf(count));
    }

    @Test
    void absent_parameters_mean_the_first_page_at_the_default_size() {
        assertThat(PageRequest.of(null, null)).isEqualTo(new PageRequest(1, 100));
    }

    @Test
    void the_first_page_is_the_default_page() {
        assertThat(PageRequest.FIRST_PAGE).isEqualTo(new PageRequest(1, 100));
    }

    @ParameterizedTest
    @CsvSource({
        // requested, coerced — a start index below one is not an error, it is one
        "0, 1",
        "-5, 1",
        "-99999999999, 1",
        "1, 1",
        "7, 7",
        "2147483647, 2147483647",
        // beyond an int is only further out of range: the furthest page there is
        "2147483648, 2147483647",
        "99999999999999999999, 2147483647",
    })
    void a_start_index_is_coerced_into_range(String requested, int coerced) {
        assertThat(page(requested, null).startIndex()).isEqualTo(coerced);
    }

    @ParameterizedTest
    @CsvSource({
        // requested, coerced
        "-99999999999, 0",
        "-1, 0",
        "0, 0",
        "1, 1",
        "200, 200",
        "201, 200",
        "100000, 200",
        "99999999999, 200",
    })
    void a_count_is_clamped_to_the_advertised_maximum(String requested, int coerced) {
        assertThat(page(null, requested).count()).isEqualTo(coerced);
    }

    /** Each parameter is coerced on its own: a defaulted one does not reset the other. */
    @Test
    void both_parameters_are_coerced_together() {
        assertThat(page("-3", "99999999999")).isEqualTo(new PageRequest(1, 200));
        assertThat(page("5", null)).isEqualTo(new PageRequest(5, 100));
    }

    /**
     * Zero survives coercion. It means "tell me the total and send no resources", which is
     * a question a client asks; turning it into the default page would answer a different
     * one.
     */
    @Test
    void a_count_of_zero_is_a_request_rather_than_an_omission() {
        assertThat(page("1", "0").count()).isZero();
    }

    /** Only coerced values exist: a page outside the rules cannot be made at all. */
    @ParameterizedTest
    @CsvSource({"0, 10", "-1, 10", "1, -1", "1, 201", "-2147483648, 0"})
    void a_page_outside_the_rules_is_refused(int startIndex, int count) {
        assertThatThrownBy(() -> new PageRequest(startIndex, count))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The bounds themselves are pages the rules produce. */
    @Test
    void the_edges_of_the_rules_are_pages() {
        assertThat(new PageRequest(1, 0).count()).isZero();
        assertThat(new PageRequest(Integer.MAX_VALUE, PageRequest.MAX_COUNT).count()).isEqualTo(200);
    }

    @Test
    void the_offset_is_the_one_based_start_index_minus_one() {
        assertThat(new PageRequest(1, 10).offset()).isZero();
        assertThat(new PageRequest(101, 10).offset()).isEqualTo(100);
    }

    @ParameterizedTest
    @CsvSource({
        // startIndex, count, totalResults, whether a page is read
        "1, 10, 3, true",
        "3, 10, 3, true",
        // past the last row
        "4, 10, 3, false",
        "1, 10, 0, false",
        // a count of zero asks only for the total
        "1, 0, 3, false",
        "2147483647, 200, 2147483647, true",
        "2147483647, 200, 2147483646, false",
    })
    void a_page_is_read_only_when_it_holds_a_row(
            int startIndex, int count, long totalResults, boolean read) {
        assertThat(new PageRequest(startIndex, count).readsAny(totalResults)).isEqualTo(read);
    }

    /** The advertised ceiling and the enforced one are the same constant. */
    @Test
    void the_maximum_count_is_the_number_discovery_advertises() {
        assertThat(PageRequest.MAX_COUNT).isEqualTo(200);
        assertThat(PageRequest.DEFAULT_COUNT).isEqualTo(100);
    }
}

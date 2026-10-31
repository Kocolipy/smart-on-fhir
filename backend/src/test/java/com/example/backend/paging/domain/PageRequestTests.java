package com.example.backend.paging.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RFC 7644 §3.4.2.4's paging coercions, each one a case. */
class PageRequestTests {

    @Test
    void absent_parameters_mean_the_first_page_at_the_default_size() {
        PageRequest page = PageRequest.of(null, null);

        assertThat(page.startIndex()).isEqualTo(1);
        assertThat(page.count()).isEqualTo(100);
        assertThat(page).isEqualTo(PageRequest.FIRST_PAGE);
    }

    @ParameterizedTest
    @CsvSource({
        // requested, coerced — a start index below one is not an error, it is one
        "0, 1",
        "-5, 1",
        "1, 1",
        "7, 7",
    })
    void a_start_index_below_one_becomes_one(int requested, int coerced) {
        assertThat(PageRequest.of(requested, null).startIndex()).isEqualTo(coerced);
    }

    @ParameterizedTest
    @CsvSource({
        // requested, coerced
        "-1, 0",
        "0, 0",
        "1, 1",
        "200, 200",
        "201, 200",
        "100000, 200",
    })
    void a_count_is_clamped_to_the_advertised_maximum(int requested, int coerced) {
        assertThat(PageRequest.of(null, requested).count()).isEqualTo(coerced);
    }

    /**
     * Zero survives coercion. It means "tell me the total and send no resources", which is
     * a question a client asks; turning it into the default page would answer a different
     * one.
     */
    @Test
    void a_count_of_zero_is_a_request_rather_than_an_omission() {
        assertThat(PageRequest.of(1, 0).count()).isZero();
    }

    @Test
    void the_offset_is_the_one_based_start_index_minus_one() {
        assertThat(PageRequest.of(1, 10).offset()).isZero();
        assertThat(PageRequest.of(101, 10).offset()).isEqualTo(100);
    }

    /** The advertised ceiling and the enforced one are the same constant. */
    @Test
    void the_maximum_count_is_the_number_discovery_advertises() {
        assertThat(PageRequest.MAX_COUNT).isEqualTo(200);
        assertThat(PageRequest.DEFAULT_COUNT).isEqualTo(100);
    }
}

package com.example.backend.paging.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * How a raw {@code startIndex} or {@code count} parses, through the paging module's interface: an
 * integer of any size, blank as absent, and the one refusal the paging rules have.
 */
class PageParameterTests {

    /** Blank text is no value, so it is defaulted like an absent one. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void a_blank_parameter_is_absent(String blank) {
        assertThat(PageParameter.valueOf(blank)).isNull();
    }

    @Test
    void a_missing_parameter_is_absent() {
        assertThat(PageParameter.valueOf(null)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        // text, the integer it holds
        "7, 7",
        "' 4 ', 4",
        "+2, 2",
        "-5, -5",
        "99999999999999999999, 99999999999999999999",
    })
    void a_parameter_is_a_decimal_integer_of_any_size(String text, String integer) {
        assertThat(PageParameter.valueOf(text).value()).isEqualTo(new BigInteger(integer));
    }

    /** The one refusal the paging rules have: a value that is not an integer at all. */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "-", "1e3", "0x10", "1 000"})
    void a_parameter_that_is_not_an_integer_is_refused(String text) {
        assertThatThrownBy(() -> PageParameter.valueOf(text))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void a_parameter_holds_an_integer() {
        assertThatThrownBy(() -> new PageParameter(null)).isInstanceOf(NullPointerException.class);
    }
}

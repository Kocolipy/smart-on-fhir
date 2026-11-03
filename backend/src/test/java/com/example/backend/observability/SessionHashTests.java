package com.example.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link SessionHash}: a session named for correlation, never by its id. */
class SessionHashTests {

    /** The first 64 bits of SHA-256("abc"), the FIPS 180-2 test vector. */
    @Test
    void isTheFirstSixteenHexCharactersOfTheIdsSha256() {
        assertThat(SessionHash.of("abc")).isEqualTo("ba7816bf8f01cfea");
    }

    @Test
    void tellsTwoSessionsApart() {
        assertThat(SessionHash.of("a-session")).isNotEqualTo(SessionHash.of("another-session"));
    }

    @Test
    void namesNoSessionWhenThereIsNone() {
        assertThat(SessionHash.of(null)).isNull();
    }
}

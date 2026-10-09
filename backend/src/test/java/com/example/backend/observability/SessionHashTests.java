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
    void namesOneSessionAlikeEveryTime() {
        assertThat(SessionHash.of("a-session")).isEqualTo(SessionHash.of("a-session"));
    }

    @Test
    void tellsTwoSessionsApart() {
        assertThat(SessionHash.of("a-session")).isNotEqualTo(SessionHash.of("another-session"));
    }

    @Test
    void neverContainsTheId() {
        String id = "4f3c2a10-7a8e-4b3d-9c61-2f0e8d5a1b77";

        assertThat(SessionHash.of(id)).doesNotContain(id.substring(0, 8));
    }

    @Test
    void namesNoSessionWhenThereIsNone() {
        assertThat(SessionHash.of(null)).isNull();
    }
}

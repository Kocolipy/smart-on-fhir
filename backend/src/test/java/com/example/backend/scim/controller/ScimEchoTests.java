package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What a SCIM refusal may echo of a client-supplied name or path. */
class ScimEchoTests {

    @Test
    void an_echo_is_lower_cased() {
        assertThat(ScimEcho.sanitized("NickName")).isEqualTo("nickname");
    }

    /** CWE-117: a control character or line break could forge a log record. */
    @Test
    void an_echo_carries_no_control_characters_or_line_breaks() {
        assertThat(ScimEcho.sanitized("nick\u0007na\r\nme\u0000\u001b")).isEqualTo("nickname");
    }

    /** A megabyte-long key is not echoed whole: 64 code points, counted after stripping. */
    @Test
    void an_echo_is_cut_at_sixty_four_code_points() {
        String sixtyFour = "a".repeat(64);
        assertThat(ScimEcho.sanitized("\n" + sixtyFour + "b")).isEqualTo(sixtyFour);
        assertThat(ScimEcho.sanitized("😀".repeat(65)))
                .isEqualTo("😀".repeat(64));
    }
}

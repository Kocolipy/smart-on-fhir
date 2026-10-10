package com.example.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * What a read of a path is, for every chain rule that permits one: the path's {@code GET}, and
 * the {@code HEAD} the dispatcher answers with the same handler, and nothing else.
 */
class ReadRequestsTests {

    private final RequestMatcher readOfCount = ReadRequests.read("/api/count");

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void aGetOrHeadOfThePathIsARead(String method) {
        assertThat(readOfCount.matches(new MockHttpServletRequest(method, "/api/count"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void anyOtherMethodOnThePathIsNotARead(String method) {
        assertThat(readOfCount.matches(new MockHttpServletRequest(method, "/api/count")))
                .isFalse();
    }

    @Test
    void aGetOfAnotherPathIsNotAReadOfThisOne() {
        assertThat(readOfCount.matches(new MockHttpServletRequest("GET", "/api/count/reset")))
                .isFalse();
    }
}

package com.example.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The path algebra both the filter chain and the error view resolver ask, tested
 * once and without a servlet stack. The suites that exercise those two adapters
 * prove only that they ask.
 */
class SpaRoutesTests {

    @Nested
    class ReservedServerPaths {

        /** A reserved prefix reserves the exact path as much as anything beneath it. */
        @ParameterizedTest
        @ValueSource(strings = {
            "/api",
            "/api/",
            "/api/self",
            "/api/auth/login",
            "/actuator",
            "/actuator/health"
        })
        void reservesEachPrefixAndEverythingBeneathIt(String path) {
            assertThat(SpaRoutes.isReservedServerPath(path)).isTrue();
        }

        /**
         * A prefix reserves a path segment, not a string prefix: {@code /apidocs}
         * shares five characters with {@code /api} and belongs to the frontend.
         */
        @ParameterizedTest
        @ValueSource(strings = {"/", "/dashboard", "/apidocs", "/actuatorial", "/api-docs"})
        void doesNotReservePathsThatMerelyShareAPrefixString(String path) {
            assertThat(SpaRoutes.isReservedServerPath(path)).isFalse();
        }
    }

    @Nested
    class SpaShell {

        /** Unmatched routes belong to the client-side router. */
        @ParameterizedTest
        @ValueSource(strings = {"/", "/dashboard", "/account/settings"})
        void servesTheShellForClientSideRoutes(String path) {
            assertThat(SpaRoutes.servesSpaShell(path)).isTrue();
        }

        /**
         * Only the last segment decides: a dot in an earlier segment is a
         * versioned route, not a file request.
         */
        @Test
        void servesTheShellWhenOnlyAnEarlierSegmentCarriesADot() {
            assertThat(SpaRoutes.servesSpaShell("/v1.0/settings")).isTrue();
        }

        /** A missing file stays a 404 rather than returning the HTML shell. */
        @ParameterizedTest
        @ValueSource(strings = {"/favicon.ico", "/assets/missing.js", "/assets/index-a1b2c3.css"})
        void withholdsTheShellFromFileRequests(String path) {
            assertThat(SpaRoutes.servesSpaShell(path)).isFalse();
        }

        /** Reserved paths answer for themselves, 404 included. */
        @ParameterizedTest
        @ValueSource(strings = {"/api", "/api/missing", "/actuator", "/actuator/health"})
        void withholdsTheShellFromReservedServerPaths(String path) {
            assertThat(SpaRoutes.servesSpaShell(path)).isFalse();
        }
    }

    /**
     * The two questions are deliberately not the same one. A hashed asset is
     * served without authentication, yet a missing one must not be answered with
     * the HTML shell — the divergence the filter chain and the resolver used to
     * express as two drifting private predicates.
     */
    @Test
    void servesAssetsWithoutAuthenticationYetWithholdsTheShellFromThem() {
        assertThat(SpaRoutes.isReservedServerPath("/assets/index-a1b2c3.js")).isFalse();
        assertThat(SpaRoutes.servesSpaShell("/assets/index-a1b2c3.js")).isFalse();
    }
}

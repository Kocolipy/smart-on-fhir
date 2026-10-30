package com.example.backend.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * That the SCIM namespace is a reserved server path.
 *
 * <p>Its own class rather than a case added to {@code SpaRoutesTests}, because the
 * claim is about a different consequence: not merely that {@code /scim} is on a list,
 * but that a mistyped SCIM path stays a {@code 404} instead of becoming the SPA index
 * with a {@code 200} — which a provisioning client would parse as a successful empty
 * response rather than as an error.
 */
class SpaRoutesScimNamespaceTests {

    @ParameterizedTest
    @ValueSource(strings = {
        "/scim",
        "/scim/v2",
        "/scim/v2/Users",
        "/scim/v2/Users/8f14e45f-ceea-467a-9575-28a1b0e0e8a1",
        "/scim/v2/ServiceProviderConfig",
        "/scim/v2/NotAResourceType",
    })
    void the_scim_namespace_is_reserved_for_the_server(String path) {
        assertThat(SpaRoutes.isReservedServerPath(path)).isTrue();
        assertThat(SpaRoutes.servesSpaIndex(path)).isFalse();
    }

    /**
     * The prefix reserves {@code /scim} and everything beneath it, and nothing that
     * merely starts with those letters — an SPA route named {@code /scimulator} is
     * still the frontend's.
     */
    @Test
    void a_path_that_only_starts_with_the_same_letters_is_not_reserved() {
        assertThat(SpaRoutes.isReservedServerPath("/scimulator")).isFalse();
        assertThat(SpaRoutes.servesSpaIndex("/scimulator")).isTrue();
    }
}

package com.example.backend.scim.config;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.auth.config.SecurityConfig;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.savedrequest.RequestCacheAwareFilter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

/**
 * The order and composition of the two {@link SecurityFilterChain} beans, which
 * {@link ScimSecurityConfig} calls load-bearing.
 *
 * <p>Both chains exist in one {@link FilterChainProxy} and Spring Security consults them in
 * order, stopping at the first whose matcher accepts the request. The application chain
 * declares no {@code securityMatcher}, so it accepts everything: ordered ahead of the SCIM
 * chain it would answer SCIM requests with the SPA's session and CSRF rules, and the SCIM
 * chain would never run. Asserting the order constants alone would not catch that — an
 * {@code @Order} moved onto the class body leaves the constants untouched and still inverts
 * the registration — so the binding assertion is which chain a real SCIM request actually
 * reaches inside the built proxy.
 *
 * <p><strong>One test method, deliberately.</strong> A configuration class runs once, while
 * the application context is being built, and a Spring test context is cached for the whole
 * JVM — so only the first test method to execute in this class actually runs
 * {@code ScimSecurityConfig}. Mutation testing attributes coverage per test method and then
 * re-runs only the covering tests, which means a chain assertion in any later method is
 * invisible to it: split across four methods, every mutant of the chain builder survived
 * because the sole covering test compared two constants and nothing else. Keeping the
 * assertions in the method that boots the context is what makes them load-bearing. They are
 * soft, so one broken expectation does not hide the rest.
 *
 * <p>The composition assertions are what pin the individual builder calls. Each
 * {@code disable()} in the SCIM chain removes a filter, and each {@code addFilterBefore} adds
 * one at a stated position, so naming the filters that must be absent and the two that must be
 * present — with the gate first and the bearer filter immediately before authorization — fails
 * if any of those calls is dropped. Filters the chain merely inherits from the defaults are
 * not named, so a Spring Security upgrade that adds one does not break this test.
 *
 * <p>The release gate is left at its default (closed), because the order of the chains is a
 * fact about the wiring rather than about whether this deployment serves SCIM. That also
 * shares a context with the other tests that set no SCIM property.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimSecurityChainOrderTests {

    /**
     * Filters the SCIM chain's {@code disable()} calls remove. Present means a mechanism the
     * chain declined is installed anyway: a CSRF check on a bearer API, a form-login or basic
     * authentication entry point beside the bearer one, a logout endpoint inside the SCIM
     * namespace, a saved-request replay, or an anonymous identity where every request must
     * carry a token.
     */
    private static final List<Class<? extends Filter>> MUST_BE_ABSENT = List.of(
            CsrfFilter.class,
            UsernamePasswordAuthenticationFilter.class,
            BasicAuthenticationFilter.class,
            LogoutFilter.class,
            RequestCacheAwareFilter.class,
            AnonymousAuthenticationFilter.class);

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Test
    void the_scim_chain_is_ordered_and_composed_as_its_configuration_claims() {
        List<SecurityFilterChain> chains = filterChainProxy.getFilterChains();
        SecurityFilterChain scimRequestChain = firstChainMatching(request("/scim/v2/Users"));
        SecurityFilterChain applicationRequestChain = firstChainMatching(request("/api/login"));
        SecurityFilterChain last = chains.get(chains.size() - 1);

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(ScimSecurityConfig.SCIM_CHAIN_ORDER)
                    .as("the SCIM chain must sort before the catch-all application chain")
                    .isLessThan(SecurityConfig.APPLICATION_CHAIN_ORDER);

            softly.assertThat(scimRequestChain)
                    .as("no chain matched a SCIM request")
                    .isNotNull();
            softly.assertThat(isScimChain(scimRequestChain))
                    .as("the first chain matching /scim/v2/** must be the SCIM chain, not the "
                            + "application chain that matches everything")
                    .isTrue();

            softly.assertThat(applicationRequestChain)
                    .as("no chain matched an application request")
                    .isNotNull();
            softly.assertThat(isScimChain(applicationRequestChain))
                    .as("the SCIM chain declares a securityMatcher and must not claim an "
                            + "application path")
                    .isFalse();

            softly.assertThat(isScimChain(last))
                    .as("the last chain must be the application chain")
                    .isFalse();
            softly.assertThat(last.matches(request("/api/login")))
                    .as("the application chain matches an application path")
                    .isTrue();
            softly.assertThat(last.matches(request("/scim/v2/Users")))
                    .as("the application chain matches a SCIM path too, which is exactly why it "
                            + "has to be ordered last")
                    .isTrue();

            assertComposition(softly, scimRequestChain);
        });
    }

    /**
     * The SCIM chain's own filter list: the gate ahead of everything, the bearer filter
     * immediately before authorization, and none of the mechanisms the chain disables.
     */
    private static void assertComposition(SoftAssertions softly, SecurityFilterChain chain) {
        if (chain == null) {
            return;
        }
        List<Class<?>> filters = chain.getFilters().stream()
                .<Class<?>>map(Filter::getClass)
                .toList();

        softly.assertThat(filters.indexOf(ScimReleaseGateFilter.class))
                .as("the release gate answers 404 while the namespace is closed and must "
                        + "therefore run before every filter that reads the request, "
                        + "authentication included")
                .isEqualTo(filters.indexOf(WebAsyncManagerIntegrationFilter.class) - 1);
        softly.assertThat(filters.subList(0, Math.max(filters.indexOf(ScimReleaseGateFilter.class), 0)))
                .as("only the response-encoding filter, which reads nothing and decides "
                        + "nothing, may precede the gate")
                .containsAnyOf(DisableEncodeUrlFilter.class)
                .hasSizeLessThanOrEqualTo(1);

        int bearer = filters.indexOf(ScimBearerAuthenticationFilter.class);
        int authorization = filters.indexOf(AuthorizationFilter.class);
        int bodyLimit = filters.indexOf(ScimRequestBodyLimitFilter.class);
        int dispatcherErrors = filters.indexOf(ScimDispatcherErrorFilter.class);
        int headerWriter = filters.indexOf(HeaderWriterFilter.class);
        softly.assertThat(bodyLimit)
                .as("the body bound must be installed after the gate and before authentication, "
                        + "so a declared oversized body is refused without being read")
                .isGreaterThan(filters.indexOf(ScimReleaseGateFilter.class))
                .isLessThan(bearer);
        softly.assertThat(dispatcherErrors)
                .as("the dispatcher-error renderer must wrap the header writer, or a sent error "
                        + "would lose the security headers")
                .isNotNegative()
                .isLessThan(headerWriter);
        softly.assertThat(bodyLimit)
                .as("the body bound must run ahead of the header writer too")
                .isLessThan(headerWriter);
        softly.assertThat(bearer)
                .as("the SCIM chain must install the bearer authentication filter")
                .isNotNegative();
        softly.assertThat(authorization)
                .as("the SCIM chain must still authorize requests")
                .isNotNegative();
        softly.assertThat(bearer < authorization)
                .as("the bearer filter must authenticate before authorization decides, "
                        + "otherwise every authenticated endpoint rejects a valid token")
                .isTrue();

        softly.assertThat(filters)
                .as("the SCIM chain must install an entry point for rejected requests")
                .contains(ExceptionTranslationFilter.class);

        softly.assertThat(filters)
                .as("filters the SCIM chain's disable() calls remove")
                .doesNotContainAnyElementsOf(MUST_BE_ABSENT);
    }

    private SecurityFilterChain firstChainMatching(HttpServletRequest request) {
        return filterChainProxy.getFilterChains().stream()
                .filter(chain -> chain.matches(request))
                .findFirst()
                .orElse(null);
    }

    /**
     * Identified by the release gate filter, which only the SCIM chain installs, rather than
     * by list position — so an inverted order fails on the request that reaches the wrong
     * chain instead of on an index that happens to have moved.
     */
    private static boolean isScimChain(SecurityFilterChain chain) {
        return chain != null
                && chain.getFilters().stream().anyMatch(ScimSecurityChainOrderTests::isReleaseGate);
    }

    private static boolean isReleaseGate(Filter filter) {
        return filter instanceof ScimReleaseGateFilter;
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }
}

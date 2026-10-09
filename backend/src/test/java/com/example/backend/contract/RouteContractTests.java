package com.example.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.ContainerTestConfiguration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every route the application's controllers map is a documented operation, and every documented
 * operation under {@code /api} and {@code /scim/v2} is a mapped route.
 *
 * <p>Read from the live handler mapping rather than from annotations, so a route contributed by a
 * class-level prefix, a method-less mapping or a configuration change is seen exactly as the
 * dispatcher sees it. Path variable names are not compared — {@code {id}} and {@code {userId}}
 * name the same route — but literal segments and methods are.
 *
 * <p>The actuator's operations are not controller routes; that they exist and answer as documented
 * is {@link ApiContractFixtureTests}' to show.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class RouteContractTests {

    /** The namespaces whose routes the contract covers. */
    private static final List<String> NAMESPACES = List.of("/api/", "/scim/v2/");

    /**
     * Documented operations a security filter serves rather than a controller, so the handler
     * mapping cannot show them. Each is held by its own integration tests instead.
     */
    private static final Set<String> FILTER_SERVED = Set.of(
            // Epic Login's callback: the OAuth 2.0 login filter's processing URL, which answers
            // every request to it while Epic Login is on (EpicLoginIntegrationTests), and which
            // the release gate answers 404 while it is off.
            "GET /api/auth/epic/callback");

    /** What a mapping with no method condition answers: every method the contract names. */
    private static final List<String> ALL_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void the_mapped_routes_and_the_documented_operations_are_the_same_set() {
        Set<String> mapped = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (NAMESPACES.stream().noneMatch(pattern::startsWith)) {
                    continue;
                }
                if (methods.isEmpty()) {
                    ALL_METHODS.forEach(method -> mapped.add(route(method, pattern)));
                } else {
                    methods.forEach(method -> mapped.add(route(method.name(), pattern)));
                }
            }
        }
        Set<String> documented = new TreeSet<>();
        for (OpenApiContract.Operation operation : OpenApiContract.load().operations()) {
            if (NAMESPACES.stream().anyMatch(operation.template()::startsWith)) {
                documented.add(route(operation.method(), operation.template()));
            }
        }

        assertThat(mapped).as("the handler mapping was read").isNotEmpty();
        assertThat(mapped).as("mapped routes docs/openapi.yaml does not document")
                .isSubsetOf(documented);
        assertThat(mapped).as("a filter-served operation is not also a controller route")
                .doesNotContainAnyElementsOf(FILTER_SERVED);
        assertThat(documented).as("filter-served operations are documented")
                .containsAll(FILTER_SERVED);
        documented.removeAll(FILTER_SERVED);
        assertThat(documented).as("documented operations no handler maps")
                .isSubsetOf(mapped);
    }

    /** A route with its path variables' names erased. */
    private static String route(String method, String pattern) {
        return method + " " + pattern.replaceAll("\\{[^}]*}", "{}");
    }
}

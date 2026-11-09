package arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.scim.controller.AdminConnectorController;
import com.example.backend.scim.controller.ScimSchemas;
import com.example.backend.web.ApiExceptionHandler;
import com.example.backend.web.ErrorDocumentFilter;
import com.example.backend.web.ErrorNamespaces;
import com.example.backend.web.NamespaceAdvice;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The error-namespace rules in {@link ArchitectureTest}, each shown reporting the shape it forbids
 * and passing the one it allows, on planted classes rather than on the service — whose code
 * complies, so the rules alone would never be seen to fail.
 *
 * <p>These rules call {@code JavaClass#reflect()} to read a controller's mappings and an advice's
 * annotation attributes, so each planted class must be <em>loadable</em>: they are ordinary
 * test-source classes imported with {@link ClassFileImporter#importClasses(Class[])}, not bytecode
 * written into a directory as in {@link UtilityClassRulesTests}. {@link ArchitectureTest} imports
 * with {@code DoNotIncludeTests}, so the planted classes in {@code target/test-classes} cannot
 * reach the real suite.
 *
 * <p>A planted class set holds fewer namespaces than the running service does, so the whole-set
 * assertions of {@code every_error_namespace_is_answered_by_exactly_one_advice} report the
 * namespaces no planted advice answers as well. Those cases assert {@code contains}, not equality.
 */
class ErrorNamespaceRulesTests {

    /** No namespace of this service, and so no legal name for an advice. */
    private static final String NO_NAMESPACE = "/nope";

    // ---- every route is in a namespace ---------------------------------------------------------

    @Test
    void a_controller_mapping_a_route_outside_every_namespace_is_reported() {
        assertThat(violations(ArchitectureTest.every_controller_route_is_in_an_error_namespace,
                        MapsOutsideEveryNamespace.class))
                .contains("MapsOutsideEveryNamespace")
                .contains("/elsewhere/thing, which is in no error namespace");
    }

    @Test
    void a_controller_mapping_an_application_api_route_is_not_reported() {
        assertThat(violations(ArchitectureTest.every_controller_route_is_in_an_error_namespace,
                        MapsTheApplicationApi.class))
                .isEmpty();
    }

    // ---- exactly one advice per namespace ------------------------------------------------------

    @Test
    void a_namespace_answered_by_two_advices_is_reported() {
        assertThat(violations(ArchitectureTest.every_error_namespace_is_answered_by_exactly_one_advice,
                        FirstApiAdvice.class, SecondApiAdvice.class))
                .contains("the error namespace " + ErrorNamespaces.API_PATH + " is answered by ["
                        + FirstApiAdvice.class.getName() + ", " + SecondApiAdvice.class.getName()
                        + "], not exactly one advice");
    }

    @Test
    void an_advice_naming_something_that_is_no_namespace_is_reported() {
        assertThat(violations(ArchitectureTest.every_error_namespace_is_answered_by_exactly_one_advice,
                        AnswersNoNamespace.class))
                .contains(AnswersNoNamespace.class.getName() + " answers " + NO_NAMESPACE
                        + ", which is no error namespace");
    }

    /**
     * The rule keeps its tally in a field of a static {@code ArchCondition}, so it outlives one
     * evaluation: before {@code init} cleared it, a second evaluation in the same JVM counted each
     * advice again and reported every namespace as answered by the same advice twice. The real
     * advices are the subject, because the defect only shows where the first evaluation passes.
     */
    @Test
    void the_real_advices_pass_the_rule_however_often_it_is_evaluated()
            throws ClassNotFoundException {
        JavaClasses advices = new ClassFileImporter().importClasses(ApiExceptionHandler.class,
                Class.forName("com.example.backend.scim.controller.ScimExceptionHandler"));

        assertThat(violations(
                ArchitectureTest.every_error_namespace_is_answered_by_exactly_one_advice, advices))
                .isEmpty();
        assertThat(violations(
                ArchitectureTest.every_error_namespace_is_answered_by_exactly_one_advice, advices))
                .isEmpty();
    }

    // ---- an advice's scope ---------------------------------------------------------------------

    @Test
    void an_advice_scoped_to_its_own_package_is_reported() {
        assertThat(violations(ArchitectureTest.namespace_advices_are_not_scoped_by_the_handler,
                        ScopedToAnotherPackage.class))
                .contains(ScopedToAnotherPackage.class.getName()
                        + " is a namespace advice scoped other than to "
                        + NamespaceAdvice.APPLICATION_HANDLERS);
    }

    @Test
    void an_advice_scoped_to_the_applications_handlers_is_not_reported() {
        assertThat(violations(ArchitectureTest.namespace_advices_are_not_scoped_by_the_handler,
                        FirstApiAdvice.class))
                .isEmpty();
    }

    // ---- the one sendError adapter -------------------------------------------------------------

    @Test
    void a_response_wrapper_nested_in_something_else_is_reported() {
        assertThat(violations(ArchitectureTest.one_send_error_adapter, NestedWrapper.Response.class))
                .contains("wraps a response outside ErrorDocumentFilter");
    }

    @Test
    void a_top_level_response_wrapper_is_reported() {
        // Both halves fire on it: it is not a nested class at all, and so not
        // ErrorDocumentFilter's either.
        assertThat(violations(ArchitectureTest.one_send_error_adapter, TopLevelWrapper.class))
                .contains("TopLevelWrapper> is no nested class")
                .contains("wraps a response outside ErrorDocumentFilter");
    }

    @Test
    void error_document_filters_own_wrapper_is_not_reported() throws ClassNotFoundException {
        assertThat(violations(ArchitectureTest.one_send_error_adapter,
                        ErrorDocumentFilter.class,
                        Class.forName(ErrorDocumentFilter.class.getName() + "$DocumentResponse")))
                .isEmpty();
    }

    // ---- connector administration --------------------------------------------------------------

    /**
     * Positive case only. The rule selects its subject by the fully qualified name
     * {@code com.example.backend.scim.controller.AdminConnectorController}, and a planted negative
     * would have to carry that name: a loadable test-source class cannot, because the real class
     * already holds it on the classpath. Planting it as a class file instead — the route
     * {@link UtilityClassRulesTests} takes — does not work either, since the rule reflects the
     * class to read its mappings and would load the real one. This case still fails on either
     * half: a route of the real controller that left the application API's namespace, and
     * {@code ApiExceptionHandler} ceasing to answer {@code /api}, which the condition checks
     * whatever the subject is.
     */
    @Test
    void the_real_connector_administration_serves_the_application_api() {
        assertThat(violations(
                ArchitectureTest.connector_administration_is_answered_as_the_application_api,
                AdminConnectorController.class))
                .isEmpty();
    }

    // ---- harness -------------------------------------------------------------------------------

    /** The rule's violation report for {@code planted}, or empty when it reports none. */
    private static String violations(ArchRule rule, Class<?>... planted) {
        return violations(rule, new ClassFileImporter().importClasses(planted));
    }

    private static String violations(ArchRule rule, JavaClasses classes) {
        EvaluationResult result = rule.evaluate(classes);
        return result.hasViolation() ? String.join("\n", result.getFailureReport().getDetails())
                : "";
    }

    // ---- planted classes -----------------------------------------------------------------------

    @RestController
    @RequestMapping("/elsewhere")
    static class MapsOutsideEveryNamespace {
        @GetMapping("/thing")
        String read() {
            return "";
        }
    }

    @RestController
    @RequestMapping(ErrorNamespaces.API_PATH + "/x")
    static class MapsTheApplicationApi {
        @GetMapping("/thing")
        String read() {
            return "";
        }
    }

    @RestControllerAdvice(basePackages = NamespaceAdvice.APPLICATION_HANDLERS)
    @NamespaceAdvice(ErrorNamespaces.API_PATH)
    static class FirstApiAdvice {
    }

    @RestControllerAdvice(basePackages = NamespaceAdvice.APPLICATION_HANDLERS)
    @NamespaceAdvice(ErrorNamespaces.API_PATH)
    static class SecondApiAdvice {
    }

    @RestControllerAdvice(basePackages = NamespaceAdvice.APPLICATION_HANDLERS)
    @NamespaceAdvice(NO_NAMESPACE)
    static class AnswersNoNamespace {
    }

    @RestControllerAdvice(basePackages = "com.other")
    @NamespaceAdvice(ScimSchemas.BASE_PATH)
    static class ScopedToAnotherPackage {
    }

    /** A wrapper nested in something that is not {@code ErrorDocumentFilter}. */
    static class NestedWrapper {
        static class Response extends HttpServletResponseWrapper {
            Response(HttpServletResponse response) {
                super(response);
            }
        }
    }
}

/** A wrapper that is not nested at all, which the rule's first half reports on its own. */
class TopLevelWrapper extends HttpServletResponseWrapper {

    TopLevelWrapper(HttpServletResponse response) {
        super(response);
    }
}

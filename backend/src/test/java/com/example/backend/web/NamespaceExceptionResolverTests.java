package com.example.backend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.method.HandlerMethod;

/**
 * How the request's namespace chooses among the namespace advices, against a namespace of this
 * test's own beside the application API's, and how the resolver refuses to start on advices that
 * do not answer every namespace exactly once.
 */
class NamespaceExceptionResolverTests {

    private static final String ELSEWHERE = "/elsewhere/v1";

    private static final String REFUSED_REASON = "elsewhere.refused";

    private final ErrorNamespaces namespaces = new ErrorNamespaces(List.of(new Elsewhere()));

    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * The application API's advice is consulted first, and its catch-all would answer this; the
     * request is in the other namespace, so that namespace's advice answers instead — with its
     * {@code @ResponseStatus} reason resolved through the application's messages, as Spring
     * resolves an advice it chose itself.
     */
    @Test
    void the_requests_namespace_chooses_its_own_advice() throws Exception {
        NamespaceExceptionResolver resolver = resolver(ElsewhereAdvice.class);

        resolve(resolver, ELSEWHERE + "/things", new IllegalArgumentException("x"));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getErrorMessage()).isEqualTo("Refused elsewhere");
    }

    @Test
    void the_application_apis_advice_answers_its_own_namespace() throws Exception {
        NamespaceExceptionResolver resolver = resolver(ElsewhereAdvice.class);

        resolve(resolver, "/api/things", new IllegalArgumentException("x"));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).isEqualTo("application/json");
    }

    /**
     * A failure the namespace's own advice declares nothing for is left unresolved, though
     * another namespace's advice would have answered it.
     */
    @Test
    void a_failure_the_namespaces_advice_does_not_answer_is_left_unresolved() throws Exception {
        NamespaceExceptionResolver resolver = resolver(ElsewhereAdvice.class);

        assertThat(resolver.resolveException(request(ELSEWHERE + "/things"), response, handler(),
                new IllegalStateException("x"))).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /** An advice scoped to named handlers is no namespace's, and Spring consults it as ever. */
    @Test
    void an_advice_scoped_to_named_handlers_answers_as_spring_orders_it() throws Exception {
        NamespaceExceptionResolver resolver =
                resolver(ElsewhereAdvice.class, HandlerScopedAdvice.class);

        resolve(resolver, ELSEWHERE + "/things", new UnsupportedOperationException("x"));

        assertThat(response.getStatus()).isEqualTo(418);
    }

    @Test
    void an_advice_naming_no_namespace_is_refused_at_startup() {
        assertThatThrownBy(() -> resolver(ElsewhereAdvice.class, NowhereAdvice.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(NowhereAdvice.class.getName())
                .hasMessageContaining("/nowhere");
    }

    @Test
    void two_advices_for_one_namespace_are_refused_at_startup() {
        assertThatThrownBy(() -> resolver(ElsewhereAdvice.class, SecondElsewhereAdvice.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two controller advices")
                .hasMessageContaining(ELSEWHERE);
    }

    /** An advice behind a namespace advice would never be consulted, so it may not start. */
    @Test
    void an_advice_ordered_after_a_namespace_advice_is_refused_at_startup() {
        assertThatThrownBy(() -> resolver(ElsewhereAdvice.class, LateAdvice.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LateAdvice.class.getName())
                .hasMessageContaining("ordered after a namespace advice");
    }

    @Test
    void a_namespace_no_advice_answers_is_refused_at_startup() {
        assertThatThrownBy(() -> resolver())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No controller advice")
                .hasMessageContaining(ELSEWHERE);
    }

    /** What Spring MVC installs, configured as it configures it, chooses by namespace too. */
    @Test
    void the_registration_installs_this_resolver_over_the_namespaces() throws Exception {
        NamespaceExceptionResolver installed = configured((NamespaceExceptionResolver)
                new NamespaceExceptionResolver.Registration(namespaces)
                        .getExceptionHandlerExceptionResolver(), ElsewhereAdvice.class);

        resolve(installed, ELSEWHERE + "/things", new IllegalArgumentException("x"));

        assertThat(response.getStatus()).isEqualTo(409);
    }

    // ---- harness ------------------------------------------------------------------------------

    /** A resolver over the application API's advice, registered first, and {@code advices}. */
    private NamespaceExceptionResolver resolver(Class<?>... advices) {
        return configured(new NamespaceExceptionResolver(namespaces), advices);
    }

    private NamespaceExceptionResolver configured(
            NamespaceExceptionResolver resolver, Class<?>... advices) {
        StaticWebApplicationContext context = new StaticWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.registerBean(ApiExceptionHandler.class, () -> new ApiExceptionHandler(namespaces));
        for (Class<?> advice : advices) {
            context.registerBean(advice);
        }
        context.getStaticMessageSource()
                .addMessage(REFUSED_REASON, Locale.getDefault(), "Refused elsewhere");
        context.refresh();
        resolver.setApplicationContext(context);
        resolver.setMessageConverters(List.of(new JacksonJsonHttpMessageConverter()));
        resolver.afterPropertiesSet();
        return resolver;
    }

    private void resolve(NamespaceExceptionResolver resolver, String path, Exception failure)
            throws Exception {
        assertThat(resolver.resolveException(request(path), response, handler(), failure))
                .as("resolved by an exception handler").isNotNull();
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private static HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(new Handler(), Handler.class.getMethod("handle"));
    }

    /** A handler in this application's packages, so in every namespace advice's scope. */
    static class Handler {

        public void handle() {
        }
    }

    /** This test's own namespace. */
    static final class Elsewhere implements ErrorNamespace {

        @Override
        public String path() {
            return ELSEWHERE;
        }

        @Override
        public void refuse(HttpServletResponse response, int status) throws IOException {
            response.setStatus(status);
        }
    }

    @RestControllerAdvice(basePackages = NamespaceAdvice.APPLICATION_HANDLERS)
    @NamespaceAdvice(ELSEWHERE)
    static class ElsewhereAdvice {

        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(code = HttpStatus.CONFLICT, reason = REFUSED_REASON)
        void refused() {
        }
    }

    @RestControllerAdvice(basePackages = NamespaceAdvice.APPLICATION_HANDLERS)
    @NamespaceAdvice(ELSEWHERE)
    static class SecondElsewhereAdvice {

        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        void refused() {
        }
    }

    @RestControllerAdvice(basePackages = NamespaceAdvice.APPLICATION_HANDLERS)
    @NamespaceAdvice("/nowhere")
    static class NowhereAdvice {

        @ExceptionHandler(IllegalArgumentException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        void refused() {
        }
    }

    /** Scoped to named handlers, but at the namespace advices' own, lowest, precedence. */
    @RestControllerAdvice(assignableTypes = Handler.class)
    static class LateAdvice {

        @ExceptionHandler(UnsupportedOperationException.class)
        ResponseEntity<Void> unsupported() {
            return ResponseEntity.status(HttpStatus.I_AM_A_TEAPOT).build();
        }
    }

    @RestControllerAdvice(assignableTypes = Handler.class)
    @org.springframework.core.annotation.Order(0)
    static class HandlerScopedAdvice {

        @ExceptionHandler(UnsupportedOperationException.class)
        ResponseEntity<Void> unsupported() {
            return ResponseEntity.status(HttpStatus.I_AM_A_TEAPOT).build();
        }
    }
}

package com.example.backend.web;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod;

/**
 * Lets the request's {@link ErrorNamespace}, not the handler's type, choose which advice answers
 * a handler's failure.
 *
 * <p>Spring MVC scopes a controller advice by the handler's type — its package, its class, an
 * annotation on it — and that was how the application API's advice and the SCIM namespace's were
 * kept apart, until a controller in one module served the other's namespace: connector
 * administration lives in {@code scim} but serves {@code /api}. Here the namespace advices (each
 * marked {@link NamespaceAdvice}) all share one scope, this application's handlers, and the
 * request's namespace picks among them.
 *
 * <p>The handler's own {@code @ExceptionHandler}s still answer first, and every advice that is no
 * namespace's (one scoped to named handlers) is consulted as Spring orders it; only when Spring
 * would hand the failure to a namespace advice is it handed instead to the request's namespace's
 * — or, if that advice declares nothing for it, left unresolved, as it would have been under that
 * advice alone. A namespace advice must therefore be ordered after every other advice, or an
 * advice behind it would never be consulted: the resolver refuses to start otherwise.
 *
 * <p>A refusal made before a handler is chosen — no route, a method, {@code Accept} or body type
 * no route takes — is not a handler's failure, and no namespace advice answers it, since their
 * shared scope covers handlers only: the dispatcher answers it with {@code sendError}, which
 * {@link ErrorDocumentFilter} renders in the namespace's document. Nor does any answer a failure
 * of a handler in no namespace.
 */
public final class NamespaceExceptionResolver extends ExceptionHandlerExceptionResolver {

    private final ErrorNamespaces namespaces;

    /** Each namespace's advice, by the namespace's path; filled once the advice beans are known. */
    private Map<String, Advice> byNamespace;

    /** The namespace advices' types; filled with {@link #byNamespace}. */
    private Set<Class<?>> namespaceAdvices;

    public NamespaceExceptionResolver(ErrorNamespaces namespaces) {
        this.namespaces = namespaces;
    }

    /**
     * Finds every namespace's advice among the advice beans.
     *
     * @throws IllegalStateException unless every namespace is named by exactly one advice, every
     *                               name is a namespace, and every namespace advice is ordered
     *                               after every other advice
     */
    @Override
    public void afterPropertiesSet() {
        super.afterPropertiesSet();
        Map<String, Advice> named = new HashMap<>();
        Set<String> paths = new HashSet<>();
        namespaces.all().forEach(namespace -> paths.add(namespace.path()));
        // The cache is in the order Spring consults the advices.
        getExceptionHandlerAdviceCache().forEach((advice, methods) -> {
            NamespaceAdvice declared = AnnotatedElementUtils.findMergedAnnotation(
                    advice.getBeanType(), NamespaceAdvice.class);
            if (declared == null) {
                if (!named.isEmpty()) {
                    throw new IllegalStateException(advice.getBeanType().getName()
                            + " is ordered after a namespace advice, which would shadow it");
                }
                return;
            }
            for (String path : declared.value()) {
                if (!paths.contains(path)) {
                    throw new IllegalStateException(advice.getBeanType().getName()
                            + " answers for " + path + ", which is no error namespace");
                }
                if (named.put(path, new Advice(advice, methods)) != null) {
                    throw new IllegalStateException(
                            "Two controller advices answer for the error namespace " + path);
                }
            }
        });
        paths.removeAll(named.keySet());
        if (!paths.isEmpty()) {
            throw new IllegalStateException(
                    "No controller advice answers for the error namespaces " + paths);
        }
        byNamespace = Map.copyOf(named);
        namespaceAdvices = named.values().stream()
                .map(advice -> advice.advice().getBeanType())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected @Nullable ServletInvocableHandlerMethod getExceptionHandlerMethod(
            @Nullable HandlerMethod handlerMethod, Exception exception, ServletWebRequest request) {
        ServletInvocableHandlerMethod chosen =
                super.getExceptionHandlerMethod(handlerMethod, exception, request);
        if (chosen == null || !namespaceAdvices.contains(chosen.getBeanType())) {
            // The handler's own, an advice scoped to named handlers, or nothing — always nothing
            // for a failure with no handler, which no namespace advice's scope covers.
            return chosen;
        }
        Optional<ErrorNamespace> namespace = namespaces.of(request.getRequest());
        if (namespace.isEmpty()) {
            return null;
        }
        Advice owner = byNamespace.get(namespace.get().path());
        Method method = owner.methods().resolveMethod(exception);
        return method == null
                ? null
                : new ServletInvocableHandlerMethod(
                        owner.advice().resolveBean(), method, getApplicationContext());
    }

    /** A namespace advice and the {@code @ExceptionHandler} methods it declares. */
    private record Advice(ControllerAdviceBean advice, ExceptionHandlerMethodResolver methods) {
    }

    /** Installs this resolver as Spring MVC's {@code @ExceptionHandler} resolver. */
    @Component
    static class Registration implements WebMvcRegistrations {

        private final ErrorNamespaces namespaces;

        Registration(ErrorNamespaces namespaces) {
            this.namespaces = namespaces;
        }

        @Override
        public ExceptionHandlerExceptionResolver getExceptionHandlerExceptionResolver() {
            return new NamespaceExceptionResolver(namespaces);
        }
    }
}

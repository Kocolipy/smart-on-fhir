package com.example.backend.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller advice as the one that answers a failure of a handler serving a request in
 * the named {@link ErrorNamespace}s — whichever module or package the handler is in.
 *
 * <p>{@link NamespaceExceptionResolver} hands each such failure to the advice naming the request's
 * namespace, and to no other namespace advice; it refuses to start unless every namespace is named
 * by exactly one advice and every name is a namespace. The advice itself, being one namespace's
 * among several, declares the scope every namespace advice shares, {@link #APPLICATION_HANDLERS},
 * rather than one of its own, and is ordered after every other advice.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NamespaceAdvice {

    /**
     * The scope every namespace advice declares: this application's handlers, whichever module
     * they are in, and never a framework's (the actuator's). The request's namespace, not the
     * handler's package, picks among the advices ({@link NamespaceExceptionResolver}).
     */
    String APPLICATION_HANDLERS = "com.example.backend";

    /** The {@link ErrorNamespace#path() paths} of the namespaces this advice answers for. */
    String[] value();
}

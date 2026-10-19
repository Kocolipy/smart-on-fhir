package com.example.backend.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * A namespace of request paths and the one error document every refusal under it is answered
 * with, whichever layer refuses: a handler's advice, the security chain, a release gate, the
 * request firewall or the dispatcher.
 *
 * <p>{@link ErrorNamespaces} holds the application API's two, {@code /api} and
 * {@code /api/patient}; a module that serves a namespace of its own contributes it as a bean —
 * the SCIM namespace's is the {@code scim} module's, so this module never names the SCIM error
 * document (the module graph lets {@code web} depend on {@code observability} alone).
 */
public interface ErrorNamespace {

    /**
     * The path the namespace owns: exactly it and everything beneath it. The one controller
     * advice that names it in its {@link NamespaceAdvice} answers a failure of a handler serving a
     * request here.
     */
    String path();

    /**
     * Writes the namespace's document for a refusal no handler made, answered with
     * {@code status}, as the whole response — and the record such a refusal has in this
     * namespace, if any. Called on a response with nothing buffered; the caller commits it.
     * Nothing the request carried may reach the body.
     */
    void refuse(HttpServletResponse response, int status) throws IOException;
}

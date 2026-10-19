package com.example.backend.web;

import com.example.backend.observability.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every refusal in an {@link ErrorNamespace} that is a {@code sendError} the namespace's
 * error document — the one {@code sendError} adapter, for every namespace.
 *
 * <p>Those refusals are the request firewall's (a line break in the path), the dispatcher's own —
 * a path no operation serves ({@code 404}), a method ({@code 405}), an {@code Accept}
 * ({@code 406}) or a body type ({@code 415}) a declared route does not take — and any other
 * layer's that answers with {@code sendError}. A servlet container answers each by dispatching to
 * the error page, whose body is no namespace's document and quotes the request's path, and so
 * whatever id or value it carried. This wrapper turns that {@code sendError} into the document
 * itself ({@link ErrorNamespace#refuse}), so the error page is never reached under a namespace,
 * the status alone picks the content, and the message the caller of {@code sendError} passed —
 * the dispatcher's quote the request — is discarded.
 *
 * <p>A container filter, ordered just inside {@link RequestIdFilter} — so the request record
 * still reads the status written here — and ahead of both security chains, whose firewall refuses
 * a request before any filter in them runs. Headers set before the refusal survive: a {@code 405}
 * keeps the {@code Allow} header the dispatcher wrote, and a chain's security headers are written
 * as they are for any other answer, because its header writer wraps this response and writes them
 * as the error commits it. Records are each namespace's: the application API's documents add none
 * — each refusal already has its record, or, for the dispatcher's, the request record alone — and
 * the SCIM document writes the {@code scim.refusal} record it always has.
 */
@Component
@Order(ErrorDocumentFilter.ORDER)
public class ErrorDocumentFilter extends OncePerRequestFilter {

    /** Just inside the request record's filter, and outside the security chains. */
    public static final int ORDER = RequestIdFilter.ORDER + 1;

    private final ErrorNamespaces namespaces;

    public ErrorDocumentFilter(ErrorNamespaces namespaces) {
        this.namespaces = namespaces;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return namespaces.of(request).isEmpty();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request,
                new DocumentResponse(response, namespaces.of(request).orElseThrow()));
    }

    /** Renders {@code sendError} as the namespace's document instead of an error dispatch. */
    private static final class DocumentResponse extends HttpServletResponseWrapper {

        private final ErrorNamespace namespace;

        DocumentResponse(HttpServletResponse response, ErrorNamespace namespace) {
            super(response);
            this.namespace = namespace;
        }

        @Override
        public void sendError(int status) throws IOException {
            ErrorNamespaces.refuse(namespace, (HttpServletResponse) getResponse(), status);
        }

        /** The message is discarded: the dispatcher's quote the request. */
        @Override
        public void sendError(int status, String message) throws IOException {
            sendError(status);
        }
    }
}

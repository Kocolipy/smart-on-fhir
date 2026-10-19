package com.example.backend.web;

import com.example.backend.observability.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every refusal under the FHIR path that is not a handler's the {@link FhirOperationOutcome}
 * a handler's gets from {@link ApiExceptionHandler}.
 *
 * <p>Those refusals are {@code sendError}s: the request firewall's (a line break in the path), the
 * security chain's {@code 401} and {@code 403}, and the dispatcher's own — a method no operation
 * serves ({@code 405}), a path none serves ({@code 404}). A servlet container answers each by
 * dispatching to the error page, whose body is not FHIR and quotes the request's path, and so the
 * id it was sent. This wrapper turns that {@code sendError} into the outcome itself, so the error
 * page is never reached, the status alone picks the issue, and the message the caller of
 * {@code sendError} passed — the dispatcher's quote the request — is discarded.
 *
 * <p>A container filter, ordered just inside {@link RequestIdFilter} — so the request record
 * still reads the status written here — and ahead of the security chain, whose firewall refuses a
 * request before any filter in it runs. Headers set before the refusal survive: a {@code 405}
 * keeps the {@code Allow} header the dispatcher wrote, and the chain's security headers are
 * written as they are for any other answer. Nothing here records anything: each refusal already
 * has its record, or, for the dispatcher's, the request record alone.
 */
@Component
@Order(FhirErrorDocumentFilter.ORDER)
public class FhirErrorDocumentFilter extends OncePerRequestFilter {

    /** Just inside the request record's filter, and outside the security chain. */
    public static final int ORDER = RequestIdFilter.ORDER + 1;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !FhirOperationOutcome.coversPath(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, new OutcomeResponse(response));
    }

    /** Renders {@code sendError} as an {@code OperationOutcome} instead of an error dispatch. */
    private static final class OutcomeResponse extends HttpServletResponseWrapper {

        OutcomeResponse(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void sendError(int status) throws IOException {
            render(status);
        }

        /** The message is discarded: the dispatcher's quote the request. */
        @Override
        public void sendError(int status, String message) throws IOException {
            render(status);
        }

        /**
         * Discards anything buffered and writes the outcome in its place. A response already
         * committed cannot take an error any more: {@code resetBuffer} refuses it with the
         * {@link IllegalStateException} the Servlet API specifies for {@code sendError}.
         */
        private void render(int status) throws IOException {
            resetBuffer();
            write((HttpServletResponse) getResponse(), status);
            flushBuffer();
        }
    }

    /**
     * Writes the outcome for {@code status} as the whole response: this layer has no message
     * converter to hand the record to. Every value written is a literal of
     * {@link FhirOperationOutcome}, and {@code FhirErrorDocumentFilterTests} holds the rendering
     * to the record's own serialization. Written as bytes: a writer would have the container add
     * a {@code charset} to the media type, which a message converter's answer does not carry.
     */
    private static void write(HttpServletResponse response, int status) throws IOException {
        FhirOperationOutcome.Issue issue = FhirOperationOutcome.of(status).issue().getFirst();
        response.setStatus(status);
        response.setContentType(FhirOperationOutcome.FHIR_JSON_R5.toString());
        response.getOutputStream().write("""
                {"resourceType":"OperationOutcome","issue":[\
                {"severity":"%s","code":"%s","diagnostics":"%s"}]}"""
                .formatted(issue.severity(), issue.code(), issue.diagnostics())
                .getBytes(StandardCharsets.UTF_8));
    }
}

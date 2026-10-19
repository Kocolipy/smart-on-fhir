package com.example.backend.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one answer to which error document a request is answered with: the document of the
 * {@link ErrorNamespace} its path is in, the most specific one where namespaces nest.
 *
 * <ul>
 *   <li>{@value #API_PATH} → {@link ApiError} ({@link #API});
 *   <li>{@value #FHIR_PATH} → {@link FhirOperationOutcome} ({@link #FHIR});
 *   <li>and each namespace another module contributes as a bean — {@code /scim/v2} → the SCIM
 *       error document.
 * </ul>
 *
 * <p>Every layer that can refuse a request asks it: the app-wide exception handler, the
 * application chain's entry point and access-denied handler, the Epic release gate, the
 * {@code sendError} adapter ({@link ErrorDocumentFilter}) and, to choose the advice that answers
 * a handler's failure, {@link NamespaceExceptionResolver}. A path in no namespace — the
 * single-page application's — has no document, and keeps the container's handling.
 */
@Component
public class ErrorNamespaces {

    /** Where the application API is served. */
    public static final String API_PATH = "/api";

    /**
     * Where the API serves FHIR: the patient module's resources. A new FHIR resource is served
     * beneath it, and so is answered as FHIR by every layer with no further change.
     */
    public static final String FHIR_PATH = "/api/patient";

    /** The application API's namespace: {@link ApiError}, as {@code application/json}. */
    public static final ErrorNamespace API = new BuiltIn(API_PATH) {
        @Override
        public void refuse(HttpServletResponse response, int status) throws IOException {
            write(response, status, MediaType.APPLICATION_JSON_VALUE,
                    JSON.writeValueAsBytes(ApiError.of(HttpStatusCode.valueOf(status))));
        }
    };

    /**
     * The FHIR namespace: an {@link FhirOperationOutcome}, as FHIR JSON of version 5.0. Every
     * value written is a literal of the record, and {@code ErrorNamespacesTests} holds the
     * rendering to the record's own serialization.
     */
    public static final ErrorNamespace FHIR = new BuiltIn(FHIR_PATH) {
        @Override
        public void refuse(HttpServletResponse response, int status) throws IOException {
            FhirOperationOutcome.Issue issue = FhirOperationOutcome.of(status).issue().getFirst();
            write(response, status, FhirOperationOutcome.FHIR_JSON_R5.toString(), """
                    {"resourceType":"OperationOutcome","issue":[\
                    {"severity":"%s","code":"%s","diagnostics":"%s"}]}"""
                    .formatted(issue.severity(), issue.code(), issue.diagnostics())
                    .getBytes(StandardCharsets.UTF_8));
        }
    };

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Decodes the path and drops {@code ;} parameters, as request matching does. */
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    /** Every namespace, the most specific first, so the first whose path covers a request wins. */
    private final List<ErrorNamespace> namespaces;

    /**
     * The application API's namespaces and the {@code contributed} ones.
     *
     * @throws IllegalStateException if two namespaces claim one path
     */
    public ErrorNamespaces(List<ErrorNamespace> contributed) {
        List<ErrorNamespace> all = new ArrayList<>(List.of(API, FHIR));
        all.addAll(contributed);
        Set<String> paths = new HashSet<>();
        for (ErrorNamespace namespace : all) {
            if (!paths.add(namespace.path())) {
                throw new IllegalStateException(
                        "Two error namespaces claim " + namespace.path());
            }
        }
        all.sort(Comparator.comparingInt((ErrorNamespace namespace) -> namespace.path().length())
                .reversed());
        this.namespaces = List.copyOf(all);
    }

    /** Every namespace, the most specific first. */
    public List<ErrorNamespace> all() {
        return namespaces;
    }

    /**
     * The namespace {@code request}'s path is in, if any — the path as Spring routes on it,
     * decoded and below the context path, so {@code /%61pi/self} is in {@code /api} as surely as
     * the handler it reaches is.
     */
    public Optional<ErrorNamespace> of(HttpServletRequest request) {
        return of(PATHS.getPathWithinApplication(request));
    }

    /** The namespace {@code path}, relative to the context path, is in, if any. */
    public Optional<ErrorNamespace> of(String path) {
        return namespaces.stream()
                .filter(namespace -> path.equals(namespace.path())
                        || path.startsWith(namespace.path() + "/"))
                .findFirst();
    }

    /**
     * Answers {@code request} with {@code status} and its namespace's document as the whole
     * response, committed — or, for a path in no namespace, with the container's own
     * {@code sendError}. Anything buffered is discarded; a response already committed cannot
     * take an error any more, and {@code resetBuffer} refuses it with the
     * {@link IllegalStateException} the Servlet API specifies for {@code sendError}.
     */
    public void refuse(HttpServletRequest request, HttpServletResponse response, int status)
            throws IOException {
        Optional<ErrorNamespace> namespace = of(request);
        if (namespace.isEmpty()) {
            response.sendError(status);
            return;
        }
        refuse(namespace.get(), response, status);
    }

    static void refuse(ErrorNamespace namespace, HttpServletResponse response, int status)
            throws IOException {
        response.resetBuffer();
        namespace.refuse(response, status);
        response.flushBuffer();
    }

    /**
     * Writes {@code body}, answered with {@code status}, as the whole response. As bytes: a writer
     * would have the container add a {@code charset} to the media type, which a message
     * converter's answer does not carry.
     */
    private static void write(
            HttpServletResponse response, int status, String contentType, byte[] body)
            throws IOException {
        response.setStatus(status);
        response.setContentType(contentType);
        response.getOutputStream().write(body);
    }

    /** One of the application API's namespaces, answered by {@link ApiExceptionHandler}. */
    private abstract static class BuiltIn implements ErrorNamespace {

        private final String path;

        BuiltIn(String path) {
            this.path = path;
        }

        @Override
        public String path() {
            return path;
        }
    }
}

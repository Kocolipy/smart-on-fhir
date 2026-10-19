package com.example.backend.web;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;

/**
 * The application API's error body in its FHIR namespace ({@link ErrorNamespaces#FHIR}): a FHIR
 * R5 {@code OperationOutcome} with one issue, as FHIR JSON of version 5.0 (ADR 0014). Every
 * failure answered under {@value ErrorNamespaces#FHIR_PATH} has this shape whichever layer answers
 * it — {@link ApiExceptionHandler} for a handler's, the security chain for its own, and
 * {@link ErrorDocumentFilter} for the firewall's and the dispatcher's.
 *
 * <p>Like {@link ApiError}, nothing in it comes from the request or the exception: the issue's
 * {@code code} is chosen by the status alone and its {@code diagnostics} are a fixed sentence per
 * code, so an outcome can never echo the submitted id, an exception message, SQL or a stack trace.
 *
 * @param resourceType always {@code OperationOutcome}
 * @param issue        the one issue
 */
public record FhirOperationOutcome(String resourceType, List<Issue> issue) {

    /**
     * FHIR's JSON media type, naming the FHIR version the body conforms to. The patient module's
     * resources are written with the same one, which it holds itself: it does not reach this
     * module (ADR 0014).
     */
    public static final MediaType FHIR_JSON_R5 =
            new MediaType("application", "fhir+json", Map.of("fhirVersion", "5.0"));

    /** The outcome for a failure answered with {@code status}. */
    public static FhirOperationOutcome of(int status) {
        return new FhirOperationOutcome("OperationOutcome", List.of(Issue.of(status)));
    }

    /**
     * One issue.
     *
     * @param severity    the FHIR {@code IssueSeverity} code; always {@code error}
     * @param code        the FHIR {@code IssueType} code
     * @param diagnostics a fixed sentence per code, never anything the request carried
     */
    public record Issue(String severity, String code, String diagnostics) {

        static Issue of(int status) {
            if (status >= 500) {
                return error("exception", "The request could not be completed.");
            }
            return switch (status) {
                case HttpServletResponse.SC_UNAUTHORIZED ->
                        error("login", "A signed-in session is required.");
                case HttpServletResponse.SC_FORBIDDEN ->
                        error("forbidden", "The request is not permitted.");
                case HttpServletResponse.SC_NOT_FOUND ->
                        error("not-found", "The resource was not found.");
                case HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                        HttpServletResponse.SC_NOT_ACCEPTABLE,
                        HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE ->
                        error("not-supported", "The request is not supported.");
                default -> error("invalid", "The request is not valid.");
            };
        }

        private static Issue error(String code, String diagnostics) {
            return new Issue("error", code, diagnostics);
        }
    }
}

package com.example.backend.patient.controller;

import java.util.List;

/**
 * A FHIR R5 {@code OperationOutcome}: why a FHIR request was not answered with the resource.
 *
 * @param resourceType always {@code OperationOutcome}
 * @param issue        the issues; one, for every outcome this service writes today
 */
public record FhirOperationOutcome(String resourceType, List<Issue> issue) {

    /**
     * The outcome for a resource that is not there. Its diagnostics are generic: they say nothing
     * about the id asked for, or about which resources exist.
     */
    public static FhirOperationOutcome notFound() {
        return new FhirOperationOutcome("OperationOutcome", List.of(
                new Issue("error", "not-found", "No patient is held under this id.")));
    }

    /**
     * One issue.
     *
     * @param severity    the FHIR {@code IssueSeverity} code
     * @param code        the FHIR {@code IssueType} code
     * @param diagnostics human-readable detail, never anything the request carried
     */
    public record Issue(String severity, String code, String diagnostics) {
    }
}

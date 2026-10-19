package com.example.backend.patient.controller;

import com.example.backend.patient.application.PatientReadService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Inbound HTTP adapter for reading a Patient as FHIR R5 (ADR 0014): {@code GET /api/patient/{id}}.
 *
 * <p>Requires {@code patient:read}, declared here with method security and repeated by the chain
 * as a backstop (ADR 0010). Read-only: the module has no write operation, and the dispatcher
 * answers any other method {@code 405} before anything here runs.
 *
 * <p>The answer is FHIR JSON of version 5.0: the {@code Patient} itself, or an
 * {@code OperationOutcome}. This adapter renders only the {@code Patient}. Every failure — an id
 * that is not a UUID, an id no patient is held under, a fault — is left to the app-wide
 * exception handler, which classifies it and answers it, on this path, with an outcome whose
 * diagnostics are fixed (/docs/domain/patient.md, "Errors"). A fault gets its record there; a
 * {@code 400} or {@code 404} gets none beyond the request's own, which carries its
 * classification. The {@code 404} is an ordinary answer to the SPA ("No patient in context"), not a
 * failure.
 *
 * <p>Nothing here logs. A patient is Restricted data, and the request's own record names the
 * route template, never the id it was asked for.
 */
@RestController
@RequestMapping("/api/patient")
public class PatientController {

    /** FHIR's JSON media type, naming the FHIR version the body conforms to. */
    static final MediaType FHIR_JSON_R5 =
            new MediaType("application", "fhir+json", Map.of("fhirVersion", "5.0"));

    private final PatientReadService patients;

    public PatientController(PatientReadService patients) {
        this.patients = patients;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('patient:read')")
    public ResponseEntity<FhirPatient> read(@PathVariable UUID id) {
        return patients.find(id)
                .map(patient -> ResponseEntity.ok()
                        .contentType(FHIR_JSON_R5)
                        .body(FhirPatient.of(patient)))
                .orElseThrow(NoSuchPatientException::new);
    }

    /** No patient is held under the id asked for. It carries no message and no id. */
    static final class NoSuchPatientException extends ResponseStatusException {

        NoSuchPatientException() {
            super(HttpStatus.NOT_FOUND);
        }
    }
}

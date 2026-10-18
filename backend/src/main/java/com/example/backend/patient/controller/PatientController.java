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

/**
 * Inbound HTTP adapter for reading a Patient as FHIR R5 (ADR 0014): {@code GET /api/patient/{id}}.
 *
 * <p>Requires {@code patient:read}, declared here with method security and repeated by the chain
 * as a backstop (ADR 0010). Read-only: the module has no write operation.
 *
 * <p>Both answers are FHIR JSON of version 5.0, the {@code Patient} itself or, for an id no patient
 * is held under, a {@code not-found} {@code OperationOutcome}. The {@code 404} is an ordinary
 * answer to the SPA ("No patient in context"), not a failure.
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
    public ResponseEntity<Object> read(@PathVariable UUID id) {
        return patients.find(id)
                .<ResponseEntity<Object>>map(patient -> ResponseEntity.ok()
                        .contentType(FHIR_JSON_R5)
                        .body(FhirPatient.of(patient)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(FHIR_JSON_R5)
                        .body(FhirOperationOutcome.notFound()));
    }
}

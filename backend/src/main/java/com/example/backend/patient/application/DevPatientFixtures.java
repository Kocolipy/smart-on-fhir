package com.example.backend.patient.application;

import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientName;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The development patient fixtures: dummy patients seeded only where the development fixtures are
 * on ({@link PatientSeedService}), each under a fixed id so seeding is idempotent.
 *
 * <p>Every NRIC here is well-formed but carries a deliberately wrong check letter, so it cannot be
 * a real person's. Each is unique.
 *
 * <p>This class owns the default patient's id. The SPA's {@code DEFAULT_PATIENT_ID}
 * ({@code frontend/src/pages/home-api.ts}) is a copy of it, which Home reads until a SMART launch
 * supplies the patient in context.
 */
public final class DevPatientFixtures {

    /**
     * The default patient: a Chinese name, family name first. Its NRIC's valid check letter would
     * be {@code D}.
     */
    public static final Patient DEFAULT_PATIENT = new Patient(
            UUID.fromString("00000000-0000-4000-8000-00000000c001"),
            "S1234567A",
            new PatientName("Tan Wei Ming", "Tan", List.of("Wei", "Ming")),
            AdministrativeGender.MALE,
            LocalDate.of(1985, 3, 12));

    /** Every fixture, in seeding order. */
    public static final List<Patient> ALL = List.of(DEFAULT_PATIENT);

    private DevPatientFixtures() {
    }
}

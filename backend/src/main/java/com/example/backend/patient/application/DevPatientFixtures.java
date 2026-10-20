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
 * <p>Four patients, covering Singapore's name styles and every gender code (the table in
 * /docs/domain/patient.md). Every NRIC here is well-formed but carries a deliberately wrong check
 * letter, so it cannot be a real person's. Each is unique.
 *
 * <p>Nothing in the SPA names any of these ids: Home reads a patient only when a launch puts one in
 * context.
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

    /**
     * A Malay name with a patronymic, {@code binte Ahmad}, which is not a family name, so the name
     * is not split at all: no {@code family} and no {@code given}. Its NRIC's valid check letter
     * would be {@code F}.
     */
    public static final Patient MALAY_PATIENT = new Patient(
            UUID.fromString("00000000-0000-4000-8000-00000000c002"),
            "S9212345A",
            new PatientName("Siti Nurhaliza binte Ahmad", null, List.of()),
            AdministrativeGender.FEMALE,
            LocalDate.of(1992, 8, 21));

    /**
     * An Indian single name: one given name and no {@code family}. Gender {@code unknown}. Its
     * NRIC's valid check letter would be {@code B}.
     */
    public static final Patient SINGLE_NAME_PATIENT = new Patient(
            UUID.fromString("00000000-0000-4000-8000-00000000c003"),
            "T0312345C",
            new PatientName("Kumaravel", null, List.of("Kumaravel")),
            AdministrativeGender.UNKNOWN,
            LocalDate.of(2003, 11, 5));

    /**
     * A Eurasian name, given names first and a family name of two words. Gender {@code other}. Its
     * NRIC's valid check letter would be {@code A}.
     */
    public static final Patient EURASIAN_PATIENT = new Patient(
            UUID.fromString("00000000-0000-4000-8000-00000000c004"),
            "S7812345B",
            new PatientName("Marcus Anthony de Souza", "de Souza", List.of("Marcus", "Anthony")),
            AdministrativeGender.OTHER,
            LocalDate.of(1978, 6, 30));

    /** Every fixture, in seeding order: between them, every name split and every gender code. */
    public static final List<Patient> ALL =
            List.of(DEFAULT_PATIENT, MALAY_PATIENT, SINGLE_NAME_PATIENT, EURASIAN_PATIENT);

    private DevPatientFixtures() {
    }
}

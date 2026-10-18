package com.example.backend.patient.config;

import com.example.backend.observability.LogEvent;
import com.example.backend.observability.LogEvent.Category;
import com.example.backend.observability.LogEvent.Operation;
import com.example.backend.observability.LogEvent.Type;
import com.example.backend.patient.application.DevPatientFixtures;
import com.example.backend.patient.application.PatientSeedService;
import com.example.backend.patient.application.PatientSeedService.SeedCounts;
import com.example.backend.patient.domain.Patient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seeds the development patient fixtures at startup, in a deployment with the development fixtures
 * on ({@code app.dev-fixtures.enabled}, {@code APP_DEV_FIXTURES_ENABLED}) and in no other.
 *
 * <p>The patient module binds the flag itself rather than reading the directory's
 * {@code DevFixtureProperties}, so it depends on nothing in {@code scim} and the directory's
 * seeding knows nothing of patients. Never registered where the flag is off, which it is in every
 * deployed environment, so no dummy patient reaches one.
 *
 * <p>Each run writes one INFO record: how many fixtures it inserted and how many were already
 * there, and nothing about any patient.
 */
@Configuration
@ConditionalOnBooleanProperty("app.dev-fixtures.enabled")
public class PatientSeedConfig {

    /** How many fixtures a run inserted. */
    static final String INSERTED_COUNT = "patient.seed.inserted_count";

    /** How many fixtures a run found already stored under their ids. */
    static final String PRESENT_COUNT = "patient.seed.present_count";

    private static final Logger log = LoggerFactory.getLogger(PatientSeedConfig.class);

    @Bean
    ApplicationRunner seedDevPatients(PatientSeedService seeding) {
        return arguments -> seed(seeding, DevPatientFixtures.ALL);
    }

    /** Seeds {@code fixtures}, then records the counts. */
    static void seed(PatientSeedService seeding, List<Patient> fixtures) {
        SeedCounts counts = seeding.seedDevFixtures(fixtures);
        LogEvent.success(log, Operation.PATIENT_SEED, Category.DATABASE, Type.CREATION)
                .addKeyValue(INSERTED_COUNT, counts.inserted())
                .addKeyValue(PRESENT_COUNT, counts.present())
                .log();
    }
}

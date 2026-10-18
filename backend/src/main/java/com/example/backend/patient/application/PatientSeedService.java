package com.example.backend.patient.application;

import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientStore;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the development patient fixtures ({@link DevPatientFixtures}). Owned by the patient module
 * rather than by the directory's seeding, so the directory never depends on it.
 *
 * <p>Called only where the development fixtures are on ({@code APP_DEV_FIXTURES_ENABLED}), so no
 * dummy patient reaches a deployed environment. Idempotent by the fixtures' fixed ids: a fixture
 * already stored under its id is counted and left exactly as it is — never overwritten — and only
 * a missing one is inserted. Like the directory's seeding it looks first rather than inserting and
 * catching the unique violation, which would leave the transaction unusable.
 */
@Service
public class PatientSeedService {

    private final PatientStore patients;

    public PatientSeedService(PatientStore patients) {
        this.patients = patients;
    }

    /**
     * Inserts each fixture not yet stored under its id, in one transaction.
     *
     * @return how many were inserted and how many were already present
     */
    @Transactional
    public SeedCounts seedDevFixtures(List<Patient> fixtures) {
        int inserted = 0;
        for (Patient fixture : fixtures) {
            if (!patients.existsById(fixture.id())) {
                patients.insert(fixture);
                inserted++;
            }
        }
        return new SeedCounts(inserted, fixtures.size() - inserted);
    }

    /**
     * What one seeding did.
     *
     * @param inserted the fixtures inserted
     * @param present  the fixtures already stored under their ids, and left alone
     */
    public record SeedCounts(int inserted, int present) {
    }
}

package com.example.backend.patient.infrastructure.persistence;

import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientName;
import com.example.backend.patient.domain.PatientStore;
import com.example.backend.patient.infrastructure.persistence.entity.PatientEntity;
import jakarta.persistence.EntityManager;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Adapts the domain's {@link PatientStore} port onto the {@code patients} table, translating
 * between the domain type and the persistence entity.
 *
 * <p>The given names are one column, joined by single spaces, and split on them when read back.
 */
@Repository
class PatientPersistenceAdapter implements PatientStore {

    private static final String GIVEN_SEPARATOR = " ";

    private final PatientJpaRepository patients;

    private final EntityManager entities;

    PatientPersistenceAdapter(PatientJpaRepository patients, EntityManager entities) {
        this.patients = patients;
        this.entities = entities;
    }

    @Override
    public Optional<Patient> findById(UUID id) {
        return patients.findById(id).map(PatientPersistenceAdapter::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return patients.existsById(id);
    }

    /**
     * Persisted rather than saved: {@code save} on an assigned id merges, which reads the row
     * first and would turn an existing one into an update the app login may not make.
     */
    @Override
    public void insert(Patient patient) {
        PatientName name = patient.name();
        entities.persist(new PatientEntity(
                patient.id(),
                patient.nric(),
                name.text(),
                name.family(),
                name.given().isEmpty() ? null : String.join(GIVEN_SEPARATOR, name.given()),
                patient.gender().code(),
                patient.birthDate()));
        entities.flush();
    }

    private static Patient toDomain(PatientEntity entity) {
        List<String> given = entity.getNameGiven() == null
                ? List.of()
                : Arrays.asList(entity.getNameGiven().split(GIVEN_SEPARATOR));
        return new Patient(
                entity.getId(),
                entity.getNric(),
                new PatientName(entity.getNameText(), entity.getNameFamily(), given),
                AdministrativeGender.fromCode(entity.getGender()).orElseThrow(),
                entity.getBirthDate());
    }
}

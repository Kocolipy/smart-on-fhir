package com.example.backend.patient.infrastructure.persistence;

import com.example.backend.patient.infrastructure.persistence.entity.PatientEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to {@link PatientEntity}. Infrastructure detail; the application layer talks
 * to the domain port instead.
 */
interface PatientJpaRepository extends JpaRepository<PatientEntity, String> {
}

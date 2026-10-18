package com.example.backend.patient.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA representation of a row of {@code patients}. Kept separate from the domain type so that
 * mapping concerns stay in infrastructure. Every column is insertable and none updatable: nothing
 * in the service changes a stored patient, and the app login holds no UPDATE on the table.
 */
@Entity
@Table(name = "patients")
public class PatientEntity {

    @Id
    @Column(updatable = false)
    private UUID id;

    @Column(updatable = false)
    private String nric;

    @Column(name = "name_text", updatable = false)
    private String nameText;

    @Column(name = "name_family", updatable = false)
    private String nameFamily;

    /** The given names in order, one space between each; {@code null} when there are none. */
    @Column(name = "name_given", updatable = false)
    private String nameGiven;

    @Column(updatable = false)
    private String gender;

    @Column(name = "birth_date", updatable = false)
    private LocalDate birthDate;

    protected PatientEntity() {
    }

    public PatientEntity(
            UUID id,
            String nric,
            String nameText,
            String nameFamily,
            String nameGiven,
            String gender,
            LocalDate birthDate) {
        this.id = id;
        this.nric = nric;
        this.nameText = nameText;
        this.nameFamily = nameFamily;
        this.nameGiven = nameGiven;
        this.gender = gender;
        this.birthDate = birthDate;
    }

    public UUID getId() {
        return id;
    }

    public String getNric() {
        return nric;
    }

    public String getNameText() {
        return nameText;
    }

    public String getNameFamily() {
        return nameFamily;
    }

    public String getNameGiven() {
        return nameGiven;
    }

    public String getGender() {
        return gender;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }
}

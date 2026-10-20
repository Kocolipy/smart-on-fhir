-- A Patient is identified by its EHR's FHIR id rather than a UUID the service
-- assigns (ADR 0014, /docs/domain/patient.md), so the patient an EHR launch
-- names can be read by that id directly. The id column takes FHIR's id grammar,
-- `[A-Za-z0-9\-.]{1,64}`: at most 64 ASCII characters, so VARCHAR(64). The
-- grammar itself is checked in the service (PatientId), where a refusal is the
-- read's 400. V2 is left as it is, so its checksum holds on every database that
-- already ran it; the change goes here instead.
--
-- A column in a primary key cannot change type, so the key is dropped and added
-- back. Converting a UNIQUEIDENTIFIER to VARCHAR keeps each existing id as its
-- upper-case text, which is a valid FHIR id. No other table references
-- `patients.id`, and V2's grant on the table is unaffected.

ALTER TABLE patients DROP CONSTRAINT pk_patients;

ALTER TABLE patients ALTER COLUMN id VARCHAR(64) NOT NULL;

ALTER TABLE patients ADD CONSTRAINT pk_patients PRIMARY KEY (id);

-- The four development fixtures take the ids of Epic's public sandbox test
-- patients (fhir.epic.com, "Sandbox Test Data"), keeping their own names, NRICs,
-- genders and birth dates (DevPatientFixtures). A database seeded before this
-- migration holds them under their old UUIDs; rewriting those rows lets the
-- seeder, which inserts a fixture only when nothing is stored under its id, find
-- each one present instead of inserting it again and colliding on its NRIC.
-- Each old id is matched as the GUID it was, converted as the column was, so the
-- comparison does not depend on the converted text's letter case. Deployed
-- environments run without the fixtures, so these rows match nothing there.

UPDATE patients SET id = 'eq081-VQEgP8drUUqCWzHfw3'
    WHERE id = CONVERT(VARCHAR(64), CONVERT(UNIQUEIDENTIFIER, '00000000-0000-4000-8000-00000000c001'));

UPDATE patients SET id = 'erXuFYUfucBZaryVksYEcMg3'
    WHERE id = CONVERT(VARCHAR(64), CONVERT(UNIQUEIDENTIFIER, '00000000-0000-4000-8000-00000000c002'));

UPDATE patients SET id = 'e0w0LEDCYtfckT6N.CkJKCw3'
    WHERE id = CONVERT(VARCHAR(64), CONVERT(UNIQUEIDENTIFIER, '00000000-0000-4000-8000-00000000c003'));

UPDATE patients SET id = 'egqBHVfQlt4Bw3XGXoxVxHg3'
    WHERE id = CONVERT(VARCHAR(64), CONVERT(UNIQUEIDENTIFIER, '00000000-0000-4000-8000-00000000c004'));

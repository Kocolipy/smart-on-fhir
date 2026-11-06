-- Patients are no longer held here: the backend reads each from the in-house patient
-- source, by its EHR's FHIR id, on every read (ADR 0014). The table only ever held the
-- development fixtures, which now live in the source's local mock
-- (backend/patient-source-mock), so there is nothing to carry over: no deployed
-- database holds a real patient.
--
-- Dropping the table drops the app login's SELECT and INSERT on it, and its primary
-- key, its unique NRIC and its gender check with it, so no REVOKE is needed.
DROP TABLE patients;

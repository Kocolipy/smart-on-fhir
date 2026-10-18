---
type: Domain Rules
title: Patients
description: The FHIR R5 Patient the service serves — its element rules, the NRIC system, the authoritative name text, derived age, the default patient, and seeding only with the development fixtures.
tags: [patient, fhir]
---

# Patients

**Patient** — the person care is about, as this service holds them. The backend's
`patient` module stores each in its own `patients` table and serves it, read-only,
as a FHIR **R5** `Patient` resource at `GET /api/patient/{id}`, holding
`patient:read` (a baseline Permission). The resource is rendered from the table's
columns, not stored as JSON. Why R5, and why the service's own tables rather than
a FHIR server, is ADR 0014; the wire contract is `backend/docs/openapi.yaml`.

## The resource

Base R5 `Patient`, with no `meta` and so no `meta.profile`, and no element beyond
these:

```json
{
  "resourceType": "Patient",
  "id": "<uuid>",
  "identifier": [
    {
      "system": "https://fhir.synapxe.sg/NamingSystem/nric",
      "value": "S1234567A"
    }
  ],
  "name": [
    {
      "use": "official",
      "text": "Tan Wei Ming",
      "family": "Tan",
      "given": ["Wei", "Ming"]
    }
  ],
  "gender": "male",
  "birthDate": "1985-03-12"
}
```

- **`id`** — the Patient's UUID, its stable id in the table and its FHIR resource id.
- **`identifier`** — exactly one, the NRIC, under Synapxe's NRIC NamingSystem,
  `https://fhir.synapxe.sg/NamingSystem/nric`. Unique across Patients. Its shape is
  not checked.
- **`name`** — exactly one `HumanName`, `use: official`.
  - **`text` is required and authoritative**: it is the name as the person writes
    it, and it is what the SPA shows.
  - `family` and `given` are optional, and are left out rather than sent empty when
    the name is not split. Many Singapore names do not split cleanly into a family
    and given names, so nothing may assume the split exists or rebuild the name
    from it. The table holds the given names in one column, in order, a space
    between each.
- **`gender`** — required; FHIR's administrative gender, one of `male`, `female`,
  `other` or `unknown`. The table's `CHECK` holds the column to those four codes.
- **`birthDate`** — required, to the day.

An id no Patient is held under answers `404` with an `OperationOutcome` holding one
issue — `severity: error`, `code: not-found` — whose `diagnostics` are generic and
say nothing about the id. Both answers are `application/fhir+json;
fhirVersion=5.0`. The SPA reads the `404` as "No patient in context", an ordinary
answer rather than a failure.

## Age is derived

Age is **never stored**, in the table or on the wire. Wherever it is shown it is
derived from `birthDate` as **completed years**: a year counts from its birthday
on, and a 29 February birthday counts from 1 March in a common year. The SPA
counts it on today's date **in Asia/Singapore**, read from an injectable clock, so
a test states the day it runs on and the age never depends on the viewer's zone.

## The default patient and its seeding

**Default patient** — the one Patient Home's **Patient card** shows until a SMART
launch supplies the patient in context: `Tan Wei Ming` (a Chinese name, family name
first), under the fixed id `00000000-0000-4000-8000-00000000c001`. The backend's
`DevPatientFixtures` owns that id; the SPA's `DEFAULT_PATIENT_ID` is a copy.

Patients are **development fixtures** only. The patient module's own seeder runs at
startup **only when `APP_DEV_FIXTURES_ENABLED` is set**, so no dummy Patient
reaches a deployed environment, where the flag is off and the default patient is
therefore absent. The module binds the flag itself; the directory's seeding knows
nothing of Patients, and the patient module nothing of the directory.

- **Idempotent.** Every fixture has a fixed id. A run inserts each fixture not yet
  stored under its id and leaves an existing one exactly as it is — it never
  overwrites — so running it twice leaves one row per fixture.
- **Logged without values.** Each run writes one INFO record
  (`app.event.action` `patient.seed`) with how many fixtures it inserted and how
  many were already present, and no field of any Patient.
- **Dummy NRICs.** Every fixture's NRIC is well-formed but carries a
  **deliberately wrong check letter**, so it cannot be a real person's: the default
  patient's `S1234567A` would be `S1234567D` if it were valid.

The app login holds `SELECT` and `INSERT` on `patients` — the insert for the seeder,
which runs on the app pool because a deployed service never holds the owner login —
and no `UPDATE` or `DELETE`: nothing in the service changes or removes a Patient.

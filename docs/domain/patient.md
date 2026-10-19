---
type: Domain Rules
title: Patients
description: The FHIR R5 Patient the service serves — its element rules, the NRIC system, the authoritative name text, derived age, the default patient, the four development fixtures and their seeding, and how R5 conformance is proven.
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
  many were already present, and no field of any Patient. It is built with
  `LogEvent`, as every record is, which is why the module may reach
  `observability` as well as `auth` (ADR 0014).
- **Dummy NRICs.** Every fixture's NRIC has the NRIC shape — a prefix letter,
  seven digits and a check letter — but carries a **deliberately wrong check
  letter**, so it cannot be a real person's: the default patient's `S1234567A`
  would be `S1234567D` if it were valid. No two fixtures share an NRIC. The check
  letter is the seven digits weighted 2, 7, 6, 5, 4, 3, 2, plus 4 for a `T` or `G`
  prefix, taken mod 11 to index `J Z I H G F E D C B A` (`S`/`T`) or
  `X W U T R Q P N M L K` (`F`/`G`). That algorithm lives in test code only
  (`DevPatientFixturesTests`), which asserts that every fixture's NRIC **fails**
  it, so a real-looking value cannot slip in later; the service itself never
  checks an NRIC.

### The fixtures

Four dummy Patients, `DevPatientFixtures.ALL`, seeded in this order. Between them
they cover Singapore's name styles, every way a name splits — family and given,
given only, neither — and all four gender codes, so each rendering path the
resource has is exercised by real data:

| Id (`00000000-0000-4000-8000-…`) | `name.text` | `family` | `given` | Gender | NRIC (valid letter) | Purpose |
| --- | --- | --- | --- | --- | --- | --- |
| `…c001` | `Tan Wei Ming` | `Tan` | `Wei`, `Ming` | `male` | `S1234567A` (`D`) | The default patient: a Chinese name, family name first |
| `…c002` | `Siti Nurhaliza binte Ahmad` | — | — | `female` | `S9212345A` (`F`) | A Malay name: `binte Ahmad` is a patronymic, not a family name, so the name is not split at all |
| `…c003` | `Kumaravel` | — | `Kumaravel` | `unknown` | `T0312345C` (`B`) | An Indian single name: one given name and no family name |
| `…c004` | `Marcus Anthony de Souza` | `de Souza` | `Marcus`, `Anthony` | `other` | `S7812345B` (`A`) | A Eurasian name: given names first, a family name of two words |

The given names are held as one string and split on spaces into `given[]` — a
known shortcut, fine for dummy data, and the reason a fixture's given name never
contains a space of its own. A part a fixture does not hold is left out of the
JSON entirely, never sent as `null` or empty. The seeder's INFO record therefore
reports `inserted_count` 4 on a fresh database and `present_count` 4 on every
start after.

## R5 conformance is proven, not assumed

The service writes its FHIR JSON by hand (ADR 0014), so a test holds it against
the specification: `PatientR5ConformanceTests` seeds the four fixtures, reads each
through `GET /api/patient/{id}`, and validates the response body exactly as
written with **HAPI FHIR's R5 validator** against base R5 `Patient`. It does the
same for the `404` body against base R5 `OperationOutcome`. Any validation message
of severity error or fatal fails the test; a warning, such as a best-practice
rule, does not. The same class checks that the
validator rejects a Patient that is not base R5, so a pass means something.

HAPI is a **test-scope dependency only**. The production code carries no FHIR
library, and the backend build's Enforcer (`hapi-test-scope-only` in
`backend/pom.xml`) fails the build if any `ca.uhn.hapi.fhir` artifact, direct or
transitive, resolves in a scope that reaches the packaged JAR.

The app login holds `SELECT` and `INSERT` on `patients` — the insert for the seeder,
which runs on the app pool because a deployed service never holds the owner login —
and no `UPDATE` or `DELETE`: nothing in the service changes or removes a Patient.

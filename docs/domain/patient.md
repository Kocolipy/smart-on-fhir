---
type: Domain Rules
title: Patients
description: The FHIR R5 Patient the service serves — its element rules, the NRIC system, the authoritative name text, derived age, the data classification, the error contract, the logging rules, the deliberate absence of read auditing, the Patient card's patient in context, the four development fixtures and their seeding, and how R5 conformance is proven.
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
  "id": "eq081-VQEgP8drUUqCWzHfw3",
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

- **`id`** — the Patient's **EHR's FHIR id**, its stable id in the table and its
  FHIR resource id here, so the patient an EHR launch names is read by that id
  directly. The service assigns no id of its own. Any value in FHIR's id grammar,
  `[A-Za-z0-9\-.]{1,64}` (letters, digits, `-` and `.`, 1 to 64 of them), is
  well-formed; anything else is refused `400` before any read. The grammar lives
  in the domain's `PatientId`, which the read binds its path segment through, and
  the table holds the id as `VARCHAR(64)`.
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

The path follows the repo's convention: an unversioned `/api` base, held in code.

## Data classification

Patient data is **Restricted, Sensitive Normal**: the NRIC, the name and the date
of birth, and the resource or its JSON as a whole. It is shown to a signed-in
user holding `patient:read` and goes nowhere else — not into a log, an error body
or an audit row.

## Errors

**Every answer is `application/fhir+json; fhirVersion=5.0`**, success or error.
Every error body is an `OperationOutcome` with exactly one issue, `severity:
error`, whose `code` is chosen by the status alone and whose `diagnostics` are a
fixed sentence per code:

| Case                               | Status | `code`          | `diagnostics`                       |
| ---------------------------------- | ------ | --------------- | ----------------------------------- |
| Id outside the FHIR id grammar     | `400`  | `invalid`       | The request is not valid.           |
| No session                         | `401`  | `login`         | A signed-in session is required.    |
| Session lacks `patient:read`       | `403`  | `forbidden`     | The request is not permitted.       |
| Well-formed id, no patient held    | `404`  | `not-found`     | The resource was not found.         |
| `POST`, `PUT`, `PATCH` or `DELETE` | `405`  | `not-supported` | The request is not supported.       |
| Unexpected failure                 | `5xx`  | `exception`     | The request could not be completed. |

An outcome never contains an exception message, SQL, a stack trace or the
submitted id — markup or a line break in the id included. Malformed covers an id
over 64 characters, markup, a line break, an empty segment (`a//b`) and an
encoded slash (`a%2F%2Fb`); the last three never reach the dispatcher, since the
request firewall refuses them, and are answered with the same outcome. An
encoded slash reaches the firewall at all because Tomcat passes `%2F` through,
still encoded, rather than refusing it with its own HTML error page
(`web.EncodedSlashPassthrough`): passed through, it is never decoded into a path
separator, and the firewall refuses it on every path, so path security is
unchanged and only the answer becomes the outcome. A well-formed id that names no patient —
`not-a-uuid` is one — is the `404`. The SPA reads the `404` as "No patient in
context", an ordinary answer rather than a failure; every other error is the
Patient card's failed state.

Every layer answers in this one shape, and the backend's `web` module owns it
(`FhirOperationOutcome`), so the `patient` module keeps its two edges (ADR 0014):

- **The app-wide exception handler** answers a handler's failure — the malformed
  id, the unknown one (the controller raises `NoSuchPatientException`, carrying no
  message and no id) and a fault — and maps it to an `error.category`: `data`
  for the `400` and the `404`, and `application` (or the database mapping, for a
  database failure) for a `5xx`, in its one `ERROR` fault record. The `400` and
  the `404` get no record of the handler's own: under `/api/patient` the handler
  writes no `http.request.refusal` record and puts its classification
  (`error.code`, `error.category` `data`, `error.follow_up_action` `false`) on
  the request record instead (`RequestRefusal`), so the classification exists
  and the request still has one record. Everywhere else a refusal keeps its own
  `WARN` refusal record.
- **The security chain** refuses a session without `patient:read` — by the
  handler's `@PreAuthorize`, or by its backstop, which matches a read (`GET`,
  `HEAD`) only. The `403` is logged at `WARN` through the
  authorization-failure path (`access.denied`, reason `insufficient-permissions`,
  `error.category` `cert/auth`) and audited as an `ACCESS_DENIED`, as every
  authorization refusal is.
- **The dispatcher** answers a write method `405` before any handler runs. The
  chain lets one through for a session holding baseline access so that the answer
  says what is true — the resource takes no write — rather than `403`.

Every layer asks `web.ErrorNamespaces` which document the request's namespace
answers with, and `/api/patient` is the FHIR namespace: the chain's entry point
and access-denied handler write the outcome themselves, and the firewall's and
the dispatcher's refusals, which are `sendError`s a servlet container would
answer with its error page — not FHIR, and quoting the request's path, so the id
— are written as the outcome by `ErrorDocumentFilter`, the one `sendError`
adapter every namespace shares. A new FHIR resource served beneath
`/api/patient` is answered as FHIR by every layer with no further change.

## Logging

- **A patient is identified in a log only by its id.** The NRIC, the name and
  the `birthDate` are never logged, and neither is the resource or its JSON.
- **`Patient.toString()` prints the id alone.** A Java record's generated
  `toString` prints every field, so a Patient printed into a message or an
  exception would carry all three.
- **A read adds no record of its own.** A success, a `400` and a `404` each
  have the request record alone, which names the route template, never the raw
  `{id}`; the `400`'s and the `404`'s carries their `data` classification. A
  `403` adds the authorization refusal's `WARN` record and a `5xx` the fault's
  `ERROR` record, neither naming the id.
- **The NRIC's name is masked at the logging boundary**, as defence in depth: the
  Semgrep rule `be-log-sensitive-value` refuses any value named `nric` (ADR 0003,
  "Redaction gates").

`PatientErrorContractIntegrationTests` captures the stream of a successful read, a
`400` and a `404` and asserts that none of the patient's NRIC, name or date of
birth, and none of the ids asked for, appear in it.

## Patient reads are not audited

A read of a patient writes **no audit row**. This is deliberate for now: every
Patient is a development fixture with a dummy NRIC, and the audit trail records
security-relevant changes and refusals, not reads. A refused read is still audited,
as `ACCESS_DENIED`.

**Follow-up, before any real patient data arrives:** decide whether — and how —
patient reads are audited (who read which patient, when), since access to real
Restricted data must be accountable.

## Age is derived

Age is **never stored**, in the table or on the wire. Wherever it is shown it is
derived from `birthDate` as **completed years**: a year counts from its birthday
on, and a 29 February birthday counts from 1 March in a common year. The SPA
counts it on today's date **in Asia/Singapore**, read from an injectable clock, so
a test states the day it runs on and the age never depends on the viewer's zone.

## The patient in context and the seeding

Home's **Patient card** shows the patient in context, and only that patient: the
patient of the tab's **EHR context**, which the Launch context an EHR launch
passes to the SPA seeds ([Epic Login](./epic-login.md)). With none in context it
shows "No patient in context" and reads nothing, and a `404` for the id in
context shows the same. The SPA holds no patient id beyond the EHR context, and
the read is not bound to it: `patient:read` reads any id.

Patients are **development fixtures** only. The patient module's own seeder runs at
startup **only when `APP_DEV_FIXTURES_ENABLED` is set**, so no dummy Patient
reaches a deployed environment, where the flag is off. Each fixture's fixed id is
owned by the backend's `DevPatientFixtures`. The module binds the flag itself; the directory's seeding knows
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
  letter**, so it cannot be a real person's: Tan Wei Ming's `S1234567A`
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
resource has is exercised by real data.

A Patient's id is its EHR's FHIR id, so each fixture borrows the id of one of
Epic's public sandbox test patients (fhir.epic.com, "Sandbox Test Data"), named
in the table. Only the id is borrowed: the name, NRIC, gender and birth date are
the fixture's own.

| Id (Epic sandbox patient it borrows from)    | `name.text`                  | `family`   | `given`             | Gender    | NRIC (valid letter) | Purpose                                                                                         |
| -------------------------------------------- | ---------------------------- | ---------- | ------------------- | --------- | ------------------- | ----------------------------------------------------------------------------------------------- |
| `eq081-VQEgP8drUUqCWzHfw3` (Derrick Lin)     | `Tan Wei Ming`               | `Tan`      | `Wei`, `Ming`       | `male`    | `S1234567A` (`D`)   | A Chinese name, family name first                                                               |
| `erXuFYUfucBZaryVksYEcMg3` (Camila Lopez)    | `Siti Nurhaliza binte Ahmad` | —          | —                   | `female`  | `S9212345A` (`F`)   | A Malay name: `binte Ahmad` is a patronymic, not a family name, so the name is not split at all |
| `e0w0LEDCYtfckT6N.CkJKCw3` (Warren McGinnis) | `Kumaravel`                  | —          | `Kumaravel`         | `unknown` | `T0312345C` (`B`)   | An Indian single name: one given name and no family name                                        |
| `egqBHVfQlt4Bw3XGXoxVxHg3` (Elijah Davis)    | `Marcus Anthony de Souza`    | `de Souza` | `Marcus`, `Anthony` | `other`   | `S7812345B` (`A`)   | A Eurasian name: given names first, a family name of two words                                  |

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
same for the `404` and `400` bodies against base R5 `OperationOutcome`, answered
by the app-wide exception handler as in the running service. Any validation message
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

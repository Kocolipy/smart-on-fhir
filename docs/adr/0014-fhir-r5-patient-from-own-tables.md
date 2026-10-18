# 14. Patients are served as FHIR R5, from the service's own tables

Date: 2026-10-18

## Status

Accepted, and implemented (#127): `GET /api/patient/{id}` serves a base R5
`Patient` rendered from the `patients` table, and Home's Patient card reads it.

## Context

Home shows a Patient (`/docs/domain/patient.md`). Later the SMART patient context of
an EHR launch will name which one; until then it is the default patient, a
development fixture. Two choices had to be made before the first Patient was
served, and both are hard to reverse once a client depends on the wire:

1. **Which FHIR version.** The EHRs this application launches from are R4: Epic's
   FHIR API is R4, and the local SMART launcher that stands in for Epic (ADR 0012,
   "Local Epic launcher") serves `/v/r4/fhir`.
2. **Where the data comes from.** The service could proxy a FHIR server — Epic's,
   or a HAPI server of its own — or hold the Patients itself and render FHIR from
   its own rows.

## Decision

### R5 on the wire

The service serves FHIR **R5**, as base `Patient` with no `meta.profile`, and the
SPA types it with `@types/fhir`'s `fhir5`.

- R5 is the current normative release, and the version the in-house FHIR work
  this application is meant to join is written against. Serving R4 now would mean
  a second migration of every client later.
- For the elements served today — `identifier`, `name` (`use`, `text`, `family`,
  `given`), `gender`, `birthDate` — R4 and R5 are identical: same names,
  cardinalities, types and value sets. So the R4 sources cost nothing: a future
  EHR-backed adapter maps an R4 `Patient` to this one as a **straight copy** of
  those elements. An element that does differ between the versions is that
  adapter's mapping to write, once, behind the port below, and no client sees R4.
- The version is stated on every answer, `application/fhir+json; fhirVersion=5.0`,
  so a client can tell, and a later version change is visible on the wire.

### The service's own tables, behind a port

The `patient` module stores Patients in its own `patients` table, one column per
element, and renders the FHIR JSON from those columns with plain records and
Jackson. No FHIR server is proxied, and no FHIR library (HAPI or other) is on the
runtime classpath.

- **The port is the seam.** The application layer reads through the domain's
  `PatientStore`. The table adapter is today's implementation; an adapter that
  reads the launching EHR's FHIR API, with the SMART launch's patient context and
  the Epic tokens ADR 0012 keeps server-side, can replace or sit beside it without
  the controller, the wire or the SPA changing.
- **Proxying would couple every request to an external server** this application
  does not run: its availability, latency and auth, and its version (R4, above),
  on a page every signed-in User opens. A local FHIR server would be a second
  datastore and a heavyweight runtime dependency for five elements.
- **The schema keeps the rules.** Columns let SQL Server enforce what a Patient
  must be — a unique NRIC, a required name text, gender and birth date, a gender
  among the four codes — which a stored JSON document would not. Age is never a
  column; it is derived.
- **Rendering by hand is small and exact.** The records emit exactly the elements
  the domain doc lists and nothing else, which a general FHIR model would make
  easy to drift from.

### The module's edges: `auth` and `observability`

The `patient` module is a leaf, like `counter`, and its ArchUnit entry
(`backend/src/test/java/arch/ArchitectureTest.java`) lets it reach `auth` and
`observability`. Issue #127 asked for `auth` alone. The deviation is deliberate:

- The same issue requires the seeder to log one INFO record with its counts, and
  the backend builds every log record through `observability.LogEvent`
  (`be-log-record-outside-log-event`), so the seeder has to import it.
- No compliant route avoids the edge. `auth` exposes no logging, and adding a
  pass-through there would make `auth` a proxy for a module every other module
  already reaches directly. The scheduling module's entry calls `observability`
  "the logging base every module depends on"; it depends on nothing back, so the
  edge adds no cycle and no state.
- The edge carries only that one record. Nothing in `patient` reaches any other
  module that holds state: not `scim`, so the directory's seeding and the patient
  fixtures' stay apart.

## Consequences

- Patient data in a deployment exists only where something writes it. Today only
  the development fixtures' seeder does, so a deployed environment (fixtures off)
  has no Patients and Home shows "No patient in context".
- An EHR-backed `PatientStore` adapter is the next step for real data. It owns the
  R4 → R5 mapping and decides how a Patient's id relates to the EHR's.
- If served elements grow to ones that differ between R4 and R5, the mapping in
  that adapter grows with them; the wire stays R5.
- Adopting a FHIR library later, for validation or for many more resource types,
  is open: it would replace the hand-written records inside the controller
  package, not the port or the wire.

---
type: Decision Record
title: "ADR 0014: FHIR R5 Patient, read from the in-house patient source"
description: Patients are served as base FHIR R5, rendered by hand with no FHIR library on the runtime classpath, and read on every request, uncached, from the in-house patient source's FHIR R4 Patient/{id} through an HTTP adapter behind PatientStore; the adapter sends no credential, a WireMock mock stands in for the source locally and in E2E, and the tests read the mock's own stub files.
tags: [fhir, patient, r5, integration]
---

# 14. Patients are served as FHIR R5, read from the in-house patient source

Date: 2026-10-18

## Status

Accepted. `GET /api/patient/{id}` serves a base R5 `Patient` the backend reads
from the in-house patient source, and Home's Patient card reads it.

## Context

Home shows a Patient (`/docs/domain/patient.md`): the one the Launch context of
an EHR launch names, by its EHR's FHIR id (ADR 0012, D8). Two choices are hard to
reverse once a client depends on the wire:

1. **Which FHIR version.** The EHRs this application launches from are R4: Epic's
   FHIR API is R4, and the local Epic stand-in, Medplum
   (`backend/docs/configuration/epic-login.md`, "Local Epic stand-in"), serves
   `/fhir/R4`.
2. **Where the data comes from.** Real patients come from an in-house system.
   The adapter contract is FHIR R4 `GET {base}/Patient/{id}`, with no request
   credential and with source failures mapped to `502`. The patient's EHR FHIR
   id is the id an EHR launch and a FHIRcast `Patient-open` already name. Home's
   wire contract remains base R5 with `identifier`, `name`, `gender` and
   `birthDate`, the `patient:read` gate and the `OperationOutcome` table,
   independent of changes behind the adapter.

## Decision

### R5 on the wire

The service serves FHIR **R5**, as base `Patient` with no `meta.profile`, and the
SPA types it with `@types/fhir`'s `fhir5`.

- R5 is the current normative release, and the version the in-house FHIR work
  this application is meant to join is written against. Serving R4 now would mean
  a second migration of every client later.
- For the elements served — `identifier`, `name` (`use`, `text`, `family`,
  `given`), `gender`, `birthDate` — R4 and R5 are identical: same names,
  cardinalities, types and value sets. So the R4 source costs nothing: its
  adapter maps an R4 `Patient` to this one as a **straight copy** of those
  elements. An element that does differ between the versions is that adapter's
  mapping to write, once, behind the domain's `PatientStore`, and no client sees
  R4.
- The version is stated on every answer, `application/fhir+json; fhirVersion=5.0`,
  so a client can tell, and a later version change is visible on the wire.
- **Rendering by hand is small and exact.** The controller renders the FHIR
  JSON with plain records and Jackson, emitting exactly the elements the domain
  doc lists and nothing else, which a general FHIR model would make easy to
  drift from. No FHIR library (HAPI or other) is on the runtime classpath.

### The in-house patient source, behind the port

`PatientStore` has one operation, `findById`, and one adapter,
`PatientSourceAdapter` (`patient.infrastructure.http`), which reads
`GET {base}/Patient/{id}` from the source. The service holds no Patient of its
own: no table, no grant on one, no seeded fixtures.

- **Source contract**: the source speaks **FHIR R4** and needs **no request
authorisation**, so the call presents no credential. The id is the EHR's FHIR
id, sent unchanged: there is no mapping between ids.
- **A straight copy.** The adapter binds the R4 JSON to plain records with
  Jackson and copies the identifier under the NRIC NamingSystem, the name whose
  `use` is `official`, `gender` and `birthDate` into the domain's Patient; every
  other element is never bound, and the runtime classpath stays
  FHIR-library-free.
- **Only a `404` is no patient.** It stays the `404` the SPA reads as "No
  patient in context". Every other answer — another status, a redirect, no
  answer within the timeouts, a `200` that is not a Patient the service can hold
  (another patient's id, no NRIC, no official name or one with no text, no
  gender or an unknown code, a birth date missing or not to the day) — is a
  failure of the source, the `502` `exception` outcome, distinct from the `500`
  of a fault on this side. A silent "no patient" for a failing source would hide
  the failure from the clinician and from operations.
- **Nothing patient-identifying in a failure.** The adapter's exception carries a
  fixed message, naming at most a status or an element, and its cause redacted
  to its type (`observability.RedactedFaultException`): an HTTP client's message
  quotes the URL, and so the id, and a parser's quotes the body. An error
  answer's body is never read. Its one `ERROR` record names the failure or the
  broken rule, under the `error.category` ADR 0012 gives an outbound call's
  failure (`network`, `server`, `data`), decided by the classifier the Epic
  calls use (ADR 0012, D23).
- **One call per read, nothing cached.** No Patient is kept in Redis or in
  memory: every read calls the source, so what the card shows is what the source
  holds now, and Restricted data rests nowhere in this service.
- **The outbound rules of ADR 0012's D25, scaled to one call.** The client is
  built from Spring Boot's `RestClient.Builder`, so a call is observed as every
  client's is (`http.client.requests`, under the URI template, never the id), on
  the JDK client, which follows no redirect, with 2 seconds to connect and 5 to
  answer, and no retry. Nothing here logs.
- **Required configuration.** `APP_PATIENT_SOURCE_BASE_URL` has no default
  (`application.yaml` names it with no fallback): a start without it, or with
  anything but an absolute http or https URL, fails.

### A mock for local runs and E2E, sharing its stubs with the tests

The source is mocked by WireMock, nested under the backend
(`backend/patient-source-mock`), serving FHIR R4 `Patient/{id}` for the
development fixtures — ids borrowed from Epic's public sandbox test patients,
with dummy values — and a `404` for any other well-formed id. Compose runs it
beside SQL Server and Redis with no profile, so `make infra-up` and
`make integration-test` start it; nothing under `infra/` builds or deploys it.

The backend's patient tests run WireMock **in process over the same stub
files**, so the mock and the tests cannot drift, and the fixture rules (every
NRIC carries a wrong check letter) are checked over the stubs themselves.
WireMock is a **test-scope** dependency only: the Enforcer's
`wiremock-test-scope-only` fails the build if it resolves in a scope that
reaches the packaged JAR, as `hapi-test-scope-only` does for HAPI. The Medplum
seed names the same ids, so an EHR launch and a FHIRcast event name a
patient the mock holds.

### The module's edges

The `patient` module is a leaf, and its one ArchUnit edge
(`backend/src/test/java/arch/ArchitectureTest.java`) is `observability`:

- The adapter redacts a failure's cause with `RedactedFaultException`, the
  logging base's one tool for attaching a fault with no value in it, and raises
  its failure as an `UpstreamFaultException`, the logging base's type for a
  called service failing this one, which the app-wide handler in `web` answers
  `502` under the fault's own category and reason. Those are the edge's only uses:
  the module writes no log record.
- No edge to `auth`. The in-house source is not the launching EHR's FHIR API,
  and the call presents none of the Epic tokens ADR 0012 holds; its handler
  checks `patient:read` by the authority's name. An authorised call (below)
  declares the edge it needs when it is written.
- Outbound HTTP is allowed in `patient.infrastructure.http` beside
  `auth.epic` (`one_outbound_http_client`), and `http` is an infrastructure
  adapter the onion rule knows.
- **The error `OperationOutcome` is not the module's.** Every error under
  `/api/patient` is answered by a layer outside it — the app-wide exception
  handler, the security chain, the dispatcher — so the shape lives in `web`
  (`FhirOperationOutcome`), beside `ApiError`. `/api/patient` is one of the
  error namespaces in `web.ErrorNamespaces`, which every refusing layer asks
  for its document, and `ErrorDocumentFilter` is the one `sendError` adapter
  for every namespace. `patient` renders only the `Patient` and reaches `web`
  for nothing.

## Consequences

- A Patient's id is the EHR's FHIR id (FHIR's id grammar,
  `[A-Za-z0-9\-.]{1,64}`): the service assigns no id of its own, so the patient
  a SMART launch names is read by the id the EHR gave it.
- **A deployment needs the source's URL.** The stack (`infra/`) does not set
  `APP_PATIENT_SOURCE_BASE_URL`; a deployment must supply it for the service to
  start.
- **The source integration sends no request credential.** It supports only a
  source that accepts that contract. A source requiring authentication needs an
  adapter and external-configuration change before deployment; credentials have
  no fallback in this repository.
- **Home's card depends on the source's availability.** A slow or failing
  source is the card's failed state within the read timeout, never a stale or
  cached patient.
- **The wire is independent of the source.** A different source FHIR version,
  element shape or authentication requirement changes the adapter and its
  configuration, not the R5 response contract.
- Validation needed no runtime library: HAPI FHIR's R5 validator holds the
  served JSON against base R5 in **test scope only**, and the build's Enforcer
  refuses HAPI in any scope that reaches the packaged JAR, so the runtime
  classpath stays FHIR-library-free (`/docs/domain/patient.md`).

## Alternatives considered

- **Hold the Patients in the service's own tables, behind the port.** Rejected:
  a copy of Restricted data at rest, kept fresh by a sync, for a source whose
  contract is unknown; reading through is simpler and keeps no copy.
- **Proxying the launching EHR's FHIR server, or a HAPI server of the service's
  own.** Rejected: it would couple every request to an external server's auth
  and version (R4, above) through the Epic tokens ADR 0012 holds, and a local
  FHIR server would be a second datastore for the Patient projection. The
  in-house source is the system real patients come from.
- **Cache Patients in Redis.** Would spare the source repeated reads of one
  patient, but holds Restricted data in a store the sessions share, and shows a
  stale patient after a change. Out of scope until load says otherwise.
- **A mock with its own fixtures in Java.** Two copies of the fixtures — the
  mock's and the tests' — drift; one set of stub files read by both cannot.

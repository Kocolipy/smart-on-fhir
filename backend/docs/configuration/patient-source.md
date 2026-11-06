---
type: Configuration Reference
title: Patient source
description: APP_PATIENT_SOURCE_BASE_URL — the in-house patient source the backend reads every Patient from, its required base URL, the fixed timeouts, and the local WireMock mock compose runs on 127.0.0.1:8104.
tags: [patient, configuration]
---

# Patient source

| Variable                      | Default | Meaning                                                          |
| ----------------------------- | ------- | ---------------------------------------------------------------- |
| `APP_PATIENT_SOURCE_BASE_URL` | none    | The in-house patient source's FHIR base, an absolute http(s) URL |

The backend reads every Patient from the in-house patient source at
`GET {base}/Patient/{id}`, as FHIR R4, on every read, with no cache and **no
credential** (ADR 0014; `/docs/domain/patient.md`, "The in-house patient
source"). The SPA never reaches it.

- **Required, with no default.** `application.yaml` names
  `app.patient-source.base-url` as `${APP_PATIENT_SOURCE_BASE_URL}` with no
  fallback, so a start without it fails, and so does one with anything but an
  absolute `http` or `https` URL. A trailing slash is ignored, and a path is
  kept: `https://patients.internal/fhir/r4/` reads
  `https://patients.internal/fhir/r4/Patient/{id}`. The test JVMs drop it
  (`pom.xml`); the tests point every context at their own mock.
- **Timeouts, fixed:** 2 seconds to connect and 5 to answer, as Epic's
  defaults are (ADR 0012, D25). A source slower than that is the Patient card's
  failed state, a `502`. No redirect is followed and no call is retried.
- **No deployed value yet.** The stack (`/infra/`) does not set it: the
  in-house system's contract is not known, and the mock below is for local runs
  and E2E only. A deployed service fails at startup until the stack sets it.
- **Authorisation is a follow-up.** Today's call presents nothing to the source;
  it must be authorised before any real patient data arrives
  (`/docs/domain/patient.md`, "Follow-ups before any real patient data
  arrives"). A credential it needs will be external configuration with no
  fallback, like every credential here.

## Local mock

`compose.yaml`'s `patient-source` service is WireMock (`wiremock/wiremock`,
pinned by tag and digest, the same release as the backend's test dependency)
serving `patient-source-mock/mappings` on **`127.0.0.1:8104`**: FHIR R4
`Patient/{id}` for the four development fixtures, and a `404` for any other
well-formed id. It has no profile, so `make infra-up` and
`make integration-test` start it beside SQL Server and Redis, healthy when
WireMock's `/__admin/health` answers. `.env.example` points the backend at it:

```bash
APP_PATIENT_SOURCE_BASE_URL=http://127.0.0.1:8104
```

```bash
curl -s http://127.0.0.1:8104/Patient/eq081-VQEgP8drUUqCWzHfw3   # Tan Wei Ming
```

A change to a stub file changes the backend's patient tests' fixtures too: they
serve the same files with WireMock in process (`PatientSourceStubs`).

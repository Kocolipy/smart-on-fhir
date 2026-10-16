# API contract check

`docs/openapi.yaml` is held against the running service by the backend's own test suite, so a
change that drifts from it fails the baseline gate (`./scripts/verify.sh`) instead of waiting for
a reviewer to notice. The check lives in `src/test/java/com/example/backend/contract/`.

## What is checked

| Direction                 | Check                                                                                                                                                                                                                                                                                                                                                                                        | Where                                                   |
| ------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| implementation → document | Every route a controller maps under `/api` or `/scim/v2` is a documented operation.                                                                                                                                                                                                                                                                                                          | `RouteContractTests`                                    |
| document → implementation | Every documented operation under `/api` or `/scim/v2` is a mapped route.                                                                                                                                                                                                                                                                                                                     | `RouteContractTests`                                    |
| implementation → document | Every response a fixture receives has a status its operation documents — or, for a refusal made before any operation is selected, one `x-namespace-responses` documents — carries every header that response documents, has a documented media type, has no body where none is documented, and validates against the documented schema. A `writeOnly` property in a response is a violation. | `ContractRecorder`, `OpenApiContract`                   |
| document → implementation | Every status the document gives an operation (and every namespace-level status) is produced by at least one fixture.                                                                                                                                                                                                                                                                         | `every_documented_*_status_is_produced_by_some_fixture` |
| document → implementation | Every operation under `/api` and `/actuator` enforces the `security` requirement it declares — public, self-service, or exactly one Permission — and its handler declares the same Permission with method security (ADR 0010).                                                                                                                                                               | `AuthorizationContractTests`                            |

The fixtures are `ScimConformanceFixtureTests` (the SCIM namespace: generic RFC 7643/7644
conformance, both resource types through the same fixtures, with the cases themselves in
`ScimConformanceCases`) and `ApiContractFixtureTests` (the application API and the actuator). Both
go through the real filter chain, SQL Server and Redis.

`OpenApiContractTests` shows the checker reporting each kind of drift on a planted response, and
fails if the document starts using a schema keyword the validator does not implement.

## Running it

```bash
./mvnw -Dtest='com.example.backend.contract.*Tests' test
```

Run a fixture class whole: the coverage test in each runs last and needs the fixtures to have run
in the same JVM. It says so when they have not.

## When it fails

- **"departs from docs/openapi.yaml"** — a response the document does not describe. Decide which is
  wrong. If the behaviour is right, document it; if not, fix the code. Never widen a schema just to
  make a response fit.
- **"no fixture produced"** — the document promises a status nothing exercises. Add the fixture, or
  remove the status if the service cannot produce it. A status that genuinely cannot be produced in
  a test context (a dependency being down) goes in `ApiContractFixtureTests.NOT_PRODUCIBLE` with its
  reason; the SCIM fixtures have no such set, so every SCIM status needs a fixture.
- **"mapped routes … does not document" / "documented operations no handler maps"** — a route was
  added, removed or renamed on one side only.

import assert from "node:assert/strict";
import { test } from "node:test";

import { notification, parseArguments } from "../src/publish.mjs";

test("an event and a patient id are taken as they are", () => {
  assert.deepEqual(
    parseArguments(["Patient-open", "eq081-VQEgP8drUUqCWzHfw3"]),
    {
      event: "Patient-open",
      patient: "eq081-VQEgP8drUUqCWzHfw3",
    },
  );
  assert.deepEqual(parseArguments(["Patient-close", "150dc43a.9b07"]), {
    event: "Patient-close",
    patient: "150dc43a.9b07",
  });
});

test("a missing or extra argument is refused with the usage", () => {
  for (const args of [[], ["Patient-open"], ["Patient-open", "p1", "extra"]]) {
    assert.throws(() => parseArguments(args), /usage:/);
  }
});

test("an event other than the two subscribed is refused", () => {
  for (const event of ["patient-open", "Encounter-open", ""]) {
    assert.throws(
      () => parseArguments([event, "p1"]),
      /must be one of Patient-open, Patient-close/,
    );
  }
});

test("a patient id outside FHIR's id grammar is refused", () => {
  for (const patient of ["", "has space", "slash/id", "x".repeat(65)]) {
    assert.throws(
      () => parseArguments(["Patient-open", patient]),
      /is not a FHIR id/,
    );
  }
});

test("the notification is FHIRcast STU3's, the patient under the patient key", () => {
  assert.deepEqual(
    notification({
      topic: "topic-1",
      event: "Patient-open",
      patient: "p1",
      id: "n1",
      now: new Date("2026-10-25T01:02:03.000Z"),
    }),
    {
      timestamp: "2026-10-25T01:02:03.000Z",
      id: "n1",
      event: {
        "hub.topic": "topic-1",
        "hub.event": "Patient-open",
        context: [
          { key: "patient", resource: { resourceType: "Patient", id: "p1" } },
        ],
      },
    },
  );
});

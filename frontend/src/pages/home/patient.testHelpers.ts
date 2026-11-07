import { act, screen, within } from "@testing-library/react";
import type { Patient } from "fhir/r5";

import { adoptLaunchLanding, followPatientEvent } from "@/auth/ehr-context";
import { bare } from "@/auth/session-adapter.testHelpers";

/**
 * The read of Tan Wei Ming, one of the seeder's development fixtures, as an
 * answer-table key. Written out rather than built from `PATIENT.path`, so a
 * suite asserting the request sent checks the path against the contract and
 * not against the code under test.
 */
export const TAN_WEI_MING_READ = "GET /api/patient/eq081-VQEgP8drUUqCWzHfw3";

/** Tan Wei Ming, the R5 `Patient` the backend serves for `TAN_WEI_MING_READ`. */
export const TAN_WEI_MING = {
  birthDate: "1985-03-12",
  gender: "male",
  id: "eq081-VQEgP8drUUqCWzHfw3",
  identifier: [{ system: "https://fhir.synapxe.sg/NamingSystem/nric", value: "S1234567A" }],
  name: [{ family: "Tan", given: ["Wei", "Ming"], text: "Tan Wei Ming", use: "official" }],
  resourceType: "Patient",
} satisfies Patient;

/** The read of Siti Nurhaliza binte Ahmad, another development fixture, as an answer-table key. */
export const SITI_READ = "GET /api/patient/erXuFYUfucBZaryVksYEcMg3";

/** Siti Nurhaliza binte Ahmad, the R5 `Patient` the backend serves for `SITI_READ`. */
export const SITI = {
  birthDate: "1992-08-21",
  gender: "female",
  id: "erXuFYUfucBZaryVksYEcMg3",
  identifier: [{ system: "https://fhir.synapxe.sg/NamingSystem/nric", value: "S9212345A" }],
  name: [{ text: "Siti Nurhaliza binte Ahmad", use: "official" }],
  resourceType: "Patient",
} satisfies Patient;

/** The tab loaded at an Epic landing with Patient `patientId` in context. */
export const landedFromEpicWith = (patientId: string) => {
  window.history.replaceState(null, "", `/#patient=${patientId}`);
  adoptLaunchLanding();
};

/**
 * The FHIRcast release an Epic session's layout asks for beside the patient,
 * answered "nothing released" (`fhircast-card.test.tsx` has the connection).
 */
export const NO_FHIRCAST_RELEASE = { "GET /api/auth/epic/fhircast": bare(404) };

/**
 * The patient reads among `sent`, in order, as answer-table keys to compare
 * with `TAN_WEI_MING_READ` and `SITI_READ`; the FHIRcast release is left out.
 */
export const patientReads = (sent: readonly { method: string; path: string }[]) =>
  sent
    .map(({ method, path }) => `${method} ${path}`)
    .filter((key) => key.startsWith("GET /api/patient/"));

/** The EHR moves to Patient `patientId`, as the FHIRcast hub's `Patient-open` says. */
export const patientOpened = (patientId: string) =>
  act(() => {
    followPatientEvent({ event: "Patient-open", patientId, timestamp: "2026-03-12T01:00:00Z" });
  });

/** The page beneath the top bar, which names the patient in context too. */
export const page = () => within(screen.getByRole("main"));

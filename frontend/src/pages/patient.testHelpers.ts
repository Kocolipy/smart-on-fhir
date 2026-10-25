import type { Patient } from "fhir/r5";

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

import type { Patient } from "fhir/r5";

/**
 * The read of Tan Wei Ming, one of the seeder's development fixtures, as an
 * answer-table key. Written out rather than built from `PATIENT.path`, so a
 * suite asserting the request sent checks the path against the contract and
 * not against the code under test.
 */
export const TAN_WEI_MING_READ = "GET /api/patient/00000000-0000-4000-8000-00000000c001";

/** Tan Wei Ming, the R5 `Patient` the backend serves for `TAN_WEI_MING_READ`. */
export const TAN_WEI_MING = {
  birthDate: "1985-03-12",
  gender: "male",
  id: "00000000-0000-4000-8000-00000000c001",
  identifier: [{ system: "https://fhir.synapxe.sg/NamingSystem/nric", value: "S1234567A" }],
  name: [{ family: "Tan", given: ["Wei", "Ming"], text: "Tan Wei Ming", use: "official" }],
  resourceType: "Patient",
} satisfies Patient;

/**
 * The endpoints the Home page reads and drives — the patient it shows and the
 * counter — `/frontend/AGENTS.md`'s page-owned `api.ts`, named for its page.
 *
 * Nothing here requests anything: the page sends each declaration through
 * `useGatedRead` or `useGatedWrite` (both behind `useSessionRequest`), so a
 * `401` still ends the session in one place. This file says what a response
 * looks like and declares each endpoint — its path, its decoder and the
 * Permission it requires — once.
 */

import type { ReadDeclaration, WriteDeclaration } from "@/auth/declarations";
import { DecodeError, jsonDecoder, readObject } from "@/lib/decode";

/**
 * The default patient's id, which Home shows until a SMART launch supplies
 * the patient in context.
 *
 * A copy: the backend's development patient seeder
 * (`backend/.../patient/application/DevPatientFixtures.java`) owns the value,
 * and seeds the patient under it only where the development fixtures are on.
 */
export const DEFAULT_PATIENT_ID = "00000000-0000-4000-8000-00000000c001";

/** Synapxe's NRIC NamingSystem, the `system` of the identifier that holds a patient's NRIC. */
const NRIC_SYSTEM = "https://fhir.synapxe.sg/NamingSystem/nric";

/**
 * The FHIR R5 administrative gender codes. Spelled out rather than imported:
 * `@types/fhir` is a devDependency, which production source may not import
 * (`cq-no-devdep-in-prod`), so `home-api.test.ts` holds this to `fhir5`'s own.
 */
const GENDERS = ["male", "female", "other", "unknown"] as const;

export type PatientGender = (typeof GENDERS)[number];

/** A FHIR `date` to the day, the precision the backend serves a birth date at. */
const DAY_PRECISION_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The part of an R5 `Patient` Home shows, read off the wire (its suite reads
 * `fhir5.Patient` fixtures through the decoder): the official
 * name's `text`, which is authoritative (its `family` and `given` parts are
 * optional and never read), the NRIC, the gender code and the birth date.
 * No age: age is derived where it is shown, never sent.
 */
export interface PatientSummary {
  birthDate: string;
  gender: PatientGender;
  name: string;
  nric: string;
}

/** The value of the identifier under the NRIC system, among a patient's identifiers. */
const nricOf = (identifiers: unknown[]): string => {
  const nric = identifiers
    .map((identifier) => readObject(identifier, "Identifier"))
    .find((identifier) => identifier.string("system") === NRIC_SYSTEM);
  if (nric === undefined) throw new DecodeError("Patient.identifier has no NRIC");
  return nric.string("value");
};

/** The first name's `text`. */
const nameTextOf = (names: unknown[]): string => {
  if (names.length === 0) throw new DecodeError("Patient.name has no entry");
  return readObject(names[0], "HumanName").string("text");
};

const decodePatient = jsonDecoder((body: unknown): PatientSummary => {
  const patient = readObject(body, "Patient");
  if (patient.string("resourceType") !== "Patient") {
    throw new DecodeError("Patient.resourceType is not Patient");
  }
  const birthDate = patient.string("birthDate");
  if (!DAY_PRECISION_DATE.test(birthDate)) throw new DecodeError("Patient.birthDate is not a date");
  return {
    birthDate,
    gender: patient.oneOf("gender", GENDERS),
    name: nameTextOf(patient.array("name", (name) => name)),
    nric: nricOf(patient.array("identifier", (identifier) => identifier)),
  };
});

/**
 * A patient by id, `GET /api/patient/{id}`, served as an R5 `Patient`. A
 * `404` is an ordinary answer — no patient there — so it reads as `absent`.
 */
export const PATIENT: ReadDeclaration<PatientSummary, [id: string], true> = {
  absentOnNotFound: true,
  decode: decodePatient,
  path: (id) => `/api/patient/${encodeURIComponent(id)}`,
  permission: "patient:read",
};

/** Every counter endpoint's `CountResponse`, read down to its one field. */
const decodeCount = jsonDecoder((body: unknown): number =>
  readObject(body, "CountResponse").integer("count"),
);

/** The current count, `GET /api/count`. */
export const COUNTER: ReadDeclaration<number> = {
  decode: decodeCount,
  path: () => "/api/count",
  permission: "counter:read",
};

/** Adds one; answers the new count. */
export const INCREMENT_COUNTER: WriteDeclaration<[], number> = {
  decode: decodeCount,
  method: "POST",
  path: () => "/api/count/increment",
  permission: "counter:write",
};

/** Returns the count to zero; answers the new count. */
export const RESET_COUNTER: WriteDeclaration<[], number> = {
  decode: decodeCount,
  method: "POST",
  path: () => "/api/count/reset",
  permission: "counter:write",
};

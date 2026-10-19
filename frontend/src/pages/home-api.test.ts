import type { Patient } from "fhir/r5";
import { describe, expect, expectTypeOf, it } from "vitest";

import {
  COUNTER,
  DEFAULT_PATIENT_ID,
  INCREMENT_COUNTER,
  PATIENT,
  type PatientGender,
  RESET_COUNTER,
} from "./home-api";

describe("COUNTER", () => {
  it("reads the count at /api/count, under counter:read", () => {
    expect({ path: COUNTER.path(), permission: COUNTER.permission }).toEqual({
      path: "/api/count",
      permission: "counter:read",
    });
  });
});

describe.each([
  ["INCREMENT_COUNTER", INCREMENT_COUNTER, "/api/count/increment"],
  ["RESET_COUNTER", RESET_COUNTER, "/api/count/reset"],
] as const)("%s", (_, write, path) => {
  it("posts, with no body, to its own path under counter:write", () => {
    expect({
      body: write.body,
      method: write.method,
      path: write.path(),
      permission: write.permission,
    }).toEqual({ body: undefined, method: "POST", path, permission: "counter:write" });
  });
});

describe.each([
  ["COUNTER", COUNTER.decode],
  ["INCREMENT_COUNTER", INCREMENT_COUNTER.decode],
  ["RESET_COUNTER", RESET_COUNTER.decode],
] as const)("%s's answer", (_, decode) => {
  it("is read down to the count the backend's body carries", async () => {
    await expect(decode(Response.json({ count: 7 }))).resolves.toBe(7);
  });

  it.each([
    ["a missing count", {}, "CountResponse.count is not an integer"],
    ["a string count", { count: "7" }, "CountResponse.count is not an integer"],
    ["a fractional count", { count: 1.5 }, "CountResponse.count is not an integer"],
    ["a non-object body", [7], "CountResponse is not an object"],
  ])("is refused with %s", async (_, body, message) => {
    await expect(decode(Response.json(body))).rejects.toThrow(
      expect.objectContaining({ message, name: "DecodeError" }),
    );
  });
});

describe("PATIENT", () => {
  it("reads a patient at /api/patient/{id}, under patient:read, a 404 being absent", () => {
    expect({
      absentOnNotFound: PATIENT.absentOnNotFound,
      path: PATIENT.path("00000000-0000-4000-8000-0000000000aa"),
      permission: PATIENT.permission,
    }).toEqual({
      absentOnNotFound: true,
      path: "/api/patient/00000000-0000-4000-8000-0000000000aa",
      permission: "patient:read",
    });
  });

  it("reads exactly fhir5's administrative gender codes", () => {
    expectTypeOf<PatientGender>().toEqualTypeOf<NonNullable<Patient["gender"]>>();
  });

  it("names the seeder's default patient by its fixed UUID", () => {
    expect(DEFAULT_PATIENT_ID).toBe("00000000-0000-4000-8000-00000000c001");
  });
});

/** The R5 `Patient` the backend serves, shaped as the issue's example is. */
const TAN_WEI_MING = {
  birthDate: "1985-03-12",
  gender: "male",
  id: "00000000-0000-4000-8000-00000000c001",
  identifier: [{ system: "https://fhir.synapxe.sg/NamingSystem/nric", value: "S1234567A" }],
  name: [{ family: "Tan", given: ["Wei", "Ming"], text: "Tan Wei Ming", use: "official" }],
  resourceType: "Patient",
} satisfies Patient;

describe("PATIENT's answer", () => {
  it("is read down to the name text, NRIC, gender and date of birth", async () => {
    await expect(PATIENT.decode(Response.json(TAN_WEI_MING))).resolves.toEqual({
      birthDate: "1985-03-12",
      gender: "male",
      name: "Tan Wei Ming",
      nric: "S1234567A",
    });
  });

  it("reads a name with no family or given part by its text alone", async () => {
    const mononym = { ...TAN_WEI_MING, name: [{ text: "Suriani", use: "official" }] };

    await expect(PATIENT.decode(Response.json(mononym))).resolves.toEqual(
      expect.objectContaining({ name: "Suriani" }),
    );
  });

  it("reads the NRIC from the identifier under the NRIC system, past any other", async () => {
    const twoIdentifiers = {
      ...TAN_WEI_MING,
      identifier: [{ system: "urn:example:other", value: "X-1" }, ...TAN_WEI_MING.identifier],
    };

    await expect(PATIENT.decode(Response.json(twoIdentifiers))).resolves.toEqual(
      expect.objectContaining({ nric: "S1234567A" }),
    );
  });

  it.each([
    [
      "another resource type",
      { ...TAN_WEI_MING, resourceType: "Practitioner" },
      "Patient.resourceType is not Patient",
    ],
    ["no name", { ...TAN_WEI_MING, name: [] }, "Patient.name has no entry"],
    [
      "a name with no text",
      { ...TAN_WEI_MING, name: [{ use: "official" }] },
      "HumanName.text is not a string",
    ],
    [
      "no NRIC identifier",
      { ...TAN_WEI_MING, identifier: [{ system: "urn:example:other", value: "X-1" }] },
      "Patient.identifier has no NRIC",
    ],
    [
      "an unknown gender",
      { ...TAN_WEI_MING, gender: "M" },
      "Patient.gender is not male | female | other | unknown",
    ],
    [
      "a malformed birth date",
      { ...TAN_WEI_MING, birthDate: "12/03/1985" },
      "Patient.birthDate is not a date",
    ],
    [
      "a birth date with a time after it",
      { ...TAN_WEI_MING, birthDate: "1985-03-12T00:00:00" },
      "Patient.birthDate is not a date",
    ],
    [
      "a birth date with text before it",
      { ...TAN_WEI_MING, birthDate: "c. 1985-03-12" },
      "Patient.birthDate is not a date",
    ],
    [
      "a birth date in a month past December",
      { ...TAN_WEI_MING, birthDate: "1985-13-12" },
      "Patient.birthDate is not a date",
    ],
    [
      "a birth date on 29 February in a common year",
      { ...TAN_WEI_MING, birthDate: "2023-02-29" },
      "Patient.birthDate is not a date",
    ],
    [
      "an identifier that is not an object",
      { ...TAN_WEI_MING, identifier: ["S1234567A"] },
      "Identifier is not an object",
    ],
    ["a non-object body", [TAN_WEI_MING], "Patient is not an object"],
  ])("is refused with %s", async (_, body, message) => {
    await expect(PATIENT.decode(Response.json(body))).rejects.toThrow(
      expect.objectContaining({ message, name: "DecodeError" }),
    );
  });
});

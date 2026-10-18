import { screen } from "@testing-library/react";
import type { Patient } from "fhir/r5";
import { describe, expect, it } from "vitest";

import { bare, deferred, holding, json, type WireAnswer } from "@/auth/session-adapter.testHelpers";
import type { Clock } from "@/lib/calendar";

import { PatientCard } from "./patient-card";
import { renderInLayout } from "./signed-in-shell.testHelpers";

const DEFAULT_PATIENT_READ = "GET /api/patient/00000000-0000-4000-8000-00000000c001";

/** The default patient, as the backend serves it. */
const TAN_WEI_MING = {
  birthDate: "1985-03-12",
  gender: "male",
  id: "00000000-0000-4000-8000-00000000c001",
  identifier: [{ system: "https://fhir.synapxe.sg/NamingSystem/nric", value: "S1234567A" }],
  name: [{ family: "Tan", given: ["Wei", "Ming"], text: "Tan Wei Ming", use: "official" }],
  resourceType: "Patient",
} satisfies Patient;

/** A clock stopped at `instant`. */
const at =
  (instant: string): Clock =>
  () =>
    new Date(instant);

/** 12 March 2026 in Singapore: Tan Wei Ming's 41st birthday. */
const ON_THE_BIRTHDAY = at("2026-03-12T01:00:00+08:00");

/**
 * The card alone, routed in the signed-in layout that renders the page's one
 * error line, as a session holding `patient:read` sees it, the default patient
 * read answered with `answer`.
 */
const renderCard = (answer: WireAnswer | Promise<WireAnswer>, clock: Clock = ON_THE_BIRTHDAY) =>
  renderInLayout(
    [{ element: <PatientCard clock={clock} />, path: "/" }],
    "/",
    holding(["patient:read"]),
    { [DEFAULT_PATIENT_READ]: answer },
  );

/** What the card lists, each field's label beside its value. */
const fields = () => {
  const values = screen.getAllByRole("definition").map((value) => value.textContent);
  return Object.fromEntries(
    screen.getAllByRole("term").map((label, index) => [label.textContent, values[index]]),
  );
};

describe("PatientCard", () => {
  it("reads the default patient, and nothing else", async () => {
    const { requests } = await renderCard(json(TAN_WEI_MING));

    await screen.findByText("Tan Wei Ming");
    expect(requests()).toEqual([
      { method: "GET", path: "/api/patient/00000000-0000-4000-8000-00000000c001" },
    ]);
  });

  it("is headed Patient", async () => {
    await renderCard(json(TAN_WEI_MING));

    expect(screen.getByRole("heading", { name: "Patient" })).toBeInTheDocument();
  });

  it("shows the loaded patient's name, NRIC in full, gender, date of birth and age", async () => {
    await renderCard(json(TAN_WEI_MING));
    await screen.findByText("Tan Wei Ming");

    expect(fields()).toEqual({
      "Date of birth": "12 Mar 1985 (41 y)",
      Gender: "Male",
      Name: "Tan Wei Ming",
      NRIC: "S1234567A",
    });
  });

  it("shows nothing of a patient while the read is in flight", async () => {
    await renderCard(deferred().answer);

    expect(screen.getByText("Loading the patient…")).toBeInTheDocument();
    expect(screen.queryAllByRole("definition")).toEqual([]);
  });

  it("says no patient is in context for a 404, and reports nothing", async () => {
    await renderCard(bare(404));

    expect(await screen.findByText("No patient in context")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryAllByRole("definition")).toEqual([]);
  });

  it("reports any other failure on the page's error line, with no patient and no retry", async () => {
    await renderCard(bare(503));

    expect(await screen.findByRole("alert")).toHaveTextContent(/^Couldn't load patient$/);
    expect(screen.queryAllByRole("definition")).toEqual([]);
    expect(screen.queryByText("No patient in context")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /retry|try again/i })).not.toBeInTheDocument();
  });

  it("reports a body that is not a Patient as a failure", async () => {
    await renderCard(json({ ...TAN_WEI_MING, resourceType: "Practitioner" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/^Couldn't load patient$/);
    expect(screen.queryAllByRole("definition")).toEqual([]);
  });

  it("does not count the year whose birthday is tomorrow in Singapore", async () => {
    // 23:59 on 11 March in Singapore, though already 12 March nowhere east of it.
    await renderCard(json(TAN_WEI_MING), at("2026-03-11T23:59:00+08:00"));
    await screen.findByText("Tan Wei Ming");

    expect(fields()["Date of birth"]).toBe("12 Mar 1985 (40 y)");
  });

  it("counts the year from the first minute of the birthday in Singapore", async () => {
    // Still 11 March in UTC.
    await renderCard(json(TAN_WEI_MING), at("2026-03-11T16:00:00Z"));
    await screen.findByText("Tan Wei Ming");

    expect(fields()["Date of birth"]).toBe("12 Mar 1985 (41 y)");
  });

  it("shows a name with no family or given part by its text", async () => {
    await renderCard(json({ ...TAN_WEI_MING, name: [{ text: "Suriani", use: "official" }] }));

    expect(await screen.findByText("Suriani")).toBeInTheDocument();
    expect(fields().Name).toBe("Suriani");
  });

  it.each([
    ["female", "Female"],
    ["other", "Other"],
    ["unknown", "Unknown"],
  ] as const)("shows the gender %s as %s", async (gender, label) => {
    await renderCard(json({ ...TAN_WEI_MING, gender }));
    await screen.findByText("Tan Wei Ming");

    expect(fields().Gender).toBe(label);
  });

  it("shows markup in a field as the text it is, never as markup", async () => {
    const markup = "<b>Tan</b> <img src=x onerror=alert(1)>";
    await renderCard(json({ ...TAN_WEI_MING, name: [{ text: markup, use: "official" }] }));

    expect(await screen.findByText(markup)).toBeInTheDocument();
    expect(document.querySelector("b, img")).toBeNull();
  });

  it.each([
    ["while loading", deferred().answer, "Loading the patient…"],
    ["for a 404", bare(404), "No patient in context"],
  ])("shows its note %s as muted text", async (_, answer, note) => {
    await renderCard(answer);

    expect(await screen.findByText(note)).toHaveClass("text-sm", "text-muted-foreground");
  });

  it("asks nothing and says so for a session without patient:read", async () => {
    const { requests } = await renderInLayout(
      [{ element: <PatientCard clock={ON_THE_BIRTHDAY} />, path: "/" }],
      "/",
      holding([]),
    );

    expect(screen.getByText("Your account has no access to patients.")).toBeInTheDocument();
    expect(requests()).toEqual([]);
  });
});

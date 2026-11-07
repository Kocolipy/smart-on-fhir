import { act, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";

import type { Permission } from "@/auth/api";
import { adoptLaunchLanding, clearEhrContext, followPatientEvent } from "@/auth/ehr-context";
import { bare, holding, json, type Answers } from "@/auth/session-adapter.testHelpers";
import { SITI, SITI_READ, TAN_WEI_MING, TAN_WEI_MING_READ } from "@/pages/home/patient.testHelpers";

import { renderInLayout, renderSignedIn } from "./signed-in-shell.testHelpers";

/** The tab loaded at an Epic landing with Tan Wei Ming in context. */
const landedWithTanWeiMing = () => {
  window.history.replaceState(null, "", `/#patient=${TAN_WEI_MING.id}`);
  adoptLaunchLanding();
};

/**
 * The layout over a page that reads nothing, for a session holding
 * `permissions`; the FHIRcast release an Epic session's layout asks for is
 * answered "nothing released".
 */
const renderBar = (permissions: Permission[], answers: Answers = {}) =>
  renderInLayout([{ element: <p>page body</p>, path: "/" }], "/", holding(permissions), {
    "GET /api/auth/epic/fhircast": bare(404),
    ...answers,
  });

/** The patient reads among `sent`, the FHIRcast release left out. */
const patientReads = (sent: readonly { method: string; path: string }[]) =>
  sent.filter(({ path }) => path.startsWith("/api/patient/"));

const topBar = () => within(screen.getByRole("banner"));

const patientInContext = () => screen.queryByRole("region", { name: "Patient in context" });

afterEach(() => {
  clearEhrContext();
  window.history.replaceState(null, "", "/");
});

describe("TopBar", () => {
  it("shows who is signed in, by name beneath their avatar", async () => {
    await renderBar([]);

    expect([
      topBar().getByText("Signed in as ada") !== null,
      topBar().getByText("ada", { selector: "span" }) !== null,
    ]).toEqual([true, true]);
  });

  it("links Settings", async () => {
    await renderBar([]);

    expect(topBar().getByRole("link", { name: "Open settings" })).toHaveAttribute(
      "href",
      "/settings",
    );
  });

  it.each([
    ["Notifications", "No notifications."],
    ["Help", "Press Ctrl+B (⌘B on a Mac) to collapse or expand the sidebar."],
  ])("opens %s to say %s", async (control, says) => {
    await renderBar([]);

    await userEvent.setup().click(topBar().getByRole("button", { name: control }));

    expect(await screen.findByRole("dialog")).toHaveTextContent(says);
  });

  it("names the patient in context by name and NRIC", async () => {
    landedWithTanWeiMing();
    await renderBar(["patient:read"], { [TAN_WEI_MING_READ]: json(TAN_WEI_MING) });

    expect((await screen.findByRole("region", { name: "Patient in context" })).textContent).toBe(
      "Tan Wei MingS1234567A",
    );
  });

  it("reads the patient in context once per change on a page that shows no patient", async () => {
    landedWithTanWeiMing();
    const { requests } = await renderSignedIn("/settings", holding(["patient:read"]), {
      "GET /api/auth/epic/fhircast": bare(404),
      [SITI_READ]: json(SITI),
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
    });
    await topBar().findByText("Tan Wei Ming");

    act(() => {
      followPatientEvent({ event: "Patient-open", patientId: SITI.id, timestamp: "2026-03-12" });
    });
    await topBar().findByText("Siti Nurhaliza binte Ahmad");

    expect(patientReads(requests())).toEqual([
      { method: "GET", path: `/api/patient/${TAN_WEI_MING.id}` },
      { method: "GET", path: `/api/patient/${SITI.id}` },
    ]);
  });

  it("keeps the page as it was when a patient comes into context", async () => {
    window.history.replaceState(null, "", "/#launch");
    adoptLaunchLanding();
    await renderSignedIn("/settings", holding(["patient:read"]), {
      "GET /api/auth/epic/fhircast": bare(404),
      [SITI_READ]: json(SITI),
    });
    await userEvent.setup().type(screen.getByLabelText("Current password"), "typed so far");

    act(() => {
      followPatientEvent({ event: "Patient-open", patientId: SITI.id, timestamp: "2026-03-12" });
    });
    await topBar().findByText("Siti Nurhaliza binte Ahmad");

    expect(screen.getByLabelText("Current password")).toHaveValue("typed so far");
  });

  it("names no patient, and asks nothing, with no patient in context", async () => {
    const { requests } = await renderBar(["patient:read"]);

    expect([patientInContext(), patientReads(requests())]).toEqual([null, []]);
  });

  it("names no patient, and asks nothing, for a session without patient:read", async () => {
    landedWithTanWeiMing();
    const { requests } = await renderBar([]);

    expect([patientInContext(), patientReads(requests())]).toEqual([null, []]);
  });

  it("names no patient, and reports nothing, for a patient that is not there", async () => {
    landedWithTanWeiMing();
    const { requests } = await renderBar(["patient:read"], { [TAN_WEI_MING_READ]: bare(404) });
    await act(async () => {});

    expect([
      patientInContext(),
      screen.queryByRole("alert"),
      patientReads(requests()).length,
    ]).toEqual([null, null, 1]);
  });

  it.each([403, 503])(
    "reports a %i on the page's error line as a failed read, naming no patient",
    async (status) => {
      landedWithTanWeiMing();
      await renderBar(["patient:read"], { [TAN_WEI_MING_READ]: bare(status) });

      expect(await screen.findByRole("alert")).toHaveTextContent(/^Couldn't load patient$/);
      expect(patientInContext()).toBeNull();
    },
  );

  it("drops the patient as soon as the tab's EHR context is cleared", async () => {
    landedWithTanWeiMing();
    await renderBar(["patient:read"], { [TAN_WEI_MING_READ]: json(TAN_WEI_MING) });
    await screen.findByRole("region", { name: "Patient in context" });

    act(() => clearEhrContext());

    expect(patientInContext()).toBeNull();
  });
});

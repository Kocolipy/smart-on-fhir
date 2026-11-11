import { act, fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";

import type { AuthUser, Permission } from "@/auth/api";
import { adoptLaunchLanding, clearEhrContext } from "@/auth/ehr-context";
import { bare, holding, json, type Answers } from "@/auth/session-adapter.testHelpers";
import {
  landedFromEpicWith,
  NO_FHIRCAST_RELEASE,
  patientOpened,
  patientReads,
  SITI,
  SITI_READ,
  TAN_WEI_MING,
  TAN_WEI_MING_READ,
} from "@/pages/home/patient.testHelpers";

import { renderInLayout, renderSignedIn } from "./signed-in-shell.testHelpers";

/** The tab loaded at an Epic landing with Tan Wei Ming in context. */
const landedWithTanWeiMing = () => landedFromEpicWith(TAN_WEI_MING.id);

/**
 * The layout over a page that reads nothing, for a session holding
 * `permissions`; the FHIRcast release an Epic session's layout asks for is
 * answered "nothing released".
 */
const renderBar = (permissions: Permission[], answers: Answers = {}) =>
  renderInLayout([{ element: <p>page body</p>, path: "/" }], "/", holding(permissions), {
    ...NO_FHIRCAST_RELEASE,
    ...answers,
  });

/** The layout over a page that reads nothing, for a session signed in as `user`. */
const renderBarFor = (user: Partial<Omit<AuthUser, "permissions">>) =>
  renderInLayout(
    [{ element: <p>page body</p>, path: "/" }],
    "/",
    holding([], user),
    NO_FHIRCAST_RELEASE,
  );

const topBar = () => within(screen.getByRole("banner"));

/** The lines naming who is signed in, top to bottom, as the bar shows them. */
const signedInLines = () => topBar().getAllByTestId("signed-in-line");

const textOf = (lines: HTMLElement[]) => lines.map((line) => line.textContent);

const patientInContext = () => screen.queryByRole("region", { name: "Patient in context" });

afterEach(() => {
  clearEhrContext();
  window.history.replaceState(null, "", "/");
});

describe("TopBar", () => {
  it("names who is signed in exactly as the backend sends the name, in the primary colour", async () => {
    await renderBarFor({ username: "Ada.Lovelace" });

    const [name] = signedInLines();
    expect([
      name?.textContent,
      name?.classList.contains("text-primary"),
      name?.classList.contains("uppercase"),
    ]).toEqual(["Ada.Lovelace", true, false]);
  });

  it("lists each Group beneath the name, in the order sent", async () => {
    await renderBarFor({ groups: ["Helpdesk", "auditor"] });

    expect(textOf(signedInLines())).toEqual(["ada", "Helpdesk", "auditor"]);
  });

  it("styles every line beneath the name as a muted subtitle, upper-cased by style alone", async () => {
    await renderBarFor({ groups: ["auditor", "Helpdesk", "Ward 7B"] });

    expect(
      signedInLines()
        .slice(1)
        .map((line) => [
          line.textContent,
          line.classList.contains("uppercase"),
          line.classList.contains("text-muted-foreground"),
        ]),
    ).toEqual([
      ["auditor", true, true],
      ["Helpdesk", true, true],
      ["+1 more", true, true],
    ]);
  });

  it("shows two Groups in full", async () => {
    await renderBarFor({ groups: ["Admin", "Auditor"] });

    expect(textOf(signedInLines())).toEqual(["ada", "Admin", "Auditor"]);
  });

  it("shows the first two of more Groups, then how many more there are", async () => {
    await renderBarFor({ groups: ["Admin", "Auditor", "Helpdesk", "Ward 7B"] });

    expect(textOf(signedInLines())).toEqual(["ada", "Admin", "Auditor", "+2 more"]);
  });

  it("lists every Group in the tooltip of the line saying how many more there are", async () => {
    await renderBarFor({ groups: ["Admin", "Auditor", "Helpdesk"] });

    fireEvent.focus(topBar().getByText("+1 more"));

    expect(textOf(within(await screen.findByRole("tooltip")).getAllByRole("listitem"))).toEqual([
      "Admin",
      "Auditor",
      "Helpdesk",
    ]);
  });

  it("names the line saying how many more there are for the Groups it opens", async () => {
    await renderBarFor({ groups: ["Admin", "Auditor", "Helpdesk"] });

    expect(topBar().getByText("+1 more")).toBe(
      topBar().getByRole("button", { name: "Show all 3 Groups" }),
    );
  });

  it("shows only the name for a User in no Group", async () => {
    await renderBarFor({ groups: [] });

    expect(textOf(signedInLines())).toEqual(["ada"]);
  });

  it("tells a screen reader who is signed in and every Group they are in", async () => {
    await renderBarFor({ groups: ["Admin", "Auditor", "Helpdesk"] });

    expect(topBar().getByText(/^Signed in as/).textContent).toBe(
      "Signed in as ada, in Admin, Auditor, Helpdesk",
    );
  });

  it("tells a screen reader only who is signed in for a User in no Group", async () => {
    await renderBarFor({ groups: [] });

    expect(topBar().getByText(/^Signed in as/).textContent).toBe("Signed in as ada");
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
      ...NO_FHIRCAST_RELEASE,
      [SITI_READ]: json(SITI),
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
    });
    await topBar().findByText("Tan Wei Ming");

    patientOpened(SITI.id);
    await topBar().findByText("Siti Nurhaliza binte Ahmad");

    expect(patientReads(requests())).toEqual([TAN_WEI_MING_READ, SITI_READ]);
  });

  it("keeps the page as it was when a patient comes into context", async () => {
    window.history.replaceState(null, "", "/#launch");
    adoptLaunchLanding();
    await renderSignedIn("/settings", holding(["patient:read"]), {
      ...NO_FHIRCAST_RELEASE,
      [SITI_READ]: json(SITI),
    });
    await userEvent.setup().type(screen.getByLabelText("Current password"), "typed so far");

    patientOpened(SITI.id);
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

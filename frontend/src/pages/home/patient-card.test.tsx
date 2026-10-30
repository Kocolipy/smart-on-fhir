import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import type { OperationOutcome } from "fhir/r5";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { Permission } from "@/auth/api";
import { adoptLaunchLanding } from "@/auth/ehr-context";
import {
  bare,
  deferred,
  holding,
  json,
  type Answers,
  type WireAnswer,
} from "@/auth/session-adapter.testHelpers";
import {
  fakeHub,
  HUB_RELEASE,
  type HubOptions,
  patientNotification,
} from "@/lib/fake-hub.testHelpers";
import type { Clock } from "@/lib/time";

import { PatientCard } from "./patient-card";
import { SITI, SITI_READ, TAN_WEI_MING, TAN_WEI_MING_READ } from "./patient.testHelpers";
import { renderInLayout, renderSignedIn } from "@/shell/signed-in-shell.testHelpers";

/** An error answer as the backend sends it: one issue, its diagnostics fixed per code. */
const outcome = (code: OperationOutcome["issue"][number]["code"]) =>
  ({
    issue: [{ code, diagnostics: "A fixed sentence.", severity: "error" }],
    resourceType: "OperationOutcome",
  }) satisfies OperationOutcome;

/** A clock stopped at `instant`. */
const at =
  (instant: string): Clock =>
  () =>
    new Date(instant);

/** 12 March 2026 in Singapore: Tan Wei Ming's 41st birthday. */
const ON_THE_BIRTHDAY = at("2026-03-12T01:00:00+08:00");

/**
 * The card alone, routed in the signed-in layout that renders the page's one
 * error line, as a session holding `patient:read` sees it, with Tan Wei Ming
 * in context and the read of him answered with `answer`.
 */
const renderCard = (answer: WireAnswer | Promise<WireAnswer>, clock: Clock = ON_THE_BIRTHDAY) =>
  renderInLayout(
    [{ element: <PatientCard clock={clock} patientId={TAN_WEI_MING.id} />, path: "/" }],
    "/",
    holding(["patient:read"]),
    { [TAN_WEI_MING_READ]: answer },
  );

/** The card with no patient in context, for a session holding exactly `permissions`. */
const renderWithoutPatient = (permissions: Permission[]) =>
  renderInLayout(
    [{ element: <PatientCard clock={ON_THE_BIRTHDAY} />, path: "/" }],
    "/",
    holding(permissions),
  );

/** What the card lists, each field's label beside its value. */
const fields = () => {
  const values = screen.getAllByRole("definition").map((value) => value.textContent);
  return Object.fromEntries(
    screen.getAllByRole("term").map((label, index) => [label.textContent, values[index]]),
  );
};

describe("PatientCard with no patient in context", () => {
  it.each([[["patient:read"]], [[]]] as Permission[][][])(
    "says no patient is in context and asks nothing, for a session holding %j",
    async (permissions) => {
      const { requests } = await renderWithoutPatient(permissions);

      expect(screen.getByText("No patient in context")).toBeInTheDocument();
      expect(requests()).toEqual([]);
    },
  );

  it("shows its note as muted text, under the heading Patient, and reports nothing", async () => {
    await renderWithoutPatient(["patient:read"]);

    expect(screen.getByText("No patient in context")).toHaveClass(
      "text-sm",
      "text-muted-foreground",
    );
    expect(screen.getByRole("heading", { name: "Patient" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("PatientCard with a patient in context", () => {
  it("reads that patient, and nothing else", async () => {
    const { requests } = await renderCard(json(TAN_WEI_MING));

    await screen.findByText("Tan Wei Ming");
    expect(requests()).toEqual([{ method: "GET", path: "/api/patient/eq081-VQEgP8drUUqCWzHfw3" }]);
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

  it.each([
    [400, "invalid"],
    [403, "forbidden"],
    [500, "exception"],
    [503, "exception"],
  ] as const)(
    "shows its failed state for a %i OperationOutcome, never the absent one",
    async (status, code) => {
      await renderCard(json(outcome(code), status));

      expect(await screen.findByRole("alert")).toHaveTextContent(/^Couldn't load patient$/);
      expect(screen.queryAllByRole("definition")).toEqual([]);
      expect(screen.queryByText("No patient in context")).not.toBeInTheDocument();
    },
  );

  it("says no patient is in context for the 404's OperationOutcome, and reports nothing", async () => {
    await renderCard(json(outcome("not-found"), 404));

    expect(await screen.findByText("No patient in context")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("reports a body that is not a Patient as a failure", async () => {
    await renderCard(json({ ...TAN_WEI_MING, resourceType: "Practitioner" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/^Couldn't load patient$/);
    expect(screen.queryAllByRole("definition")).toEqual([]);
  });

  it("shows its failed state for a birth date that is not a real calendar date", async () => {
    await renderCard(json({ ...TAN_WEI_MING, birthDate: "1985-13-45" }));

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
      [{ element: <PatientCard clock={ON_THE_BIRTHDAY} patientId={TAN_WEI_MING.id} />, path: "/" }],
      "/",
      holding([]),
    );

    expect(screen.getByText("Your account has no access to patients.")).toBeInTheDocument();
    expect(requests()).toEqual([]);
  });
});

describe("PatientCard following the FHIRcast hub", () => {
  const RELEASE_REQUEST = "GET /api/auth/epic/fhircast";

  /** Every retry a dropped connection gets, each failing, until it is given up. */
  const RETRIES_RUN_OUT_MS = 1_000 + 2_000 + 4_000 + 8_000 + 16_000 + 1_000;

  /** The FHIRcast card's one status line. */
  const fhircastStatus = () => screen.getByRole("status", { name: "FHIRcast" });

  /** Every patient read the page sent, in order. */
  const patientReads = (requests: () => readonly { method: string; path: string }[]) =>
    requests()
      .map(({ method, path }) => `${method} ${path}`)
      .filter((key) => key.startsWith("GET /api/patient/"));

  /**
   * Home for an Epic session that landed at `landing`, inside the signed-in
   * layout that starts the session's FHIRcast connection, the backend
   * answering `answers` beside the release and the hub `options`; once the
   * FHIRcast card is connected, the hub, beside what the page sent.
   */
  async function connectedHome(landing: string, answers: Answers = {}, options: HubOptions = {}) {
    window.history.replaceState(null, "", landing);
    adoptLaunchLanding();
    const hub = fakeHub(options);
    const rendered = await renderSignedIn("/", holding(["patient:read"]), {
      [RELEASE_REQUEST]: json(HUB_RELEASE),
      ...answers,
    });
    await waitFor(() => expect(fhircastStatus()).toHaveTextContent(/^Connected$/));
    return { hub, ...rendered };
  }

  /** The hub sends `event` for Patient `patientId`, a notification of its own. */
  let sent = 0;
  const publish = (hub: { notify: (message: unknown) => void }, event: string, patientId: string) =>
    act(() => {
      sent += 1;
      hub.notify(patientNotification(event, { id: `event-${sent}`, patientId }));
    });

  afterEach(() => {
    window.history.replaceState(null, "", "/");
  });

  it("shows the patient a Patient-open names, after a launch that named none", async () => {
    const { hub } = await connectedHome("/#launch", { [TAN_WEI_MING_READ]: json(TAN_WEI_MING) });

    publish(hub, "Patient-open", TAN_WEI_MING.id);

    expect(await screen.findByText("Tan Wei Ming")).toBeInTheDocument();
  });

  it("shows the loading note at once on a Patient-open for another patient, never the previous one", async () => {
    const siti = deferred();
    const { hub } = await connectedHome(`/#patient=${TAN_WEI_MING.id}`, {
      [SITI_READ]: siti.answer,
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
    });
    await screen.findByText("Tan Wei Ming");

    publish(hub, "Patient-open", SITI.id);

    expect([
      screen.queryByText("Loading the patient…") !== null,
      screen.queryByText("Tan Wei Ming"),
    ]).toEqual([true, null]);
  });

  it("shows the other patient a Patient-open names once read", async () => {
    const { hub } = await connectedHome(`/#patient=${TAN_WEI_MING.id}`, {
      [SITI_READ]: json(SITI),
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
    });
    await screen.findByText("Tan Wei Ming");

    publish(hub, "Patient-open", SITI.id);

    expect(await screen.findByText("Siti Nurhaliza binte Ahmad")).toBeInTheDocument();
  });

  it("says no patient is in context when the opened patient reads 404, reporting nothing", async () => {
    const { hub } = await connectedHome(`/#patient=${TAN_WEI_MING.id}`, {
      [SITI_READ]: bare(404),
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
    });
    await screen.findByText("Tan Wei Ming");

    publish(hub, "Patient-open", SITI.id);

    expect(await screen.findByText("No patient in context")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("reports any other failure reading the opened patient on the page's error line", async () => {
    const { hub } = await connectedHome(`/#patient=${TAN_WEI_MING.id}`, {
      [SITI_READ]: bare(503),
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
    });
    await screen.findByText("Tan Wei Ming");

    publish(hub, "Patient-open", SITI.id);

    expect(await screen.findByRole("alert")).toHaveTextContent(/^Couldn't load patient$/);
  });

  it("never lands a late answer for the patient before on the card", async () => {
    const tan = deferred();
    const { hub } = await connectedHome(`/#patient=${TAN_WEI_MING.id}`, {
      [SITI_READ]: json(SITI),
      [TAN_WEI_MING_READ]: tan.answer,
    });
    publish(hub, "Patient-open", SITI.id);
    await screen.findByText("Siti Nurhaliza binte Ahmad");

    await tan.settle(json(TAN_WEI_MING));

    expect([
      screen.queryByText("Siti Nurhaliza binte Ahmad") !== null,
      screen.queryByText("Tan Wei Ming"),
    ]).toEqual([true, null]);
  });

  it("reads nothing again on a Patient-open for the patient already in context", async () => {
    const { hub, requests } = await connectedHome(`/#patient=${TAN_WEI_MING.id}&encounter=enc-1`, {
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
    });
    await screen.findByText("Tan Wei Ming");

    publish(hub, "Patient-open", TAN_WEI_MING.id);

    expect([patientReads(requests), screen.queryByText("Tan Wei Ming") !== null]).toEqual([
      [TAN_WEI_MING_READ],
      true,
    ]);
  });

  it("ignores a Patient-open naming no valid id, reading nothing", async () => {
    const { hub, requests } = await connectedHome(`/#patient=${TAN_WEI_MING.id}`, {
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
    });
    await screen.findByText("Tan Wei Ming");

    publish(hub, "Patient-open", "patient/1");

    expect([patientReads(requests), screen.queryByText("Tan Wei Ming") !== null]).toEqual([
      [TAN_WEI_MING_READ],
      true,
    ]);
  });

  it.each([
    ["the patient in context", TAN_WEI_MING.id],
    ["another patient", SITI.id],
    ["no valid id", "patient/1"],
  ])(
    "says no patient is in context on a Patient-close naming %s, the FHIRcast card still connected",
    async (_name, patientId) => {
      const { hub } = await connectedHome(`/#patient=${TAN_WEI_MING.id}`, {
        [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
      });
      await screen.findByText("Tan Wei Ming");

      publish(hub, "Patient-close", patientId);

      expect([
        screen.queryByText("No patient in context") !== null,
        screen.queryByText("Tan Wei Ming"),
        fhircastStatus().textContent,
      ]).toEqual([true, null, "Connected"]);
    },
  );

  it("brings the patient back on a Patient-open after a Patient-close", async () => {
    const { hub } = await connectedHome(`/#patient=${TAN_WEI_MING.id}`, {
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
    });
    await screen.findByText("Tan Wei Ming");
    publish(hub, "Patient-close", TAN_WEI_MING.id);
    await screen.findByText("No patient in context");

    publish(hub, "Patient-open", TAN_WEI_MING.id);

    expect(await screen.findByText("Tan Wei Ming")).toBeInTheDocument();
  });

  it("shows, back on Home, the patient a Patient-open named while the clinician was on another page", async () => {
    const { hub } = await connectedHome("/#launch", { [SITI_READ]: json(SITI) });
    fireEvent.click(screen.getByRole("link", { name: "Change password" }));
    await screen.findByRole("heading", { level: 1, name: "Change password" });

    publish(hub, "Patient-open", SITI.id);
    fireEvent.click(screen.getByRole("link", { name: "Back" }));

    expect(await screen.findByText("Siti Nurhaliza binte Ahmad")).toBeInTheDocument();
  });

  it("keeps the opened patient while the connection reconnects", async () => {
    const { hub } = await connectedHome("/#launch", { [SITI_READ]: json(SITI) });
    publish(hub, "Patient-open", SITI.id);
    await screen.findByText("Siti Nurhaliza binte Ahmad");

    act(() => hub.dropSockets());

    expect([
      fhircastStatus().textContent,
      screen.queryByText("Siti Nurhaliza binte Ahmad") !== null,
    ]).toEqual(["Reconnecting", true]);
  });

  describe("on the clock", () => {
    beforeEach(() => {
      vi.useFakeTimers({
        shouldAdvanceTime: true,
        toFake: ["setTimeout", "clearTimeout", "Date"],
      });
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("keeps the opened patient once the connection is given up", async () => {
      const { hub } = await connectedHome(
        "/#launch",
        { [SITI_READ]: json(SITI) },
        { socket: ["confirms", "closes"] },
      );
      publish(hub, "Patient-open", SITI.id);
      await screen.findByText("Siti Nurhaliza binte Ahmad");
      act(() => hub.dropSockets());

      await act(() => vi.advanceTimersByTimeAsync(RETRIES_RUN_OUT_MS));
      await waitFor(() => expect(fhircastStatus()).toHaveTextContent(/^Disconnected$/));

      expect(screen.queryByText("Siti Nurhaliza binte Ahmad")).toBeInTheDocument();
    });
  });

  it.each<[string, Answers, HubOptions, string]>([
    ["the backend releases nothing", { [RELEASE_REQUEST]: bare(404) }, {}, "FHIRcast unavailable"],
    [
      "the browser blocks the local hub",
      {},
      { fhircastConfiguration: "unreachable", localNetworkAccess: "denied" },
      "Local hub blocked by browser",
    ],
  ])("keeps the launch's patient when %s", async (_name, answers, options, shown) => {
    window.history.replaceState(null, "", `/#patient=${TAN_WEI_MING.id}`);
    adoptLaunchLanding();
    fakeHub(options);
    await renderSignedIn("/", holding(["patient:read"]), {
      [RELEASE_REQUEST]: json(HUB_RELEASE),
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
      ...answers,
    });

    await waitFor(() => expect(fhircastStatus()).toHaveTextContent(shown));
    expect(screen.queryByText("Tan Wei Ming")).toBeInTheDocument();
  });
});

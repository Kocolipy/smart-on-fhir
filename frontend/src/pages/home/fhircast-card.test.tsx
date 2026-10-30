import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AUTH_REQUESTS } from "@/auth/auth-context.testHelpers";
import { adoptLaunchLanding } from "@/auth/ehr-context";
import { bare, deferred, holding, json, type Answers } from "@/auth/session-adapter.testHelpers";
import {
  type FakeHub,
  fakeHub,
  HUB_RELEASE as RELEASE,
  type HubOptions,
  patientNotification as notification,
  REGISTRATION_CLIENT_URI,
  REGISTRATION_ENDPOINT,
} from "@/lib/fake-hub.testHelpers";

import { renderSignedIn } from "@/shell/signed-in-shell.testHelpers";

const RELEASE_REQUEST = "GET /api/auth/epic/fhircast";

/** What a hub or Epic might say about a refusal: never shown, never logged. */
const HUB_DETAIL = "registration refused: token 7f3a for practitioner Tan";

const SESSION = holding(["patient:read"]);

/** The tab loaded at an Epic landing that put no patient in context. */
const landedFromEpic = () => {
  window.history.replaceState(null, "", "/#launch");
  adoptLaunchLanding();
};

/**
 * Home for an Epic session, inside the signed-in layout that starts the
 * session's FHIRcast connection, the backend answering `answers` and the hub
 * `options`; the hub, beside what the page sent and the session.
 */
async function renderEpicHome(answers: Answers, options: HubOptions = {}) {
  landedFromEpic();
  const hub = fakeHub(options);
  return { hub, ...(await renderSignedIn("/", SESSION, answers)) };
}

/** The card's one status line. */
const status = () => screen.getByRole("status", { name: "FHIRcast" });

/**
 * The reads of the patients this suite's events open, which the Patient card
 * beside this card sends as it follows them: never answered, as what the
 * Patient card shows is its own suite's.
 */
const PATIENTS_OPENED: Answers = Object.fromEntries(
  ["patient-1", "patient-2", "patient-secret"].map((id) => [
    `GET /api/patient/${id}`,
    deferred().answer,
  ]),
);

/**
 * Home for an Epic session, connected to a hub answering `options`, the
 * backend answering `answers` beside the release; what the page sent with it.
 */
async function connectedHome(options: HubOptions = {}, answers: Answers = {}) {
  const { hub, requests, session } = await renderEpicHome(
    { [RELEASE_REQUEST]: json(RELEASE), ...PATIENTS_OPENED, ...answers },
    options,
  );
  await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));
  return Object.assign(hub, { pageRequests: requests, session });
}

/** Leaves Home for another signed-in page, the password change, by Home's own link. */
async function leaveHome() {
  fireEvent.click(screen.getByRole("link", { name: "Change password" }));
  await screen.findByRole("heading", { level: 1, name: "Change password" });
}

/** Comes back to Home by the page's back link. */
async function returnHome() {
  fireEvent.click(screen.getByRole("link", { name: "Back" }));
  await screen.findByRole("heading", { level: 1, name: "Home" });
}

/** Signs out by the layout's Sign out, and waits for the session to have ended. */
async function signOut(session: () => { status: string }) {
  fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
  await waitFor(() => expect(session().status).toBe("guest"));
}

/** The backend's answer to a logout that ends the session. */
const SIGNS_OUT: Answers = { [AUTH_REQUESTS.logout]: bare(204) };

/** Fake timers that still follow the real clock, so `waitFor` keeps polling. */
const onTheClock = () =>
  vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["setTimeout", "clearTimeout", "Date"] });

/** Every retry a dropped connection gets, each failing, until it is given up. */
const RETRIES_RUN_OUT_MS = 1_000 + 2_000 + 4_000 + 8_000 + 16_000 + 1_000;

/** A hub whose first socket confirms, then five that close, then one that confirms again. */
const RETRIES_FAIL_THEN_CONFIRM: HubOptions = {
  socket: ["confirms", "closes", "closes", "closes", "closes", "closes", "confirms"],
};

afterEach(() => {
  window.history.replaceState(null, "", "/");
  // A console spy left in place would collect what a later test writes.
  vi.restoreAllMocks();
});

describe("the FHIRcast card", () => {
  it("shows connecting while the release is on its way", async () => {
    const release = deferred();
    await renderEpicHome({ [RELEASE_REQUEST]: release.answer });

    expect(status()).toHaveTextContent(/^Connecting$/);
  });

  it("reaches connected once the hub confirms the subscription", async () => {
    await renderEpicHome({ [RELEASE_REQUEST]: json(RELEASE) });

    await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));
  });

  it("asks the backend for the release once", async () => {
    const { requests } = await renderEpicHome({ [RELEASE_REQUEST]: json(RELEASE) });
    await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));

    expect(requests()).toEqual([{ method: "GET", path: "/api/auth/epic/fhircast" }]);
  });

  it("shows FHIRcast unavailable when the backend releases nothing", async () => {
    await renderEpicHome({ [RELEASE_REQUEST]: bare(404) });

    await waitFor(() => expect(status()).toHaveTextContent(/^FHIRcast unavailable$/));
  });

  it("shows FHIRcast unavailable when the hub refuses, never what it said", async () => {
    const console = [
      vi.spyOn(globalThis.console, "error"),
      vi.spyOn(globalThis.console, "warn"),
      vi.spyOn(globalThis.console, "log"),
      vi.spyOn(globalThis.console, "info"),
    ];
    await renderEpicHome(
      { [RELEASE_REQUEST]: json(RELEASE) },
      {
        registration: {
          body: { error: "invalid_token", error_description: HUB_DETAIL },
          status: 401,
        },
      },
    );

    await waitFor(() => expect(status()).toHaveTextContent(/^FHIRcast unavailable$/));
    expect([
      document.body.textContent?.includes(HUB_DETAIL),
      console.flatMap((spy) => spy.mock.calls),
    ]).toEqual([false, []]);
  });

  it("shows local hub blocked by browser when the clinician refused Local Network Access", async () => {
    await renderEpicHome(
      { [RELEASE_REQUEST]: json(RELEASE) },
      { fhircastConfiguration: "unreachable", localNetworkAccess: "denied" },
    );

    await waitFor(() => expect(status()).toHaveTextContent(/^Local hub blocked by browser$/));
  });

  it("shows reconnecting once the hub drops a connection", async () => {
    const hub = await connectedHome();

    act(() => hub.dropSockets());

    expect(status()).toHaveTextContent(/^Reconnecting$/);
  });

  it("offers no retry while connected", async () => {
    await renderEpicHome({ [RELEASE_REQUEST]: json(RELEASE) });
    await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));

    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  });

  describe("on the clock", () => {
    beforeEach(onTheClock);

    afterEach(() => {
      vi.useRealTimers();
    });

    it("shows connected again once a retry wins the connection back", async () => {
      const hub = await connectedHome();
      act(() => hub.dropSockets());
      expect(status()).toHaveTextContent(/^Reconnecting$/);

      await act(() => vi.advanceTimersByTimeAsync(1_000));

      await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));
    });

    it("shows disconnected, with a retry, once the retries run out", async () => {
      const hub = await connectedHome({ socket: ["confirms", "closes"] });
      act(() => hub.dropSockets());

      await act(() => vi.advanceTimersByTimeAsync(RETRIES_RUN_OUT_MS));

      await waitFor(() =>
        expect([
          status().textContent,
          screen.queryByRole("button", { name: "Retry" }) !== null,
        ]).toEqual(["Disconnected", true]),
      );
    });

    it("shows connecting as soon as the clinician retries", async () => {
      const hub = await connectedHome(RETRIES_FAIL_THEN_CONFIRM);
      act(() => hub.dropSockets());
      await act(() => vi.advanceTimersByTimeAsync(RETRIES_RUN_OUT_MS));
      await waitFor(() => expect(status()).toHaveTextContent(/^Disconnected$/));

      fireEvent.click(screen.getByRole("button", { name: "Retry" }));
      const shownOnRetry = status().textContent;
      // Let the retry's connection settle, so nothing of it reaches the next test.
      await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));

      expect(shownOnRetry).toBe("Connecting");
    });

    it("connects again when the clinician retries, resuming the tab's one registration", async () => {
      const hub = await connectedHome(RETRIES_FAIL_THEN_CONFIRM);
      act(() => hub.dropSockets());
      await act(() => vi.advanceTimersByTimeAsync(RETRIES_RUN_OUT_MS));
      await waitFor(() => expect(status()).toHaveTextContent(/^Disconnected$/));

      fireEvent.click(screen.getByRole("button", { name: "Retry" }));

      await waitFor(() =>
        expect([status().textContent, hub.requestsTo(REGISTRATION_ENDPOINT).length]).toEqual([
          "Connected",
          1,
        ]),
      );
    });
  });

  it("keeps its socket open while Home stays", async () => {
    const hub = await connectedHome();

    expect(hub.openSockets()).toBe(1);
  });

  it("keeps its socket open while the clinician is on another page", async () => {
    const hub = await connectedHome();

    await leaveHome();

    expect(hub.openSockets()).toBe(1);
  });

  it("stays connected, registering nothing again, when the clinician leaves Home and comes back", async () => {
    const hub = await connectedHome();
    await leaveHome();

    await returnHome();

    expect([status().textContent, hub.requestsTo(REGISTRATION_ENDPOINT).length]).toEqual([
      "Connected",
      1,
    ]);
  });

  it("asks the backend for the release once however often Home mounts", async () => {
    const hub = await connectedHome();
    await leaveHome();
    await returnHome();

    expect(
      hub.pageRequests().filter(({ path }) => path === "/api/auth/epic/fhircast"),
    ).toHaveLength(1);
  });

  it("connects on whichever signed-in page the tab is on, Home or not", async () => {
    landedFromEpic();
    const hub = fakeHub();

    await renderSignedIn("/change-password", SESSION, {
      [RELEASE_REQUEST]: json(RELEASE),
    });

    await waitFor(() => expect(hub.openSockets()).toBe(1));
  });

  it("ends the session when the backend answers the release 401", async () => {
    const { session } = await renderEpicHome({ [RELEASE_REQUEST]: bare(401) });

    await waitFor(() => expect(session().status).toBe("guest"));
  });

  it("tears the connection down at the hub on sign-out", async () => {
    const hub = await connectedHome({}, SIGNS_OUT);

    await signOut(hub.session);

    await waitFor(() =>
      expect([hub.requestsTo(REGISTRATION_CLIENT_URI).length, hub.openSockets()]).toEqual([1, 0]),
    );
  });

  it("connects to nothing when the session ends before the release answers", async () => {
    const release = deferred();
    const { hub, session } = await renderEpicHome({
      [RELEASE_REQUEST]: release.answer,
      ...SIGNS_OUT,
    });

    await signOut(session);
    await release.settle(json(RELEASE));

    expect(hub.requests()).toEqual([]);
  });

  it("closes the socket a subscription confirms after the session has ended", async () => {
    let confirm = () => undefined as void;
    const { hub, session } = await renderEpicHome(
      { [RELEASE_REQUEST]: json(RELEASE), ...SIGNS_OUT },
      { hold: new Promise((resolve) => (confirm = resolve)) },
    );
    await waitFor(() => expect(hub.sockets()).toHaveLength(1));

    await signOut(session);
    confirm();

    await waitFor(() => expect(hub.openSockets()).toBe(0));
  });

  describe("the last event", () => {
    /** What the card shows of `notification("Patient-open")` with its defaults. */
    const SHOWN_OPEN = { Event: "Patient-open", Patient: "patient-1", Time: "2026-10-24 10:00" };

    /** What the card lists of the last event, each field's label beside its value. */
    const shown = () => {
      const list = screen.queryByRole("group", { name: "Last event" });
      if (list === null) return null;
      const values = within(list)
        .getAllByRole("definition")
        .map((value) => value.textContent);
      return Object.fromEntries(
        within(list)
          .getAllByRole("term")
          .map((label, index) => [label.textContent, values[index]]),
      );
    };

    const acknowledged = (hub: FakeHub) =>
      hub.received().map((sent) => JSON.parse(sent) as unknown);

    it("shows a Patient-open: its name, its time in Singapore, and the Patient id", async () => {
      const hub = await connectedHome();

      act(() => hub.notify(notification("Patient-open")));

      expect(shown()).toEqual(SHOWN_OPEN);
    });

    it("shows a Patient-close", async () => {
      const hub = await connectedHome();

      act(() => hub.notify(notification("Patient-close")));

      expect(shown()).toEqual({ ...SHOWN_OPEN, Event: "Patient-close" });
    });

    it.each(["Patient-open", "Patient-close"])("acknowledges a %s with its id", async (event) => {
      const hub = await connectedHome();

      act(() => hub.notify(notification(event, { id: "event-9" })));

      expect(acknowledged(hub)).toEqual([{ id: "event-9", status: "200" }]);
    });

    it("shows no event before the hub sends one", async () => {
      await connectedHome();

      expect(shown()).toBeNull();
    });

    it("replaces the shown event with a newer one", async () => {
      const hub = await connectedHome();
      act(() => hub.notify(notification("Patient-open", { id: "event-1" })));

      act(() =>
        hub.notify(
          notification("Patient-close", {
            id: "event-2",
            patientId: "patient-2",
            timestamp: "2026-10-24T03:30:00Z",
          }),
        ),
      );

      expect(shown()).toEqual({
        Event: "Patient-close",
        Patient: "patient-2",
        Time: "2026-10-24 11:30",
      });
    });

    it.each<[string, unknown]>([
      ["a malformed notification", { ...notification("Patient-open"), timestamp: undefined }],
      ["a notification on another topic", notification("Patient-open", { topic: "topic-2" })],
      ["an event not subscribed to", notification("Encounter-open")],
    ])("ignores %s, unacknowledged", async (_name, message) => {
      const hub = await connectedHome();

      act(() => hub.notify(message));

      expect([shown(), acknowledged(hub)]).toEqual([null, []]);
    });

    it("keeps showing the last event past one it ignores", async () => {
      const hub = await connectedHome();
      act(() => hub.notify(notification("Patient-open")));

      act(() => hub.notify(notification("Patient-close", { topic: "topic-2" })));

      expect(shown()).toEqual(SHOWN_OPEN);
    });

    it("omits a Patient id outside the grammar, still showing the event", async () => {
      const hub = await connectedHome();

      act(() => hub.notify(notification("Patient-open", { patientId: "patient/1" })));

      expect(shown()).toEqual({ Event: "Patient-open", Time: "2026-10-24 10:00" });
    });

    it("never renders markup a notification carries", async () => {
      const markup = "<b>Tan</b> <img src=x onerror=alert(1)>";
      const hub = await connectedHome();
      const open = notification("Patient-open", { id: markup, patientId: markup });

      act(() =>
        hub.notify({
          ...open,
          event: {
            ...open.event,
            context: [...open.event.context, { key: markup, resource: { id: markup } }],
          },
        }),
      );

      expect([
        shown(),
        document.querySelector("b, img"),
        document.body.textContent?.includes("Tan"),
      ]).toEqual([{ Event: "Patient-open", Time: "2026-10-24 10:00" }, null, false]);
    });

    it("logs nothing of an event's context", async () => {
      const hub = await connectedHome();
      const console = [
        vi.spyOn(globalThis.console, "error"),
        vi.spyOn(globalThis.console, "warn"),
        vi.spyOn(globalThis.console, "log"),
        vi.spyOn(globalThis.console, "info"),
        vi.spyOn(globalThis.console, "debug"),
      ];

      act(() => {
        hub.notify(notification("Patient-open", { patientId: "patient-secret" }));
        hub.notify(notification("Patient-open", { patientId: "patient/secret" }));
        hub.notify(notification("Patient-open", { patientId: "secret", topic: "topic-2" }));
        hub.notify("not json secret");
      });

      expect(console.flatMap((spy) => spy.mock.calls)).toEqual([]);
    });

    it("shows, back on Home, an event the hub sent while the clinician was on another page", async () => {
      const hub = await connectedHome();
      await leaveHome();

      act(() => hub.notify(notification("Patient-open")));
      await returnHome();

      expect(shown()).toEqual(SHOWN_OPEN);
    });

    it("keeps showing the last event while reconnecting", async () => {
      const hub = await connectedHome();
      act(() => hub.notify(notification("Patient-open")));

      act(() => hub.dropSockets());

      expect([status().textContent, shown()]).toEqual(["Reconnecting", SHOWN_OPEN]);
    });

    describe("on the clock", () => {
      beforeEach(onTheClock);

      afterEach(() => {
        vi.useRealTimers();
      });

      it("keeps showing the last event through a renewal", async () => {
        const hub = await connectedHome({ leaseSeconds: 200 });
        act(() => hub.notify(notification("Patient-open")));

        await act(() => vi.advanceTimersByTimeAsync(140_000));
        await waitFor(() => expect([hub.sockets().length, hub.openSockets()]).toEqual([2, 1]));

        expect([status().textContent, shown()]).toEqual(["Connected", SHOWN_OPEN]);
      });

      it("keeps showing the last event once reconnected", async () => {
        const hub = await connectedHome();
        act(() => hub.notify(notification("Patient-open")));
        act(() => hub.dropSockets());

        await act(() => vi.advanceTimersByTimeAsync(1_000));
        await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));

        expect(shown()).toEqual(SHOWN_OPEN);
      });

      it("shows no event once disconnected", async () => {
        const hub = await connectedHome({ socket: ["confirms", "closes"] });
        act(() => hub.notify(notification("Patient-open")));
        act(() => hub.dropSockets());

        await act(() => vi.advanceTimersByTimeAsync(RETRIES_RUN_OUT_MS));
        await waitFor(() => expect(status()).toHaveTextContent(/^Disconnected$/));

        expect(shown()).toBeNull();
      });

      it("shows no event after a retry until the hub sends one", async () => {
        const hub = await connectedHome(RETRIES_FAIL_THEN_CONFIRM);
        act(() => hub.notify(notification("Patient-open")));
        act(() => hub.dropSockets());
        await act(() => vi.advanceTimersByTimeAsync(RETRIES_RUN_OUT_MS));
        await waitFor(() => expect(status()).toHaveTextContent(/^Disconnected$/));

        fireEvent.click(screen.getByRole("button", { name: "Retry" }));
        await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));

        expect(shown()).toBeNull();
      });
    });
  });

  it("shows no card, and asks for nothing, in a session no EHR launch began", async () => {
    fakeHub();
    const { requests } = await renderSignedIn("/", SESSION);

    expect([screen.queryByRole("status", { name: "FHIRcast" }), requests()]).toEqual([null, []]);
  });
});

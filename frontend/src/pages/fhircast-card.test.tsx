import { act, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { adoptLaunchLanding } from "@/auth/launch-context";
import {
  bare,
  deferred,
  holding,
  json,
  renderAsSession,
  type Answers,
} from "@/auth/session-adapter.testHelpers";
import { type FakeHub, fakeHub, HUB_URL, type HubOptions } from "@/lib/fake-hub.testHelpers";

import { Home } from "./home";

/** The release the backend answers an Epic session that kept a hub. */
const RELEASE = {
  accessToken: `smart-${crypto.randomUUID()}`,
  expiresAt: "2026-10-24T10:00:00Z",
  hubTopic: "topic-1",
  hubUrl: HUB_URL,
  scope: ["fhircast/Patient-close.read", "fhircast/Patient-open.read", "openid"],
};

const RELEASE_REQUEST = "GET /api/auth/epic/fhircast";

/** What a hub or Epic might say about a refusal: never shown, never logged. */
const HUB_DETAIL = "registration refused: token 7f3a for practitioner Tan";

const SESSION = holding(["patient:read"]);

/** The tab loaded at an Epic landing that put no patient in context. */
const landedFromEpic = () => {
  window.history.replaceState(null, "", "/#launch");
  adoptLaunchLanding();
};

/** Home for an Epic session, the backend answering `answers` and the hub `hub`. */
async function renderEpicHome(answers: Answers, hub: HubOptions = {}) {
  landedFromEpic();
  fakeHub(hub);
  return renderAsSession(<Home pages={[]} />, SESSION, { answers });
}

/** The card's one status line. */
const status = () => screen.getByRole("status", { name: "FHIRcast" });

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

  it("shows FHIRcast unavailable once the hub drops a connection", async () => {
    landedFromEpic();
    const hub = fakeHub();
    await renderAsSession(<Home pages={[]} />, SESSION, {
      answers: { [RELEASE_REQUEST]: json(RELEASE) },
    });
    await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));

    hub.dropSockets();

    await waitFor(() => expect(status()).toHaveTextContent(/^FHIRcast unavailable$/));
  });

  it("closes its socket when Home goes", async () => {
    landedFromEpic();
    const hub = fakeHub();
    const { unmount } = await renderAsSession(<Home pages={[]} />, SESSION, {
      answers: { [RELEASE_REQUEST]: json(RELEASE) },
    });
    await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));

    unmount();

    expect(hub.openSockets()).toBe(0);
  });

  it("keeps its socket open while Home stays", async () => {
    landedFromEpic();
    const hub = fakeHub();
    await renderAsSession(<Home pages={[]} />, SESSION, {
      answers: { [RELEASE_REQUEST]: json(RELEASE) },
    });

    await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));
    expect(hub.openSockets()).toBe(1);
  });

  it("connects to nothing when Home goes before the release answers", async () => {
    landedFromEpic();
    const hub = fakeHub();
    const release = deferred();
    const { unmount } = await renderAsSession(<Home pages={[]} />, SESSION, {
      answers: { [RELEASE_REQUEST]: release.answer },
    });

    unmount();
    await release.settle(json(RELEASE));

    expect(hub.requests()).toEqual([]);
  });

  it("closes the socket a subscription confirms after Home has gone", async () => {
    landedFromEpic();
    let confirm = () => undefined as void;
    const hub = fakeHub({ hold: new Promise((resolve) => (confirm = resolve)) });
    const { unmount } = await renderAsSession(<Home pages={[]} />, SESSION, {
      answers: { [RELEASE_REQUEST]: json(RELEASE) },
    });
    await waitFor(() => expect(hub.sockets()).toHaveLength(1));

    unmount();
    confirm();

    await waitFor(() => expect(hub.openSockets()).toBe(0));
  });

  describe("the last event", () => {
    /** A notification on the session's topic: `event` for Patient `patientId`. */
    const notification = (
      event: string,
      {
        id = "event-1",
        patientId = "patient-1",
        timestamp = "2026-10-24T02:00:05Z",
        topic = "topic-1",
      } = {},
    ) => ({
      event: {
        context: [{ key: "patient", resource: { id: patientId, resourceType: "Patient" } }],
        "hub.event": event,
        "hub.topic": topic,
      },
      id,
      timestamp,
    });

    /** What the card shows of `notification("Patient-open")` with its defaults. */
    const SHOWN_OPEN = { Event: "Patient-open", Patient: "patient-1", Time: "2026-10-24 10:00" };

    /** Home for an Epic session, connected to the hub. */
    async function connectedHome() {
      landedFromEpic();
      const hub = fakeHub();
      await renderAsSession(<Home pages={[]} />, SESSION, {
        answers: { [RELEASE_REQUEST]: json(RELEASE) },
      });
      await waitFor(() => expect(status()).toHaveTextContent(/^Connected$/));
      return hub;
    }

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

    it("shows no event once the hub drops the connection", async () => {
      const hub = await connectedHome();
      act(() => hub.notify(notification("Patient-open")));

      act(() => hub.dropSockets());

      expect([status().textContent, shown()]).toEqual(["FHIRcast unavailable", null]);
    });
  });

  it("shows no card, and asks for nothing, in a session no EHR launch began", async () => {
    fakeHub();
    const { requests } = await renderAsSession(<Home pages={[]} />, SESSION);

    expect([screen.queryByRole("status", { name: "FHIRcast" }), requests()]).toEqual([null, []]);
  });
});

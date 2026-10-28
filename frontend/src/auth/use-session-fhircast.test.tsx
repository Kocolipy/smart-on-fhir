import { act, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { fakeHub, HUB_RELEASE, patientNotification } from "@/lib/fake-hub.testHelpers";
import { fhircastSnapshot } from "@/lib/fhircast";

import { adoptLaunchLanding, ehrContext } from "./ehr-context";
import {
  bare,
  GUEST,
  holding,
  json,
  renderAsSession,
  type Answers,
  type StartingSession,
} from "./session-adapter.testHelpers";
import { useSessionFhircast } from "./use-session-fhircast";

// The glue between the session and the FHIRcast module, run under the real
// `AuthProvider` over the fake backend and the fake hub: what it asks the
// backend for, whether the connection starts, what a refused release does to
// the session, and where the hub's events go.

const RELEASE_REQUEST = "GET /api/auth/epic/fhircast";

const SESSION = holding(["patient:read"]);

/** The hook, mounted as the signed-in layout mounts it. */
function SessionFhircast() {
  useSessionFhircast();
  return null;
}

/** The tab loaded at an Epic landing that put `patient` in context, or none. */
function landedFromEpic(patient?: string) {
  window.history.replaceState(
    null,
    "",
    patient === undefined ? "/#launch" : `/#patient=${patient}`,
  );
  adoptLaunchLanding();
}

/** The hook mounted for `session`, the backend answering `answers` and the hub its defaults. */
async function mounted(session: StartingSession, answers: Answers) {
  const hub = fakeHub();
  return { hub, ...(await renderAsSession(<SessionFhircast />, session, { answers })) };
}

const connected = () => waitFor(() => expect(fhircastSnapshot().status).toBe("connected"));

afterEach(() => {
  window.history.replaceState(null, "", "/");
});

describe("useSessionFhircast", () => {
  it("asks the backend for the release once, for a signed-in session in an EHR context", async () => {
    landedFromEpic();
    const { requests } = await mounted(SESSION, { [RELEASE_REQUEST]: json(HUB_RELEASE) });
    await connected();

    expect(requests()).toEqual([{ method: "GET", path: "/api/auth/epic/fhircast" }]);
  });

  it("connects to the hub the release names", async () => {
    landedFromEpic();
    const { hub } = await mounted(SESSION, { [RELEASE_REQUEST]: json(HUB_RELEASE) });
    await connected();

    expect(hub.openSockets()).toBe(1);
  });

  it("asks for no release, and connects nothing, for a session the tab holds no EHR context for", async () => {
    const { hub, requests } = await mounted(SESSION, { [RELEASE_REQUEST]: json(HUB_RELEASE) });
    await act(async () => undefined);

    expect({ requests: requests(), sockets: hub.sockets() }).toEqual({ requests: [], sockets: [] });
  });

  it("asks for no release, and connects nothing, for a guest", async () => {
    landedFromEpic();
    const { hub, requests } = await mounted(GUEST, { [RELEASE_REQUEST]: json(HUB_RELEASE) });
    await act(async () => undefined);

    expect({ requests: requests(), sockets: hub.sockets() }).toEqual({ requests: [], sockets: [] });
  });

  it("starts once the tab comes to hold an EHR context", async () => {
    const { requests } = await mounted(SESSION, { [RELEASE_REQUEST]: json(HUB_RELEASE) });
    expect(requests()).toEqual([]);

    act(() => landedFromEpic());
    await connected();

    expect(requests()).toEqual([{ method: "GET", path: "/api/auth/epic/fhircast" }]);
  });

  it("shows FHIRcast unavailable when the backend releases nothing", async () => {
    landedFromEpic();
    const { hub } = await mounted(SESSION, { [RELEASE_REQUEST]: bare(404) });

    await waitFor(() => expect(fhircastSnapshot().status).toBe("unavailable"));
    expect(hub.sockets()).toEqual([]);
  });

  it("shows FHIRcast unavailable when the release does not decode", async () => {
    landedFromEpic();
    const { hub } = await mounted(SESSION, {
      [RELEASE_REQUEST]: json({ ...HUB_RELEASE, hubUrl: undefined }),
    });

    await waitFor(() => expect(fhircastSnapshot().status).toBe("unavailable"));
    expect(hub.sockets()).toEqual([]);
  });

  it("ends the session as expired when the release is refused 401", async () => {
    landedFromEpic();
    const { session } = await mounted(SESSION, { [RELEASE_REQUEST]: bare(401) });

    await waitFor(() => expect(session()).toEqual({ signInReason: "expired", status: "guest" }));
  });

  it("moves the tab's EHR context to the patient a Patient-open names", async () => {
    landedFromEpic("patient-1");
    const { hub } = await mounted(SESSION, { [RELEASE_REQUEST]: json(HUB_RELEASE) });
    await connected();

    act(() => hub.notify(patientNotification("Patient-open", { patientId: "patient-2" })));

    await waitFor(() => expect(ehrContext()).toEqual({ patient: "patient-2" }));
  });

  it("empties the tab's EHR context on a Patient-close", async () => {
    landedFromEpic("patient-1");
    const { hub } = await mounted(SESSION, { [RELEASE_REQUEST]: json(HUB_RELEASE) });
    await connected();

    act(() => hub.notify(patientNotification("Patient-close")));

    await waitFor(() => expect(ehrContext()).toEqual({}));
  });

  it("moves the EHR context no more once unmounted", async () => {
    landedFromEpic("patient-1");
    const { hub, unmount } = await mounted(SESSION, { [RELEASE_REQUEST]: json(HUB_RELEASE) });
    await connected();
    unmount();

    hub.notify(patientNotification("Patient-open", { patientId: "patient-2" }));
    await act(async () => undefined);

    expect(ehrContext()).toEqual({ patient: "patient-1" });
  });
});

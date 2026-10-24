import { screen, waitFor } from "@testing-library/react";
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
import { fakeHub, HUB_URL, type HubOptions } from "@/lib/fake-hub.testHelpers";

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

  it("shows no card, and asks for nothing, in a session no EHR launch began", async () => {
    fakeHub();
    const { requests } = await renderAsSession(<Home pages={[]} />, SESSION);

    expect([screen.queryByRole("status", { name: "FHIRcast" }), requests()]).toEqual([null, []]);
  });
});

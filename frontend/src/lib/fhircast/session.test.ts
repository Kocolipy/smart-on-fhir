import { describe, expect, it, onTestFinished, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  type FakeHub,
  HUB_URL,
  type HubAnswer,
  hubOnly,
  requestsSince,
} from "../fake-hub.testHelpers";
import {
  endFhircast,
  fhircastSnapshot,
  followFhircastEvents,
  type FhircastReleaseSource,
  retryFhircast,
  startFhircast,
  watchFhircast,
} from ".";
import {
  eventually,
  keptState,
  PATIENT_OPEN,
  realSetTimeout,
  RELEASE,
  releasing,
} from "./fhircast.testHelpers";

// The session's one FHIRcast connection (`startFhircast`, `watchFhircast`,
// `retryFhircast`, `followFhircastEvents`): started once, ended with the session.

/** How many subscriptions `hub` was asked for: unsubscriptions are posted to the hub URL too. */
const subscribed = (hub: FakeHub) =>
  hub
    .requestsTo(HUB_URL)
    .filter(({ body }) => new URLSearchParams(body).get("hub.mode") === "subscribe").length;

describe("the session's FHIRcast connection", () => {
  /** What the connection shows, once it says connected. */
  const connectedNow = () => eventually(() => expect(fhircastSnapshot().status).toBe("connected"));

  it("connects anew on a start once the session's FHIRcast has ended", async () => {
    const hub = hubOnly();
    startFhircast(releasing);
    await connectedNow();
    await endFhircast();

    startFhircast(releasing);
    await connectedNow();

    expect(subscribed(hub)).toBe(2);
  });

  it("shows nothing of the ended session's connection to the next", async () => {
    const hub = hubOnly();
    startFhircast(releasing);
    await connectedNow();
    hub.notify(PATIENT_OPEN);

    await endFhircast();

    expect(fhircastSnapshot()).toEqual({ last: null, status: "connecting" });
  });

  it("asks for the release once however often it is started", async () => {
    hubOnly();
    const source = vi.fn(releasing);

    startFhircast(source);
    startFhircast(source);
    await connectedNow();

    expect(source).toHaveBeenCalledOnce();
  });

  it("closes the connection before when a retry connects again", async () => {
    const hub = hubOnly();
    startFhircast(releasing);
    await connectedNow();

    retryFhircast();
    await connectedNow();

    expect([hub.sockets().length, hub.openSockets()]).toEqual([2, 1]);
  });

  it("closes a connection a retry replaced while it was still connecting, once it connects", async () => {
    let confirmFirst = () => undefined as void;
    const hub = hubOnly({ hold: [new Promise((resolve) => (confirmFirst = resolve)), null] });
    startFhircast(releasing);
    await eventually(() => expect(hub.sockets()).toHaveLength(1));
    retryFhircast();
    await connectedNow();

    confirmFirst();

    await eventually(() => expect(hub.openSockets()).toBe(1));
  });

  it("keeps the subscription of the retry's connection, not of the one it replaced", async () => {
    let confirmFirst = () => undefined as void;
    const hub = hubOnly({ hold: [new Promise((resolve) => (confirmFirst = resolve)), null] });
    startFhircast(releasing);
    await eventually(() => expect(hub.sockets()).toHaveLength(1));
    retryFhircast();
    await connectedNow();

    confirmFirst();
    await eventually(() => expect(hub.openSockets()).toBe(1));

    expect(keptState()?.subscription).toMatchObject({
      endpoint: CHANNEL_ENDPOINT.replace(/1$/, "2"),
    });
  });

  it("resumes the tab's subscription on a retry, subscribing nothing anew", async () => {
    const hub = hubOnly();
    startFhircast(releasing);
    await connectedNow();

    retryFhircast();
    await connectedNow();

    expect([subscribed(hub), hub.sockets()]).toEqual([1, [CHANNEL_ENDPOINT, CHANNEL_ENDPOINT]]);
  });

  it("fetches the release again on a retry", async () => {
    hubOnly();
    const source = vi.fn(releasing);
    startFhircast(source);
    await connectedNow();

    retryFhircast();
    await connectedNow();

    expect(source).toHaveBeenCalledTimes(2);
  });

  it("connects again from the start on a retry after the hub refused", async () => {
    const hub = hubOnly({ subscription: { status: 401 } });
    startFhircast(releasing);
    await eventually(() => expect(fhircastSnapshot().status).toBe("unavailable"));

    retryFhircast();

    await eventually(() => expect(subscribed(hub)).toBe(2));
  });

  it("shows no event the hub sends while the ended session's connection is torn down", async () => {
    const never = new Promise<HubAnswer>(() => undefined);
    const hub = hubOnly({ unsubscription: never });
    startFhircast(releasing);
    await connectedNow();
    void endFhircast();

    hub.notify(PATIENT_OPEN);

    expect(fhircastSnapshot().last).toBeNull();
  });

  it("shows nothing the ended session's connection says of a socket the hub drops as the session ends", async () => {
    const hub = hubOnly();
    startFhircast(releasing);
    await connectedNow();

    const ending = endFhircast();
    hub.dropSockets();
    await ending;

    expect(fhircastSnapshot().status).toBe("connecting");
  });

  it("tells a watcher what it shows, until the watcher stops watching", async () => {
    const hub = hubOnly();
    const watcher = vi.fn();
    const stop = watchFhircast(watcher);
    startFhircast(releasing);
    await connectedNow();
    stop();
    const heard = watcher.mock.calls.length;

    hub.notify(PATIENT_OPEN);

    expect([heard > 0, watcher.mock.calls.length]).toEqual([true, heard]);
  });

  it("hands a follower each event the hub sends", async () => {
    const hub = hubOnly();
    const follower = vi.fn();
    const stop = followFhircastEvents(follower);
    startFhircast(releasing);
    await connectedNow();

    hub.notify(PATIENT_OPEN);
    stop();

    expect(follower.mock.calls).toEqual([
      [{ event: "Patient-open", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" }],
    ]);
  });

  it("hands a follower nothing once it stops following", async () => {
    const hub = hubOnly();
    const follower = vi.fn();
    followFhircastEvents(follower)();
    startFhircast(releasing);
    await connectedNow();

    hub.notify(PATIENT_OPEN);

    expect(follower).not.toHaveBeenCalled();
  });

  it("hands a follower no event the hub sends while the ended session's connection is torn down", async () => {
    const never = new Promise<HubAnswer>(() => undefined);
    const hub = hubOnly({ unsubscription: never });
    const follower = vi.fn();
    const stop = followFhircastEvents(follower);
    startFhircast(releasing);
    await connectedNow();
    void endFhircast();

    hub.notify(PATIENT_OPEN);
    stop();

    expect(follower).not.toHaveBeenCalled();
  });

  it("shows ended on a retry the backend releases nothing to once the token's expiry has passed, asking the hub nothing", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    onTestFinished(() => {
      vi.useRealTimers();
    });
    vi.setSystemTime(Date.UTC(2026, 9, 24, 2, 0, 0));
    const hub = hubOnly();
    const source = vi
      .fn<FhircastReleaseSource>()
      .mockResolvedValueOnce({ ...RELEASE, expiresAt: "2026-10-24T03:00:00Z" })
      .mockResolvedValue(null);
    startFhircast(source);
    await connectedNow();
    const since = requestsSince(hub);
    // Past the expiry without its timer having run, as a workstation waking from sleep.
    vi.setSystemTime(Date.UTC(2026, 9, 24, 3, 0, 0));

    retryFhircast();

    await eventually(() => expect(fhircastSnapshot().status).toBe("ended"));
    expect(since()).toEqual([]);
  });

  it("connects nothing on a retry before any start", async () => {
    const hub = hubOnly();

    retryFhircast();
    await new Promise((resolve) => realSetTimeout(resolve, 0));

    expect([fhircastSnapshot(), hub.requests()]).toEqual([
      { last: null, status: "connecting" },
      [],
    ]);
  });
});

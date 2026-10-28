import { describe, expect, it, vi } from "vitest";

import { hubOnly } from "../fake-hub.testHelpers";
import { connectFhircast } from ".";
import { notificationOf, verdictOf } from "./notifications";
import { RELEASE } from "./fhircast.testHelpers";

// `connectFhircast`, on a connected socket: the event notifications the hub
// sends, each held to the FHIRcast STU3 shape, acknowledged and handed on.

describe("connectFhircast", () => {
  describe("event notifications", () => {
    /** A notification as the hub sends it: `event` for Patient `patientId` on `topic`. */
    const notification = (
      event: string,
      { id = "event-1", patientId = "patient-1" as unknown, topic = "topic-1" } = {},
    ) => ({
      event: {
        context: [{ key: "patient", resource: { id: patientId, resourceType: "Patient" } }],
        "hub.event": event,
        "hub.topic": topic,
      },
      id,
      timestamp: "2026-10-24T02:00:05Z",
    });

    /** Connected to a hub, every event it hands on recorded. */
    async function connected() {
      const hub = hubOnly();
      const events = vi.fn();
      const outcome = await connectFhircast(RELEASE, { onEvent: events });
      expect(outcome.kind).toBe("connected");
      return { events, hub };
    }

    it("hands on a Patient-open with its timestamp and Patient id", async () => {
      const { events, hub } = await connected();

      hub.notify(notification("Patient-open"));

      expect(events.mock.calls).toEqual([
        [{ event: "Patient-open", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" }],
      ]);
    });

    it("hands on a Patient-close", async () => {
      const { events, hub } = await connected();

      hub.notify(notification("Patient-close"));

      expect(events.mock.calls).toEqual([
        [{ event: "Patient-close", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" }],
      ]);
    });

    it.each(["Patient-open", "Patient-close"])(
      "acknowledges a %s on the socket with its id",
      async (event) => {
        const { hub } = await connected();

        hub.notify(notification(event, { id: "event-7" }));

        expect(hub.received().map((sent) => JSON.parse(sent) as unknown)).toEqual([
          { id: "event-7", status: "200" },
        ]);
      },
    );

    it("hands on every event, in the order the hub sent them", async () => {
      const { events, hub } = await connected();

      hub.notify(notification("Patient-open", { id: "event-1" }));
      hub.notify(notification("Patient-close", { id: "event-2" }));

      expect(events.mock.calls.map(([handed]) => (handed as { event: string }).event)).toEqual([
        "Patient-open",
        "Patient-close",
      ]);
    });

    it("reads the event name whatever its case", async () => {
      const { events, hub } = await connected();

      hub.notify(notification("pATIENT-oPEN"));

      expect(events.mock.calls[0]).toEqual([
        { event: "Patient-open", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" },
      ]);
    });

    it("takes the Patient id from the context's patient entry", async () => {
      const { events, hub } = await connected();
      const open = notification("Patient-open");

      hub.notify({
        ...open,
        event: {
          ...open.event,
          context: [
            { key: "encounter", resource: { id: "encounter-1", resourceType: "Encounter" } },
            ...open.event.context,
          ],
        },
      });

      expect(events.mock.calls[0]).toEqual([
        { event: "Patient-open", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" },
      ]);
    });

    it.each([
      ["the grammar's every character", "Ab-9.z"],
      ["sixty-four characters", "a".repeat(64)],
    ])("hands on a Patient id of %s", async (_name, patientId) => {
      const { events, hub } = await connected();

      hub.notify(notification("Patient-open", { patientId }));

      expect(events.mock.calls[0]).toEqual([
        { event: "Patient-open", patientId, timestamp: "2026-10-24T02:00:05Z" },
      ]);
    });

    describe("a Patient id outside the grammar", () => {
      it.each<[string, unknown]>([
        ["sixty-five characters", "a".repeat(65)],
        ["empty", ""],
        ["holding a slash", "patient/1"],
        ["holding markup", "<b>Tan</b>"],
        ["holding an underscore", "patient_1"],
        ["a number", 7],
      ])("is omitted when it is %s, the event still handed on", async (_name, patientId) => {
        const { events, hub } = await connected();

        hub.notify(notification("Patient-open", { patientId }));

        expect(events.mock.calls).toEqual([
          [{ event: "Patient-open", patientId: null, timestamp: "2026-10-24T02:00:05Z" }],
        ]);
      });

      it("is still acknowledged", async () => {
        const { hub } = await connected();

        hub.notify(notification("Patient-open", { id: "event-3", patientId: "patient/1" }));

        expect(hub.received().map((sent) => JSON.parse(sent) as unknown)).toEqual([
          { id: "event-3", status: "200" },
        ]);
      });

      it.each<[string, unknown[]]>([
        ["names no patient", [{ key: "encounter", resource: { id: "encounter-1" } }]],
        ["is empty", []],
        ["has a patient entry with no resource", [{ key: "patient" }]],
      ])("is omitted when the context %s", async (_name, context) => {
        const { events, hub } = await connected();
        const open = notification("Patient-open");

        hub.notify({ ...open, event: { ...open.event, context } });

        expect(events.mock.calls).toEqual([
          [{ event: "Patient-open", patientId: null, timestamp: "2026-10-24T02:00:05Z" }],
        ]);
      });
    });

    describe("what is ignored, and not acknowledged", () => {
      const open = notification("Patient-open");
      const without = (key: string) =>
        Object.fromEntries(Object.entries(open).filter(([member]) => member !== key));
      const eventWithout = (key: string) => ({
        ...open,
        event: Object.fromEntries(Object.entries(open.event).filter(([member]) => member !== key)),
      });

      it.each<[string, unknown]>([
        ["no JSON", "not json"],
        ["JSON null", "null"],
        ["a JSON list", JSON.stringify([open])],
        ["no id", without("id")],
        ["an empty id", { ...open, id: "" }],
        ["an id that is no string", { ...open, id: 7 }],
        ["no timestamp", without("timestamp")],
        ["a timestamp that is no time", { ...open, timestamp: "yesterday" }],
        ["no event", without("event")],
        ["an event that is no object", { ...open, event: "Patient-open" }],
        ["no hub.topic", eventWithout("hub.topic")],
        ["no hub.event", eventWithout("hub.event")],
        ["no context", eventWithout("context")],
        ["a context that is no list", { ...open, event: { ...open.event, context: {} } }],
        ["the subscription confirmed again", { "hub.mode": "subscribe", "hub.topic": "topic-1" }],
        ["another topic", notification("Patient-open", { topic: "topic-2" })],
        ["the topic in another case", notification("Patient-open", { topic: "TOPIC-1" })],
        ["an event not subscribed to", notification("Encounter-open")],
        ["the hub's heartbeat", notification("heartbeat")],
      ])("a message with %s", async (_name, message) => {
        const { events, hub } = await connected();

        hub.notify(message);

        expect([events.mock.calls, hub.received()]).toEqual([[], []]);
      });
    });

    it("hands on nothing the hub sends before it confirms the subscription", async () => {
      let confirm = () => undefined as void;
      const hub = hubOnly({ hold: new Promise((resolve) => (confirm = resolve)) });
      const events = vi.fn();
      const outcome = connectFhircast(RELEASE, { onEvent: events });
      await vi.waitFor(() => expect(hub.sockets()).toHaveLength(1));

      hub.notify(notification("Patient-open"));
      confirm();
      await outcome;

      expect([events.mock.calls, hub.received()]).toEqual([[], []]);
    });
  });
});

// The decoders themselves, read without a session: no connection is made, so
// nothing here needs the session's FHIRcast reset.

describe("verdictOf", () => {
  it("reads a confirmation, with the lease it grants", () => {
    expect(verdictOf(JSON.stringify({ "hub.lease_seconds": 90, "hub.mode": "subscribe" }))).toEqual(
      { lease: 90 },
    );
  });

  it("reads a confirmation granting no usable lease as one with no lease", () => {
    expect(verdictOf(JSON.stringify({ "hub.lease_seconds": 0, "hub.mode": "subscribe" }))).toEqual({
      lease: null,
    });
  });

  it("reads a denial", () => {
    expect(verdictOf(JSON.stringify({ "hub.mode": "denied" }))).toBe("denied");
  });

  it.each([
    ["another mode", JSON.stringify({ "hub.mode": "unsubscribe" })],
    ["no JSON", "subscribe"],
  ])("reads %s as saying nothing", (_name, data) => {
    expect(verdictOf(data)).toBeNull();
  });
});

describe("notificationOf", () => {
  const TOPIC = "topic-1";

  /** A notification as the hub sends it, with `changes` made to it. */
  const sent = (changes: { event?: Record<string, unknown>; [key: string]: unknown } = {}) =>
    JSON.stringify({
      id: "event-1",
      timestamp: "2026-10-24T02:00:05Z",
      ...changes,
      event: {
        context: [{ key: "patient", resource: { id: "patient-1", resourceType: "Patient" } }],
        "hub.event": "Patient-open",
        "hub.topic": TOPIC,
        ...changes.event,
      },
    });

  it("reads a notification: its id, and the event it carries", () => {
    expect(notificationOf(sent(), TOPIC)).toEqual({
      event: { event: "Patient-open", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" },
      id: "event-1",
    });
  });

  it("names the event as subscribed, whatever its case", () => {
    expect(
      notificationOf(sent({ event: { "hub.event": "PATIENT-CLOSE" } }), TOPIC)?.event.event,
    ).toBe("Patient-close");
  });

  it("names no Patient whose id is outside the grammar", () => {
    const context = [{ key: "patient", resource: { id: "patient/1", resourceType: "Patient" } }];

    expect(notificationOf(sent({ event: { context } }), TOPIC)?.event.patientId).toBeNull();
  });

  it.each<[string, Parameters<typeof sent>[0]]>([
    ["another topic", { event: { "hub.topic": "topic-2" } }],
    ["an event not subscribed to", { event: { "hub.event": "Encounter-open" } }],
    ["an empty id", { id: "" }],
    ["a timestamp that is no instant", { timestamp: "yesterday" }],
  ])("reads nothing from a notification on %s", (_name, changes) => {
    expect(notificationOf(sent(changes), TOPIC)).toBeNull();
  });
});

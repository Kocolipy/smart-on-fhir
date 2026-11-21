import { describe, expect, it, vi } from "vitest";

import { hubOnly } from "../fake-hub.testHelpers";
import { fhircastSnapshot, startFhircast } from ".";
import { verdictOf } from "./notifications";
import { eventually, followed, releasing, startedWith } from "./fhircast.testHelpers";

// The session's FHIRcast connection, on a connected socket: the event
// notifications the hub sends, each held to the FHIRcast STU2 shape,
// acknowledged and handed on.

describe("the session's FHIRcast connection", () => {
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
      const events = followed();
      expect(await startedWith()).toBe("connected");
      return { events, hub };
    }

    it("hands on a patient-open with its timestamp and Patient id", async () => {
      const { events, hub } = await connected();

      hub.notify(notification("patient-open"));

      expect(events.mock.calls).toEqual([
        [{ event: "patient-open", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" }],
      ]);
    });

    it("hands on a patient-close", async () => {
      const { events, hub } = await connected();

      hub.notify(notification("patient-close"));

      expect(events.mock.calls).toEqual([
        [{ event: "patient-close", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" }],
      ]);
    });

    it.each(["patient-open", "patient-close"])(
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

      hub.notify(notification("patient-open", { id: "event-1" }));
      hub.notify(notification("patient-close", { id: "event-2" }));

      expect(events.mock.calls.map(([handed]) => (handed as { event: string }).event)).toEqual([
        "patient-open",
        "patient-close",
      ]);
    });

    it.each([
      ["Patient-open", "patient-open"],
      ["PATIENT-CLOSE", "patient-close"],
    ])("hands on a %s as the subscribed %s", async (sent, subscribed) => {
      const { events, hub } = await connected();

      hub.notify(notification(sent));

      expect(events.mock.calls[0]).toEqual([
        { event: subscribed, patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" },
      ]);
    });

    it("takes the Patient id from the context's patient entry", async () => {
      const { events, hub } = await connected();
      const open = notification("patient-open");

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
        { event: "patient-open", patientId: "patient-1", timestamp: "2026-10-24T02:00:05Z" },
      ]);
    });

    it.each([
      ["the grammar's every character", "Ab-9.z"],
      ["sixty-four characters", "a".repeat(64)],
    ])("hands on a Patient id of %s", async (_name, patientId) => {
      const { events, hub } = await connected();

      hub.notify(notification("patient-open", { patientId }));

      expect(events.mock.calls[0]).toEqual([
        { event: "patient-open", patientId, timestamp: "2026-10-24T02:00:05Z" },
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

        hub.notify(notification("patient-open", { patientId }));

        expect(events.mock.calls).toEqual([
          [{ event: "patient-open", patientId: null, timestamp: "2026-10-24T02:00:05Z" }],
        ]);
      });

      it("is still acknowledged", async () => {
        const { hub } = await connected();

        hub.notify(notification("patient-open", { id: "event-3", patientId: "patient/1" }));

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
        const open = notification("patient-open");

        hub.notify({ ...open, event: { ...open.event, context } });

        expect(events.mock.calls).toEqual([
          [{ event: "patient-open", patientId: null, timestamp: "2026-10-24T02:00:05Z" }],
        ]);
      });
    });

    describe("what is ignored, and not acknowledged", () => {
      const open = notification("patient-open");
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
        ["an event that is no object", { ...open, event: "patient-open" }],
        ["no hub.topic", eventWithout("hub.topic")],
        ["no hub.event", eventWithout("hub.event")],
        ["no context", eventWithout("context")],
        ["a context that is no list", { ...open, event: { ...open.event, context: {} } }],
        ["the subscription confirmed again", { "hub.mode": "subscribe", "hub.topic": "topic-1" }],
        ["another topic", notification("patient-open", { topic: "topic-2" })],
        ["the topic in another case", notification("patient-open", { topic: "TOPIC-1" })],
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
      const events = followed();
      startFhircast(releasing);
      await vi.waitFor(() => expect(hub.sockets()).toHaveLength(1));

      hub.notify(notification("patient-open"));
      confirm();
      await eventually(() => expect(fhircastSnapshot().status).toBe("connected"));

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

  it("reads the lease a confirmation names hub.lease-seconds", () => {
    expect(verdictOf(JSON.stringify({ "hub.lease-seconds": 90, "hub.mode": "subscribe" }))).toEqual(
      { lease: 90 },
    );
  });

  it("reads hub.lease_seconds when a confirmation names both spellings", () => {
    expect(
      verdictOf(
        JSON.stringify({
          "hub.lease-seconds": 30,
          "hub.lease_seconds": 90,
          "hub.mode": "subscribe",
        }),
      ),
    ).toEqual({ lease: 90 });
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

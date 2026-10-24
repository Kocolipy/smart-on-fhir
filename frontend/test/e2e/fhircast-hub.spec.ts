import { randomUUID } from "node:crypto";

import { expect, test } from "@playwright/test";

import {
  expectStandInConfigured,
  publishPatientEvent,
  subscribeDirectly,
  TAN_WEI_MING,
} from "./fhircast.helpers";

/**
 * The publishing helper, proved against the stand-in's hub: a websocket
 * subscriber of its own, subscribed straight to Medplum's hub through the
 * shim, receives the `Patient-open` the helper publishes to its topic. The
 * FHIRcast card's specs (#187 on) publish through the same helper.
 *
 * It runs only in the `fhircast` project, which `make integration-test`
 * declares when it starts the stand-in.
 */

test("a Patient-open published to a topic reaches a websocket subscriber of that topic", async () => {
  expectStandInConfigured();
  const topic = `e2e-${randomUUID()}`;
  const subscriber = await subscribeDirectly(topic, ["Patient-open", "Patient-close"]);
  try {
    await subscriber.next((message) => message["hub.mode"] === "subscribe");

    const id = await publishPatientEvent(topic, "Patient-open", TAN_WEI_MING);

    const notification = await subscriber.next((message) => message.id === id);
    expect(notification.event).toEqual({
      "hub.topic": topic,
      "hub.event": "Patient-open",
      context: [{ key: "patient", resource: { resourceType: "Patient", id: TAN_WEI_MING } }],
      "context.versionId": expect.any(String),
    });
  } finally {
    subscriber.close();
  }
});

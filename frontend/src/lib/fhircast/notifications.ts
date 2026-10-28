/**
 * FHIRcast event notifications: the socket at a subscription's channel
 * endpoint, and what is read off it. The hub's confirmation (or denial) of the
 * subscription, then every later message held to the FHIRcast STU3
 * notification shape, the two subscribed events, the session's topic and the
 * Patient id grammar; a notification that passes is acknowledged on the
 * socket and handed on as a {@link PatientEvent}, anything else ignored,
 * unanswered. Nothing a message carries is logged. Beside the HTTP transport,
 * one of the modules allowed a raw `WebSocket` (`test/arch/rawNetwork.test.ts`).
 */

import { readObject } from "../decode";
import { isInstant } from "../time";
import { memberOf, PATIENT_EVENTS, type PatientEvent, secondsIn } from "./model";

/** The Patient FHIR id grammar: anything else is never handed on. */
const PATIENT_ID = /^[A-Za-z0-9\-.]{1,64}$/;

/**
 * The `status` of an acknowledgement. STU3 is inconsistent on string or
 * number; the hub (Medplum's STU3 socket) accepts any JSON, and this is the
 * spec's example.
 */
const ACKNOWLEDGED = "200";

/** How long the hub has to confirm the subscription on the socket. */
const CONFIRMATION_TIMEOUT_MS = 10_000;

/**
 * What a socket message says of the subscription: JSON whose `hub.mode` is
 * `subscribe` confirms it, granting the lease its `hub.lease_seconds` names;
 * one whose `hub.mode` is `denied` refuses it, as a hub does at an endpoint it
 * no longer knows. Anything else, JSON or not, says nothing: `null`.
 */
export function verdictOf(data: unknown): { lease: number | null } | "denied" | null {
  try {
    const message: unknown = JSON.parse(String(data));
    const mode = memberOf(message, "hub.mode");
    if (mode === "subscribe") return { lease: secondsIn(memberOf(message, "hub.lease_seconds")) };
    return mode === "denied" ? "denied" : null;
  } catch {
    return null;
  }
}

/** A notification worth handing on: its `id`, to acknowledge, and the event it carries. */
interface Notification {
  id: string;
  event: PatientEvent;
}

/**
 * The Patient FHIR id in an event's `context`: the `id` of the resource under
 * the `patient` key, or `null` when there is none or it is outside the grammar.
 */
function patientIdIn(context: readonly unknown[]): string | null {
  const entry: unknown = context.find((candidate) => memberOf(candidate, "key") === "patient");
  const id: unknown = memberOf(memberOf(entry, "resource"), "id");
  return typeof id === "string" && PATIENT_ID.test(id) ? id : null;
}

/**
 * A socket message read as a FHIRcast STU3 event notification: `timestamp`,
 * `id`, and an `event` with `hub.topic`, `hub.event` and `context`, the event
 * one of the two subscribed to and the topic the session's. Anything else is
 * `null`, and what was wrong with it is dropped unread.
 */
export function notificationOf(data: unknown, topic: string): Notification | null {
  try {
    const message = readObject(JSON.parse(String(data)), "notification");
    const id = message.string("id");
    const timestamp = message.string("timestamp");
    const event = message.object("event");
    const receivedEvent = event.string("hub.event").toLowerCase();
    const subscribedEvent = PATIENT_EVENTS.find((known) => known.toLowerCase() === receivedEvent);
    const context = event.array("context", (entry) => entry);
    if (
      id === "" ||
      !isInstant(timestamp) ||
      subscribedEvent === undefined ||
      event.string("hub.topic") !== topic
    ) {
      return null;
    }
    return { event: { event: subscribedEvent, patientId: patientIdIn(context), timestamp }, id };
  } catch {
    return null;
  }
}

/** A socket the hub confirmed the subscription on: the lease it granted, and how to close it. */
export interface Channel {
  lease: number | null;
  close: () => void;
}

/**
 * How opening a socket came out: the {@link Channel} the hub confirmed;
 * `denied`, when the hub refused the endpoint; or `failed`, when the socket
 * closed, or stayed silent, before either.
 */
export type Opened = Channel | "denied" | "failed";

/**
 * Step 5: the socket, {@link Opened} as the hub answered it. A browser fires `close` after any socket `error`, so `close`
 * alone is listened for. Once confirmed, a `close` the hub causes is reported
 * through `onDropped`; one the caller asks for is not. A confirmed socket's
 * every other message is held to {@link notificationOf}: a notification that
 * passes is acknowledged on the socket and handed to `onEvent`; anything else
 * is ignored, unacknowledged.
 */
export function open(
  endpoint: string,
  topic: string,
  onEvent: (event: PatientEvent) => void,
  onDropped: () => void,
): Promise<Opened> {
  return new Promise((settle) => {
    const socket = new WebSocket(endpoint);
    let confirmed = false;
    let denied = false;
    const timer = setTimeout(() => socket.close(), CONFIRMATION_TIMEOUT_MS);
    const close = () => {
      confirmed = false;
      socket.close();
    };
    socket.addEventListener("message", ({ data }) => {
      if (confirmed) {
        const notification = notificationOf(data, topic);
        if (notification === null) return;
        socket.send(JSON.stringify({ id: notification.id, status: ACKNOWLEDGED }));
        onEvent(notification.event);
        return;
      }
      const verdict = verdictOf(data);
      if (verdict === null) return;
      if (verdict === "denied") {
        denied = true;
        socket.close();
        return;
      }
      confirmed = true;
      clearTimeout(timer);
      settle({ close, lease: verdict.lease });
    });
    // A socket closes once; settling an already settled outcome changes
    // nothing, and the timer closing a closed socket does nothing either.
    socket.addEventListener("close", () => {
      if (confirmed) onDropped();
      settle(denied ? "denied" : "failed");
    });
  });
}

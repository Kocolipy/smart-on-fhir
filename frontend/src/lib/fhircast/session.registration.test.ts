import { describe, expect, it } from "vitest";

import {
  HUB_AUDIENCE,
  HUB_TOKEN,
  HUB_URL,
  type HubAnswer,
  hubOnly,
  type HubOptions,
  hubTokenOf,
  REGISTRATION_URL,
  TOKEN_URL,
} from "../fake-hub.testHelpers";
import { endFhircast, fhircastSnapshot, retryFhircast, startFhircast } from ".";
import { eventually, held, releasing, startedWith, throughout } from "./fhircast.testHelpers";

// The session's FHIRcast connection on its way to a hub token, through the
// facade the shell calls: the browser profile's dynamic client registered
// once and reused, a read-only hub, and every refusal of the configuration,
// registration or token request shown as the one status `unavailable`, with
// no subscription made. The wire of each step is `hub-token.test.ts`'s.

const json = (body: unknown, status = 200): HubAnswer => ({ body, status });

/** Each subscription the hub was asked for. */
const subscriptionsAt = (hub: ReturnType<typeof hubOnly>) =>
  hub
    .requestsTo(HUB_URL)
    .filter(({ body }) => new URLSearchParams(body).get("hub.mode") === "subscribe");

const READ_ONLY = json({ audience: HUB_AUDIENCE, isReadOnly: "1" });

describe("the session's FHIRcast connection, registering at the hub", () => {
  it("connects on a first start, registering the profile's client", async () => {
    const hub = hubOnly();

    expect([await startedWith(), hub.requestsTo(REGISTRATION_URL).length]).toEqual([
      "connected",
      1,
    ]);
  });

  it("registers nothing more when a retry connects again with the client the profile kept", async () => {
    const hub = hubOnly();
    await startedWith();

    retryFhircast();
    await eventually(() => expect(fhircastSnapshot().status).toBe("connected"));

    expect(hub.requestsTo(REGISTRATION_URL)).toHaveLength(1);
  });

  it("connects at a read-only hub with the client the profile kept, registering none", async () => {
    const hub = hubOnly({
      configuration: [json({ audience: HUB_AUDIENCE, isReadOnly: "0" }), READ_ONLY],
    });
    await startedWith();

    retryFhircast();
    await eventually(() => expect(fhircastSnapshot().status).toBe("connected"));

    expect(hub.requestsTo(REGISTRATION_URL)).toHaveLength(1);
  });

  it("is unavailable at a read-only hub with no client kept, registering none and subscribing nothing", async () => {
    const hub = hubOnly({ configuration: READ_ONLY });

    expect([await startedWith(), hub.requestsTo(REGISTRATION_URL), subscriptionsAt(hub)]).toEqual([
      "unavailable",
      [],
      [],
    ]);
  });

  it.each<[string, HubOptions]>([
    ["the configuration is refused", { configuration: json({ error: "server_error" }, 503) }],
    ["the configuration does not decode", { configuration: json({ audience: HUB_AUDIENCE }) }],
    ["the registration is refused", { registration: json({ error: "invalid_token" }, 401) }],
    ["the token request is refused", { token: json({ error: "INVALID-CLIENT" }, 401) }],
    ["the configuration answers no JSON", { configuration: { status: 200, text: "<html>" } }],
    ["the profile refuses storage", { profile: { refusesStorage: true } }],
  ])("is unavailable, subscribing nothing, when %s", async (_name, options) => {
    const hub = hubOnly(options);

    expect([await startedWith(), subscriptionsAt(hub)]).toEqual(["unavailable", []]);
  });

  it("is blocked by the browser when it never reached the configuration, Local Network Access refused", async () => {
    hubOnly({ configuration: "unreachable", localNetworkAccess: "denied" });

    expect(await startedWith()).toBe("blocked");
  });

  it("is unavailable when it never reached the configuration, Local Network Access not refused", async () => {
    hubOnly({ configuration: "unreachable" });

    expect(await startedWith()).toBe("unavailable");
  });

  it.each<[string, () => unknown]>([
    ["the session's FHIRcast ends", () => endFhircast()],
    ["a retry replaces it", () => retryFhircast()],
  ])("subscribes nothing with a hub token that comes once %s", async (_name, replace) => {
    const token = held();
    const hub = hubOnly({ token: [token.answer, json({ access_token: hubTokenOf(2) })] });
    startFhircast(releasing);
    await eventually(() => expect(hub.requestsTo(TOKEN_URL)).toHaveLength(1));

    await replace();
    token.give(json({ access_token: HUB_TOKEN }));

    await throughout(() =>
      expect(
        subscriptionsAt(hub).filter(({ authorization }) => authorization === `Bearer ${HUB_TOKEN}`),
      ).toEqual([]),
    );
  });

  it("connects again on a retry once a refused registration is accepted", async () => {
    const hub = hubOnly({
      registration: [json({ error: "access_denied" }, 403), json({ client_id: "client-2" })],
    });
    expect(await startedWith()).toBe("unavailable");

    retryFhircast();
    await eventually(() => expect(fhircastSnapshot().status).toBe("connected"));

    expect(subscriptionsAt(hub)).toHaveLength(1);
  });
});

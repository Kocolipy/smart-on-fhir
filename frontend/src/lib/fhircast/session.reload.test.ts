import { describe, expect, it } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CLIENT_ID,
  HUB_TOKEN,
  type HubAnswer,
  REGISTRATION_ENDPOINT,
  requestsSince,
  TOKEN_ENDPOINT,
} from "../fake-hub.testHelpers";
import { type FhircastRelease } from ".";
import {
  DELETE,
  eventually,
  form,
  keptState,
  RELEASE,
  releasing,
  REVOKE,
  routes,
  SMART_ACCESS_TOKEN,
  throughout,
} from "./fhircast.testHelpers";
import {
  AT_HUB,
  connectedBefore,
  DISCOVER,
  lasting,
  MINT,
  REGISTER,
  reloaded,
  SECOND_ENDPOINT,
  startedOn,
} from "./reload.testHelpers";

// A reload, as connecting a release takes it: a stored client the hub refuses,
// and a stored registration for another hub or topic, each given up for a
// connection from scratch.

describe("a reload", () => {
  describe("when the hub refuses the stored client at the token endpoint", () => {
    it.each([400, 401])(
      "deletes the stored registration, then registers anew, on a %s",
      async (status) => {
        const hub = await connectedBefore({
          socket: ["confirms", "denies", "confirms"],
          token: [lasting(3600), { body: { error: "invalid_grant" }, status }, lasting(3600)],
        });
        const since = requestsSince(hub);

        await startedOn(await reloaded());

        expect(routes(since())).toEqual([MINT, DELETE, DISCOVER, REGISTER, MINT, AT_HUB]);
      },
    );

    it("registers anew with the release's SMART access token, under a key of its own", async () => {
      const hub = await connectedBefore({
        socket: ["confirms", "denies", "confirms"],
        token: [lasting(3600), { status: 400 }, lasting(3600)],
      });
      const before = keptState() as { keyId: string };

      await startedOn(await reloaded());

      const [, again] = hub.requestsTo(REGISTRATION_ENDPOINT);
      const registered = JSON.parse(again.body) as {
        jwks: { keys: { kid: string }[] };
      };
      expect([again.authorization, registered.jwks.keys[0].kid === before.keyId]).toEqual([
        `Bearer ${SMART_ACCESS_TOKEN}`,
        false,
      ]);
    });

    it("registers anew when the stored registration names no way to delete it", async () => {
      const hub = await connectedBefore({
        registration: { body: { client_id: CLIENT_ID }, status: 201 },
        socket: ["confirms", "denies", "confirms"],
        token: [lasting(3600), { status: 400 }, lasting(3600)],
      });
      const since = requestsSince(hub);

      await startedOn(await reloaded());

      expect(routes(since())).toEqual([MINT, DISCOVER, REGISTER, MINT, AT_HUB]);
    });

    it("deletes nothing twice, and registers nothing, once the session ended while it was refused", async () => {
      let refuse = () => undefined as void;
      const hub = await connectedBefore({
        socket: ["confirms", "denies"],
        token: [
          lasting(3600),
          new Promise<HubAnswer>((resolve) => (refuse = () => resolve({ status: 400 }))),
        ],
      });
      const page = await reloaded();
      page.startFhircast(releasing);
      await eventually(() => expect(hub.requestsTo(TOKEN_ENDPOINT)).toHaveLength(2));
      const since = requestsSince(hub);

      await page.endFhircast();
      refuse();

      await throughout(() => expect(routes(since())).toEqual([AT_HUB, REVOKE, DELETE]));
    });

    it("is unavailable, registering nothing, when the token endpoint fails instead", async () => {
      const hub = await connectedBefore({
        socket: ["confirms", "denies"],
        token: [lasting(3600), { status: 500, text: "<html>down</html>" }],
      });

      await startedOn(await reloaded(), "unavailable");

      expect([hub.requestsTo(REGISTRATION_ENDPOINT).length, keptState()?.clientId]).toEqual([
        1,
        CLIENT_ID,
      ]);
    });
  });

  describe("a stored registration for another hub or topic", () => {
    const ELSEWHERE = "http://127.0.0.1:8104/fhircast/STU3";

    it.each<[string, FhircastRelease]>([
      ["another topic", { ...RELEASE, hubTopic: "topic-2" }],
      ["another hub", { ...RELEASE, hubUrl: ELSEWHERE }],
    ])("is torn down, then the tab registers anew, for %s", async (_name, release) => {
      const hub = await connectedBefore();
      const since = requestsSince(hub);

      await startedOn(await reloaded(), "connected", release);

      expect(routes(since())).toEqual([
        AT_HUB,
        REVOKE,
        DELETE,
        `GET ${release.hubUrl}/.well-known/fhircast-configuration`,
        REGISTER,
        MINT,
        `POST ${release.hubUrl}`,
      ]);
    });

    it("is unsubscribed from at its own topic, with its own Hub Token", async () => {
      const hub = await connectedBefore();
      const since = requestsSince(hub);

      await startedOn(await reloaded(), "connected", {
        ...RELEASE,
        hubTopic: "topic-2",
      });

      const [unsubscription] = since();
      expect([unsubscription.authorization, form(unsubscription.body)]).toEqual([
        `Bearer ${HUB_TOKEN}`,
        {
          "hub.channel.endpoint": CHANNEL_ENDPOINT,
          "hub.channel.type": "websocket",
          "hub.mode": "unsubscribe",
          "hub.topic": "topic-1",
        },
      ]);
    });

    it("is never resumed against: its endpoint is not reopened", async () => {
      const hub = await connectedBefore();

      await startedOn(await reloaded(), "connected", {
        ...RELEASE,
        hubTopic: "topic-2",
      });

      expect(hub.sockets()).toEqual([CHANNEL_ENDPOINT, SECOND_ENDPOINT]);
    });

    it("is forgotten, the new registration kept for the new topic", async () => {
      await connectedBefore();

      await startedOn(await reloaded(), "connected", {
        ...RELEASE,
        hubTopic: "topic-2",
      });

      expect(keptState()).toMatchObject({
        hubTopic: "topic-2",
        subscription: { endpoint: SECOND_ENDPOINT },
      });
    });
  });
});

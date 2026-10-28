import { describe, expect, it, onTestFinished, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CLIENT_ID,
  HUB_TOKEN,
  HUB_URL,
  hubOnly,
  REGISTRATION_ACCESS_TOKEN,
  REGISTRATION_CLIENT_URI,
  REGISTRATION_ENDPOINT,
  requestsSince,
  REVOCATION_ENDPOINT,
  TOKEN_ENDPOINT,
} from "../fake-hub.testHelpers";
import { connectFhircast } from ".";
import {
  assertionOf,
  DELETE,
  eventually,
  form,
  keptState,
  RELEASE,
  releasing,
  REVOKE,
  routes,
  signedByRegisteredKey,
  throughout,
} from "./fhircast.testHelpers";
import {
  AT_HUB,
  connectedBefore,
  reloaded,
  SECOND_ENDPOINT,
  startedOn,
} from "./reload.testHelpers";

// A reload, and then the end of the session: the stored registration torn
// down at the hub, whether or not this page load resumed it.

describe("a reload", () => {
  describe("and then the end of the session", () => {
    it("unsubscribes, revokes and deletes what the tab kept, though it never resumed it", async () => {
      const hub = await connectedBefore();
      const since = requestsSince(hub);

      await (await reloaded()).endFhircast();

      expect(routes(since())).toEqual([AT_HUB, REVOKE, DELETE]);
    });

    it("unsubscribes at the stored endpoint, with the stored Hub Token", async () => {
      const hub = await connectedBefore();
      const since = requestsSince(hub);

      await (await reloaded()).endFhircast();

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

    it("revokes the stored Hub Token as the stored client, signed with the stored key", async () => {
      const hub = await connectedBefore();

      await (await reloaded()).endFhircast();

      const { body } = hub.requestTo(REVOCATION_ENDPOINT);
      const { client_assertion: signed, client_id, token } = form(body);
      expect([
        client_id,
        token,
        assertionOf(new URLSearchParams({ assertion: signed }).toString()).claims.aud,
        await signedByRegisteredKey(hub, body, "client_assertion"),
      ]).toEqual([CLIENT_ID, HUB_TOKEN, TOKEN_ENDPOINT, true]);
    });

    it("deletes the stored registration with its registration access token", async () => {
      const hub = await connectedBefore();

      await (await reloaded()).endFhircast();

      expect(hub.requestTo(REGISTRATION_CLIENT_URI)).toMatchObject({
        authorization: `Bearer ${REGISTRATION_ACCESS_TOKEN}`,
        method: "DELETE",
      });
    });

    it("forgets what the tab kept at once", async () => {
      await connectedBefore();
      const page = await reloaded();

      const ending = page.endFhircast();
      const keptOnceCalled = keptState();
      await ending;

      expect(keptOnceCalled).toBeNull();
    });

    it("tears down a registration with no subscription stored by deleting it alone", async () => {
      const hub = hubOnly({ token: { status: 400 } });
      await connectFhircast(RELEASE);
      const since = requestsSince(hub);

      await (await reloaded()).endFhircast();

      expect(routes(since())).toEqual([DELETE]);
    });

    it("skips what the hub named no way to do", async () => {
      const hub = await connectedBefore({
        fhircastConfiguration: {
          body: {
            registration_endpoint: REGISTRATION_ENDPOINT,
            token_endpoint: TOKEN_ENDPOINT,
          },
          status: 200,
        },
        registration: { body: { client_id: CLIENT_ID }, status: 201 },
      });
      const since = requestsSince(hub);

      await (await reloaded()).endFhircast();

      expect(routes(since())).toEqual([AT_HUB]);
    });

    it("tears down a resumed connection from what it holds, once what the tab kept is forgotten", async () => {
      const hub = await connectedBefore();
      const page = await reloaded();
      await startedOn(page);
      page.clearFhircastStorage();
      const since = requestsSince(hub);

      await page.endFhircast();

      expect(routes(since())).toEqual([AT_HUB, REVOKE, DELETE]);
    });

    it("resumes nothing when the session ends while the tab's state is still being read", async () => {
      const hub = await connectedBefore();
      const page = await reloaded();
      // WebCrypto holds the first stored key it is handed until the test lets it go.
      let takeKey = () => undefined as void;
      const importKey = crypto.subtle.importKey.bind(crypto.subtle);
      const importing = vi
        .spyOn(crypto.subtle, "importKey")
        .mockImplementationOnce(async (...taking: Parameters<typeof importKey>) => {
          await new Promise<void>((resolve) => (takeKey = resolve));
          return importKey(...taking);
        });
      onTestFinished(() => importing.mockRestore());
      page.startFhircast(releasing);
      await eventually(() => expect(importing).toHaveBeenCalled());
      const since = requestsSince(hub);

      await page.endFhircast();
      takeKey();

      await throughout(() =>
        expect([routes(since()), hub.sockets()]).toEqual([
          [AT_HUB, REVOKE, DELETE],
          [CHANNEL_ENDPOINT],
        ]),
      );
    });

    it("tears down a resumed connection once, closing its socket", async () => {
      const hub = await connectedBefore();
      const page = await reloaded();
      await startedOn(page);
      const since = requestsSince(hub);

      await page.endFhircast();

      expect([routes(since()), hub.openSockets()]).toEqual([[AT_HUB, REVOKE, DELETE], 0]);
    });

    it("tears down a connection still resuming once", async () => {
      let confirm = () => undefined as void;
      const hub = await connectedBefore({
        hold: [null, new Promise((resolve) => (confirm = resolve))],
      });
      const page = await reloaded();
      page.startFhircast(releasing);
      await eventually(() => expect(hub.sockets()).toHaveLength(2));
      const since = requestsSince(hub);

      await page.endFhircast();
      confirm();
      await eventually(() => expect(hub.openSockets()).toBe(0));

      await throughout(() => expect(routes(since())).toEqual([AT_HUB, REVOKE, DELETE]));
    });

    it("tears down the subscription a resumed connection moved to, not the stored one twice", async () => {
      const hub = await connectedBefore({
        socket: ["confirms", "denies", "confirms"],
      });
      const page = await reloaded();
      await startedOn(page);
      // The stored subscription, cancelled once the new one's socket is confirmed.
      await eventually(() => expect(hub.requestsTo(REVOCATION_ENDPOINT)).toHaveLength(1));
      const since = requestsSince(hub);

      await page.endFhircast();

      expect(
        since().map(({ authorization, body, method, url }) =>
          method === "DELETE"
            ? DELETE
            : [url, authorization, form(body)["hub.channel.endpoint"] ?? form(body).token],
        ),
      ).toEqual([
        [HUB_URL, `Bearer ${HUB_TOKEN}-2`, SECOND_ENDPOINT],
        [REVOCATION_ENDPOINT, null, `${HUB_TOKEN}-2`],
        DELETE,
      ]);
    });
  });
});

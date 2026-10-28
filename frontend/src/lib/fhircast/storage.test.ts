import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CLIENT_ID,
  HUB_TOKEN,
  HUB_URL,
  hubOnly,
  REGISTRATION_ACCESS_TOKEN,
  REGISTRATION_CLIENT_URI,
  REGISTRATION_ENDPOINT,
  REVOCATION_ENDPOINT,
  TOKEN_ENDPOINT,
} from "../fake-hub.testHelpers";
import { clearFhircastStorage, connectFhircast, FHIRCAST_STORAGE_KEYS } from ".";
import { keptState, RELEASE, SMART_ACCESS_TOKEN } from "./fhircast.testHelpers";
import { readStored } from "./storage";

// `connectFhircast` and `clearFhircastStorage`: what the tab keeps of a
// connection in `sessionStorage`, and forgetting it.

/** The keys an earlier version of the module kept its connection under, one each. */
const RETIRED_KEYS = [
  "fhircast-client-id",
  "fhircast-hub-token",
  "fhircast-private-jwk",
  "fhircast-smart-access-token",
];

describe("connectFhircast", () => {
  describe("what the tab keeps", () => {
    it("never keeps the SMART access token", async () => {
      hubOnly();

      await connectFhircast(RELEASE);

      expect(
        Object.keys(window.sessionStorage).filter((key) =>
          window.sessionStorage.getItem(key)?.includes(SMART_ACCESS_TOKEN),
        ),
      ).toEqual([]);
    });

    describe("on the clock", () => {
      const CONNECTED_AT = Date.UTC(2026, 9, 24, 2, 0, 0);

      beforeEach(() => {
        vi.useFakeTimers({ toFake: ["Date"] });
        vi.setSystemTime(CONNECTED_AT);
      });

      afterEach(() => {
        vi.useRealTimers();
      });

      it("keeps the registration, and the subscription with when its Hub Token and lease run out", async () => {
        hubOnly({ leaseSeconds: 600 });

        await connectFhircast(RELEASE);

        expect(keptState()).toMatchObject({
          clientId: CLIENT_ID,
          hubTopic: "topic-1",
          hubUrl: HUB_URL,
          registration: { token: REGISTRATION_ACCESS_TOKEN, uri: REGISTRATION_CLIENT_URI },
          revocationEndpoint: REVOCATION_ENDPOINT,
          subscription: {
            endpoint: CHANNEL_ENDPOINT,
            hubToken: HUB_TOKEN,
            hubTokenExpiresAt: CONNECTED_AT + 3_600_000,
            leaseExpiresAt: CONNECTED_AT + 600_000,
          },
          tokenEndpoint: TOKEN_ENDPOINT,
        });
      });

      it("keeps no expiry the hub did not state", async () => {
        hubOnly({ token: { body: { access_token: HUB_TOKEN }, status: 200 } });

        await connectFhircast(RELEASE);

        expect(keptState()).toMatchObject({
          subscription: { hubTokenExpiresAt: null, leaseExpiresAt: null },
        });
      });
    });

    it("keeps the private key it registered, under the kid it registered", async () => {
      const hub = hubOnly();

      await connectFhircast(RELEASE);

      const registered = JSON.parse(hub.requestTo(REGISTRATION_ENDPOINT).body) as {
        jwks: { keys: { kid: string; x: string }[] };
      };
      const { keyId, signingJwk } = keptState() as {
        keyId: string;
        signingJwk: JsonWebKey;
      };
      expect([keyId, signingJwk.kty, signingJwk.crv, signingJwk.x, typeof signingJwk.d]).toEqual([
        registered.jwks.keys[0].kid,
        "EC",
        "P-384",
        registered.jwks.keys[0].x,
        "string",
      ]);
    });

    it("reads the private key it kept back as one that can never be exported again", async () => {
      hubOnly();
      await connectFhircast(RELEASE);

      const stored = await readStored();

      expect(stored?.client.signing.privateKey.extractable).toBe(false);
    });

    it("keeps the registration before the hub grants a Hub Token, with no subscription", async () => {
      hubOnly({ token: { status: 400 } });

      await connectFhircast(RELEASE);

      expect(keptState()).toMatchObject({ clientId: CLIENT_ID, subscription: null });
    });

    it("keeps no registration the hub named no way to delete", async () => {
      hubOnly({ registration: { body: { client_id: CLIENT_ID }, status: 201 } });

      await connectFhircast(RELEASE);

      expect(keptState()).toMatchObject({ registration: null });
    });

    it("forgets it when cleared, and what an earlier version kept under keys of its own", async () => {
      hubOnly();
      await connectFhircast(RELEASE);
      for (const key of RETIRED_KEYS) window.sessionStorage.setItem(key, "kept");

      clearFhircastStorage();

      expect(
        [...Object.values(FHIRCAST_STORAGE_KEYS), ...RETIRED_KEYS].map((key) =>
          window.sessionStorage.getItem(key),
        ),
      ).toEqual([null, null, null, null, null]);
    });
  });
});

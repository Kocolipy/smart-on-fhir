import { expect, test, type Browser, type Page } from "@playwright/test";

import { cardTitled } from "./card.helpers";
import {
  createLaunch,
  expectStandInConfigured,
  FHIR_BASE,
  firstHubModeAt,
  hubState,
  hubTokenRequestStatus,
  seededClinician,
  signInAtMedplum,
  tanWeiMing,
} from "./fhircast.helpers";
import {
  deprovisionUser,
  freshBrowser,
  provisionServerNamedUser,
  workerConnector,
} from "./scim.helpers";

/**
 * Logout tears the FHIRcast connection down at the hub (ADR 0011, "Revocation
 * at the hub is best-effort"), end to end through the local Epic stand-in: an
 * EHR launch through Medplum signs the clinician in, Home's FHIRcast card
 * connects through the DCR shim, and "Sign out" then has the SPA unsubscribe
 * at Medplum's hub, revoke the Hub Token at the shim (RFC 7009) and delete
 * the registration there (RFC 7592). Each case reads the hub's side: Medplum's
 * answer to a socket at the card's channel endpoint, and the shim's test-only
 * state and token endpoint.
 *
 * In the `fhircast` project. Each case signs in a clinician the seed made for
 * it alone (compose.yaml's `SEED_EPIC_LOGIN_CLINICIANS`), so the Users it
 * provisions under those Practitioner ids are no other case's; each is deleted
 * again in a `finally`. The teardown runs after the logout has answered, so
 * each case polls the hub for it.
 */

const directory = workerConnector("fhircast-logout");

/** What the tab held of its FHIRcast connection just before signing out, read off the page. */
interface HeldAtSignOut {
  /** The card's channel endpoint: the websocket URL it subscribed at. */
  channelEndpoint: string;
  clientId: string;
  hubToken: string;
  privateJwk: JsonWebKey;
}

/** The FHIRcast card's status line. */
const fhircastStatus = (page: Page) => page.getByRole("status", { name: "FHIRcast" });

/** What the FHIRcast module keeps under `key` in the page's sessionStorage. */
async function kept(page: Page, key: string): Promise<string> {
  const value = await page.evaluate((name) => sessionStorage.getItem(name), key);
  expect(value, `the tab keeps ${key}`).not.toBeNull();
  return value ?? "";
}

/**
 * Launches the clinician `email` the seed made to Home and waits for the card
 * to connect; runs `beforeSignOut` with what the tab then holds; signs out;
 * and, on the login page, runs `afterSignOut` with the same.
 */
async function signedOutOfAConnectedCard(
  browser: Browser,
  email: string,
  beforeSignOut: (held: HeldAtSignOut) => Promise<void>,
  afterSignOut: (held: HeldAtSignOut) => Promise<void>,
): Promise<void> {
  expectStandInConfigured();
  const signIn = seededClinician(email);
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const channelEndpoints: string[] = [];
    page.on("websocket", (socket) => {
      if (new URL(socket.url()).pathname.startsWith("/ws/fhircast/")) {
        channelEndpoints.push(socket.url());
      }
    });
    const launch = new URLSearchParams({
      iss: FHIR_BASE,
      launch: await createLaunch(await tanWeiMing()),
    });
    await page.goto(`/api/auth/epic/launch?${launch.toString()}`);
    await signInAtMedplum(page, signIn);
    await expect(cardTitled(page, "FHIRcast")).toBeVisible();
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    expect(channelEndpoints, "the card has opened one socket to the hub").toHaveLength(1);
    const held: HeldAtSignOut = {
      channelEndpoint: channelEndpoints[0],
      clientId: await kept(page, "fhircast-client-id"),
      hubToken: await kept(page, "fhircast-hub-token"),
      privateJwk: JSON.parse(await kept(page, "fhircast-private-jwk")) as JsonWebKey,
    };
    await beforeSignOut(held);

    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/);

    await afterSignOut(held);
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
}

test("after logout the hub holds no subscription at the card's channel endpoint", async ({
  browser,
}) => {
  await signedOutOfAConnectedCard(
    browser,
    "fhircast-logout-unsubscribe@example.com",
    async ({ channelEndpoint }) => {
      expect(await firstHubModeAt(channelEndpoint), "the subscription stands before logout").toBe(
        "subscribe",
      );
    },
    async ({ channelEndpoint }) => {
      await expect.poll(() => firstHubModeAt(channelEndpoint)).toBe("denied");
    },
  );
});

test("after logout the shim reports the Hub Token revoked and the registration deleted", async ({
  browser,
}) => {
  await signedOutOfAConnectedCard(
    browser,
    "fhircast-logout-revoke@example.com",
    async () => undefined,
    async ({ clientId, hubToken }) => {
      await expect
        .poll(async () => {
          const { clientIds, revokedTokens } = await hubState();
          return {
            registered: clientIds.includes(clientId),
            revoked: revokedTokens.includes(hubToken),
          };
        })
        .toEqual({ registered: false, revoked: true });
    },
  );
});

test("after logout a token request with the old registration is refused", async ({ browser }) => {
  await signedOutOfAConnectedCard(
    browser,
    "fhircast-logout-token@example.com",
    async ({ clientId, privateJwk }) => {
      expect(
        await hubTokenRequestStatus(clientId, privateJwk),
        "the registration grants a Hub Token before logout",
      ).toBe(200);
    },
    async ({ clientId, privateJwk }) => {
      await expect.poll(() => hubTokenRequestStatus(clientId, privateJwk)).toBe(400);
    },
  );
});

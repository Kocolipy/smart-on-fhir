import { expect, test, type Browser, type Page } from "@playwright/test";

import { cardTitled } from "./card.helpers";
import {
  createLaunch,
  expectStandInConfigured,
  FHIR_BASE,
  firstHubModeAt,
  hubState,
  hubTokenRequestStatus,
  keptConnection,
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
 * state and token endpoint. One case reloads the tab before signing out: the
 * teardown then works from what the tab kept, not from what the page before
 * held in memory.
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

/** What the tab keeps of its connection, its Hub Subscription asserted confirmed. */
async function held(page: Page, channelEndpoint: string): Promise<HeldAtSignOut> {
  const { clientId, privateJwk, subscription } = await keptConnection(page);
  expect(subscription?.endpoint, "the tab keeps the subscription it opened").toBe(channelEndpoint);
  return { channelEndpoint, clientId, hubToken: subscription?.hubToken ?? "", privateJwk };
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
    const heldAtSignOut = await held(page, channelEndpoints[0]);
    await beforeSignOut(heldAtSignOut);

    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/);

    await afterSignOut(heldAtSignOut);
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

test("after a reload, logout leaves no subscription at any endpoint the tab used, and the key mints no Hub Token", async ({
  browser,
}) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-logout-reload@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const channelEndpoints = new Set<string>();
    page.on("websocket", (socket) => {
      if (new URL(socket.url()).pathname.startsWith("/ws/fhircast/")) {
        channelEndpoints.add(socket.url());
      }
    });
    const launch = new URLSearchParams({
      iss: FHIR_BASE,
      launch: await createLaunch(await tanWeiMing()),
    });
    await page.goto(`/api/auth/epic/launch?${launch.toString()}`);
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const { clientId, privateJwk } = await keptConnection(page);

    await page.reload();
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/);

    expect(channelEndpoints.size, "the tab opened a socket to the hub").toBeGreaterThan(0);
    for (const endpoint of channelEndpoints) {
      await expect.poll(() => firstHubModeAt(endpoint)).toBe("denied");
    }
    await expect.poll(() => hubTokenRequestStatus(clientId, privateJwk)).toBe(400);
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

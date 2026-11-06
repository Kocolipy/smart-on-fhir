import { expect, test, type Browser, type Page } from "@playwright/test";

import { cardTitled } from "./card.helpers";
import {
  createLaunch,
  expectStandInConfigured,
  FHIR_BASE,
  firstHubModeAt,
  keptSubscription,
  recordHubSockets,
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
 * subscribes at Medplum's hub through the shim with the SMART access token,
 * and "Sign out" then has the SPA unsubscribe there with the same token. Each
 * case reads the hub's side: Medplum's answer to a socket at the card's
 * channel endpoint. One case reloads the tab before signing out: the teardown
 * is then the connection's that resumed what the tab kept.
 *
 * In the `fhircast` project. Each case signs in a clinician the seed made for
 * it alone (compose.yaml's `SEED_EPIC_LOGIN_CLINICIANS`), so the Users it
 * provisions under those Practitioner ids are no other case's; each is deleted
 * again in a `finally`. The teardown runs after the logout has answered, so
 * each case polls the hub for it.
 */

const directory = workerConnector("fhircast-logout");

/** The FHIRcast card's status line. */
const fhircastStatus = (page: Page) => page.getByRole("status", { name: "FHIRcast" });

/**
 * Launches the clinician `email` the seed made to Home, waits for the card to
 * connect, and answers the page and every channel endpoint its tab opens a
 * socket at, as it opens them.
 */
async function launchedToAConnectedCard(browser: Browser, email: string) {
  expectStandInConfigured();
  const signIn = seededClinician(email);
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  const tidy = async () => {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  };
  try {
    const page = await clinician.newPage();
    const channelEndpoints = recordHubSockets(page);
    const launch = new URLSearchParams({
      iss: FHIR_BASE,
      launch: await createLaunch(await tanWeiMing()),
    });
    await page.goto(`/api/auth/epic/launch?${launch.toString()}`);
    await signInAtMedplum(page, signIn);
    await expect(cardTitled(page, "FHIRcast")).toBeVisible();
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    return { channelEndpoints, page, tidy };
  } catch (failure) {
    await tidy();
    throw failure;
  }
}

/** Signs out by the layout's Sign out, landing on the login page. */
async function signOut(page: Page) {
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login/);
}

test("after logout the hub holds no subscription at the card's channel endpoint", async ({
  browser,
}) => {
  const { channelEndpoints, page, tidy } = await launchedToAConnectedCard(
    browser,
    "fhircast-logout-unsubscribe@example.com",
  );
  try {
    expect(channelEndpoints, "the card has opened one socket to the hub").toHaveLength(1);
    const [channelEndpoint] = channelEndpoints;
    expect((await keptSubscription(page)).endpoint, "the tab keeps the subscription").toBe(
      channelEndpoint,
    );
    expect(await firstHubModeAt(channelEndpoint), "the subscription stands before logout").toBe(
      "subscribe",
    );

    await signOut(page);

    await expect.poll(() => firstHubModeAt(channelEndpoint)).toBe("denied");
  } finally {
    await tidy();
  }
});

test("after a reload, logout leaves no subscription at any endpoint the tab used", async ({
  browser,
}) => {
  const { channelEndpoints, page, tidy } = await launchedToAConnectedCard(
    browser,
    "fhircast-logout-reload@example.com",
  );
  try {
    await page.reload();
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });

    await signOut(page);

    expect(channelEndpoints.length, "the tab opened a socket to the hub").toBeGreaterThan(0);
    for (const endpoint of new Set(channelEndpoints)) {
      await expect.poll(() => firstHubModeAt(endpoint)).toBe("denied");
    }
  } finally {
    await tidy();
  }
});

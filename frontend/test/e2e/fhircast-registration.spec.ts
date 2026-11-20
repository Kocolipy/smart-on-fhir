import { type BrowserContext, expect, type Page, test } from "@playwright/test";

import {
  createLaunch,
  expectStandInConfigured,
  expireHubToken,
  expireSmartAccessToken,
  FHIR_BASE,
  firstHubModeAt,
  forgetHubClient,
  hubRegistrations,
  keptSubscription,
  recordHubClients,
  recordHubTokens,
  releasedAccessToken,
  seededClinician,
  setHubReadOnly,
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
 * The tab's dynamic client at the FHIRcast Hub (ADR 0016, "The dynamic client
 * and the hub token"), end to end through the local Epic stand-in: an EHR
 * launch's first connection registers the browser profile's client with the
 * SMART access token, and every connection after it in that profile — a
 * reload, a second tab — obtains its hub token with the client the profile
 * kept, registering nothing. A read-only hub registers no client: a profile
 * that kept none shows `FHIRcast unavailable`, one that kept one connects.
 * The connection lasts the session: past the SMART access token's expiry at
 * the hub, a new hub token comes from the client the profile kept and a
 * reload connects, registering nothing; a client the hub forgot is registered
 * once more with the SMART access token, and, once that has expired, ends
 * FHIRcast. Sign-out unsubscribes at the hub, and keeps the client, but
 * Logout's `Clear-Site-Data` drops it with the origin's storage, so the next
 * sign-in in the profile registers once more.
 *
 * In the `fhircast-registration` project, one case at a time and beside no
 * other launch: each case reads the shim's count of registrations, and one
 * switches the hub's read-only mode, which every launch beside it would
 * meet. Each Playwright browser context is a browser profile of its own, so
 * each case's first launch registers. Each case signs in a clinician the seed
 * made for it alone (compose.yaml's `SEED_EPIC_LOGIN_CLINICIANS`), deleted
 * again in a `finally`. That patient events still move the EHR context with
 * the hub token is `fhircast-patient.spec.ts`'.
 */

const directory = workerConnector("fhircast-registration");

/** The FHIRcast card's status line. */
const fhircastStatus = (page: Page) => page.getByRole("status", { name: "FHIRcast" });

/** What an EHR opens in the clinician's browser, for a launch with Tan Wei Ming in context. */
async function launchUrl(): Promise<string> {
  const launch = new URLSearchParams({
    iss: FHIR_BASE,
    launch: await createLaunch(await tanWeiMing()),
  });
  return `/api/auth/epic/launch?${launch.toString()}`;
}

/**
 * Runs `body` with a fresh browser profile in which the seeded clinician
 * `email` is provisioned, handed the profile; both are gone again after.
 */
async function asClinician(
  browser: Parameters<typeof freshBrowser>[0],
  email: string,
  body: (clinician: BrowserContext, signIn: ReturnType<typeof seededClinician>) => Promise<void>,
): Promise<void> {
  expectStandInConfigured();
  const signIn = seededClinician(email);
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    await body(clinician, signIn);
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
}

/** A launch in a new tab of `clinician`, signed in at Medplum, landed on Home. */
async function launched(
  clinician: BrowserContext,
  signIn: ReturnType<typeof seededClinician>,
): Promise<Page> {
  const page = await clinician.newPage();
  await page.goto(await launchUrl());
  await signInAtMedplum(page, signIn);
  await expect(page).toHaveURL("/");
  return page;
}

test("a first launch registers the tab once, and a reload and a second tab connect with the client the profile kept", async ({
  browser,
}) => {
  await asClinician(browser, "fhircast-registration@example.com", async (clinician, signIn) => {
    const hubTokens = recordHubTokens(clinician);
    const before = await hubRegistrations();

    const page = await launched(clinician, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const registeredByTheLaunch = (await hubRegistrations()) - before;

    await page.reload();
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    // A second tab of the same profile, its EHR context copied as a tab
    // opened from this one is.
    const [second] = await Promise.all([
      clinician.waitForEvent("page"),
      page.evaluate(() => void window.open("/", "_blank")),
    ]);
    await expect(fhircastStatus(second)).toHaveText("Connected", { timeout: 20_000 });

    await expect.poll(() => hubTokens.length, { message: "each tab obtained a hub token" }).toBe(3);
    expect({
      registeredByTheLaunch,
      registeredSince: (await hubRegistrations()) - before,
    }).toEqual({ registeredByTheLaunch: 1, registeredSince: 1 });
  });
});

test("at a read-only hub, a tab whose profile kept no client shows FHIRcast unavailable and registers none", async ({
  browser,
}) => {
  await asClinician(browser, "fhircast-read-only@example.com", async (clinician, signIn) => {
    const before = await hubRegistrations();
    await setHubReadOnly(true);
    try {
      const page = await launched(clinician, signIn);

      await expect(fhircastStatus(page)).toHaveText("FHIRcast unavailable", { timeout: 20_000 });
      await expect(page.getByRole("button", { name: "Retry" })).toBeVisible();
      expect(await hubRegistrations()).toBe(before);
    } finally {
      await setHubReadOnly(false);
    }
  });
});

test("at a read-only hub, a tab whose profile kept a client connects with it", async ({
  browser,
}) => {
  await asClinician(browser, "fhircast-read-only-kept@example.com", async (clinician, signIn) => {
    const page = await launched(clinician, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const registered = await hubRegistrations();
    await setHubReadOnly(true);
    try {
      await page.reload();

      await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
      expect(await hubRegistrations()).toBe(registered);
    } finally {
      await setHubReadOnly(false);
    }
  });
});

/**
 * Signs `signIn` in at Medplum once more in a browser profile that signed in
 * there before: Medplum may still remember the sign-in, and then moves on to
 * the scopes, its sign-in form shown a moment or not at all. Each step it
 * asks for is answered as it shows, until it asks for the scopes.
 */
async function signInAtMedplumAgain(page: Page, signIn: ReturnType<typeof seededClinician>) {
  const email = page.getByLabel("Email");
  const password = page.getByLabel(/^Password/);
  const scopes = page.getByRole("button", { name: "Set Scope" });
  // Each step is given a second: one the page moves on from meanwhile is
  // tried again from what the page shows next.
  const step = { timeout: 1_000 };
  await expect(async () => {
    if (await scopes.isVisible()) return;
    if (await password.isVisible()) {
      await password.fill(signIn.password, step);
      await page.getByRole("button", { name: "Sign In" }).click(step);
    } else if (await email.isVisible()) {
      await email.fill(signIn.email, step);
      await page.getByRole("button", { name: "Continue" }).click(step);
    }
    throw new Error(`Medplum has not asked for the scopes yet, at ${page.url()}`);
  }).toPass({ timeout: 20_000 });
  await scopes.click();
}

test("past the SMART access token's expiry, the connection reconnects with the client the profile kept, and a reload connects, registering nothing", async ({
  browser,
}) => {
  // A launch, a reconnect and a reload, each up to 20 s to connect: past the default 30 s.
  test.setTimeout(90_000);
  await asClinician(browser, "fhircast-past-token@example.com", async (clinician, signIn) => {
    const hubTokens = recordHubTokens(clinician);
    const page = await launched(clinician, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    await expect.poll(() => hubTokens.length, { message: "the tab obtained a hub token" }).toBe(1);
    const registered = await hubRegistrations();

    await expireSmartAccessToken(await releasedAccessToken(page));
    // The hub denies the subscription once its hub token expires: the tab
    // obtains another with the client the profile kept, and no SMART access token.
    await expireHubToken(hubTokens[0]);
    await expect
      .poll(() => hubTokens.length, {
        message: "the tab obtained a new hub token",
        timeout: 20_000,
      })
      .toBe(2);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    await page.reload();

    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    expect(await hubRegistrations()).toBe(registered);
  });
});

test("a client the hub forgot is registered once more with the SMART access token, and the card reconnects", async ({
  browser,
}) => {
  await asClinician(browser, "fhircast-forgotten@example.com", async (clinician, signIn) => {
    const clients = recordHubClients(clinician);
    const hubTokens = recordHubTokens(clinician);
    const page = await launched(clinician, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    await expect.poll(() => [clients.length, hubTokens.length]).toEqual([1, 1]);
    const registered = await hubRegistrations();

    await forgetHubClient(clients[0]);
    await expireHubToken(hubTokens[0]);

    await expect
      .poll(() => clients.length, { message: "the tab registered once more", timeout: 20_000 })
      .toBe(2);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    expect(await hubRegistrations()).toBe(registered + 1);
  });
});

test("a client the hub forgot once the SMART access token has expired ends FHIRcast, telling the clinician to reopen the application from Epic", async ({
  browser,
}) => {
  await asClinician(
    browser,
    "fhircast-forgotten-past-token@example.com",
    async (clinician, signIn) => {
      const clients = recordHubClients(clinician);
      const hubTokens = recordHubTokens(clinician);
      const page = await launched(clinician, signIn);
      await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
      await expect.poll(() => [clients.length, hubTokens.length]).toEqual([1, 1]);
      const registered = await hubRegistrations();

      await expireSmartAccessToken(await releasedAccessToken(page));
      await forgetHubClient(clients[0]);
      await expireHubToken(hubTokens[0]);

      await expect(fhircastStatus(page)).toHaveText(
        "FHIRcast ended. Reopen the application from Epic.",
        { timeout: 20_000 },
      );
      await expect(page.getByRole("button", { name: "Retry" })).toHaveCount(0);
      expect(await hubRegistrations()).toBe(registered);
    },
  );
});

test("sign-out unsubscribes at the hub, and the next sign-in after it registers once more", async ({
  browser,
}) => {
  // Two launches, each up to 20 s to connect, and the teardown between: past the default 30 s.
  test.setTimeout(90_000);
  await asClinician(browser, "fhircast-logout-registers@example.com", async (clinician, signIn) => {
    const page = await launched(clinician, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const { endpoint } = await keptSubscription(page);
    const registered = await hubRegistrations();

    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/login/);
    await expect.poll(() => firstHubModeAt(endpoint)).toBe("denied");

    // Logout's Clear-Site-Data dropped the profile's client with the origin's storage.
    await page.close();
    const again = await clinician.newPage();
    await again.goto(await launchUrl());
    await signInAtMedplumAgain(again, signIn);
    await expect(again).toHaveURL("/");
    await expect(fhircastStatus(again)).toHaveText("Connected", { timeout: 20_000 });
    expect(await hubRegistrations()).toBe(registered + 1);
  });
});

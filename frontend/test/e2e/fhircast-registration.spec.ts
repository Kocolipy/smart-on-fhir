import { type BrowserContext, expect, type Page, test } from "@playwright/test";

import {
  createLaunch,
  expectStandInConfigured,
  FHIR_BASE,
  hubRegistrations,
  recordHubTokens,
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

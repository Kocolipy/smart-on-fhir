import { expect, test, type Page } from "@playwright/test";

import { loginAs } from "./auth.helpers";
import { cardTitled } from "./card.helpers";
import {
  createLaunch,
  expectStandInConfigured,
  FHIR_BASE,
  publishPatientEvent,
  seededClinician,
  signInAtMedplum,
  tanWeiMing,
} from "./fhircast.helpers";
import {
  deprovisionUser,
  E2E_PREFIX,
  freshBrowser,
  OWN_PASSWORD,
  provisionServerNamedUser,
  provisionUser,
  runId,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * The FHIRcast card on Home (ADR 0016), end to end through the local Epic
 * stand-in: an EHR launch through Medplum signs the clinician in, the SPA
 * fetches the session's release from the backend, and connects to Medplum's
 * FHIRcast hub through the DCR shim — discovery, registration, the
 * `jwt-bearer` Hub Token, the subscription and the websocket. Once connected,
 * the card shows the last `Patient-open` or `Patient-close` published to the
 * session's topic through Medplum's FHIRcast REST API.
 *
 * In the `fhircast` project. Each launch signs in a clinician the seed made
 * for this spec alone (compose.yaml's `SEED_EPIC_LOGIN_CLINICIANS`), so the
 * Users it provisions under those Practitioner ids are no other spec's; each
 * is deleted again in a `finally`.
 *
 * No case here drops the hub: Medplum always names its hub on `127.0.0.1` and
 * a well-formed topic, so the drop rules are the backend's FakeEpic tests', and
 * the card's "FHIRcast unavailable" state is the SPA unit tests'.
 */

const directory = workerConnector("fhircast-card");

/** What an EHR opens in the clinician's browser, for a launch with Tan Wei Ming in context. */
async function launchUrl(): Promise<string> {
  const launch = new URLSearchParams({
    iss: FHIR_BASE,
    launch: await createLaunch(await tanWeiMing()),
  });
  return `/api/auth/epic/launch?${launch.toString()}`;
}

/** The FHIRcast card's status line. */
const fhircastStatus = (page: Page) => page.getByRole("status", { name: "FHIRcast" });

test("an EHR launch lands on Home and the FHIRcast card reaches connected", async ({ browser }) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-card@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);

    await expect(page).toHaveURL("/");
    await expect(cardTitled(page, "FHIRcast")).toBeVisible();
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

/** The card's last event, each field's label beside its value. */
const lastEvent = (page: Page) => page.getByRole("group", { name: "Last event" });

/** The value the last event lists under `label`. */
const field = (page: Page, label: string) =>
  lastEvent(page)
    .getByRole("term")
    .filter({ hasText: label })
    .locator("xpath=following-sibling::dd[1]");

test("the card shows the last Patient-open, then the Patient-close that replaces it", async ({
  browser,
}) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-events@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    // The topic the card subscribed on, as the backend releases it to this session.
    const release = await page.request.get("/api/auth/epic/fhircast");
    const { hubTopic } = (await release.json()) as { hubTopic: string };
    const patient = await tanWeiMing();

    await publishPatientEvent(hubTopic, "Patient-open", patient.id);

    await expect(field(page, "Event")).toHaveText("Patient-open");
    await expect(field(page, "Time")).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    await expect(field(page, "Patient")).toHaveText(patient.id);

    await publishPatientEvent(hubTopic, "Patient-close", patient.id);

    await expect(field(page, "Event")).toHaveText("Patient-close");
    await expect(field(page, "Patient")).toHaveText(patient.id);
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("a password login shows no FHIRcast card", async ({ browser }) => {
  const userName = `${E2E_PREFIX}fhircast-password-${runId()}`;
  const id = await provisionUser(directory.scim, userName);
  const visitor = await freshBrowser(browser);
  try {
    await settlePassword(userName);
    const page = await visitor.newPage();
    await loginAs(page, userName, OWN_PASSWORD);

    await expect(page.getByRole("heading", { name: "Patient" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "FHIRcast" })).toHaveCount(0);
  } finally {
    await visitor.close();
    await deprovisionUser(directory.scim, id);
  }
});

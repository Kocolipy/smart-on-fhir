import { expect, test, type Browser, type Page } from "@playwright/test";

import {
  createLaunch,
  expectStandInConfigured,
  FHIR_BASE,
  type MedplumPatient,
  PATIENT_SOURCE_FAULT,
  publishPatientEvent,
  releasedTopic,
  seededClinician,
  signInAtMedplum,
  sitiNurhaliza,
  tanWeiMing,
} from "./fhircast.helpers";
import {
  expectNoPatientInContext,
  expectPatientCardFailed,
  expectPatientInContext,
  patientBanner,
  recordPatientRequests,
} from "./patient.helpers";
import {
  deprovisionUser,
  freshBrowser,
  provisionServerNamedUser,
  workerConnector,
} from "./scim.helpers";

/**
 * The Patient card following the FHIRcast hub (ADR 0016, "The events and the
 * EHR context"), end to end through the local Epic stand-in: an EHR launch
 * through Medplum signs the clinician in and the FHIRcast card connects, then
 * each case publishes `Patient-open` or `Patient-close` to the session's topic
 * through Medplum's FHIRcast REST API, naming a patient by the Epic id the
 * seed made it under — the id the backend holds its fixture by — and the
 * Patient card shows what the EHR now has open.
 *
 * In the `fhircast` project. Each case that launches signs in a clinician the seed made for
 * it alone (compose.yaml's `SEED_EPIC_LOGIN_CLINICIANS`), provisioned under
 * its Practitioner id and deleted again in a `finally`.
 */

const directory = workerConnector("fhircast-patient");

/** The FHIRcast card's status line. */
const fhircastStatus = (page: Page) => page.getByRole("status", { name: "FHIRcast" });

/**
 * Runs `body` on Home, landed by an EHR launch with `patient` in context (none
 * when omitted) for the seeded clinician `email`, once the FHIRcast card is
 * connected; `body` is handed the page and the topic the card subscribed on.
 */
async function onConnectedHome(
  browser: Browser,
  email: string,
  patient: MedplumPatient | undefined,
  body: (page: Page, hubTopic: string) => Promise<void>,
): Promise<void> {
  expectStandInConfigured();
  const signIn = seededClinician(email);
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const launch = new URLSearchParams({ iss: FHIR_BASE, launch: await createLaunch(patient) });
    await page.goto(`/api/auth/epic/launch?${launch.toString()}`);
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    await body(page, await releasedTopic(page));
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
}

test("a Patient-open after a launch that named no patient shows that patient, and a reload keeps them", async ({
  browser,
}) => {
  await onConnectedHome(
    browser,
    "fhircast-open-unnamed@example.com",
    undefined,
    async (page, hubTopic) => {
      await expectNoPatientInContext(page);

      await publishPatientEvent(hubTopic, "Patient-open", (await tanWeiMing()).epicId);

      await expectPatientInContext(page, "Tan Wei Ming");
      await page.reload();
      await expectPatientInContext(page, "Tan Wei Ming");
    },
  );
});

test("a Patient-open for another patient replaces the launch's patient, read once for the Patient card and the top bar", async ({
  browser,
}) => {
  await onConnectedHome(
    browser,
    "fhircast-open-another@example.com",
    await tanWeiMing(),
    async (page, hubTopic) => {
      await expectPatientInContext(page, "Tan Wei Ming");
      const siti = await sitiNurhaliza();
      const patientRequests = recordPatientRequests(page);

      await publishPatientEvent(hubTopic, "Patient-open", siti.epicId);

      await expectPatientInContext(page, "Siti Nurhaliza binte Ahmad");
      await expect(
        page.getByRole("region", { name: "Patient in context" }).getByText(/^Siti Nurhaliza/),
      ).toBeVisible();
      await expect(patientBanner(page).getByText("Tan Wei Ming", { exact: true })).toHaveCount(0);
      expect(patientRequests()).toEqual([`GET /api/patient/${siti.epicId}`]);
    },
  );
});

test("a Patient-open published while the clinician is on another page shows on Home on return", async ({
  browser,
}) => {
  await onConnectedHome(
    browser,
    "fhircast-open-elsewhere@example.com",
    undefined,
    async (page, hubTopic) => {
      await page.getByRole("link", { name: "Open settings", exact: true }).click();
      await expect(page.getByRole("heading", { level: 1, name: "Settings" })).toBeVisible();

      await publishPatientEvent(hubTopic, "Patient-open", (await sitiNurhaliza()).epicId);
      await page.getByRole("link", { name: "Profile", exact: true }).click();

      await expectPatientInContext(page, "Siti Nurhaliza binte Ahmad");
    },
  );
});

test("a Patient-close empties the Patient card, staying connected and empty across a reload, until the next Patient-open", async ({
  browser,
}) => {
  await onConnectedHome(
    browser,
    "fhircast-close@example.com",
    await tanWeiMing(),
    async (page, hubTopic) => {
      await expectPatientInContext(page, "Tan Wei Ming");

      await publishPatientEvent(hubTopic, "Patient-close", (await tanWeiMing()).epicId);

      await expectNoPatientInContext(page);
      await expect(fhircastStatus(page)).toHaveText("Connected");

      await page.reload();
      await expectNoPatientInContext(page);
      await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });

      await publishPatientEvent(hubTopic, "Patient-open", (await tanWeiMing()).epicId);

      await expectPatientInContext(page, "Tan Wei Ming");
    },
  );
});

test("a Patient-open for a patient the patient source fails on shows the Patient card's failed state, not no patient", async ({
  browser,
}) => {
  await onConnectedHome(
    browser,
    "fhircast-open-failing@example.com",
    undefined,
    async (page, hubTopic) => {
      await expectNoPatientInContext(page);

      await publishPatientEvent(hubTopic, "Patient-open", PATIENT_SOURCE_FAULT);

      await expectPatientCardFailed(page);
    },
  );
});

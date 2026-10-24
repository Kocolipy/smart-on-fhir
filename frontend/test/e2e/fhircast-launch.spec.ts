import { expect, test } from "@playwright/test";

import {
  createLaunch,
  expectStandInConfigured,
  FHIR_BASE,
  PRACTITIONER_ID,
  signInAtMedplum,
  tanWeiMing,
} from "./fhircast.helpers";
import { expectPatientInContext } from "./patient.helpers";
import {
  deprovisionUser,
  freshBrowser,
  provisionServerNamedUser,
  workerConnector,
} from "./scim.helpers";

/**
 * The local Epic stand-in, proved as the SMART EHR launcher: an EHR launch
 * through Medplum opens our launch route, passes Medplum's authorization with
 * the seeded clinician signing in on Medplum's own page, and our callback, and
 * lands on Home (`/`) signed in as the User provisioned for that clinician's
 * Practitioner — with the patient the launch put in context.
 *
 * It runs only in the `fhircast` project, which `make integration-test`
 * declares when it starts the stand-in and the backend with Epic Login pointed
 * at Medplum; the `epic` project drives Epic Login itself through the same
 * stand-in.
 *
 * Medplum gives the clinician a server-assigned Practitioner id, so the User's
 * `userName` is that id rather than an `e2e-` name: `provisionServerNamedUser`
 * clears any User of that name a crashed run left behind before provisioning
 * its own, and the spec deletes its own in a `finally`. The seeded clinician
 * is this spec's alone; the seed makes the `epic` spec clinicians of its own.
 */

const directory = workerConnector("fhircast");

test("an EHR launch through Medplum signs the provisioned clinician in and lands on Home", async ({
  browser,
}) => {
  expectStandInConfigured();

  const id = await provisionServerNamedUser(directory.scim, PRACTITIONER_ID);
  const clinician = await freshBrowser(browser);
  try {
    // What the EHR opens in the clinician's browser.
    const launch = new URLSearchParams({
      iss: FHIR_BASE,
      launch: await createLaunch(await tanWeiMing()),
    });
    const page = await clinician.newPage();
    await page.goto(`/api/auth/epic/launch?${launch.toString()}`);
    await signInAtMedplum(page);

    await expect(page).toHaveURL("/");
    await expect(page.getByText(`Signed in as ${PRACTITIONER_ID}`)).toBeVisible();
    await expectPatientInContext(page, "Tan Wei Ming");
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

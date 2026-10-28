import { randomUUID } from "node:crypto";

import { expect, test, type Browser, type Page } from "@playwright/test";

import { cardTitled } from "./card.helpers";
import {
  createLaunch,
  expectStandInConfigured,
  FHIR_BASE,
  type MedplumPatient,
  publishPatientEvent,
  releasedTopic,
  seededClinician,
  signInAtMedplum,
  sitiNurhaliza,
  subscribeDirectly,
  TAN_WEI_MING,
  tanWeiMing,
} from "./fhircast.helpers";
import { expectNoPatientInContext, expectPatientInContext } from "./patient.helpers";
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

/**
 * The publishing helper every case below relies on, proved against the
 * stand-in's hub first: a websocket subscriber of its own, subscribed straight
 * to Medplum's hub through the shim, receives the `Patient-open` the helper
 * publishes to its topic. It signs no one in.
 */
test("a Patient-open published to a topic reaches a websocket subscriber of that topic", async () => {
  expectStandInConfigured();
  const topic = `e2e-${randomUUID()}`;
  const subscriber = await subscribeDirectly(topic, ["Patient-open", "Patient-close"]);
  try {
    await subscriber.next((message) => message["hub.mode"] === "subscribe");

    const id = await publishPatientEvent(topic, "Patient-open", TAN_WEI_MING);

    const notification = await subscriber.next((message) => message.id === id);
    expect(notification.event).toEqual({
      "hub.topic": topic,
      "hub.event": "Patient-open",
      context: [{ key: "patient", resource: { resourceType: "Patient", id: TAN_WEI_MING } }],
      "context.versionId": expect.any(String),
    });
  } finally {
    subscriber.close();
  }
});

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

test("a Patient-open for another patient replaces the launch's patient", async ({ browser }) => {
  await onConnectedHome(
    browser,
    "fhircast-open-another@example.com",
    await tanWeiMing(),
    async (page, hubTopic) => {
      await expectPatientInContext(page, "Tan Wei Ming");

      await publishPatientEvent(hubTopic, "Patient-open", (await sitiNurhaliza()).epicId);

      await expectPatientInContext(page, "Siti Nurhaliza binte Ahmad");
      await expect(
        cardTitled(page, "Patient").getByText("Tan Wei Ming", { exact: true }),
      ).toHaveCount(0);
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
      await page.getByRole("link", { name: "Change password" }).click();
      await expect(page.getByRole("heading", { level: 1, name: "Change password" })).toBeVisible();

      await publishPatientEvent(hubTopic, "Patient-open", (await sitiNurhaliza()).epicId);
      await page.getByRole("link", { name: "Back" }).click();

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

import { execFileSync } from "node:child_process";

import { expect, test } from "@playwright/test";

import { DEV_FIXTURES, groupPermissionsOf } from "./auth.helpers";
import {
  createLaunch,
  expectStandInConfigured,
  FHIR_BASE,
  seededClinician,
  signInAtMedplum,
  tanWeiMing,
} from "./fhircast.helpers";
import {
  expectNoPatientInContext,
  expectPatientInContext,
  recordPatientRequests,
} from "./patient.helpers";
import {
  deprovisionUser,
  freshBrowser,
  provisionServerNamedUser,
  scimPatch,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * Epic Login end to end, with the local Epic stand-in, Medplum, standing in
 * for Epic: an EHR launch for a clinician whose Practitioner id is the
 * `userName` of a provisioned User opens our launch route, passes Medplum's
 * authorization with that clinician signing in on Medplum's own page, and our
 * callback, and lands on Home (`/`) signed in as that User with the
 * Permissions its Groups confer, the Patient card showing the patient the
 * launch put in context — or "No patient in context" for a launch that put
 * none; a launch for a clinician no User is provisioned for lands on the login
 * page's refused notice instead, as does a launch whose `iss` is not exactly
 * the configured FHIR base (D10), and a launch whose token call the stand-in
 * cannot answer lands on the unavailable notice. The other protocol refusals —
 * a forged or replayed callback, a bad `id_token` — cannot be forged through
 * Medplum, and are the backend's fake-Epic integration tests'
 * (`EpicProtocolIntegrationTests`).
 *
 * The clinicians and Tan Wei Ming are Medplum seed data
 * (`backend/fhircast-shim/src/seed.mjs`), each case signing in as a clinician
 * of its own: Medplum signs in whoever signs in on its page, as the
 * Practitioner that sign-in's profile is, and assigns that Practitioner's id
 * itself, a UUID, which the spec reads back. So a User is named with that id,
 * through `provisionServerNamedUser`, rather than with an `e2e-` name; the id
 * holds from one run to the next, so that also clears any User a crashed run
 * left behind.
 *
 * The unavailable case pauses the DCR shim's container, which serves Medplum's
 * token endpoint, for the length of one callback. That would stall any other
 * launch in flight, so the spec's cases run one after another in a single
 * worker (`mode: "default"`: in order, and a failure skips nothing after it),
 * and the `epic` project runs after the `fhircast` project, whose specs reach
 * the same shim (playwright.config.ts).
 *
 * Epic Login's part of the E2E gate (frontend/AGENTS.md). It runs only in the
 * `epic` project, which `make integration-test` declares when it starts the
 * stand-in and the backend with Epic Login pointed at it.
 *
 * The User is one this spec provisions over SCIM, as `accounts-admin.spec.ts`
 * does, with a token issued through the Admin session `setup` saved; it is
 * deleted again in a `finally`. Its password is settled first, as every spec
 * that signs a provisioned User in does: the connector's write leaves a
 * password change pending, which confines a password Login of the User to
 * `/change-password`. An Epic Login is not confined by it (ADR 0008),
 * which the flagged-User case pins by launching for a User left unsettled.
 */

/** The shim's Docker container, paused to make the token endpoint unreachable. */
const SHIM_CONTAINER = process.env.E2E_EPIC_SHIM_CONTAINER ?? "";

test.describe.configure({ mode: "default" });

const directory = workerConnector("epic");

/**
 * The launch the EHR opens in the clinician's browser: `iss`, which must equal
 * `APP_EPIC_FHIR_BASE` (D10), and a SmartAppLaunch whose patient, the token
 * response's `patient`, is Tan Wei Ming — one of the dev profile's patient
 * fixtures, whose Epic id the backend passes to the SPA in the landing's
 * fragment (D8) for Home's Patient card to read — or nobody.
 */
async function launchUrl({
  iss = FHIR_BASE,
  withPatient = true,
}: { iss?: string; withPatient?: boolean } = {}): Promise<string> {
  const launch = new URLSearchParams({
    iss,
    launch: await createLaunch(withPatient ? await tanWeiMing() : undefined),
  });
  return `/api/auth/epic/launch?${launch.toString()}`;
}

test("an EHR launch from the local Epic stand-in lands on Home as the Practitioner's User", async ({
  browser,
  page: adminPage,
}) => {
  expectStandInConfigured();

  // The Group carrying the Helpdesk's Permissions, read the way an operator
  // reviews them: the Groups listing, where each row carries its Permissions.
  const groups = (await (await adminPage.request.get("/api/admin/groups")).json()) as Array<{
    id: string;
    permissions: string[];
  }>;
  const helpdesk = groups.find((group) => group.id === DEV_FIXTURES.helpdesk.group);
  expect(helpdesk?.permissions, "the Helpdesk Group's Permissions").toEqual(
    groupPermissionsOf("helpdesk"),
  );

  const medplum = await seededClinician("epic-helpdesk@example.com");
  // The Practitioner ID, and so the `userName` of the User it links to (D2, D3).
  const practitioner = medplum.practitionerId;
  const id = await provisionServerNamedUser(directory.scim, practitioner);
  const clinician = await freshBrowser(browser);
  try {
    await settlePassword(practitioner);
    const added = await scimPatch(directory.scim, `/scim/v2/Groups/${helpdesk!.id}`, [
      { op: "add", path: "members", value: [{ value: id }] },
    ]);
    expect(added.status(), "adding the User to the Helpdesk Group").toBe(200);

    const page = await clinician.newPage();
    await page.goto(await launchUrl());
    await signInAtMedplum(page, medplum);

    // The landing's fragment is read and stripped, so the address bar is Home's alone.
    await expect(page).toHaveURL("/");
    await expect(page.getByText(`Signed in as ${practitioner}`)).toBeVisible();
    const me = (await (await page.request.get("/api/auth/me")).json()) as {
      permissions: string[];
      username: string;
    };
    expect(me).toEqual(
      expect.objectContaining({
        permissions: [...DEV_FIXTURES.helpdesk.permissions],
        username: practitioner,
      }),
    );
    await expectPatientInContext(page, "Tan Wei Ming");

    // A reload keeps the tab's Launch context, though the URL no longer carries it.
    await page.reload();
    await expectPatientInContext(page, "Tan Wei Ming");
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("an EHR launch for a User with the change-required flag lands on Home, not /change-password", async ({
  browser,
}) => {
  expectStandInConfigured();

  // Provisioned and never settled: the connector's password write leaves the
  // change-required flag set. Only a password Login is confined by it; an Epic
  // Login presents no password of ours (D20), so its session is not confined.
  const medplum = await seededClinician("epic-flagged@example.com");
  const flagged = medplum.practitionerId;
  const id = await provisionServerNamedUser(directory.scim, flagged);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    await page.goto(await launchUrl());
    await signInAtMedplum(page, medplum);

    await expect(page).toHaveURL("/");
    const me = (await (await page.request.get("/api/auth/me")).json()) as {
      passwordChangeRequired: boolean;
      username: string;
    };
    expect(me).toEqual(
      expect.objectContaining({ passwordChangeRequired: false, username: flagged }),
    );
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("a launch whose token response names no patient lands on Home with no patient in context", async ({
  browser,
}) => {
  expectStandInConfigured();

  const medplum = await seededClinician("epic-no-patient@example.com");
  const id = await provisionServerNamedUser(directory.scim, medplum.practitionerId);
  const browserContext = await freshBrowser(browser);
  try {
    const page = await browserContext.newPage();
    const patientRequests = recordPatientRequests(page);
    // A SmartAppLaunch with no patient: Medplum's token response names none.
    await page.goto(await launchUrl({ withPatient: false }));
    await signInAtMedplum(page, medplum);

    await expect(page).toHaveURL("/");
    await expectNoPatientInContext(page);
    expect(patientRequests()).toEqual([]);
  } finally {
    await browserContext.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("an EHR launch for an unprovisioned Practitioner lands on the refused notice", async ({
  browser,
}) => {
  expectStandInConfigured();

  // No User carries this Practitioner's id: Epic proves a clinician nobody provisioned.
  const medplum = await seededClinician("epic-unprovisioned@example.com");
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    await page.goto(await launchUrl());
    await signInAtMedplum(page, medplum);

    await expect(page).toHaveURL(/\/login\?signin=refused$/);
    await expect(page.getByRole("status")).toHaveText("Sign-in from Epic was refused");
  } finally {
    await clinician.close();
  }
});

test("a launch whose iss is not exactly the FHIR base lands on the refused notice", async ({
  browser,
}) => {
  expectStandInConfigured();

  // Medplum's own FHIR base with a trailing slash: `iss` is compared as an
  // exact string, never normalized (D10), so the launch is refused before any
  // redirect to Medplum, and nobody signs in there.
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    await page.goto(await launchUrl({ iss: `${FHIR_BASE}/` }));

    await expect(page).toHaveURL(/\/login\?signin=refused$/);
    await expect(page.getByRole("status")).toHaveText("Sign-in from Epic was refused");
  } finally {
    await clinician.close();
  }
});

test("an EHR launch whose token endpoint is unreachable lands on the unavailable notice", async ({
  browser,
}) => {
  expectStandInConfigured();
  expect(SHIM_CONTAINER, "E2E_EPIC_SHIM_CONTAINER names the DCR shim's container").not.toBe("");

  // Who the launch is for does not matter: the Login never gets as far as a User.
  const medplum = await seededClinician("epic-unprovisioned@example.com");
  const clinician = await freshBrowser(browser);
  let paused = false;
  try {
    const page = await clinician.newPage();
    // The shim, which fronts Medplum's token endpoint, becomes unreachable as
    // Medplum's page sends the browser on to our callback, so our one token
    // call to redeem the code gets no answer and times out (D23, D26).
    await page.route("**/api/auth/epic/callback?**", async (route) => {
      execFileSync("docker", ["pause", SHIM_CONTAINER]);
      paused = true;
      await route.continue();
    });
    await page.goto(await launchUrl());
    await signInAtMedplum(page, medplum);

    // Past the backend's read timeout (APP_EPIC_READ_TIMEOUT, 5 seconds by default).
    await expect(page).toHaveURL(/\/login\?signin=unavailable$/, { timeout: 20_000 });
    expect(paused, "the shim was paused for the callback").toBe(true);
    await expect(page.getByRole("status")).toHaveText(
      "Sign-in from Epic is temporarily unavailable. Try again shortly.",
    );
  } finally {
    if (paused) {
      execFileSync("docker", ["unpause", SHIM_CONTAINER]);
    }
    await clinician.close();
  }
});

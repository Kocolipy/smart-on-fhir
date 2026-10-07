import { expect, test } from "@playwright/test";

import { DEV_ROLES } from "./auth.helpers";
import {
  deprovisionUser,
  E2E_PREFIX,
  freshBrowser,
  provisionUser,
  runId,
  scimPatch,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * Epic Login's happy path, end to end, with the SMART Health IT launcher
 * standing in for Epic: a provider EHR launch for a Practitioner whose ID is the
 * `userName` of a provisioned User opens our launch route, passes the
 * launcher's authorization and our callback, and lands on `/showcase` signed in
 * as that User with the Permissions its Groups confer.
 *
 * Epic Login's E2E conditional gate (frontend/AGENTS.md). It runs only in the
 * `epic` project, which `make epic-integration-test` declares by exporting the
 * launcher's FHIR base; that script also starts the launcher and the backend
 * with Epic Login pointed at it.
 *
 * The User is one this spec provisions over SCIM, as `accounts-admin.spec.ts`
 * does, with a token issued through the Admin session `setup` saved; it is
 * deleted again in a `finally`. Its password is settled first, as every spec
 * that signs a provisioned User in does: the connector's write leaves a
 * password change pending, which confines any session of the User — an Epic
 * one included — to `/change-password`.
 */

/** The launcher's FHIR base: the launch `iss`, which must equal `APP_EPIC_FHIR_BASE` (D10). */
const FHIR_BASE = process.env.E2E_EPIC_FHIR_BASE ?? "";
/** Our JWKS as the launcher container reaches it, to verify our client assertion. */
const JWKS_URL = process.env.E2E_EPIC_JWKS_URL ?? "";

/** The Practitioner ID, and so the `userName` of the User it links to (D2, D3). */
const PRACTITIONER = `${E2E_PREFIX}epic-${runId()}`;
/** Any patient: the launcher puts it in the launch context, which Epic Login drops (D8). */
const PATIENT = "e2e-epic-patient";

const directory = workerConnector("epic");

/**
 * The launch options exactly as the launcher's own UI encodes them for a
 * provider EHR launch (its `src/isomorphic/codec.ts`): a base64url JSON array.
 * The login and approval screens are skipped and no encounter is picked, so
 * the launcher's authorize answers at once; the client is asymmetric, so the
 * launcher verifies our `private_key_jwt` against our published JWKS.
 */
function launchOptions(practitioner: string): string {
  const options = [
    0, // launch_type: provider-ehr
    PATIENT,
    practitioner, // provider: the user the id_token's fhirUser names
    "NONE", // encounter: none, so nothing is looked up
    1, // skip_login
    1, // skip_auth
    0, // sim_ehr
    "", // scope: whatever the client asks for
    "", // redirect_uris: not pinned
    "", // client_id: not pinned
    "", // client_secret
    "", // auth_error: none simulated
    JWKS_URL,
    "", // jwks
    2, // client_type: confidential-asymmetric
    1, // pkce: auto
    "", // fhir_server: the launcher's default
  ];
  return Buffer.from(JSON.stringify(options), "utf8").toString("base64url");
}

test("a provider EHR launch from the SMART launcher lands on /showcase as the Practitioner's User", async ({
  browser,
  page: adminPage,
}) => {
  expect(FHIR_BASE, "E2E_EPIC_FHIR_BASE names the launcher's FHIR base").not.toBe("");
  expect(JWKS_URL, "E2E_EPIC_JWKS_URL names our JWKS as the launcher reaches it").not.toBe("");

  // The Group conferring the Account admin Role, read the way an operator would find it.
  const roles = (await (await adminPage.request.get("/api/admin/roles")).json()) as Array<{
    name: string;
    groups: Array<{ id: string }>;
  }>;
  const accountAdmin = roles.find((role) => role.name === "Account admin");
  expect(accountAdmin?.groups, "the Account admin Role is conferred by one Group").toHaveLength(1);

  const id = await provisionUser(directory.scim, PRACTITIONER);
  const clinician = await freshBrowser(browser);
  try {
    await settlePassword(PRACTITIONER);
    const added = await scimPatch(directory.scim, `/scim/v2/Groups/${accountAdmin!.groups[0].id}`, [
      { op: "add", path: "members", value: [{ value: id }] },
    ]);
    expect(added.status(), "adding the User to the Account admin Group").toBe(200);

    // What the EHR opens in the clinician's browser.
    const launch = new URLSearchParams({ iss: FHIR_BASE, launch: launchOptions(PRACTITIONER) });
    const page = await clinician.newPage();
    await page.goto(`/api/auth/epic/launch?${launch.toString()}`);

    await expect(page).toHaveURL(/\/showcase$/);
    await expect(page.getByText(`Signed in as ${PRACTITIONER}`)).toBeVisible();
    const me = (await (await page.request.get("/api/auth/me")).json()) as {
      permissions: string[];
      username: string;
    };
    expect(me).toEqual(
      expect.objectContaining({
        permissions: [...DEV_ROLES.accountAdmin.permissions],
        username: PRACTITIONER,
      }),
    );
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

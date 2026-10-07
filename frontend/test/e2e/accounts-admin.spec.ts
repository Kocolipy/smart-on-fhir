import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

import { LOCKOUT_MAX_ATTEMPTS, submitLoginViaApi } from "./auth.helpers";
import {
  anonymousApi,
  createConnector,
  deleteConnector,
  deprovisionUser,
  E2E_PREFIX,
  OWN_PASSWORD,
  provisionUser,
  rowOf,
  runId,
  scimApi,
  scimPatch,
  settlePassword,
  USER_SCHEMA,
  workerConnector,
} from "./scim.helpers";

/**
 * The Accounts page driven as an Admin uses it: both read-only projections,
 * Unlock, the forced password change, and a connector's token lifecycle, all
 * from the one interface.
 *
 * Every User this spec changes is one it PROVISIONS for itself, over SCIM,
 * with a token it issued through the page. The seeded `user` is never locked or
 * flagged here: since Unlock and a forced change both require a password change,
 * and password history refuses the old password back, a seeded identity put in
 * either state could not be restored for the specs that sign in as it. The
 * throwaway Users and the connectors are deleted in a `finally`; whatever a
 * crashed run leaves behind is swept by `auth.setup.ts` before the next run.
 *
 * Serial because the steps depend on each other: the token issued in the
 * second step is what provisions the Users every later step acts on.
 */

/** The backend's lockout threshold; see `LOCKOUT_MAX_ATTEMPTS`. */
const REFUSALS_BEFORE_LOCKOUT = LOCKOUT_MAX_ATTEMPTS;

const RUN = runId();
const CONNECTOR_NAME = `${E2E_PREFIX}connector-${RUN}`;
const READ_ONLY_CONNECTOR = `${E2E_PREFIX}connector-ro-${RUN}`;
const LOCKED_USER = `${E2E_PREFIX}locked-${RUN}`;
const FORCED_USER = `${E2E_PREFIX}forced-${RUN}`;

/** For the tests outside the serial journey, whose own connector is created and deleted in it. */
const directory = workerConnector("accounts");

/** Whether a fresh login as this User is accepted, and whether it is confined to the change. */
async function signInState(userName: string) {
  const api = await anonymousApi();
  try {
    const login = await submitLoginViaApi(api, userName, OWN_PASSWORD);
    if (login.status() !== 200) return { accepted: false, confined: false };
    const me = (await (await api.get("/api/auth/me")).json()) as {
      passwordChangeRequired?: boolean;
    };
    return { accepted: true, confined: me.passwordChangeRequired === true };
  } finally {
    await api.dispose();
  }
}

const usersTable = (page: Page) => page.getByRole("table", { name: "Users" });
const groupsTable = (page: Page) => page.getByRole("table", { name: "Groups" });

async function openAccounts(page: Page) {
  await page.goto("/accounts");
  await expect(page.getByRole("heading", { name: "Accounts" })).toBeVisible();
  await expect(usersTable(page).getByRole("row").nth(1)).toBeVisible();
  await expect(groupsTable(page).getByRole("row").nth(1)).toBeVisible();
}

/** Create a connector from the page and issue it a token carrying `permissions`, read off the disclosure. */
async function issueFromPage(page: Page, name: string, permissions: readonly string[]) {
  await page.getByLabel("New connector name").fill(name);
  await page.getByRole("button", { name: "Create connector" }).click();
  const section = page.getByRole("region", { name: `Connector ${name}` });
  await expect(section).toBeVisible();
  const choices = section.getByRole("group", { name: `Permissions for ${name}` });
  for (const permission of permissions) {
    await choices.getByRole("checkbox", { name: new RegExp(`^${permission} `) }).check();
  }
  await section.getByRole("button", { name: `Issue token for ${name}` }).click();
  const value = await page.getByLabel("New token value").textContent();
  expect(value).toBeTruthy();
  return { section, value: value! };
}

test.describe.serial("ADMIN accounts page", () => {
  test("shows both projections with no writable identity or membership control", async ({
    page,
  }) => {
    await openAccounts(page);

    for (const table of [usersTable(page), groupsTable(page)]) {
      await expect(table.getByRole("textbox")).toHaveCount(0);
      await expect(table.getByRole("checkbox")).toHaveCount(0);
      await expect(table.getByRole("combobox")).toHaveCount(0);
      await expect(table.getByRole("spinbutton")).toHaveCount(0);
    }
    await expect(groupsTable(page).getByRole("button")).toHaveCount(0);
    for (const label of await usersTable(page).getByRole("button").allTextContents()) {
      expect(label).toMatch(/^(Unlock|Force password change for) /);
    }
    await expect(page.getByRole("button", { name: /Disable|Enable|Deactivate/ })).toHaveCount(0);

    // The protected Admin group is marked, and Unlock is described as the only lift.
    await expect(groupsTable(page).getByText("Protected Admin group")).toHaveCount(1);
    await expect(
      page.getByText(/A lockout never expires: Unlock is the only way it ends/),
    ).toBeVisible();

    // The Bootstrap Admin — this project's own session — shows no lockout state and no Unlock.
    const bootstrap = rowOf(page, usersTable(page), "admin");
    await expect(bootstrap.getByText("Bootstrap Admin")).toBeVisible();
    await expect(bootstrap.getByTitle("The Bootstrap Admin cannot be locked")).toBeVisible();
    await expect(bootstrap.getByText(/^(Locked|Not locked)$/)).toHaveCount(0);
    await expect(bootstrap.getByRole("button", { name: /Unlock/ })).toHaveCount(0);
  });

  test("issues a read-only token, which may read but not write", async ({ page }) => {
    let client: APIRequestContext | undefined;
    try {
      await openAccounts(page);
      const { section, value } = await issueFromPage(page, READ_ONLY_CONNECTOR, [
        "user:read",
        "group:read",
      ]);

      // The disclosure and the token row both name the Permissions that were issued.
      await expect(page.getByText(/^group:read, user:read · expires /)).toBeVisible();
      await expect(
        section.getByRole("cell", { name: "group:read, user:read", exact: true }),
      ).toHaveCount(1);

      client = await scimApi(value);
      expect((await client.get("/scim/v2/Users?count=1")).status()).toBe(200);
      const write = await client.post("/scim/v2/Users", {
        data: { schemas: [USER_SCHEMA], userName: `${E2E_PREFIX}ro-write-${RUN}` },
      });
      // 403, not 401: the token authenticated, and it lacks user:write.
      expect(write.status()).toBe(403);
      expect(write.headers()["www-authenticate"]).toContain('error="insufficient_scope"');

      await section.getByRole("button", { name: `Delete ${READ_ONLY_CONNECTOR}` }).click();
      await expect(section).toHaveCount(0);
    } finally {
      await client?.dispose();
      await deleteOwnConnectors(page);
    }
  });

  test("unlocks a User, forces another's change and manages a connector's tokens", async ({
    page,
  }) => {
    // Two provisions, two self-service changes, a lockout's worth of refused
    // logins and four confirming sign-ins, each a password hash on the backend.
    test.setTimeout(120_000);
    const provisioned: string[] = [];
    let scim: APIRequestContext | undefined;

    try {
      // A connector and a token carrying every directory Permission, from the
      // page. The value is read off the one-time disclosure — there is no other
      // way to get it.
      await openAccounts(page);
      const { section, value: firstValue } = await issueFromPage(page, CONNECTOR_NAME, [
        "group:read",
        "group:write",
        "user:read",
        "user:write",
      ]);
      await expect(section.getByText("Active")).toHaveCount(1);

      // Two Users of this spec's own, each settled on a password it chose.
      scim = await scimApi(firstValue);
      provisioned.push(
        await provisionUser(scim, LOCKED_USER),
        await provisionUser(scim, FORCED_USER),
      );
      await settlePassword(LOCKED_USER);
      await settlePassword(FORCED_USER);

      // Lock the first the way a forgetful person does.
      const guesser = await anonymousApi();
      try {
        for (let attempt = 0; attempt < REFUSALS_BEFORE_LOCKOUT; attempt += 1) {
          expect((await submitLoginViaApi(guesser, LOCKED_USER, "not-the-password")).status()).toBe(
            401,
          );
        }
      } finally {
        await guesser.dispose();
      }
      expect(await signInState(LOCKED_USER)).toEqual({ accepted: false, confined: false });

      // Unlock it from the page.
      await page.reload();
      const locked = rowOf(page, usersTable(page), LOCKED_USER);
      await expect(locked.getByText("Locked: failed logins", { exact: true })).toBeVisible();
      await locked.getByRole("button", { name: `Unlock ${LOCKED_USER}` }).click();
      await expect(locked.getByText("Not locked")).toBeVisible();
      await expect(locked.getByText("Required")).toBeVisible();
      await expect(locked.getByRole("button", { name: /^Unlock/ })).toHaveCount(0);
      // The backend's doing: the password works again, into the change flow only.
      expect(await signInState(LOCKED_USER)).toEqual({ accepted: true, confined: true });

      // Force the second's change from the page.
      const forced = rowOf(page, usersTable(page), FORCED_USER);
      await expect(forced.getByText("No", { exact: true })).toBeVisible();
      await forced
        .getByRole("button", { name: `Force password change for ${FORCED_USER}` })
        .click();
      await expect(forced.getByText("Required")).toBeVisible();
      await expect(forced.getByRole("button", { name: /^Force password change/ })).toHaveCount(0);
      expect(await signInState(FORCED_USER)).toEqual({ accepted: true, confined: true });

      // Deprovision with the token that provisioned them, before rotating it away.
      for (const id of provisioned.splice(0)) await deprovisionUser(scim, id);
      await scim.dispose();
      scim = undefined;

      // The disclosure did not survive the reload before the Unlock: the value
      // exists nowhere on the page any more, only in the client that used it.
      await expect(page.getByLabel("New token value")).toHaveCount(0);
      expect(await page.content()).not.toContain(firstValue);

      // Rotate from the page: a new value, disclosed once; the old one stops working.
      await section.getByRole("button", { name: /^Rotate token/ }).click();
      const rotatedValue = await page.getByLabel("New token value").textContent();
      expect(rotatedValue).toBeTruthy();
      expect(rotatedValue).not.toBe(firstValue);
      const oldClient = await scimApi(firstValue);
      const newClient = await scimApi(rotatedValue!);
      try {
        expect((await oldClient.get("/scim/v2/Users?count=1")).status()).toBe(401);
        expect((await newClient.get("/scim/v2/Users?count=1")).status()).toBe(200);

        // The disclosure is gone after a reload, and nothing on the page carries the value.
        await page.reload();
        await expect(page.getByLabel("New token value")).toHaveCount(0);
        expect(await page.content()).not.toContain(rotatedValue!);

        // Revoke from the page.
        await section.getByRole("button", { name: /^Revoke token/ }).click();
        await expect(section.getByRole("button", { name: /^Revoke token/ })).toHaveCount(0);
        await expect(section.getByText(/^Revoked /)).toHaveCount(1);
        expect((await newClient.get("/scim/v2/Users?count=1")).status()).toBe(401);
      } finally {
        await oldClient.dispose();
        await newClient.dispose();
      }

      // Delete the connector from the page.
      await section.getByRole("button", { name: `Delete ${CONNECTOR_NAME}` }).click();
      await expect(section).toHaveCount(0);
    } finally {
      await cleanUp(page, scim, provisioned);
    }
  });
});

/**
 * Unlocking and deactivating are separate capabilities, and neither performs the
 * other: a User the directory deactivated stays refused after an Admin unlocks
 * it, and signs in again only once the directory reactivates it — into the
 * change, since both the Unlock and the reactivation flag one.
 */
test("an Unlock leaves a deactivated User deactivated, until the directory reactivates it", async ({
  page,
}) => {
  test.setTimeout(90_000);
  const userName = `${E2E_PREFIX}inactive-${RUN}`;
  const id = await provisionUser(directory.scim, userName);
  try {
    await settlePassword(userName);
    const guesser = await anonymousApi();
    try {
      for (let attempt = 0; attempt < REFUSALS_BEFORE_LOCKOUT; attempt += 1) {
        expect((await submitLoginViaApi(guesser, userName, "not-the-password")).status()).toBe(401);
      }
    } finally {
      await guesser.dispose();
    }
    const deactivated = await scimPatch(directory.scim, `/scim/v2/Users/${id}`, [
      { op: "replace", path: "active", value: false },
    ]);
    expect(deactivated.status(), "deactivating over SCIM").toBe(200);

    await openAccounts(page);
    const row = rowOf(page, usersTable(page), userName);
    await expect(row.getByText("Inactive", { exact: true })).toBeVisible();
    await expect(row.getByText("Locked: failed logins", { exact: true })).toBeVisible();

    await row.getByRole("button", { name: `Unlock ${userName}` }).click();
    await expect(row.getByText("Not locked")).toBeVisible();
    await expect(row.getByText("Inactive", { exact: true })).toBeVisible();
    expect(await signInState(userName)).toEqual({ accepted: false, confined: false });
    const read = await directory.scim.get(`/scim/v2/Users/${id}`);
    expect(((await read.json()) as { active: boolean }).active).toBe(false);

    const reactivated = await scimPatch(directory.scim, `/scim/v2/Users/${id}`, [
      { op: "replace", path: "active", value: true },
    ]);
    expect(reactivated.status(), "reactivating over SCIM").toBe(200);
    expect(await signInState(userName)).toEqual({ accepted: true, confined: true });
  } finally {
    await deprovisionUser(directory.scim, id);
  }
});

/**
 * Deletes this test's own Users and connectors, if the happy path did not get
 * to. Only its own, by this run's suffix: a prefix sweep here could delete a
 * parallel spec's fixtures. A crashed run's leftovers are swept by
 * `auth.setup.ts` before the next run instead.
 */
async function cleanUp(page: Page, scim: APIRequestContext | undefined, provisioned: string[]) {
  try {
    if (provisioned.length > 0) {
      // The test's own token may already be rotated or revoked; mint one for the purpose.
      const client =
        scim ?? (await scimApi((await createConnector(page, `${E2E_PREFIX}cleanup-${RUN}`)).token));
      try {
        for (const id of provisioned) await deprovisionUser(client, id);
      } finally {
        await client.dispose();
      }
    } else {
      await scim?.dispose();
    }
  } finally {
    await deleteOwnConnectors(page);
  }
}

async function deleteOwnConnectors(page: Page) {
  const connectors = (await (await page.request.get("/api/admin/connectors")).json()) as Array<{
    displayName: string;
    id: string;
  }>;
  for (const own of connectors.filter((connector) => connector.displayName.endsWith(RUN))) {
    await deleteConnector(page, own.id);
  }
}

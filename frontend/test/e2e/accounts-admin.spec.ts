import { expect, test, type APIRequestContext, type Page } from "@playwright/test";

import { LOCKOUT_MAX_ATTEMPTS, postAdminAction, submitLoginViaApi } from "./auth.helpers";
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
 * Unlock of a locked User — and its refusal once the User is no longer locked —
 * and a connector's token lifecycle, all from the one interface.
 *
 * Every User this spec changes is one it PROVISIONS for itself, over SCIM,
 * with a token it issued through the page. The seeded `user` is never locked or
 * flagged here: since Unlock requires a password change, and password history
 * refuses the old password back, a seeded identity put in either state could
 * not be restored for the specs that sign in as it. The
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
/** Locked, then unlocked behind the page's back, so the page's Unlock meets the 409. */
const RACED_USER = `${E2E_PREFIX}raced-${RUN}`;

/** For the tests outside the serial journey, whose own connector is created and deleted in it. */
const directory = workerConnector("accounts");

/** Lock `userName` the way a forgetful person does: a lockout's worth of refused logins. */
async function lockOut(userName: string) {
  const guesser = await anonymousApi();
  try {
    for (let attempt = 0; attempt < REFUSALS_BEFORE_LOCKOUT; attempt += 1) {
      expect((await submitLoginViaApi(guesser, userName, "not-the-password")).status()).toBe(401);
    }
  } finally {
    await guesser.dispose();
  }
}

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
    // The Groups table's one write is the Superuser's Edit permissions (#159), offered on
    // every Group but the Admin group; membership stays the directory's.
    for (const label of await groupsTable(page).getByRole("button").allTextContents()) {
      expect(label).toMatch(/^Edit permissions for /);
    }
    await expect(
      groupsTable(page)
        .getByRole("row")
        .filter({ has: page.getByRole("cell", { exact: true, name: "Superuser" }) })
        .getByRole("button"),
    ).toHaveCount(0);
    for (const label of await usersTable(page).getByRole("button").allTextContents()) {
      expect(label).toMatch(/^Unlock /);
    }
    await expect(page.getByRole("button", { name: /Disable|Enable|Deactivate/ })).toHaveCount(0);

    // The Admin group confers the Superuser, a fixture Group shows the Permissions it
    // carries, and Unlock is described as the only lift.
    await expect(
      groupsTable(page).getByRole("cell", { exact: true, name: "Superuser" }),
    ).toHaveCount(1);
    await expect(
      groupsTable(page)
        .getByRole("row", { name: /^Helpdesk/ })
        .getByRole("cell", {
          exact: true,
          name: "account:unlock, audit:read, connector:read, group:read, ops:read, user:read",
        }),
    ).toBeVisible();
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

  test("unlocks a locked User, reports one unlocked meanwhile, and manages a connector's tokens", async ({
    page,
  }) => {
    // Two provisions, two self-service changes, two lockouts' worth of refused
    // logins and three confirming sign-ins, each a password hash on the backend.
    test.setTimeout(150_000);
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
        await provisionUser(scim, RACED_USER),
      );
      await settlePassword(LOCKED_USER);
      await settlePassword(RACED_USER);

      // Lock both the way a forgetful person does.
      await lockOut(LOCKED_USER);
      await lockOut(RACED_USER);
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

      // The second is unlocked behind the page's back — as by another
      // administrator — so the Unlock the page still offers has nothing to lift:
      // refused, said so, and nothing changes.
      const raced = rowOf(page, usersTable(page), RACED_USER);
      await expect(raced.getByText("Locked: failed logins", { exact: true })).toBeVisible();
      const raceId = provisioned[1]!;
      expect((await postAdminAction(page, raceId, "unlock")).status()).toBe(200);
      await raced.getByRole("button", { name: `Unlock ${RACED_USER}` }).click();
      await expect(page.getByRole("alert")).toHaveText(
        `${RACED_USER} is no longer locked. Reload the page for the current list.`,
      );
      expect((await postAdminAction(page, raceId, "unlock")).status()).toBe(409);
      await page.reload();
      await expect(rowOf(page, usersTable(page), RACED_USER).getByText("Not locked")).toBeVisible();
      await expect(
        rowOf(page, usersTable(page), RACED_USER).getByRole("button", { name: /^Unlock/ }),
      ).toHaveCount(0);
      expect(await signInState(RACED_USER)).toEqual({ accepted: true, confined: true });

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
 * A lifetime chosen on the page is the one the token is issued with, and one
 * past the 365-day maximum is held back by the browser before any request.
 */
test("issues a token for the lifetime typed, and holds back one past the maximum", async ({
  page,
}) => {
  const name = `${E2E_PREFIX}lifetime-${RUN}`;
  const issues: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "POST" && request.url().endsWith("/tokens"))
      issues.push(request.url());
  });
  try {
    await openAccounts(page);
    await page.getByLabel("New connector name").fill(name);
    await page.getByRole("button", { name: "Create connector" }).click();
    const section = page.getByRole("region", { name: `Connector ${name}` });
    await section
      .getByRole("group", { name: `Permissions for ${name}` })
      .getByRole("checkbox", { name: /^user:read / })
      .check();
    const lifetime = section.getByLabel(`Lifetime in days for ${name}`);
    const issue = section.getByRole("button", { name: `Issue token for ${name}` });

    await lifetime.fill("366");
    await issue.click();
    expect(await lifetime.evaluate((input: HTMLInputElement) => input.validity.rangeOverflow)).toBe(
      true,
    );
    await expect(page.getByLabel("New token value")).toHaveCount(0);
    expect(issues).toEqual([]);

    await lifetime.fill("7");
    const issued = page.waitForResponse(
      (response) => response.request().method() === "POST" && response.url().endsWith("/tokens"),
    );
    await issue.click();
    const response = await issued;
    expect(response.status()).toBe(201);
    const { expiresAt, issuedAt } = (await response.json()) as {
      expiresAt: string;
      issuedAt: string;
    };
    expect(Date.parse(expiresAt) - Date.parse(issuedAt)).toBe(7 * 24 * 60 * 60 * 1000);

    // Shown as its date in Singapore, on the disclosure and in the token's row.
    const singaporeDate = singaporeDateOf(expiresAt);
    await expect(page.getByText(`user:read · expires ${singaporeDate}`)).toBeVisible();
    await expect(section.getByRole("cell", { name: singaporeDate, exact: true })).toHaveCount(1);
  } finally {
    await deleteOwnConnectors(page);
  }
});

/** The calendar date, `YYYY-MM-DD`, an ISO instant falls on in Singapore. */
function singaporeDateOf(instant: string) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Singapore" }).format(new Date(instant));
}

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

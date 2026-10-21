import { expect, test, type Page } from "@playwright/test";

import { FIXTURE_PASSWORD, loginAsRole, submitLogin, userIdOf } from "./auth.helpers";
import { FIXTURE_GROUPS, freshBrowser, rowOf } from "./scim.helpers";

/**
 * The dormancy lockout and membership revocation end to end (ADR 0007), through
 * the `dormant` development fixture.
 *
 * The dormancy job runs nightly and has no HTTP surface, so this spec relies on
 * the development profile the e2e backend runs with: seeding backdates the
 * fixture's dormancy basis a day past the revocation window — resets its
 * password and lock, and puts it back into the Auditors and Monitoring Groups —
 * at every startup, and `DevDormancyStartupConfig` then runs the real job once.
 * By the time this spec runs, the job has locked the fixture for dormancy and
 * removed every Group membership it held.
 *
 * The journey consumes that state: once the fixture has been unlocked and has
 * chosen a password, it is locked again only by the next backend start. So
 * this spec runs in a project of its own, after every other one, and a rerun
 * against a backend that is still up fails at its first assertion with that
 * reason rather than somewhere unexplained.
 */

const DORMANT = "dormant";
// Free of the user name, which the password policy refuses inside a new password. Fresh per
// run: startup seeding resets the fixture's password but not its password history, so a
// password an earlier run chose is still refused as a reuse.
const CHOSEN = `Returned-After-Absence-${Date.now().toString(36)}`;

const usersTable = (page: Page) => page.getByRole("table", { name: "Users" });

/** The Users table's Groups column, among a row's cells (the User is its row header). */
const GROUPS_COLUMN = 7;

/** The two Groups seeding puts the fixture back into (`dormant-groups` in `authorization.yaml`). */
const { auditors: AUDITORS, monitoring: MONITORING } = FIXTURE_GROUPS;

async function changePassword(page: Page, current: string, next: string) {
  await page.getByLabel("Current password").fill(current);
  await page.getByLabel("New password", { exact: true }).fill(next);
  await page.getByLabel("Confirm new password").fill(next);
  await page.getByRole("button", { name: "Change password" }).click();
}

test("a dormant account is locked for dormancy until an Account admin unlocks it", async ({
  browser,
  page,
}) => {
  test.setTimeout(120_000);
  const returning = await freshBrowser(browser);
  try {
    // An Account admin — a Role holding account:unlock, not the Superuser — reads the list.
    await loginAsRole(page, "accountAdmin");
    await page.goto("/accounts");
    const row = rowOf(page, usersTable(page), DORMANT);
    await expect(
      row.getByText("Locked: dormant"),
      "the dormant fixture is re-armed only by a backend restart; restart it before rerunning",
    ).toBeVisible();
    await expect(row.getByText("Locked: failed logins")).toHaveCount(0);
    // Past the revocation window too: every direct Group membership is gone.
    await expect(row.getByRole("cell").nth(GROUPS_COLUMN)).toHaveText("—");

    // The trail lists both Groups removed and what the User held before.
    const dormantId = await userIdOf(page, DORMANT);
    // In a browser of its own: the dormant User signs in through `returning` below.
    const auditing = await freshBrowser(browser);
    const reader = await auditing.newPage();
    await loginAsRole(reader, "auditor");
    await reader.goto("/audit");
    await reader.getByLabel("Operation").selectOption("DORMANCY_MEMBERSHIP_REVOCATION");
    await reader.getByLabel("Resource id").fill(dormantId);
    await reader.getByRole("button", { name: "Apply filters" }).click();
    const newest = reader
      .getByRole("table", { name: "Audit events" })
      .getByRole("row")
      .filter({ has: reader.getByRole("cell") })
      .first();
    await expect(newest.getByRole("cell").last()).toHaveText(
      `permissions: audit:read, ops:read → none · groups: ${AUDITORS}, ${MONITORING}`,
    );
    await auditing.close();

    // The dormant User cannot sign in: the same refusal a wrong password gets.
    const own = await returning.newPage();
    await submitLogin(own, DORMANT, FIXTURE_PASSWORD);
    await expect(own.getByRole("alert")).toHaveText("The username or password is incorrect.");
    await expect(own).toHaveURL(/\/login$/);

    // The Account admin unlocks it from the page.
    await row.getByRole("button", { name: `Unlock ${DORMANT}` }).click();
    await expect(row.getByText("Not locked")).toBeVisible();
    await expect(row.getByText("Required")).toBeVisible();
    await expect(row.getByRole("button", { name: /^Unlock/ })).toHaveCount(0);

    // Its password now works — into the mandatory change, which it makes.
    await submitLogin(own, DORMANT, FIXTURE_PASSWORD);
    await expect(own).toHaveURL(/\/change-password$/);
    await changePassword(own, FIXTURE_PASSWORD, CHOSEN);
    await expect(own.getByRole("status")).toHaveText(
      "Your password was changed. Sign in with your new password.",
    );

    // And it signs in normally.
    await submitLogin(own, DORMANT, CHOSEN);
    await expect(own).toHaveURL("/");
    await expect(own.getByText(`Signed in as ${DORMANT}`)).toBeVisible();

    // The unlock restarted its dormancy window, so the list shows it in use again.
    await page.reload();
    await expect(rowOf(page, usersTable(page), DORMANT).getByText("Not locked")).toBeVisible();
  } finally {
    await returning.close();
  }
});

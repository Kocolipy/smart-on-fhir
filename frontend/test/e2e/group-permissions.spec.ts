import { expect, test, type Page } from "@playwright/test";

import { loginAs, userIdOf, ADMIN_USERNAME } from "./auth.helpers";
import {
  deprovisionUser,
  E2E_PREFIX,
  freshBrowser,
  OWN_PASSWORD,
  provisionUser,
  rowOf,
  runId,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * A Superuser sets a Group's Permissions from the Accounts page (#159, ADR
 * 0015), seen end to end: the Bootstrap Admin grants `audit:read` to a Group,
 * its member signs in again and can open Audit; taking it away ends that
 * member's live session at once; and the audit page shows both changes as
 * `GROUP_PERMISSIONS_CHANGE` rows.
 *
 * The User and the Group are this spec's own, provisioned over SCIM with an
 * `e2e-` name and removed in a `finally`, so no fixture Group whose members'
 * sessions other projects replay is touched.
 */

const RUN = runId();
const connector = workerConnector("group-permissions");
const GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
const EXPIRED_NOTICE = "Your session ended. Please sign in again.";

/** Ticks or unticks `permission` in the Group's editor on the Accounts page, and saves. */
async function setPermission(page: Page, group: string, permission: string, held: boolean) {
  await page.goto("/accounts");
  const groups = page.getByRole("table", { name: "Groups" });
  await rowOf(page, groups, group)
    .getByRole("button", { name: `Edit permissions for ${group}` })
    .click();
  const dialog = page.getByRole("dialog", { name: `Permissions of ${group}` });
  await dialog.getByRole("checkbox", { name: permission }).setChecked(held);
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(dialog).toBeHidden();
  await expect(rowOf(page, groups, group).getByRole("cell").nth(1)).toHaveText(
    held ? permission : "—",
  );
}

test("the Superuser grants and takes back a Group's Permission, and the trail shows both", async ({
  browser,
  page: adminPage,
}) => {
  test.setTimeout(120_000);
  const userName = `${E2E_PREFIX}trial-member-${RUN}`;
  const groupName = `${E2E_PREFIX}trial-group-${RUN}`;
  const userId = await provisionUser(connector.scim, userName);
  let groupId: string | undefined;
  try {
    await settlePassword(userName);
    const created = await connector.scim.post("/scim/v2/Groups", {
      data: { displayName: groupName, members: [{ value: userId }], schemas: [GROUP_SCHEMA] },
    });
    expect(created.status(), `creating ${groupName}`).toBe(201);
    groupId = ((await created.json()) as { id: string }).id;

    // The grant: the member's next sign-in holds it, and the Audit page opens.
    await setPermission(adminPage, groupName, "audit:read", true);
    const member = await (await freshBrowser(browser)).newPage();
    try {
      await loginAs(member, userName, OWN_PASSWORD);
      await member.goto("/audit");
      await expect(member.getByRole("heading", { name: "Audit", exact: true })).toBeVisible();

      // The removal ends the member's live session at once: its next read is
      // refused, and the SPA sends it to sign in, told the session ended.
      await setPermission(adminPage, groupName, "audit:read", false);
      await member.getByRole("button", { name: "Apply filters" }).click();
      await expect(member.getByRole("heading", { name: "Welcome back" })).toBeVisible();
      await expect(member.getByRole("status")).toHaveText(EXPIRED_NOTICE);
      expect((await member.request.get("/api/auth/me")).status()).toBe(401);
    } finally {
      await member.context().close();
    }

    // Both changes are on the trail, attributed to the Bootstrap Admin.
    await adminPage.goto("/audit");
    await adminPage.getByLabel("Operation").selectOption("GROUP_PERMISSIONS_CHANGE");
    await adminPage.getByLabel("Resource id").fill(groupId);
    await adminPage.getByRole("button", { name: "Apply filters" }).click();
    const rows = adminPage
      .getByRole("table", { name: "Audit events" })
      .getByRole("row")
      .filter({ has: adminPage.getByRole("cell") });
    await expect(rows).toHaveCount(2);
    const admin = await userIdOf(adminPage, ADMIN_USERNAME);
    // Newest first: the removal, then the grant.
    await expect(rows.nth(0).getByRole("cell").last()).toHaveText("permissions: audit:read → none");
    await expect(rows.nth(1).getByRole("cell").last()).toHaveText("permissions: none → audit:read");
    for (const row of [rows.nth(0), rows.nth(1)]) {
      await expect(row.getByRole("cell").nth(0)).toHaveText("GROUP_PERMISSIONS_CHANGE");
      await expect(row.getByRole("cell").nth(1)).toHaveText("SUCCESS");
      await expect(row.getByRole("cell").nth(2)).toHaveText(admin);
    }
  } finally {
    if (groupId !== undefined) {
      const deleted = await connector.scim.delete(`/scim/v2/Groups/${groupId}`);
      expect([204, 404], `deleting ${groupName}`).toContain(deleted.status());
    }
    await deprovisionUser(connector.scim, userId);
  }
});

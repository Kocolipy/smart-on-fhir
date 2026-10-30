import { expect, test } from "@playwright/test";

import { adminRequest, DEV_FIXTURES, loginAs } from "./auth.helpers";
import {
  deprovisionGroup,
  deprovisionUser,
  E2E_PREFIX,
  freshBrowser,
  OWN_PASSWORD,
  provisionGroup,
  provisionUser,
  rowOf,
  searchCard,
  runId,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * A Group's direct members, read on expanding its row on the Accounts page
 * (#213). Member identities are Users-projection data, so the read needs
 * `user:read` beside `group:read`: the replayed Admin session holds both and
 * expands the seeded Auditors Group to find its fixture member, and a session
 * whose Group carries `group:read` alone sees the plain count with nothing to
 * expand.
 *
 * In the `admin` project. The `group:read`-only User and its Group are this
 * spec's own, provisioned over SCIM with an `e2e-` name and removed in a
 * `finally`; the seeded Auditors Group is only read.
 */

const RUN = runId();
const connector = workerConnector("group-members");

test("an admin holding group:read and user:read expands a seeded Group and sees its members", async ({
  page,
}) => {
  await page.goto("/accounts");
  await searchCard(page, "Groups", "Auditors");
  const groups = page.getByRole("table", { name: "Groups" });
  const control = rowOf(page, groups, "Auditors").getByRole("button", {
    name: "Members of Auditors",
  });
  await expect(control).toHaveAttribute("aria-expanded", "false");

  await control.click();

  await expect(control).toHaveAttribute("aria-expanded", "true");
  const members = page.getByRole("region", { name: "Members of Auditors" });
  // The member's line names it by userName, exactly; its display name, if it has one, comes first.
  await expect(
    members.getByRole("listitem").filter({
      has: page.getByText(DEV_FIXTURES.auditor.username, { exact: true }).first(),
    }),
  ).toBeVisible();
  await expect(members.getByText(/^Members 1–\d+ of \d+$/)).toBeVisible();
});

test("a session holding group:read without user:read sees the member count and nothing to expand", async ({
  browser,
  page: adminPage,
}) => {
  test.setTimeout(90_000);
  const userName = `${E2E_PREFIX}group-reader-${RUN}`;
  const groupName = `${E2E_PREFIX}group-readers-${RUN}`;
  const userId = await provisionUser(connector.scim, userName);
  let groupId: string | undefined;
  try {
    await settlePassword(userName);
    groupId = await provisionGroup(connector.scim, groupName, [userId]);
    const granted = await adminRequest(
      adminPage,
      "PUT",
      `/api/admin/groups/${groupId}/permissions`,
      ["group:read"],
    );
    expect(granted.status(), `granting ${groupName} group:read`).toBe(200);

    const reader = await (await freshBrowser(browser)).newPage();
    try {
      await loginAs(reader, userName, OWN_PASSWORD);
      await reader.goto("/accounts");
      await searchCard(reader, "Groups", groupName);
      const groups = reader.getByRole("table", { name: "Groups" });
      const row = rowOf(reader, groups, groupName);
      await expect(row.getByRole("cell").first()).toHaveText("1");
      await expect(groups.getByRole("button", { name: /^Members of / })).toHaveCount(0);
      // The backend refuses the read itself, whatever the page offers.
      expect((await reader.request.get(`/api/admin/groups/${groupId}/members`)).status()).toBe(403);
    } finally {
      await reader.context().close();
    }
  } finally {
    if (groupId !== undefined) await deprovisionGroup(connector.scim, groupId);
    await deprovisionUser(connector.scim, userId);
  }
});

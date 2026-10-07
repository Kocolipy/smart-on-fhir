import { expect, test, type Page } from "@playwright/test";

import { ADMIN_USERNAME, submitLoginViaApi, userIdOf } from "./auth.helpers";
import {
  anonymousApi,
  deprovisionUser,
  E2E_PREFIX,
  provisionUser,
  rowOf,
  runId,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * The audit trail read from the page: an action taken elsewhere in the SPA is
 * found again on `/audit` through the filters, attributed to who took it.
 *
 * `dev-roles.spec.ts` proves who may reach the page. What this spec adds is
 * that the page shows the trail itself — the event an Admin's forced change
 * recorded, a refused login's failure, and a filter the page refuses locally.
 *
 * Every filter is pinned to the Resource id of a User this spec provisions, so
 * the rows are this run's own whatever the shared trail holds; the User is
 * deleted in a `finally`.
 */

const connector = workerConnector("audit");
const AUDITED_USER = `${E2E_PREFIX}audited-${runId()}`;

const eventsTable = (page: Page) => page.getByRole("table", { name: "Audit events" });
/** The body rows: the header row holds column headers, never a cell. */
const eventRows = (page: Page) =>
  eventsTable(page)
    .getByRole("row")
    .filter({ has: page.getByRole("cell") });

async function applyFilters(
  page: Page,
  filters: { operation?: string; outcome?: string; resourceId?: string },
) {
  await page.getByLabel("Operation").selectOption(filters.operation ?? "");
  await page.getByLabel("Outcome").selectOption(filters.outcome ?? "");
  await page.getByLabel("Resource id").fill(filters.resourceId ?? "");
  await page.getByRole("button", { name: "Apply filters" }).click();
}

test("an Admin's forced change and a refused login are found on the audit page", async ({
  page,
}) => {
  test.setTimeout(90_000);
  const id = await provisionUser(connector.scim, AUDITED_USER);
  try {
    await settlePassword(AUDITED_USER);

    // A refused login, recorded against the User it named.
    const guesser = await anonymousApi();
    try {
      expect((await submitLoginViaApi(guesser, AUDITED_USER, "not-the-password")).status()).toBe(
        401,
      );
    } finally {
      await guesser.dispose();
    }

    // The forced change, from the Accounts page.
    await page.goto("/accounts");
    const row = rowOf(page, page.getByRole("table", { name: "Users" }), AUDITED_USER);
    await row.getByRole("button", { name: `Force password change for ${AUDITED_USER}` }).click();
    await expect(row.getByText("Required")).toBeVisible();

    await page.goto("/audit");
    await expect(page.getByRole("heading", { name: "Audit", exact: true })).toBeVisible();

    // The forced change: one event, a success, attributed to the Admin by id.
    await applyFilters(page, { operation: "PASSWORD_CHANGE_REQUIRE", resourceId: id });
    await expect(eventRows(page)).toHaveCount(1);
    const forced = eventRows(page).first();
    await expect(forced.getByRole("cell").nth(0)).toHaveText("PASSWORD_CHANGE_REQUIRE");
    await expect(forced.getByRole("cell").nth(1)).toHaveText("SUCCESS");
    await expect(forced.getByRole("cell").nth(2)).toHaveText(await userIdOf(page, ADMIN_USERNAME));
    await expect(forced.getByRole("cell").nth(3)).toContainText(id);

    // Every failure against the User: only the refused login, which no actor made.
    await applyFilters(page, { outcome: "FAILURE", resourceId: id });
    await expect(eventRows(page)).toHaveCount(1);
    const refused = eventRows(page).first();
    await expect(refused.getByRole("cell").nth(0)).toHaveText("LOGIN_FAILURE");
    await expect(refused.getByRole("cell").nth(2)).toHaveText("—");

    // A Resource id that is not a UUID is refused on the page; the listing stays as it was.
    await page.getByLabel("Resource id").fill("not-a-uuid");
    await page.getByRole("button", { name: "Apply filters" }).click();
    await expect(page.getByRole("alert")).toHaveText("Resource id must be a valid UUID.");
    await expect(eventRows(page)).toHaveCount(1);

    // An operation the User never had performed on it matches nothing.
    await applyFilters(page, { operation: "LOCKOUT_LIFT", resourceId: id });
    await expect(page.getByText("No audit events match the current filters.")).toBeVisible();
  } finally {
    await deprovisionUser(connector.scim, id);
  }
});

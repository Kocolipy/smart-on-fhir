import { expect, test, type Page } from "@playwright/test";

import { ADMIN_USERNAME, postAdminAction, submitLoginViaApi, userIdOf } from "./auth.helpers";
import {
  anonymousApi,
  deprovisionUser,
  E2E_PREFIX,
  FIXTURE_GROUPS,
  provisionUser,
  rowOf,
  runId,
  scimPatch,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * The audit trail read from the page: an action taken elsewhere in the SPA is
 * found again on `/audit` through the filters, attributed to who took it.
 *
 * `dev-roles.spec.ts` proves who may reach the page. What this spec adds is
 * that the page shows the trail itself — the event an Admin's forced change
 * recorded, a refused login's failure, a connector's membership grant with the
 * Permissions it conferred, and a filter the page refuses locally.
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

/** The page size the Audit page asks for (`audit-api.ts`). */
const PAGE_SIZE = 50;

async function applyFilters(
  page: Page,
  filters: { actorId?: string; operation?: string; outcome?: string; resourceId?: string },
) {
  await page.getByLabel("Operation").selectOption(filters.operation ?? "");
  await page.getByLabel("Outcome").selectOption(filters.outcome ?? "");
  await page.getByLabel("Actor id").fill(filters.actorId ?? "");
  await page.getByLabel("Resource id").fill(filters.resourceId ?? "");
  await page.getByRole("button", { name: "Apply filters" }).click();
}

/**
 * Provisions `userName`, records one refused login against it, and hands its
 * Resource id to `body`; the User is deleted afterwards whatever `body` does.
 */
async function withRefusedLoginUser(userName: string, body: (id: string) => Promise<void>) {
  const id = await provisionUser(connector.scim, userName);
  try {
    await settlePassword(userName);
    const guesser = await anonymousApi();
    try {
      expect((await submitLoginViaApi(guesser, userName, "not-the-password")).status()).toBe(401);
    } finally {
      await guesser.dispose();
    }
    await body(id);
  } finally {
    await deprovisionUser(connector.scim, id);
  }
}

test("an Admin's forced change and a refused login are found on the audit page", async ({
  page,
}) => {
  test.setTimeout(90_000);
  // A refused login, recorded against the User it named.
  await withRefusedLoginUser(AUDITED_USER, async (id) => {
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
  });
});

/** The development mapping's Monitoring Group, conferring `ops:read`. */
const MONITORING_GROUP = FIXTURE_GROUPS.monitoring;

test("a connector adding a User to a Group that confers Permissions shows as a PERMISSION_GRANT", async ({
  page,
}) => {
  test.setTimeout(90_000);
  const userName = `${E2E_PREFIX}audit-grant-${runId()}`;
  const id = await provisionUser(connector.scim, userName);
  const group = `/scim/v2/Groups/${MONITORING_GROUP}`;
  try {
    const added = await scimPatch(connector.scim, group, [
      { op: "add", path: "members", value: [{ value: id }] },
    ]);
    expect(added.status(), "adding the User to Monitoring").toBe(200);

    await page.goto("/audit");
    await applyFilters(page, {
      actorId: connector.id,
      operation: "PERMISSION_GRANT",
      resourceId: MONITORING_GROUP,
    });
    await expect(eventRows(page)).toHaveCount(1);
    const grant = eventRows(page).first();
    await expect(grant.getByRole("cell").nth(0)).toHaveText("PERMISSION_GRANT");
    await expect(grant.getByRole("cell").nth(1)).toHaveText("SUCCESS");
    await expect(grant.getByRole("cell").nth(2)).toHaveText(connector.id);
    await expect(grant.getByRole("cell").nth(3)).toHaveText(`Group ${MONITORING_GROUP}`);
    await expect(grant.getByRole("cell").last()).toHaveText("permissions: none → ops:read");
  } finally {
    await scimPatch(connector.scim, group, [{ op: "remove", path: `members[value eq "${id}"]` }]);
    await deprovisionUser(connector.scim, id);
  }
});

/**
 * The listing past its first page. An Unlock of a User serving no lockout
 * changes nothing but is still recorded, so one more Unlock than a page holds,
 * all of this spec's own User, makes a second page whatever else the trail
 * holds — and costs no password hash, unlike a refused login.
 */
test("pages through the trail, refreshes it, and narrows it by actor and by date range", async ({
  page,
}) => {
  test.setTimeout(120_000);
  const userName = `${E2E_PREFIX}audit-paged-${runId()}`;
  const id = await provisionUser(connector.scim, userName);
  const unlock = async () =>
    expect((await postAdminAction(page, id, "unlock")).status(), "unlocking").toBe(200);
  try {
    for (let count = 0; count <= PAGE_SIZE; count += 1) await unlock();
    const adminId = await userIdOf(page, ADMIN_USERNAME);
    const position = page.getByText(/^Page \d+/);
    const previous = page.getByRole("button", { name: "Previous" });
    const next = page.getByRole("button", { name: "Next" });

    await page.goto("/audit");
    await applyFilters(page, { actorId: adminId, operation: "LOCKOUT_LIFT", resourceId: id });
    await expect(position).toHaveText("Page 1 of 2");
    await expect(eventRows(page)).toHaveCount(PAGE_SIZE);
    await expect(previous).toBeDisabled();

    await next.click();
    await expect(position).toHaveText("Page 2 of 2");
    await expect(eventRows(page)).toHaveCount(1);
    await expect(next).toBeDisabled();
    // The oldest, which page one did not show.
    await expect(eventRows(page).first().getByRole("cell").nth(2)).toHaveText(adminId);

    // A Refresh re-reads the page shown: one more Unlock pushes one more row onto it.
    await unlock();
    await expect(eventRows(page)).toHaveCount(1);
    await page.getByRole("button", { name: "Refresh" }).click();
    await expect(eventRows(page)).toHaveCount(2);

    await previous.click();
    await expect(position).toHaveText("Page 1 of 2");
    await expect(eventRows(page)).toHaveCount(PAGE_SIZE);

    // Another actor took none of these actions.
    await applyFilters(page, {
      actorId: crypto.randomUUID(),
      operation: "LOCKOUT_LIFT",
      resourceId: id,
    });
    await expect(page.getByText("No audit events match the current filters.")).toBeVisible();

    // A To far in the future keeps every one; a To before any of them keeps none.
    await page.getByLabel("To", { exact: true }).fill("2099-01-01T00:00");
    await applyFilters(page, { operation: "LOCKOUT_LIFT", resourceId: id });
    await expect(position).toHaveText("Page 1 of 2");
    await expect(eventRows(page)).toHaveCount(PAGE_SIZE);
    await page.getByLabel("To", { exact: true }).fill("2000-01-01T00:00");
    await applyFilters(page, { operation: "LOCKOUT_LIFT", resourceId: id });
    await expect(page.getByText("No audit events match the current filters.")).toBeVisible();

    // A From after the To is refused on the page; the listing stays as it was.
    await page.getByLabel("From").fill("2001-01-01T00:00");
    await page.getByRole("button", { name: "Apply filters" }).click();
    await expect(page.getByRole("alert")).toHaveText("The from date must be before the to date.");
    await expect(page.getByText("No audit events match the current filters.")).toBeVisible();

    // A To past the last instant a Date holds is refused on the page too. The
    // browser's picker never offers one, so the field is typed into as text,
    // as the Audit page's own unit tests do.
    const to = page.getByLabel("To", { exact: true });
    await to.evaluate((input: HTMLInputElement) => {
      input.type = "text";
    });
    await to.fill("275760-12-31T23:59");
    await page.getByRole("button", { name: "Apply filters" }).click();
    await expect(page.getByRole("alert")).toHaveText("To date must be a valid date.");
    await expect(page.getByText("No audit events match the current filters.")).toBeVisible();
  } finally {
    await deprovisionUser(connector.scim, id);
  }
});

test.describe("in a browser whose zone is not Singapore's", () => {
  // Fifteen to sixteen hours behind Singapore, with daylight saving: a From
  // read in this zone instead of Singapore's lands that many hours after the
  // event it was copied from, so the event drops out of the listing.
  test.use({ timezoneId: "America/Los_Angeles" });

  test("a From typed from a listed event's shown time still lists that event", async ({ page }) => {
    test.setTimeout(90_000);
    const userName = `${E2E_PREFIX}audit-zone-${runId()}`;
    await withRefusedLoginUser(userName, async (id) => {
      await page.goto("/audit");
      await applyFilters(page, { outcome: "FAILURE", resourceId: id });
      await expect(eventRows(page)).toHaveCount(1);
      // The time an event is shown at heads its row, `YYYY-MM-DD HH:mm`.
      const shown = await eventRows(page).first().getByRole("rowheader").innerText();
      expect(shown).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);

      await page.getByLabel("From").fill(shown.replace(" ", "T"));
      await applyFilters(page, { outcome: "FAILURE", resourceId: id });

      await expect(eventRows(page)).toHaveCount(1);
      await expect(eventRows(page).first().getByRole("cell").nth(0)).toHaveText("LOGIN_FAILURE");
    });
  });
});

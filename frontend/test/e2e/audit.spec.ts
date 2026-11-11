import { expect, test } from "@playwright/test";

import {
  applyAuditFilters,
  detailField,
  eventCell,
  eventRows,
  openEventDetail,
  openAuditFilters,
  operationPicker,
  outcomeBadge,
  selectAuditOperations,
} from "./audit.helpers";
import {
  ADMIN_USERNAME,
  LOCKOUT_MAX_ATTEMPTS,
  postAdminAction,
  submitLoginViaApi,
  userIdOf,
} from "./auth.helpers";
import {
  anonymousApi,
  deprovisionUser,
  E2E_PREFIX,
  FIXTURE_GROUPS,
  provisionUser,
  rowOf,
  searchCard,
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
 * that the page shows the trail itself — the change an Admin's Unlock required,
 * the refused logins that locked the User, each with its Failure badge, a
 * connector's membership grant with the Permissions its expanded detail names,
 * and a listing paged and narrowed by the backend. A filter the page refuses
 * locally sends nothing, so it is the page's unit tests'. Operations are
 * filtered by, and read as, their labels.
 *
 * Every filter is pinned to the Resource id of a User this spec provisions, so
 * the rows are this run's own whatever the shared trail holds; the User is
 * deleted in a `finally`.
 */

const connector = workerConnector("audit");
const AUDITED_USER = `${E2E_PREFIX}audited-${runId()}`;

/** The page size the Audit page asks for (`audit-api.ts`). */
const PAGE_SIZE = 20;

/**
 * Provisions `userName`, records `refusals` refused logins against it — enough,
 * at the lockout threshold, to lock it — and hands its Resource id to `body`;
 * the User is deleted afterwards whatever `body` does.
 */
async function withRefusedLoginUser(
  userName: string,
  body: (id: string) => Promise<void>,
  refusals = 1,
) {
  const id = await provisionUser(connector.scim, userName);
  try {
    await settlePassword(userName);
    const guesser = await anonymousApi();
    try {
      for (let attempt = 0; attempt < refusals; attempt += 1) {
        expect((await submitLoginViaApi(guesser, userName, "not-the-password")).status()).toBe(401);
      }
    } finally {
      await guesser.dispose();
    }
    await body(id);
  } finally {
    await deprovisionUser(connector.scim, id);
  }
}

test("an Admin's Unlock and the refused logins that locked the User are found on the audit page", async ({
  page,
}) => {
  test.setTimeout(90_000);
  // Refused logins, recorded against the User they named, the last of them locking it.
  await withRefusedLoginUser(
    AUDITED_USER,
    async (id) => {
      // The Unlock, from the Accounts page, which requires a change of password.
      await page.goto("/accounts");
      await searchCard(page, "Users", AUDITED_USER);
      const row = rowOf(page, page.getByRole("table", { name: "Users" }), AUDITED_USER);
      await row.getByRole("button", { name: `Unlock ${AUDITED_USER}` }).click();
      await expect(row.getByText("Required")).toBeVisible();

      await page.goto("/audit");
      await expect(page.getByRole("heading", { name: "Audit", exact: true })).toBeVisible();

      // The change it required: one event, a success, attributed to the Admin by id.
      await applyAuditFilters(page, { operation: "Password change required", resourceId: id });
      await expect(eventRows(page)).toHaveCount(1);
      const required = eventRows(page).first();
      await expect(await eventCell(required, "Operation")).toHaveText("Password change required");
      await expect(await outcomeBadge(required)).toHaveText("Success");
      await expect(await eventCell(required, "Actor")).toHaveText(
        await userIdOf(page, ADMIN_USERNAME),
      );
      await expect(await eventCell(required, "Resource")).toContainText(id);

      // Every failure against the User: only the refused logins, which no actor made, each
      // badged as the failure it was.
      await applyAuditFilters(page, { outcome: "Failure", resourceId: id });
      await expect(eventRows(page)).toHaveCount(LOCKOUT_MAX_ATTEMPTS);
      const refused = eventRows(page).first();
      await expect(await eventCell(refused, "Operation")).toHaveText("Login failed");
      await expect(await outcomeBadge(refused)).toHaveText("Failure");
      await expect(await eventCell(refused, "Actor")).toHaveText("—");

      // An operation the User never had performed on it matches nothing: it was
      // locked by its failure run, never by the dormancy job.
      await applyAuditFilters(page, { operation: "Dormancy lockout", resourceId: id });
      await expect(page.getByText("No audit events match the current filters.")).toBeVisible();
    },
    LOCKOUT_MAX_ATTEMPTS,
  );
});

/**
 * The Operation picker over a User's own trail, which holds its refused logins, the Lockout the last
 * of them set, and its provisioning, whose events neither selected operation is.
 */
test("two operations selected through the picker's search list events of both, and only those", async ({
  page,
}) => {
  test.setTimeout(90_000);
  const userName = `${E2E_PREFIX}audit-picked-${runId()}`;
  await withRefusedLoginUser(
    userName,
    async (id) => {
      await page.goto("/audit");
      await applyAuditFilters(page, { resourceId: id });
      await expect(eventRows(page).filter({ hasText: "User created" })).toHaveCount(1);

      await selectAuditOperations(page, ["Lockout set", "Login failed"]);
      await expect(operationPicker(page)).toHaveText("Operation: 2 selected");
      await page.getByRole("button", { name: "Apply", exact: true }).click();

      await expect(eventRows(page)).toHaveCount(LOCKOUT_MAX_ATTEMPTS + 1);
      await expect(eventRows(page).filter({ hasText: "Lockout set" })).toHaveCount(1);
      await expect(eventRows(page).filter({ hasText: "Login failed" })).toHaveCount(
        LOCKOUT_MAX_ATTEMPTS,
      );

      // Reset drops them and the Resource id with them: the whole trail, from its first page.
      await openAuditFilters(page);
      await page.getByRole("button", { name: "Reset" }).click();
      await openAuditFilters(page);
      await expect(operationPicker(page)).toHaveText("Operation: Any");
      await expect(page.getByLabel("Resource id")).toHaveValue("");
      await expect(page.getByText(/^Showing 1 to \d+ of \d+ events$/)).toBeVisible();
    },
    LOCKOUT_MAX_ATTEMPTS,
  );
});

/** The development mapping's Auditor Group, conferring every read. */
const AUDITORS_GROUP = FIXTURE_GROUPS.auditors;

test("a connector adding a User to a Group that confers Permissions shows as a Permission granted, its detail naming them", async ({
  page,
}) => {
  test.setTimeout(90_000);
  const userName = `${E2E_PREFIX}audit-grant-${runId()}`;
  const id = await provisionUser(connector.scim, userName);
  const group = `/scim/v2/Groups/${AUDITORS_GROUP}`;
  try {
    const added = await scimPatch(connector.scim, group, [
      { op: "add", path: "members", value: [{ value: id }] },
    ]);
    expect(added.status(), "adding the User to Auditor").toBe(200);

    await page.goto("/audit");
    await applyAuditFilters(page, {
      actorId: connector.id,
      operation: "Permission granted",
      resourceId: AUDITORS_GROUP,
    });
    await expect(eventRows(page)).toHaveCount(1);
    const grant = eventRows(page).first();
    await expect(await eventCell(grant, "Operation")).toHaveText("Permission granted");
    await expect(await outcomeBadge(grant)).toHaveText("Success");
    await expect(await eventCell(grant, "Actor")).toHaveText(connector.id);
    await expect(await eventCell(grant, "Resource")).toHaveText(`Group ${AUDITORS_GROUP}`);

    // The rest is behind the row's disclosure, as plain text.
    const detail = await openEventDetail(grant);
    await expect(await detailField(detail, "Changed paths")).toHaveText("members");
    await expect(await detailField(detail, "Details")).toHaveText(
      "permissions: none → audit:read, connector:read, group:read, ops:read, user:read",
    );
    await expect(detail.getByRole("link")).toHaveCount(0);
  } finally {
    await scimPatch(connector.scim, group, [{ op: "remove", path: `members[value eq "${id}"]` }]);
    await deprovisionUser(connector.scim, id);
  }
});

/**
 * The listing past its first page. An Unlock of a User serving no lockout is
 * refused and changes nothing, but is still recorded as a refused
 * `LOCKOUT_LIFT`, so one more Unlock than a page holds, all of this spec's own
 * User, makes a second page whatever else the trail holds — and costs no
 * password hash, unlike a refused login. The filters exclude something: the
 * User's own provisioning is also about its Resource id, so a Next or Previous
 * that dropped them would count it, and show it on a page of refused Unlocks.
 */
test("pages through the trail with its filters, refreshes it, and narrows it by actor and by date range", async ({
  page,
}) => {
  test.setTimeout(120_000);
  const userName = `${E2E_PREFIX}audit-paged-${runId()}`;
  const id = await provisionUser(connector.scim, userName);
  const unlock = async () =>
    expect((await postAdminAction(page, id, "unlock")).status(), "a refused unlock").toBe(409);
  try {
    for (let count = 0; count <= PAGE_SIZE; count += 1) await unlock();
    const adminId = await userIdOf(page, ADMIN_USERNAME);
    // The pager names the range shown, "Showing 1 to 20 of 21 events".
    const position = page.getByText(/^Showing \d+ to \d+ of \d+ events$/);
    const firstPageOf = (total: number) => `Showing 1 to ${PAGE_SIZE} of ${total} events`;
    const previous = page.getByRole("button", { name: "Previous" });
    const next = page.getByRole("button", { name: "Next" });

    await page.goto("/audit");
    await applyAuditFilters(page, {
      actorId: adminId,
      operation: "Lockout lifted",
      resourceId: id,
    });
    await expect(position).toHaveText(firstPageOf(PAGE_SIZE + 1));
    await expect(eventRows(page)).toHaveCount(PAGE_SIZE);
    const firstPageTop = await eventRows(page).first().getByRole("rowheader").innerText();
    await expect(previous).toBeDisabled();

    await next.click();
    await expect(position).toHaveText(
      `Showing ${PAGE_SIZE + 1} to ${PAGE_SIZE + 1} of ${PAGE_SIZE + 1} events`,
    );
    await expect(eventRows(page)).toHaveCount(1);
    await expect(next).toBeDisabled();
    // The oldest, which page one did not show, and one the filters kept.
    const oldest = eventRows(page).first();
    await expect(await eventCell(oldest, "Actor")).toHaveText(adminId);
    await expect(await eventCell(oldest, "Operation")).toHaveText("Lockout lifted");
    await expect(await eventCell(oldest, "Resource")).toContainText(id);

    // A Refresh re-reads the page shown: one more Unlock pushes one more row onto it.
    await unlock();
    await expect(eventRows(page)).toHaveCount(1);
    await page.getByRole("button", { name: "Refresh" }).click();
    await expect(eventRows(page)).toHaveCount(2);
    await expect(position).toHaveText(
      `Showing ${PAGE_SIZE + 1} to ${PAGE_SIZE + 2} of ${PAGE_SIZE + 2} events`,
    );

    await previous.click();
    await expect(position).toHaveText(firstPageOf(PAGE_SIZE + 2));
    await expect(eventRows(page)).toHaveCount(PAGE_SIZE);
    // The Refresh's new row now heads page one, so the old top is second.
    await expect(eventRows(page).nth(1).getByRole("rowheader")).toHaveText(firstPageTop);
    await expect(eventRows(page).filter({ hasText: "Lockout lifted" })).toHaveCount(PAGE_SIZE);

    // Another actor took none of these actions.
    await applyAuditFilters(page, {
      actorId: crypto.randomUUID(),
      operation: "Lockout lifted",
      resourceId: id,
    });
    await expect(page.getByText("No audit events match the current filters.")).toBeVisible();

    // A To far in the future keeps every one; a To before any of them keeps none.
    await openAuditFilters(page);
    await page.getByLabel("To", { exact: true }).fill("2099-01-01T00:00");
    await applyAuditFilters(page, { operation: "Lockout lifted", resourceId: id });
    await expect(position).toHaveText(firstPageOf(PAGE_SIZE + 2));
    await expect(eventRows(page)).toHaveCount(PAGE_SIZE);
    await openAuditFilters(page);
    await page.getByLabel("To", { exact: true }).fill("2000-01-01T00:00");
    await applyAuditFilters(page, { operation: "Lockout lifted", resourceId: id });
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
      await applyAuditFilters(page, { outcome: "Failure", resourceId: id });
      await expect(eventRows(page)).toHaveCount(1);
      // The time an event is shown at heads its row, `YYYY-MM-DD HH:mm`.
      const shown = await eventRows(page).first().getByRole("rowheader").innerText();
      expect(shown).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);

      await openAuditFilters(page);
      await page.getByLabel("From").fill(shown.replace(" ", "T"));
      await applyAuditFilters(page, { outcome: "Failure", resourceId: id });

      await expect(eventRows(page)).toHaveCount(1);
      await expect(await eventCell(eventRows(page).first(), "Operation")).toHaveText(
        "Login failed",
      );
    });
  });
});

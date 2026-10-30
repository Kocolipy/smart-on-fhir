import { expect, test } from "@playwright/test";

import { applyAuditFilters, eventRows } from "./audit.helpers";
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
 * the refused logins that locked the User, a connector's membership grant with
 * the Permissions it conferred, and a filter the page refuses locally.
 *
 * Every filter is pinned to the Resource id of a User this spec provisions, so
 * the rows are this run's own whatever the shared trail holds; the User is
 * deleted in a `finally`.
 */

const connector = workerConnector("audit");
const AUDITED_USER = `${E2E_PREFIX}audited-${runId()}`;

/** The page size the Audit page asks for (`audit-api.ts`). */
const PAGE_SIZE = 50;

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
      await applyAuditFilters(page, { operation: "PASSWORD_CHANGE_REQUIRE", resourceId: id });
      await expect(eventRows(page)).toHaveCount(1);
      const required = eventRows(page).first();
      await expect(required.getByRole("cell").nth(0)).toHaveText("PASSWORD_CHANGE_REQUIRE");
      await expect(required.getByRole("cell").nth(1)).toHaveText("SUCCESS");
      await expect(required.getByRole("cell").nth(2)).toHaveText(
        await userIdOf(page, ADMIN_USERNAME),
      );
      await expect(required.getByRole("cell").nth(3)).toContainText(id);

      // Every failure against the User: only the refused logins, which no actor made.
      await applyAuditFilters(page, { outcome: "FAILURE", resourceId: id });
      await expect(eventRows(page)).toHaveCount(LOCKOUT_MAX_ATTEMPTS);
      const refused = eventRows(page).first();
      await expect(refused.getByRole("cell").nth(0)).toHaveText("LOGIN_FAILURE");
      await expect(refused.getByRole("cell").nth(2)).toHaveText("—");

      // A Resource id that is not a UUID is refused on the page; the listing stays as it was.
      await page.getByLabel("Resource id").fill("not-a-uuid");
      await page.getByRole("button", { name: "Apply filters" }).click();
      await expect(page.getByRole("alert")).toHaveText("Resource id must be a valid UUID.");
      await expect(eventRows(page)).toHaveCount(LOCKOUT_MAX_ATTEMPTS);

      // An operation the User never had performed on it matches nothing: it was
      // locked by its failure run, never by the dormancy job.
      await applyAuditFilters(page, { operation: "DORMANCY_LOCKOUT", resourceId: id });
      await expect(page.getByText("No audit events match the current filters.")).toBeVisible();
    },
    LOCKOUT_MAX_ATTEMPTS,
  );
});

/** The development mapping's Auditors Group, conferring every read. */
const AUDITORS_GROUP = FIXTURE_GROUPS.auditors;

test("a connector adding a User to a Group that confers Permissions shows as a PERMISSION_GRANT", async ({
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
    expect(added.status(), "adding the User to Auditors").toBe(200);

    await page.goto("/audit");
    await applyAuditFilters(page, {
      actorId: connector.id,
      operation: "PERMISSION_GRANT",
      resourceId: AUDITORS_GROUP,
    });
    await expect(eventRows(page)).toHaveCount(1);
    const grant = eventRows(page).first();
    await expect(grant.getByRole("cell").nth(0)).toHaveText("PERMISSION_GRANT");
    await expect(grant.getByRole("cell").nth(1)).toHaveText("SUCCESS");
    await expect(grant.getByRole("cell").nth(2)).toHaveText(connector.id);
    await expect(grant.getByRole("cell").nth(3)).toHaveText(`Group ${AUDITORS_GROUP}`);
    await expect(grant.getByRole("cell").last()).toHaveText(
      "permissions: none → audit:read, connector:read, group:read, ops:read, user:read",
    );
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
 * password hash, unlike a refused login.
 */
test("pages through the trail, refreshes it, and narrows it by actor and by date range", async ({
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
    const position = page.getByText(/^Page \d+/);
    const previous = page.getByRole("button", { name: "Previous" });
    const next = page.getByRole("button", { name: "Next" });

    await page.goto("/audit");
    await applyAuditFilters(page, { actorId: adminId, operation: "LOCKOUT_LIFT", resourceId: id });
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
    await applyAuditFilters(page, {
      actorId: crypto.randomUUID(),
      operation: "LOCKOUT_LIFT",
      resourceId: id,
    });
    await expect(page.getByText("No audit events match the current filters.")).toBeVisible();

    // A To far in the future keeps every one; a To before any of them keeps none.
    await page.getByLabel("To", { exact: true }).fill("2099-01-01T00:00");
    await applyAuditFilters(page, { operation: "LOCKOUT_LIFT", resourceId: id });
    await expect(position).toHaveText("Page 1 of 2");
    await expect(eventRows(page)).toHaveCount(PAGE_SIZE);
    await page.getByLabel("To", { exact: true }).fill("2000-01-01T00:00");
    await applyAuditFilters(page, { operation: "LOCKOUT_LIFT", resourceId: id });
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
      await applyAuditFilters(page, { outcome: "FAILURE", resourceId: id });
      await expect(eventRows(page)).toHaveCount(1);
      // The time an event is shown at heads its row, `YYYY-MM-DD HH:mm`.
      const shown = await eventRows(page).first().getByRole("rowheader").innerText();
      expect(shown).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);

      await page.getByLabel("From").fill(shown.replace(" ", "T"));
      await applyAuditFilters(page, { outcome: "FAILURE", resourceId: id });

      await expect(eventRows(page)).toHaveCount(1);
      await expect(eventRows(page).first().getByRole("cell").nth(0)).toHaveText("LOGIN_FAILURE");
    });
  });
});

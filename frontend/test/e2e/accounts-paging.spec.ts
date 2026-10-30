import { expect, test, type Page } from "@playwright/test";

import { LOCKOUT_MAX_ATTEMPTS, submitLoginViaApi } from "./auth.helpers";
import {
  anonymousApi,
  deprovisionGroup,
  deprovisionUser,
  E2E_PREFIX,
  provisionGroup,
  provisionUser,
  rowOf,
  runId,
  searchCard,
  workerConnector,
} from "./scim.helpers";

/**
 * The Users and Groups cards of the Accounts page read a page at a time and
 * searched by name (#214), against the running stack. The spec seeds one more
 * User and one more Group than a page holds, all named under a tag of its own,
 * so a search for the tag is a listing of exactly them that spans two pages
 * whatever else the directory holds; the Admin then moves between the pages,
 * and unlocks a User found by searching for it alone.
 *
 * In the `admin` project. Everything it seeds is its own, provisioned over SCIM
 * with an `e2e-` name and removed in a `finally`.
 */

/** What one page of either card holds: `LISTING_PAGE_SIZE` in `accounts-api.ts`. */
const PAGE_SIZE = 20;
const SEEDED = PAGE_SIZE + 1;

const RUN = runId();
const TAG = `${E2E_PREFIX}paged-${RUN}`;
const connector = workerConnector("accounts-paging");

/** The `index`th seeded name, zero-padded so the names sort as they number. */
const nameOf = (index: number) => `${TAG}-${String(index).padStart(2, "0")}`;

const pager = (page: Page, card: "Users" | "Groups") =>
  page.getByRole("navigation", { name: `${card} pages` });

/** Locks `userName` the way a forgetful person does: a lockout's worth of refused logins. */
async function lockOut(userName: string) {
  const guesser = await anonymousApi();
  try {
    for (let attempt = 0; attempt < LOCKOUT_MAX_ATTEMPTS; attempt += 1) {
      expect((await submitLoginViaApi(guesser, userName, "not-the-password")).status()).toBe(401);
    }
  } finally {
    await guesser.dispose();
  }
}

/** Moves one card to its next page and back, checking where each page sits and what it shows. */
async function pageThrough(page: Page, card: "Users" | "Groups") {
  const table = page.getByRole("table", { name: card });
  await expect(pager(page, card)).toContainText(`${card} 1–${PAGE_SIZE} of ${SEEDED}`);
  await expect(rowOf(page, table, nameOf(1))).toBeVisible();
  await expect(rowOf(page, table, nameOf(SEEDED))).toHaveCount(0);
  await expect(pager(page, card).getByRole("button", { name: "Previous" })).toBeDisabled();

  await pager(page, card).getByRole("button", { name: "Next" }).click();

  await expect(pager(page, card)).toContainText(`${card} ${SEEDED}–${SEEDED} of ${SEEDED}`);
  await expect(rowOf(page, table, nameOf(SEEDED))).toBeVisible();
  await expect(rowOf(page, table, nameOf(1))).toHaveCount(0);
  await expect(pager(page, card).getByRole("button", { name: "Next" })).toBeDisabled();

  await pager(page, card).getByRole("button", { name: "Previous" }).click();

  await expect(pager(page, card)).toContainText(`${card} 1–${PAGE_SIZE} of ${SEEDED}`);
}

test("an admin pages through and searches both cards, and unlocks a User found by search", async ({
  page,
}) => {
  // Twenty-one provisions, each a password hash, and a lockout's worth of refused logins.
  test.setTimeout(180_000);
  const userIds: string[] = [];
  const groupIds: string[] = [];
  try {
    for (let index = 1; index <= SEEDED; index += 1) {
      userIds.push(await provisionUser(connector.scim, nameOf(index)));
      groupIds.push(await provisionGroup(connector.scim, nameOf(index)));
    }
    const locked = nameOf(SEEDED);
    await lockOut(locked);

    await page.goto("/accounts");
    await searchCard(page, "Users", TAG);
    await pageThrough(page, "Users");
    await searchCard(page, "Groups", TAG);
    await pageThrough(page, "Groups");

    // A search that matches nothing says so, rather than that nothing is provisioned.
    await searchCard(page, "Groups", `${TAG}-none`);
    await expect(page.getByText(`No groups match "${TAG}-none".`)).toBeVisible();

    // The locked User, found by searching for it alone, unlocked in place.
    await searchCard(page, "Users", locked);
    const row = rowOf(page, page.getByRole("table", { name: "Users" }), locked);
    await expect(row.getByText("Locked: failed logins", { exact: true })).toBeVisible();
    await row.getByRole("button", { name: `Unlock ${locked}` }).click();
    await expect(row.getByText("Not locked")).toBeVisible();
    await expect(row.getByText("Required")).toBeVisible();
    await expect(pager(page, "Users")).toContainText("Users 1–1 of 1");
  } finally {
    for (const id of groupIds) await deprovisionGroup(connector.scim, id);
    for (const id of userIds) await deprovisionUser(connector.scim, id);
  }
});

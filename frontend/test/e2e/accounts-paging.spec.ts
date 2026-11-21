import { expect, test, type Page } from "@playwright/test";

import { PROJECTIONS, readAt } from "./auth.helpers";
import {
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
 * searched by name, against the running stack. The spec seeds one more
 * User and one more Group than a page holds, all named under a tag of its own,
 * so a search for the tag is a listing of exactly them that spans two pages
 * whatever else the directory holds; the Admin then moves between the pages,
 * and finds one User by searching for it alone.
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

/** The answer to the card's read of its search for the tag from `startIndex`, once it comes. */
const cardRead = (page: Page, card: "Users" | "Groups", startIndex: number) =>
  readAt(page, PROJECTIONS[card].path, { search: TAG, startIndex: String(startIndex) });

/**
 * Moves one card to its next page and back, checking where each page sits and
 * what it shows. Each move waits for the read it makes, so a refused or
 * missing read fails there, named.
 */
async function pageThrough(page: Page, card: "Users" | "Groups") {
  const table = page.getByRole("table", { name: card });
  await expect(pager(page, card)).toContainText(
    `Showing 1 to ${PAGE_SIZE} of ${SEEDED} ${card.toLowerCase()}`,
  );
  await expect(rowOf(page, table, nameOf(1))).toBeVisible();
  await expect(rowOf(page, table, nameOf(SEEDED))).toHaveCount(0);
  await expect(pager(page, card).getByRole("button", { name: "Previous" })).toBeDisabled();

  const next = cardRead(page, card, SEEDED);
  await pager(page, card).getByRole("button", { name: "Next" }).click();
  expect((await next).status(), `reading the second page of ${card}`).toBe(200);

  await expect(pager(page, card)).toContainText(
    `Showing ${SEEDED} to ${SEEDED} of ${SEEDED} ${card.toLowerCase()}`,
  );
  await expect(rowOf(page, table, nameOf(SEEDED))).toBeVisible();
  await expect(rowOf(page, table, nameOf(1))).toHaveCount(0);
  await expect(pager(page, card).getByRole("button", { name: "Next" })).toBeDisabled();

  const previous = cardRead(page, card, 1);
  await pager(page, card).getByRole("button", { name: "Previous" }).click();
  expect((await previous).status(), `reading the first page of ${card} again`).toBe(200);

  await expect(pager(page, card)).toContainText(
    `Showing 1 to ${PAGE_SIZE} of ${SEEDED} ${card.toLowerCase()}`,
  );
}

test("an admin pages through and searches both cards", async ({ page }) => {
  // Twenty-one provisions, each a password hash.
  test.setTimeout(180_000);
  const userIds: string[] = [];
  const groupIds: string[] = [];
  try {
    for (let index = 1; index <= SEEDED; index += 1) {
      userIds.push(await provisionUser(connector.scim, nameOf(index)));
      groupIds.push(await provisionGroup(connector.scim, nameOf(index)));
    }

    await page.goto("/accounts");
    await searchCard(page, "Users", TAG);
    await pageThrough(page, "Users");
    await searchCard(page, "Groups", TAG);
    await pageThrough(page, "Groups");

    // A search that matches nothing says so, rather than that nothing is provisioned.
    await searchCard(page, "Groups", `${TAG}-none`);
    await expect(page.getByText(`No groups match "${TAG}-none".`)).toBeVisible();

    // One User, found by searching for it alone.
    const alone = nameOf(SEEDED);
    await searchCard(page, "Users", alone);
    await expect(rowOf(page, page.getByRole("table", { name: "Users" }), alone)).toBeVisible();
    // One User matches, so there is no page to move to and no pager.
    await expect(pager(page, "Users")).toHaveCount(0);
  } finally {
    for (const id of groupIds) await deprovisionGroup(connector.scim, id);
    for (const id of userIds) await deprovisionUser(connector.scim, id);
  }
});

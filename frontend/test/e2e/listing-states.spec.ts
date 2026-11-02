import { expect, test, type Route } from "@playwright/test";

import { PROJECTIONS } from "./auth.helpers";
import { E2E_PREFIX, runId, searchCard } from "./scim.helpers";

/**
 * The states the one listing body draws around a listing's rows (#254), seen
 * in a real browser on the Accounts page's Users card: skeleton rows while
 * its first read is in flight, and the designed empty state for a search that
 * matches nothing. Every listing renders through the same body, whose own
 * unit tests cover each state; this spec proves the states reach the page.
 *
 * In the `admin` project. It writes nothing: the read is only held, then sent
 * on, and the search names a User no one provisions.
 */

const USERS_READ = (url: URL) => url.pathname === PROJECTIONS.Users.path;

test("a listing shows skeleton rows while its first read is held, then its rows", async ({
  page,
}) => {
  // StrictMode mounts the card's read twice under `npm run dev`, so every Users
  // read is held, not just the first: one let through would land the rows
  // before the skeleton is asserted.
  const held: Route[] = [];
  let holding = true;
  await page.route(USERS_READ, async (route) => {
    if (holding) {
      held.push(route);
      return;
    }
    await route.continue();
  });

  await page.goto("/accounts");
  // The step this test is on: a Users read is in flight and held. Without it the
  // skeleton below could be the instant before an unheld answer lands.
  await expect.poll(() => held.length, { message: "a Users read is held" }).toBeGreaterThan(0);
  const loading = page.getByRole("status").filter({ hasText: "Loading users…" });
  await expect(loading.locator('[data-slot="skeleton"]').first()).toBeVisible();
  await expect(page.getByRole("table", { name: "Users" })).toHaveCount(0);

  holding = false;
  await Promise.all(held.map((route) => route.continue()));

  await expect(page.getByRole("table", { name: "Users" }).getByRole("row").nth(1)).toBeVisible();
  await expect(loading).toHaveCount(0);
});

test("a search matching nothing shows the designed empty state", async ({ page }) => {
  const search = `${E2E_PREFIX}nobody-${runId()}`;
  await page.goto("/accounts");

  await searchCard(page, "Users", search);

  const empty = page
    .getByTestId("listing-empty")
    .filter({ hasText: `No users match "${search}".` });
  await expect(empty.locator("svg")).toBeVisible();
});

import { expect, test, type Route } from "@playwright/test";

import { PROJECTIONS } from "./auth.helpers";
import { cardTitled } from "./card.helpers";
import { E2E_PREFIX, runId, searchCard } from "./scim.helpers";

/**
 * The states the one listing body draws around a listing's rows, seen
 * in a real browser on the Accounts page's Users card: skeleton rows while
 * its first read is in flight, and the designed empty state for a search that
 * matches nothing. Every listing renders through the same body, whose own
 * unit tests cover each state; this spec proves the states reach the page.
 * The Connectors panel, the one listing that is not paged, proves its failed
 * read offers the same Try again the paged listings do.
 *
 * In the `admin` project. It writes nothing: a read is only held, failed or
 * sent on, and the search names a User no one provisions.
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

const CONNECTORS_READ = (url: URL) => url.pathname === "/api/admin/connectors";

test("a failed Connectors read offers Try again, which reads the listing again", async ({
  page,
}) => {
  // Every listing read fails until the test lets them through: StrictMode
  // mounts the panel's read twice under `npm run dev`, and one let through
  // would land the listing before its failure is asserted.
  let failing = true;
  let reads = 0;
  await page.route(CONNECTORS_READ, async (route) => {
    if (route.request().method() !== "GET") return route.continue();
    reads += 1;
    if (failing) return route.fulfill({ status: 503 });
    return route.continue();
  });

  await page.goto("/accounts");
  const connectors = cardTitled(page, "Connectors", { exact: true });
  await expect(page.getByRole("alert")).toHaveText(
    "Unable to load the connectors. Please try again.",
  );
  const tryAgain = connectors.getByRole("button", { name: "Try again" });
  await expect(tryAgain).toBeVisible();
  // A failed first read claims nothing about the connectors: no empty state, no rows.
  await expect(connectors.getByTestId("listing-empty")).toHaveCount(0);
  const failedReads = reads;

  failing = false;
  await tryAgain.click();

  // The listing came back: its empty state or a connector's section, whichever
  // the shared stack holds, and the failure is gone with the control.
  await expect(
    connectors
      .getByTestId("listing-empty")
      .or(connectors.getByRole("region", { name: /^Connector / }))
      .first(),
  ).toBeVisible();
  await expect(tryAgain).toHaveCount(0);
  await expect(page.getByRole("alert")).toHaveCount(0);
  expect(reads, "Try again sent one more listing read").toBe(failedReads + 1);
});

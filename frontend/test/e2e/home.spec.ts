import { expect, test, type Page } from "@playwright/test";

/** The navigation sidebar, which the layout renders beside every signed-in page. */
const sidebar = (page: Page) => page.locator('[data-slot="sidebar"]');

test.describe("Home", () => {
  test("opens Home with the saved session and the Patient card", async ({ page }) => {
    await page.goto("/");

    await expect(page).toHaveURL("/");
    await expect(page.getByText("Signed in as admin")).toBeVisible();
    await expect(page.getByRole("heading", { level: 3 })).toHaveText(["Patient"]);
  });

  // The Superuser opens every page, so the navigation sidebar links each of
  // them; Settings is the top bar's.
  test("links the Superuser to every page from the navigation sidebar", async ({ page }) => {
    await page.goto("/");

    await expect(sidebar(page).getByRole("link")).toHaveText([
      "Profile",
      "Manage accounts",
      "View audit log",
    ]);
  });

  // Each page the sidebar links opens under its own title, and the sidebar's
  // Profile link lands on Home again.
  for (const [link, title] of [
    ["Manage accounts", "Accounts"],
    ["View audit log", "Audit"],
  ] as const) {
    test(`returns from ${title} to Home through the sidebar`, async ({ page }) => {
      await page.goto("/");
      await sidebar(page).getByRole("link", { name: link, exact: true }).click();
      await expect(page.getByRole("heading", { level: 1 })).toHaveText(title);

      await sidebar(page).getByRole("link", { name: "Profile", exact: true }).click();

      await expect(page).toHaveURL("/");
      await expect(page.getByRole("heading", { level: 1 })).toHaveText("Profile");
    });
  }

  test("sends a signed-in visit to login on to Home", async ({ page }) => {
    await page.goto("/login");

    await expect(page.getByRole("heading", { name: "Profile" })).toBeVisible();
    await expect(page).toHaveURL("/");
  });
});

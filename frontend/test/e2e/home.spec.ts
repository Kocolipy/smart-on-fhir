import { expect, test, type Page } from "@playwright/test";

import { cardTitled } from "./card.helpers";
import { expectNoPatientInContext, recordPatientRequests } from "./patient.helpers";

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

  // Each page the sidebar links opens under its own title, offers no back link,
  // and the sidebar's Profile link lands on Home again.
  for (const [link, title] of [
    ["Manage accounts", "Accounts"],
    ["View audit log", "Audit"],
  ] as const) {
    test(`returns from ${title} to Home through the sidebar`, async ({ page }) => {
      await page.goto("/");
      await sidebar(page).getByRole("link", { name: link, exact: true }).click();
      await expect(page.getByRole("heading", { level: 1 })).toHaveText(title);
      await expect(page.getByRole("link", { name: /^Back/ })).toHaveCount(0);

      await sidebar(page).getByRole("link", { name: "Profile", exact: true }).click();

      await expect(page).toHaveURL("/");
      await expect(page.getByRole("heading", { level: 1 })).toHaveText("Profile");
    });
  }

  // Sign out, the sidebar's and the top bar's controls are the layout's, outside the page.
  test("offers no control of its own", async ({ page }) => {
    await page.goto("/");
    await expectNoPatientInContext(page);

    await expect(page.getByRole("main").getByRole("button")).toHaveCount(0);
  });

  // A password login puts no patient in context, though the Superuser holds
  // patient:read: the Patient card says so and reads nothing.
  test("shows no patient in context on Home, asking for no patient", async ({ page }) => {
    const patientRequests = recordPatientRequests(page);
    await page.goto("/");

    await expectNoPatientInContext(page);
    await expect(cardTitled(page, "Patient").getByRole("definition")).toHaveCount(0);
    await expect(page.getByRole("alert")).toHaveCount(0);
    expect(patientRequests()).toEqual([]);
  });

  test("sends a signed-in visit to login on to Home", async ({ page }) => {
    await page.goto("/login");

    await expect(page.getByRole("heading", { name: "Profile" })).toBeVisible();
    await expect(page).toHaveURL("/");
  });
});

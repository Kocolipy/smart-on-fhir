import { expect, test } from "@playwright/test";

import { cardTitled } from "./card.helpers";
import { expectNoPatientInContext, recordPatientRequests } from "./patient.helpers";

test.describe("Home", () => {
  test("opens Home with the saved session, the Patient card above the Pages card", async ({
    page,
  }) => {
    await page.goto("/");

    await expect(page).toHaveURL("/");
    await expect(page.getByText("Signed in as admin")).toBeVisible();
    await expect(page.getByRole("heading", { level: 3 })).toHaveText(["Patient", "Pages"]);
  });

  // The Superuser opens every page, so the Pages card links each of them.
  test("links the Superuser to every page from the Pages card", async ({ page }) => {
    await page.goto("/");

    const card = cardTitled(page, "Pages");
    await expect(card.getByRole("link")).toHaveText([
      "Manage accounts",
      "View audit log",
      "Change password",
    ]);
  });

  // Each page Home links names its way back, and following it lands on Home again.
  for (const [link, title, back] of [
    ["Manage accounts", "Accounts", "Back to home"],
    ["View audit log", "Audit", "Back to home"],
    ["Change password", "Change password", "Back"],
  ] as const) {
    test(`returns from ${title} to Home through its "${back}" link`, async ({ page }) => {
      await page.goto("/");
      await cardTitled(page, "Pages").getByRole("link", { name: link }).click();
      await expect(page.getByRole("heading", { level: 1 })).toHaveText(title);

      await page.getByRole("link", { name: back, exact: true }).click();

      await expect(page).toHaveURL("/");
      await expect(page.getByRole("heading", { level: 1 })).toHaveText("Home");
    });
  }

  test("offers no control but Sign out", async ({ page }) => {
    await page.goto("/");
    await expectNoPatientInContext(page);

    await expect(page.getByRole("button")).toHaveText(["Sign out"]);
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

    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
    await expect(page).toHaveURL("/");
  });
});

import { expect, test } from "@playwright/test";

import { cardTitled } from "./card.helpers";

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
    // Settled: the Patient card's read, Home's only one, has landed.
    await expect(page.getByText("Tan Wei Ming")).toBeVisible();

    await expect(page.getByRole("button")).toHaveText(["Sign out"]);
  });

  // The development fixtures seed the default patient (backend
  // DevPatientFixtures); every active User holds patient:read.
  test("shows the default patient on Home", async ({ page }) => {
    await page.goto("/");

    const card = cardTitled(page, "Patient");
    await expect(card.getByRole("definition")).toHaveText([
      "Tan Wei Ming",
      "S1234567A",
      "Male",
      /^12 Mar 1985 \(\d+ y\)$/,
    ]);
    await expect(page.getByRole("alert")).toHaveCount(0);
  });

  test("sends a signed-in visit to login on to Home", async ({ page }) => {
    await page.goto("/login");

    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
    await expect(page).toHaveURL("/");
  });
});

import { expect, test } from "@playwright/test";

import { resetCounterViaApi } from "./auth.helpers";

test.describe("authentication and counter", () => {
  test.beforeEach(async ({ page }) => {
    await resetCounterViaApi(page);
  });

  test("opens Home with the saved session and manages the counter", async ({ page }) => {
    await page.goto("/");

    await expect(page).toHaveURL("/");
    await expect(page.getByText("Signed in as admin")).toBeVisible();

    const count = page.getByTestId("count");
    const increment = page.getByRole("button", { name: "Increment" });
    const reset = page.getByRole("button", { name: "Reset" });

    await expect(count).toHaveText("Clicked 0 times");
    await expect(reset).toBeDisabled();
    // The Patient card above the counter grows when its own read lands, moving
    // the buttons; a click aimed before that lands where they were. So the
    // page is settled first.
    await expect(page.getByText("Tan Wei Ming")).toBeVisible();

    await increment.click();
    await expect(count).toHaveText("Clicked 1 time");

    await increment.click();
    await expect(count).toHaveText("Clicked 2 times");

    await reset.click();
    await expect(count).toHaveText("Clicked 0 times");
    await expect(reset).toBeDisabled();
  });

  // The development fixtures seed the default patient (backend
  // DevPatientFixtures); every active User holds patient:read.
  test("shows the default patient on Home", async ({ page }) => {
    await page.goto("/");

    const card = page.getByRole("heading", { name: "Patient" }).locator("../..");
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

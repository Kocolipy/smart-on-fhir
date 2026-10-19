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

  test("asks for no counter and offers no counter control", async ({ page }) => {
    const counterRequests: string[] = [];
    page.on("request", (request) => {
      if (new URL(request.url()).pathname.startsWith("/api/count")) {
        counterRequests.push(request.url());
      }
    });

    await page.goto("/");
    // Settled: the Patient card's read, Home's only one, has landed.
    await expect(page.getByText("Tan Wei Ming")).toBeVisible();

    await expect(page.getByRole("button")).toHaveText(["Sign out"]);
    expect(counterRequests).toEqual([]);
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

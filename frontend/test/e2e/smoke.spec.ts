import { expect, test } from "@playwright/test";

// The smoke suite. It asserts the things that break when the *build* is wrong
// rather than when a feature is wrong: the bundle boots, Tailwind's stylesheet
// is actually generated and applied, and React state updates reach the DOM.
//
// Flakiness rules that apply to every spec added here live in
// docs/TESTING_GUIDE.md; the short version is to assert on the end state and
// let Playwright's web-first assertions do the waiting, never
// `page.waitForTimeout()` and never `waitForLoadState("networkidle")`.
test.describe("smoke", () => {
  test("boots and renders the login page", async ({ page }) => {
    await page.goto("/login");

    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
    await expect(page).toHaveTitle("Front End");
  });

  test("applies the Tailwind design tokens", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    // If @tailwindcss/vite failed to generate the stylesheet, or src/index.css
    // never reached the bundle, the body keeps the browser default of
    // `rgba(0, 0, 0, 0)` and every utility class in the tree is inert. The
    // exact value is not the point — that it resolved to *something* is.
    const background = await page
      .locator("body")
      .evaluate((element) => getComputedStyle(element).backgroundColor);

    expect(background).not.toBe("rgba(0, 0, 0, 0)");
  });

  test("loads without console errors", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (message) => {
      const isExpectedGuestResponse =
        message.text() ===
          "Failed to load resource: the server responded with a status of 401 (Unauthorized)" &&
        message.location().url.endsWith("/api/auth/me");

      if (message.type() === "error" && !isExpectedGuestResponse) {
        errors.push(message.text());
      }
    });
    page.on("pageerror", (error) => errors.push(error.message));

    await page.goto("/login");
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();

    expect(errors).toEqual([]);
  });
});

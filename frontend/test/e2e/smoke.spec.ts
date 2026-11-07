import { expect, test } from "@playwright/test";

import { recordConsoleErrors } from "./console.helpers";

// The smoke suite. It asserts the things that break when the *build* is wrong
// rather than when a feature is wrong: the bundle boots, Tailwind's stylesheet
// is actually generated and applied, and React state updates reach the DOM.
// One page load holds all three, as one test.
//
// Flakiness rules that apply to every spec added here live in
// docs/E2E_GUIDE.md; the short version is to assert on the end state and
// let Playwright's web-first assertions do the waiting, never
// `page.waitForTimeout()` and never `waitForLoadState("networkidle")`.
test("boots the login page, styled, with an empty console", async ({ page }) => {
  // The signed-out page only; `console-errors.spec.ts` walks the signed-in routes.
  // A guest's session check is answered 401, which the browser logs.
  const errors = recordConsoleErrors(
    page,
    (message) =>
      message.text() ===
        "Failed to load resource: the server responded with a status of 401 (Unauthorized)" &&
      message.location().url.endsWith("/api/auth/me"),
  );

  await page.goto("/login");

  await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
  await expect(page).toHaveTitle("Front End");
  // If @tailwindcss/vite failed to generate the stylesheet, or src/index.css
  // never reached the bundle, the body keeps the browser default of
  // `rgba(0, 0, 0, 0)` and every utility class in the tree is inert. The
  // exact value is not the point — that it resolved to *something* is.
  const background = await page
    .locator("body")
    .evaluate((element) => getComputedStyle(element).backgroundColor);
  expect(background).not.toBe("rgba(0, 0, 0, 0)");
  expect(errors).toEqual([]);
});

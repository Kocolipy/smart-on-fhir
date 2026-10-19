import { expect, test } from "@playwright/test";

import { expireSession } from "./auth.helpers";

const CSRF_HEADER = "x-csrf-token";

// What the SPA does when a request it expected to succeed comes back refused.
// `src/lib/http.ts` classifies 401 and a 403 that survives the CSRF re-seed into
// different results, and the session seam answers them differently — a real
// backend on the other end is the only thing that proves those two statuses are
// what it actually sends. The unit suites cover the same branches against a
// stubbed `fetch`; these cover that the contract behind them is real.
//
// Neither test changes anything another spec reads: the 401 is met by the
// Patient card's read, and the password change below is refused for its token
// before the backend looks at it, so the seeded admin's password never moves.
test.describe("sessions, signed in", () => {
  test("returns to the login page when the session has expired", async ({ context, page }) => {
    await page.goto("/");
    // The Patient card's read landing also guarantees the provider has finished
    // checking the session before we break it.
    await expect(page.getByText("Tan Wei Ming")).toBeVisible();
    // Parked off Home by an in-app link, so the SPA keeps its signed-in state.
    await page.getByRole("link", { name: "Change password" }).click();
    await expect(page.getByRole("heading", { name: "Change your password" })).toBeVisible();

    await expireSession(context);

    // `Back` remounts Home, whose Patient card reads again and meets the 401.
    await page.getByRole("link", { name: "Back" }).click();

    // The 401 has to move the auth state to guest, not just show an error:
    // ProtectedRoute is what carries the user back to sign in.
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
    // And tells them why: the expired-session state carried by the redirect is
    // the SPA's whole explanation of a 401, and a cold visit shows none.
    await expect(page.getByRole("status")).toHaveText("Your session ended. Please sign in again.");
  });

  test("reports a 403 that survives the re-seed as permission denied without ending the session", async ({
    page,
  }) => {
    await page.goto("/");
    await page.getByRole("link", { name: "Change password" }).click();
    await expect(page.getByRole("heading", { name: "Change your password" })).toBeVisible();

    // Corrupt the echoed token rather than letting it go stale: `apiFetch`
    // answers a 403 by re-fetching the token and retrying once, so a stale
    // token would simply be replaced and the request would succeed. Rewriting
    // the header on every attempt makes the backend reject both; a 403 sent
    // with a just-issued token is an authorization refusal to `apiFetch`, so it
    // reaches the page as `forbidden`.
    let attempts = 0;
    await page.route("**/api/auth/change-password", async (route) => {
      attempts += 1;
      await route.continue({
        headers: { ...route.request().headers(), [CSRF_HEADER]: "not-the-current-token" },
      });
    });

    // Refused for its token before the backend reads it, so no password
    // changes. The current password is deliberately not the admin's, so the
    // change could not succeed even if a token got through.
    await page.getByLabel("Current password").fill("Not-The-Admin-Password-1");
    await page.getByLabel("New password", { exact: true }).fill("Never-Applied-Secret-7q");
    await page.getByLabel("Confirm new password").fill("Never-Applied-Secret-7q");
    await page.getByRole("button", { name: "Change password" }).click();

    await expect(page.getByRole("alert")).toHaveText("You don't have permission to do this.");
    // Exactly one retry after the re-seed, never a loop.
    expect(attempts).toBe(2);

    // A 403 is not a 401: the session survives, so the user stays where they are
    // instead of being sent back to sign in.
    await expect(page).toHaveURL("/change-password");
    await expect(page.getByText("Signed in as admin")).toBeVisible();
  });
});

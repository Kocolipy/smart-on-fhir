import { expect, test } from "@playwright/test";

import { expireSession } from "./auth.helpers";

const CSRF_HEADER = "x-csrf-token";

// What the SPA does when a request it expected to succeed comes back refused.
// `src/lib/http.ts` classifies 401 and a 403 that survives the CSRF re-seed into
// different results
// and `src/pages/home.tsx` answers them differently — a real backend on the
// other end is the only thing that proves those two statuses are what it
// actually sends. The unit suites cover the same branches against a stubbed
// `fetch`; these cover that the contract behind them is real.
//
// Neither test asserts a counter value: this spec shares the backend's single
// counter with home.spec.ts under `fullyParallel`. Both requests here
// are refused, so the count never moves.
test.describe("sessions, signed in", () => {
  test("returns to the login page when the session has expired", async ({ context, page }) => {
    await page.goto("/");

    const increment = page.getByRole("button", { name: "Increment" });
    // The initial load disables the button; waiting for it also guarantees the
    // provider has finished checking the session before we break it.
    await expect(increment).toBeEnabled();

    await expireSession(context);

    await increment.click();

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

    const increment = page.getByRole("button", { name: "Increment" });
    await expect(increment).toBeEnabled();

    // Corrupt the echoed token rather than letting it go stale: `apiFetch`
    // answers a 403 by re-fetching the token and retrying once, so a stale
    // token would simply be replaced and the request would succeed. Rewriting
    // the header on every attempt makes the backend reject both; a 403 sent
    // with a just-issued token is an authorization refusal to `apiFetch`, so it
    // reaches the page as `forbidden`.
    let attempts = 0;
    await page.route("**/api/count/increment", async (route) => {
      attempts += 1;
      await route.continue({
        headers: { ...route.request().headers(), [CSRF_HEADER]: "not-the-current-token" },
      });
    });

    await increment.click();

    await expect(page.getByRole("alert")).toHaveText("You don't have permission to do this.");
    // Exactly one retry after the re-seed, never a loop.
    expect(attempts).toBe(2);

    // A 403 is not a 401: the session survives, so the user stays where they are
    // instead of being sent back to sign in.
    await expect(page).toHaveURL("/");
    await expect(page.getByText("Signed in as admin")).toBeVisible();
  });
});

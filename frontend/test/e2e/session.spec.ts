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
// Neither test changes anything another spec reads. Both send the seeded
// admin's password change, and the backend refuses each before its password
// check runs — the first for want of a live session, the second for its token —
// so no password moves and no failed attempt is counted against the admin.
test.describe("sessions, signed in", () => {
  test("returns to the login page when the password change meets an expired session", async ({
    context,
    page,
  }) => {
    await page.goto("/change-password");
    // The signed-in layout also guarantees the provider has finished checking
    // the session before we break it.
    await expect(page.getByRole("heading", { name: "Change your password" })).toBeVisible();
    await expect(page.getByText("Signed in as admin")).toBeVisible();

    await expireSession(context);

    const answers: number[] = [];
    page.on("response", (response) => {
      if (response.url().endsWith("/api/auth/change-password")) answers.push(response.status());
    });

    // Not the admin's current password, so the change could not succeed even if
    // the session had survived. It never reaches the password check: the token
    // it is sent with belongs to a fresh, anonymous session — fetched first if
    // none is held, or by `apiFetch`'s re-fetch after a dead session's token is
    // refused 403 — so the backend refuses it 401 for having no signed-in
    // session: the bare 401, without the mark the password check puts on its own.
    await page.getByLabel("Current password").fill("Not-The-Admin-Password-1");
    await page.getByLabel("New password", { exact: true }).fill("Never-Applied-Secret-7q");
    await page.getByLabel("Confirm new password").fill("Never-Applied-Secret-7q");
    await page.getByRole("button", { name: "Change password" }).click();

    // The 401 has to move the auth state to guest, not just show an error:
    // ProtectedRoute is what carries the user back to sign in.
    await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
    // And tells them why: the expired-session state carried by the redirect is
    // the SPA's whole explanation of a 401, and a cold visit shows none.
    await expect(page.getByRole("status")).toHaveText("Your session ended. Please sign in again.");
    // The 401 was the password change's own answer, not a later read's.
    expect(answers.at(-1)).toBe(401);
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

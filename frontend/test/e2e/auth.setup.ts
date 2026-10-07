import { expect, request, test as setup, type Page } from "@playwright/test";

import {
  ADMIN_PASSWORD,
  ADMIN_USERNAME,
  csrfHeaderFor,
  FIXTURE_PASSWORD,
  SEED_PASSWORD,
  loginAs,
  submitLogin,
  submitLoginViaApi,
} from "./auth.helpers";
import { sweepLeftovers } from "./scim.helpers";

// `user` is the development fixture with no Role, on the fixture password.
setup("authenticate USER", async ({ page }) => {
  await loginAs(page, "user", FIXTURE_PASSWORD);
  await page.context().storageState({ path: "test/e2e/.auth/user.json" });
});

setup("authenticate ADMIN", async ({ page }) => {
  await settleAdminPassword(page);
  await loginAs(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  // The login discarded the session's CSRF token. Issued here, once, so the
  // workers replaying this session all read the same one: left unissued, the
  // first fetches race, each generating a token of its own, and every worker
  // but the last to save holds a token the session no longer has.
  await csrfHeaderFor(page.request);
  await page.context().storageState({ path: "test/e2e/.auth/admin.json" });
  // Here because this is the one point where an Admin session exists and no
  // spec is running yet: every other project depends on `setup`. A sweep run
  // mid-suite would delete a fixture a parallel spec is still using.
  await sweepLeftovers(page);
});

/**
 * Leave the seeded Admin on `ADMIN_PASSWORD` with no change pending.
 *
 * A fresh database seeds the Bootstrap Admin on the seed password with a change
 * required, so its first sign-in is confined to `/change-password`; that change
 * is made here, through the page, exactly as a person would. A database seeded
 * before the flag existed holds the seed password with nothing pending; the
 * same change is made voluntarily, so every database converges on one Admin
 * password and the specs need to know only that one.
 *
 * Which case applies is read from the login API in a context of its own, so the
 * probe neither touches the page nor counts as more than one refused sign-in.
 */
async function settleAdminPassword(page: Page) {
  const api = await request.newContext({
    baseURL: setup.info().project.use.baseURL,
    storageState: { cookies: [], origins: [] },
  });
  try {
    const current = await submitLoginViaApi(api, ADMIN_USERNAME, ADMIN_PASSWORD);
    if (current.status() === 200) {
      const { passwordChangeRequired } = (await current.json()) as {
        passwordChangeRequired: boolean;
      };
      // An Admin forced the change on the suite's own Admin: nothing here can
      // pick a password it would accept again, so say so rather than guess.
      expect(
        passwordChangeRequired,
        `${ADMIN_USERNAME} holds E2E_ADMIN_PASSWORD but has a password change pending`,
      ).toBe(false);
      return;
    }

    const seeded = await submitLoginViaApi(api, ADMIN_USERNAME, SEED_PASSWORD);
    expect(
      seeded.status(),
      `${ADMIN_USERNAME} accepts neither E2E_ADMIN_PASSWORD nor the seed password; ` +
        "set E2E_ADMIN_PASSWORD to its current password",
    ).toBe(200);
  } finally {
    await api.dispose();
  }

  // Through the page: the forced change lands here by itself, the voluntary one
  // is opened directly. Either way the form is the same.
  await submitLogin(page, ADMIN_USERNAME, SEED_PASSWORD);
  await expect(page).toHaveURL(/\/(?:change-password|showcase)$/);
  await page.goto("/change-password");
  await expect(page.getByRole("heading", { name: "Change your password" })).toBeVisible();

  await page.getByLabel("Current password").fill(SEED_PASSWORD);
  await page.getByLabel("New password", { exact: true }).fill(ADMIN_PASSWORD);
  await page.getByLabel("Confirm new password").fill(ADMIN_PASSWORD);
  await page.getByRole("button", { name: "Change password" }).click();

  // A change ends every session of the User and returns the page to login. A
  // refusal (the policy, or history) shows as an alert instead; matching either
  // puts the backend's reason in the failure rather than a bare timeout.
  await expect(page.getByRole("status").or(page.getByRole("alert"))).toHaveText(
    "Your password was changed. Sign in with your new password.",
  );
}

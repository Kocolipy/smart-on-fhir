import { expect, test } from "@playwright/test";

import { submitLogin } from "./auth.helpers";
import {
  deprovisionUser,
  E2E_PREFIX,
  freshBrowser,
  OWN_PASSWORD,
  provisionUser,
  runId,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * The SPA's own sign-out for inactivity (#100), against the real backend and a
 * real browser: the idle limit is the one `GET /api/auth/me` reports, the
 * warning is a native modal `alertdialog` the keyboard can answer, staying is a
 * real request, and the sign-out at the limit really logs out.
 *
 * The page's clock is Playwright's, so fifteen minutes pass in an instant. The
 * backend's clock is not faked, so its session is still live when the SPA's
 * limit arrives and the logout is a real `204`.
 *
 * The User is provisioned here and deleted in a `finally`: an idle sign-out
 * ends its session, which must never be a seeded identity's the other projects
 * replay.
 */

const RUN = runId();
const connector = workerConnector("idle");

const INACTIVE_NOTICE = "You were signed out because you were inactive. Please sign in again.";

test("warns before the idle limit, stays on Escape, and signs out at the limit", async ({
  browser,
}) => {
  test.setTimeout(90_000);
  const userName = `${E2E_PREFIX}idle-${RUN}`;
  const id = await provisionUser(connector.scim, userName);
  try {
    await settlePassword(userName);
    const context = await freshBrowser(browser);
    try {
      const page = await context.newPage();
      await page.clock.install();
      await submitLogin(page, userName, OWN_PASSWORD);
      await expect(page).toHaveURL("/");

      const me = await page.request.get("/api/auth/me");
      const { idleTimeoutSeconds } = (await me.json()) as { idleTimeoutSeconds: number };
      expect(idleTimeoutSeconds, "the backend reports its idle bound").toBeGreaterThan(120);

      // A minute before the limit the warning opens, holding focus.
      await page.clock.fastForward((idleTimeoutSeconds - 61) * 1000);
      await expect(page.getByRole("alertdialog")).toHaveCount(0);
      await page.clock.fastForward(1000);
      const warning = page.getByRole("alertdialog", { name: "Are you still there?" });
      await expect(warning).toBeVisible();
      await expect(warning).toBeFocused();

      // Escape stays signed in, with one real request that renews the session.
      const stayed = page.waitForResponse(
        (response) => response.url().endsWith("/api/auth/me") && response.status() === 200,
      );
      await page.keyboard.press("Escape");
      await stayed;
      await expect(warning).toHaveCount(0);

      // A full limit from the stay, untouched: signed out, and told why.
      await page.clock.fastForward(idleTimeoutSeconds * 1000);
      await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
      await expect(page).toHaveURL(/\/login$/);
      await expect(page.getByRole("status")).toHaveText(INACTIVE_NOTICE);
      expect((await page.request.get("/api/auth/me")).status(), "logged out on the backend").toBe(
        401,
      );
    } finally {
      await context.close();
    }
  } finally {
    await deprovisionUser(connector.scim, id);
  }
});

test("the warning's buttons answer the keyboard", async ({ browser }) => {
  test.setTimeout(90_000);
  const userName = `${E2E_PREFIX}idle-keys-${RUN}`;
  const id = await provisionUser(connector.scim, userName);
  try {
    await settlePassword(userName);
    const context = await freshBrowser(browser);
    try {
      const page = await context.newPage();
      await page.clock.install();
      await submitLogin(page, userName, OWN_PASSWORD);
      await expect(page).toHaveURL("/");
      const { idleTimeoutSeconds } = (await (await page.request.get("/api/auth/me")).json()) as {
        idleTimeoutSeconds: number;
      };

      await page.clock.fastForward((idleTimeoutSeconds - 60) * 1000);
      const warning = page.getByRole("alertdialog");
      await expect(warning).toBeFocused();

      // Tab moves through the dialog's own buttons; Enter on "Sign out" signs out.
      await page.keyboard.press("Tab");
      await expect(warning.getByRole("button", { name: "Sign out" })).toBeFocused();
      await page.keyboard.press("Tab");
      await expect(warning.getByRole("button", { name: "Stay signed in" })).toBeFocused();
      await page.keyboard.press("Shift+Tab");
      await page.keyboard.press("Enter");

      await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
      await expect(page.getByRole("status")).toHaveCount(0);
    } finally {
      await context.close();
    }
  } finally {
    await deprovisionUser(connector.scim, id);
  }
});

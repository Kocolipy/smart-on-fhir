import { expect, test, type Page } from "@playwright/test";

/**
 * Every authenticated route loads with an empty console, as an Admin and as a
 * User. The smoke suite checks the login page; these are the pages that decode
 * API bodies (#60) and could trip the error boundary, so a contract that
 * drifted between the backend and the SPA shows up here as a console error or
 * an alert before it shows up as a wrong screen.
 *
 * In the `admin` project, which runs after `user` and leaves the seeded User's
 * session alone, so `user.json` still replays a live session here.
 */

/** What each route shows once its data has loaded, so nothing is checked mid-fetch. */
const READY: Record<string, (page: Page) => Promise<void>> = {
  "/": async (page) => {
    await expect(page.getByTestId("count")).toHaveText(/^Clicked \d+ times?$/);
  },
  "/accounts": async (page) => {
    await expect(page.getByRole("table", { name: "Users" }).getByRole("row").nth(1)).toBeVisible();
    await expect(page.getByRole("table", { name: "Groups" }).getByRole("row").nth(1)).toBeVisible();
    await expect(page.getByRole("button", { name: "Create connector" })).toBeVisible();
  },
  "/change-password": async (page) => {
    await expect(page.getByRole("heading", { name: "Change your password" })).toBeVisible();
  },
};

const IDENTITIES = [
  { role: "admin", routes: ["/", "/accounts", "/change-password"] },
  {
    role: "user",
    routes: ["/", "/change-password"],
    storageState: "test/e2e/.auth/user.json",
  },
] as const;

/** Collects console errors and uncaught exceptions from `page`. */
function recordErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(`${page.url()}: ${message.text()}`);
  });
  page.on("pageerror", (error) => errors.push(`${page.url()}: ${error.message}`));
  return errors;
}

for (const identity of IDENTITIES) {
  test(`every ${identity.role} route loads without console errors`, async ({ browser, page }) => {
    // The admin project's own page replays the Admin; the User gets a context of its own.
    const target =
      "storageState" in identity
        ? await (
            await browser.newContext({
              baseURL: test.info().project.use.baseURL,
              storageState: identity.storageState,
            })
          ).newPage()
        : page;
    try {
      const errors = recordErrors(target);
      for (const route of identity.routes) {
        await target.goto(route);
        // Landing where asked proves the route rendered for this role rather
        // than redirecting; no alert means neither a refused load nor the
        // error boundary's fallback.
        await expect(target).toHaveURL(route);
        await READY[route]!(target);
        await expect(target.getByRole("alert")).toHaveCount(0);
      }
      expect(errors).toEqual([]);
    } finally {
      if (target !== page) await target.context().close();
    }
  });
}

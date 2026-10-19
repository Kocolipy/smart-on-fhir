import { expect, type Page } from "@playwright/test";

/**
 * Each signed-in route, and what it shows once its data has loaded, so a spec
 * that walks the routes checks nothing mid-fetch. Shared by
 * `console-errors.spec.ts` and `packaged-csp.spec.ts`, which walk the same
 * routes against the dev server and the packaged JAR.
 */
export const ROUTE_READY: Record<string, (page: Page) => Promise<void>> = {
  "/": async (page) => {
    await expect(page.getByText("Tan Wei Ming")).toBeVisible();
  },
  "/accounts": async (page) => {
    await expect(page.getByRole("table", { name: "Users" }).getByRole("row").nth(1)).toBeVisible();
    await expect(page.getByRole("table", { name: "Groups" }).getByRole("row").nth(1)).toBeVisible();
    await expect(page.getByRole("button", { name: "Create connector" })).toBeVisible();
  },
  "/audit": async (page) => {
    // The trail is never empty: the `setup` project's sign-ins are on it.
    await expect(
      page.getByRole("table", { name: "Audit events" }).getByRole("row").nth(1),
    ).toBeVisible();
  },
  "/change-password": async (page) => {
    await expect(page.getByRole("heading", { name: "Change your password" })).toBeVisible();
  },
};

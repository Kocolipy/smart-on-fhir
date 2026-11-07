import { expect, test } from "@playwright/test";

import { expectNoPatientInContext, recordPatientRequests } from "./patient.helpers";

// The seeded `user`: a baseline User, in no Group, so holding only the
// baseline Permissions every User holds. What it sees, and the pages the SPA
// routes it away from, are asserted here. What it holds, its self-service, and
// the backend refusing it every administrative read and the Unlock need no
// browser, and are the backend's suites' to prove
// (`DevelopmentFixturesIntegrationTests`, `AuthorizationContractTests`); Logout
// is `login.spec.ts`'.

test.describe("baseline User pages", () => {
  // A password login puts no patient in context, though every User holds
  // patient:read: the Patient card says so and reads nothing.
  test("lands on Home with no patient in context, self-service, and no administration", async ({
    page,
  }) => {
    const patientRequests = recordPatientRequests(page);
    await page.goto("/");

    await expect(page.getByRole("heading", { name: "Profile" })).toBeVisible();
    await expect(page).toHaveURL("/");
    await expectNoPatientInContext(page);
    await expect(page.getByRole("alert")).toHaveCount(0);
    expect(patientRequests()).toEqual([]);
    // The navigation sidebar links only what a baseline User may open: Profile.
    await expect(page.locator('[data-slot="sidebar"]').getByRole("link")).toHaveText(["Profile"]);
  });

  test("is routed away from the accounts page by a deep link", async ({ page }) => {
    await page.goto("/accounts");

    await expect(page.getByRole("heading", { name: "Profile" })).toBeVisible();
    await expect(page).toHaveURL("/");
  });
});

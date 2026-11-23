import { expect, test } from "@playwright/test";

import { expectSignedInLines, MEDICAL_OFFICER } from "./auth.helpers";
import { expectNoPatientInContext, recordPatientRequests } from "./patient.helpers";

/**
 * The Medical Officer's role: a member of the Medical Officer fixture Group,
 * which carries `patient:read` alone. A password login puts no patient in
 * context, so its Patient card says so — not that it has no access — and reads
 * nothing; its sidebar links Profile with no Admin group. What it holds, and the
 * backend refusing it every administrative read, need no browser
 * (`DevelopmentFixturesIntegrationTests`, `AuthorizationContractTests`); reading
 * a patient an EHR launch names is the `epic` and `fhircast` projects'.
 *
 * In the `medical-officer` project, replaying the saved `mo1` session, so its
 * login is `auth.setup.ts`'.
 */
test.describe("Medical Officer pages", () => {
  test("lands on Home with no patient in context, linked to Profile, with no Admin group", async ({
    page,
  }) => {
    const patientRequests = recordPatientRequests(page);
    await page.goto("/");

    await expect(page.getByRole("heading", { name: "Profile" })).toBeVisible();
    await expectNoPatientInContext(page);
    expect(patientRequests()).toEqual([]);
    const sidebar = page.locator('[data-slot="sidebar"]');
    await expect(sidebar.getByRole("link")).toHaveText(["Profile"]);
    await expect(sidebar.getByRole("button", { name: "Admin" })).toHaveCount(0);
  });

  test("is named in the top bar by its username, above its Group", async ({ page }) => {
    await page.goto("/");

    await expectSignedInLines(page, [MEDICAL_OFFICER.username, "MEDICAL OFFICER"]);
  });
});

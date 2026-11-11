import { expect, test, type Page } from "@playwright/test";

import { DEV_FIXTURES, loginAsFixture, type DevFixture } from "./auth.helpers";
import { expectNoPatientInContext, recordPatientRequests } from "./patient.helpers";

// The development fixtures' Users: each signs in through the login page and
// sees exactly the pages and actions its Group's Permissions allow (the `local`
// profile's stored Group Permissions, ADR 0015). None of them is the Superuser,
// so none is offered connector administration, though each holds
// `connector:read`, which lists connectors and does no more. What each holds,
// and the backend refusing each direct call outside it, need no browser:
// `DevelopmentFixturesIntegrationTests` and `AuthorizationContractTests` prove
// them.
//
// In the `guest` project — empty `storageState` — because signing in is the
// subject. The Superuser is left out on purpose: its User is the Bootstrap
// Admin, whose sign-in would end the session `admin.json` replays and that
// `login.spec.ts` uses beside this file; what it holds is the backend's
// `DevelopmentFixturesIntegrationTests`' to prove. Each fixture User is signed in by this file alone,
// and by one test, so a parallel run never signs one of them out of another.

/** What the Accounts page shows a fixture, or `null` when its deep link routes away. */
type AccountsView = { users: boolean; groups: boolean; connectors: boolean } | null;

const EXPECTED: Record<
  Exclude<DevFixture, "superuser">,
  { accounts: AccountsView; audit: boolean; createConnector: boolean }
> = {
  auditor: {
    accounts: { connectors: true, groups: true, users: true },
    audit: true,
    createConnector: false,
  },
  helpdesk: {
    accounts: { connectors: true, groups: true, users: true },
    audit: true,
    createConnector: false,
  },
};

async function expectHome(page: Page, linksToAccounts: boolean, linksToAudit: boolean) {
  const patientRequests = recordPatientRequests(page);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Profile" })).toBeVisible();
  // Every fixture's User holds the baseline patient:read, but a password
  // login puts no patient in context: the Patient card says so and reads nothing.
  await expectNoPatientInContext(page);
  expect(patientRequests()).toEqual([]);
  await expect(page.getByRole("link", { name: "Accounts", exact: true })).toHaveCount(
    linksToAccounts ? 1 : 0,
  );
  await expect(page.getByRole("link", { name: "Audit logs", exact: true })).toHaveCount(
    linksToAudit ? 1 : 0,
  );
}

async function expectAccounts(page: Page, view: AccountsView, createConnector: boolean) {
  await page.goto("/accounts");
  if (view === null) {
    // Routed away exactly as any page the User lacks the Permission for.
    await expect(page.getByRole("heading", { name: "Profile" })).toBeVisible();
    await expect(page).toHaveURL("/");
    return;
  }
  await expect(page.getByRole("heading", { name: "Accounts", exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/accounts$/);
  if (view.users) {
    await expect(page.getByRole("table", { name: "Users" }).getByRole("row").nth(1)).toBeVisible();
  }
  for (const [title, shown] of [
    ["Users", view.users],
    ["Groups", view.groups],
    ["Connectors", view.connectors],
  ] as const) {
    await expect(page.getByRole("heading", { name: title, exact: true })).toHaveCount(
      shown ? 1 : 0,
    );
  }
  await expect(page.getByRole("button", { name: "Create connector" })).toHaveCount(
    createConnector ? 1 : 0,
  );
}

/** The audit trail: visible and reachable only for a session holding `audit:read`. */
async function expectAudit(page: Page, canView: boolean) {
  await page.goto("/audit");
  if (!canView) {
    // Routed away exactly as any page the User lacks the Permission for.
    await expect(page.getByRole("heading", { name: "Profile" })).toBeVisible();
    await expect(page).toHaveURL("/");
    return;
  }
  await expect(page.getByRole("heading", { name: "Audit", exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/audit$/);
  await expect(page.getByRole("table", { name: "Audit events" })).toBeVisible();
}

test.describe("development Group Permissions", () => {
  for (const fixture of Object.keys(EXPECTED) as (keyof typeof EXPECTED)[]) {
    const { username } = DEV_FIXTURES[fixture];
    const expected = EXPECTED[fixture];

    test(`${username} sees and reaches exactly what its ${fixture} Group's Permissions allow`, async ({
      page,
    }) => {
      await loginAsFixture(page, fixture);

      // The pages and actions it is shown.
      await expectHome(page, expected.accounts !== null, expected.audit);
      await expectAccounts(page, expected.accounts, expected.createConnector);
      await expectAudit(page, expected.audit);
    });
  }
});

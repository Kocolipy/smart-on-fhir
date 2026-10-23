import { expect, test, type Page } from "@playwright/test";

import {
  DEV_FIXTURES,
  adminRequest,
  loginAsFixture,
  postAdminAction,
  type DevFixture,
} from "./auth.helpers";
import { expectNoPatientInContext, recordPatientRequests } from "./patient.helpers";
import { BACKEND_URL, E2E_PREFIX } from "./scim.helpers";

// The development fixtures' Users: each signs in through the login page, holds
// exactly what its Group carries (the `local` profile's stored Group
// Permissions, ADR 0015) on `GET /api/auth/me`, sees exactly the pages and
// actions those Permissions allow, and is refused every direct API call outside
// them. `roles-admin.spec.ts` reads each Group's stored Permissions back. None
// of them is the Superuser: connector administration is refused to each, though
// each holds `connector:read`, which lists connectors and does no more.
//
// In the `guest` project — empty `storageState` — because signing in is the
// subject. The Superuser is left out on purpose: its User is the Bootstrap
// Admin, whose sign-in would end the session `admin.json` replays and that
// `login.spec.ts` uses beside this file; `roles-admin.spec.ts` checks it on the
// replayed session instead. Each fixture User is signed in by this file alone,
// and by one test, so a parallel run never signs one of them out of another.

/** Every Permission-guarded read on the application chain, by the Permission it requires. */
const GUARDED_READS = {
  // Straight to the backend: Vite proxies only `/api` and would answer this with the SPA.
  [`${BACKEND_URL}/actuator/prometheus`]: "ops:read",
  "/api/admin/accounts": "user:read",
  "/api/admin/audit-events": "audit:read",
  "/api/admin/connectors": "connector:read",
  "/api/admin/groups": "group:read",
  "/api/admin/permissions": "group:read",
} as const;

/** What the Accounts page shows a fixture, or `null` when its deep link routes away. */
type AccountsView = { users: boolean; groups: boolean; connectors: boolean } | null;

const EXPECTED: Record<
  Exclude<DevFixture, "superuser">,
  { accounts: AccountsView; audit: boolean; unlock: boolean; createConnector: boolean }
> = {
  auditor: {
    accounts: { connectors: true, groups: true, users: true },
    audit: true,
    createConnector: false,
    unlock: false,
  },
  helpdesk: {
    accounts: { connectors: true, groups: true, users: true },
    audit: true,
    createConnector: false,
    unlock: true,
  },
};

async function expectHome(page: Page, linksToAccounts: boolean, linksToAudit: boolean) {
  const patientRequests = recordPatientRequests(page);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
  // Every fixture's User holds the baseline patient:read, but a password
  // login puts no patient in context: the Patient card says so and reads nothing.
  await expectNoPatientInContext(page);
  expect(patientRequests()).toEqual([]);
  await expect(page.getByRole("link", { name: "Manage accounts" })).toHaveCount(
    linksToAccounts ? 1 : 0,
  );
  await expect(page.getByRole("link", { name: "View audit log" })).toHaveCount(
    linksToAudit ? 1 : 0,
  );
}

async function expectAccounts(page: Page, view: AccountsView, createConnector: boolean) {
  await page.goto("/accounts");
  if (view === null) {
    // Routed away exactly as any page the User lacks the Permission for.
    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
    await expect(page).toHaveURL("/");
    return;
  }
  await expect(page.getByRole("heading", { name: "Accounts" })).toBeVisible();
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
    await expect(page.getByRole("heading", { name: "Home" })).toBeVisible();
    await expect(page).toHaveURL("/");
    return;
  }
  await expect(page.getByRole("heading", { name: "Audit", exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/audit$/);
  await expect(page.getByRole("table", { name: "Audit events" })).toBeVisible();
}

test.describe("development Group Permissions", () => {
  for (const fixture of Object.keys(EXPECTED) as (keyof typeof EXPECTED)[]) {
    const { permissions, username } = DEV_FIXTURES[fixture];
    const expected = EXPECTED[fixture];

    test(`${username} sees and reaches exactly what its ${fixture} Group's Permissions allow`, async ({
      page,
    }) => {
      await loginAsFixture(page, fixture);

      // Its Permissions.
      const me = await page.request.get("/api/auth/me");
      expect(me.status()).toBe(200);
      const body = (await me.json()) as Record<string, unknown>;
      expect(body.permissions).toEqual(permissions);
      expect(body.superuser).toBe(false);

      // The pages and actions it is shown.
      await expectHome(page, expected.accounts !== null, expected.audit);
      await expectAccounts(page, expected.accounts, expected.createConnector);
      await expectAudit(page, expected.audit);

      // A direct API call: each guarded read answers by its own Permission alone.
      const held: readonly string[] = permissions;
      for (const [path, permission] of Object.entries(GUARDED_READS)) {
        const status = (await page.request.get(path)).status();
        expect(status, `GET ${path} for ${username}`).toBe(held.includes(permission) ? 200 : 403);
      }
      // An action, with a valid CSRF token so the refusal is the Permission's: an
      // unknown id answers 404 to a holder of account:unlock, and 403 to anyone else.
      const unlock = await postAdminAction(page, crypto.randomUUID(), "unlock");
      expect(unlock.status(), `unlock for ${username}`).toBe(expected.unlock ? 404 : 403);
      // Connector administration is the Superuser's alone: refused to every Group, with
      // the generic 403 whatever the body, and nothing is created.
      const created = await adminRequest(page, "POST", "/api/admin/connectors", {
        displayName: `${E2E_PREFIX}refused-${username}`,
      });
      expect(created.status(), `create connector for ${username}`).toBe(403);
      const minted = await adminRequest(
        page,
        "POST",
        `/api/admin/connectors/${crypto.randomUUID()}/tokens`,
        { permissions: ["user:read"] },
      );
      expect(minted.status(), `issue token for ${username}`).toBe(403);
    });
  }
});

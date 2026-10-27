import { expect, test } from "@playwright/test";

import {
  DELEGATABLE_PERMISSIONS,
  DEV_FIXTURES,
  groupPermissionsOf,
  everyRowOf,
  type DevFixture,
} from "./auth.helpers";

test.describe("ADMIN route guards", () => {
  test("may view the accounts page", async ({ page }) => {
    await page.goto("/accounts");

    await expect(page.getByRole("heading", { name: "Accounts" })).toBeVisible();
    await expect(page.getByRole("rowheader", { name: /^admin/ }).first()).toBeVisible();
    await expect(page).toHaveURL(/\/accounts$/);
  });
});

test.describe("ADMIN Permissions", () => {
  // The Bootstrap Admin is a direct member of the Admin group, so its replayed
  // session is the Superuser's and holds every delegatable Permission, sorted by
  // name.
  test("is the Superuser, holding every delegatable Permission", async ({ page }) => {
    const me = await page.request.get("/api/auth/me");
    expect(me.status()).toBe(200);

    const body = (await me.json()) as { permissions: string[]; superuser: boolean };
    expect(body.superuser).toBe(true);
    expect(body.permissions).toEqual(DEV_FIXTURES.superuser.permissions);
  });
});

test.describe("ADMIN directory projections", () => {
  // `page.request` shares the context's cookie jar, so the replayed ADMIN
  // session authenticates this call. No CSRF header is needed: the token is
  // only demanded of unsafe methods.
  test("lists a page of Users with the projection's fields", async ({ page }) => {
    const response = await page.request.get("/api/admin/accounts", { params: { count: 200 } });
    expect(response.status()).toBe(200);

    const { startIndex, totalResults, users } = (await response.json()) as {
      startIndex: number;
      totalResults: number;
      users: Array<Record<string, unknown>>;
    };
    expect(startIndex).toBe(1);
    expect(users.length).toBeGreaterThanOrEqual(2);
    expect(totalResults).toBeGreaterThanOrEqual(users.length);
    // The seeded identities, by derived authority rather than by name where it
    // allows, so renaming a seed account in configuration does not fail this.
    expect(users.map((user) => user.admin)).toContain(true);
    expect(users.map((user) => user.admin)).toContain(false);

    const admin = users.find((user) => user.bootstrapAdmin === true);
    expect(admin, "the Bootstrap Admin should be listed").toBeTruthy();
    expect(Object.keys(admin!).sort()).toEqual([
      "active",
      "admin",
      "bootstrapAdmin",
      "createdAt",
      "displayName",
      "groups",
      "hasPassword",
      "id",
      "lastAuthenticatedAt",
      "lockCause",
      "locked",
      "passwordChangeRequired",
      "userName",
    ]);
    // It cannot be locked, so it has no lock cause, and its direct Groups include the Admin group.
    expect(admin!.locked).toBe(false);
    expect(admin!.lockCause).toBeNull();
    expect((admin!.groups as unknown[]).length).toBeGreaterThanOrEqual(1);
    expect(Number.isNaN(Date.parse(String(admin!.createdAt)))).toBe(false);
  });

  test("lists every Group with its member count, the Admin marker and its Permissions", async ({
    page,
  }) => {
    const groups = await everyRowOf(page, "Groups");
    const adminGroups = groups.filter((group) => group.adminGroup === true);
    expect(adminGroups).toHaveLength(1);
    expect(adminGroups[0]!.memberCount).toEqual(expect.any(Number));
    // The Admin group carries no Permission: its direct members are the Superuser.
    expect(adminGroups[0]!.permissions).toEqual([]);
  });

  /**
   * The access every development fixture signs in with (`dev-roles.spec.ts`)
   * is its Group's stored Permissions, read back the way an operator reviews
   * them (ADR 0015).
   */
  test("shows each development Group carrying what its fixture User holds", async ({ page }) => {
    const groups = await everyRowOf<{
      id: string;
      permissions: string[];
    }>(page, "Groups");
    const fixtures = (Object.keys(DEV_FIXTURES) as DevFixture[]).filter(
      (fixture): fixture is Exclude<DevFixture, "superuser"> => fixture !== "superuser",
    );

    for (const fixture of fixtures) {
      const group = groups.find((candidate) => candidate.id === DEV_FIXTURES[fixture].group);
      expect(group, `the ${fixture} Group should be listed`).toBeTruthy();
      expect(group!.permissions, `the ${fixture} Group's Permissions`).toEqual(
        groupPermissionsOf(fixture),
      );
    }
  });

  // That the listing carries no password hash, and how an Unlock or a write to
  // a directory-owned field is refused, need no browser: the backend's suites
  // prove them (`AdminAccountEndpointTests`, `AuthorizationContractTests`).
  test("lists every Permission a Group may carry", async ({ page }) => {
    const permissions = await page.request.get("/api/admin/permissions");
    expect(permissions.status()).toBe(200);
    expect(await permissions.json()).toEqual([...DELEGATABLE_PERMISSIONS]);
  });
});

import { expect, test } from "@playwright/test";

import { DEV_ROLES, adminRequest, postAdminAction, userIdOf } from "./auth.helpers";

test.describe("ADMIN route guards", () => {
  test("may view the accounts page", async ({ page }) => {
    await page.goto("/accounts");

    await expect(page.getByRole("heading", { name: "Accounts" })).toBeVisible();
    await expect(page.getByRole("rowheader", { name: /^admin/ }).first()).toBeVisible();
    await expect(page).toHaveURL(/\/accounts$/);
  });
});

test.describe("ADMIN Permissions", () => {
  // The Bootstrap Admin is the Superuser Group's member, so its replayed session
  // holds every Permission, sorted by name.
  test("holds every Permission through the Superuser Group", async ({ page }) => {
    const me = await page.request.get("/api/auth/me");
    expect(me.status()).toBe(200);

    const body = (await me.json()) as { permissions: string[] };
    expect(body.permissions).toEqual(DEV_ROLES.superuser.permissions);
  });
});

test.describe("ADMIN directory projections", () => {
  // `page.request` shares the context's cookie jar, so the replayed ADMIN
  // session authenticates this call. No CSRF header is needed: the token is
  // only demanded of unsafe methods.
  test("lists every User with the projection's fields", async ({ page }) => {
    const response = await page.request.get("/api/admin/accounts");
    expect(response.status()).toBe(200);

    const users = (await response.json()) as Array<Record<string, unknown>>;
    expect(users.length).toBeGreaterThanOrEqual(2);
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

  test("lists every Group with its member count and the Admin marker", async ({ page }) => {
    const response = await page.request.get("/api/admin/groups");
    expect(response.status()).toBe(200);

    const groups = (await response.json()) as Array<Record<string, unknown>>;
    const adminGroups = groups.filter((group) => group.adminGroup === true);
    expect(adminGroups).toHaveLength(1);
    expect(adminGroups[0]!.memberCount).toEqual(expect.any(Number));
  });

  /**
   * The acceptance criterion the whole endpoint exists to respect. Asserted on
   * the raw body, not on parsed fields: a hash nested inside an unexpected
   * object would still be caught.
   */
  test("never returns password hashes", async ({ page }) => {
    const body = await (await page.request.get("/api/admin/accounts")).text();

    expect(body).not.toContain('"password"');
    expect(body).not.toContain("$2a$");
    expect(body).not.toContain("argon2");
  });
});

test.describe("ADMIN account control", () => {
  /**
   * Unlocking a User that is serving no lockout is the safe case to assert
   * live: it is idempotent, writes nothing and requires no change, so it cannot
   * disturb a parallel spec. The locked case is driven through the page in
   * `accounts-admin.spec.ts`, on a User that spec provisions for itself.
   */
  test("reports a User as unlocked when it is serving no lockout", async ({ page }) => {
    const response = await postAdminAction(page, await userIdOf(page, "user"), "unlock");

    expect(response.status()).toBe(200);
    expect(await response.json()).toMatchObject({ locked: false, passwordChangeRequired: false });
  });

  /**
   * This project's session IS the Bootstrap Admin, the one Admin that cannot be
   * locked; unlocking yourself is refused regardless, for the reason the page
   * never offers it — a self-inflicted state takes a second Admin to undo.
   */
  test("refuses to unlock the account making the request", async ({ page }) => {
    const response = await postAdminAction(page, await userIdOf(page, "admin"), "unlock");

    expect(response.status()).toBe(403);
  });

  test("answers a request for a User that does not exist with 404", async ({ page }) => {
    const response = await postAdminAction(page, crypto.randomUUID(), "unlock");

    expect(response.status()).toBe(404);
  });

  /**
   * The read-only criterion from the backend's side, over the real stack: no
   * write reaches a directory-owned field, whatever method or path it takes.
   */
  test("refuses every write to a User or a Group", async ({ page }) => {
    const id = await userIdOf(page, "user");
    const directoryOwned = async () => {
      const rows = (await (await page.request.get("/api/admin/accounts")).json()) as Array<
        Record<string, unknown>
      >;
      const row = rows.find((candidate) => candidate.id === id)!;
      // Only the directory-owned fields: another project signing in moves
      // `lastAuthenticatedAt`, which is application-owned and not in question.
      return { active: row.active, groups: row.groups, userName: row.userName };
    };
    const before = await directoryOwned();
    const change = { active: false, groups: [], userName: "renamed" };

    for (const [method, path] of [
      ["PUT", `/api/admin/accounts/${id}`],
      ["PATCH", `/api/admin/accounts/${id}`],
      ["DELETE", `/api/admin/accounts/${id}`],
      ["POST", "/api/admin/accounts"],
      ["POST", "/api/admin/groups"],
      ["PUT", "/api/admin/groups"],
    ] as const) {
      const response = await adminRequest(page, method, path, change);
      expect(response.status(), `${method} ${path}`).toBe(403);
    }
    expect(await directoryOwned()).toEqual(before);
  });
});

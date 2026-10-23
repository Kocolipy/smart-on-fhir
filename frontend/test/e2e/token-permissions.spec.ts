import { expect, test, type APIRequestContext } from "@playwright/test";

import { adminRequest } from "./auth.helpers";
import { E2E_PREFIX, runId, scimApi, USER_SCHEMA } from "./scim.helpers";

// Connector tokens carry Permissions (#117, ADR 0015), end to end, and only the
// Superuser mints them (#155, ADR 0015): the Bootstrap Admin creates a connector
// and mints a `group:write`-only token from the SPA, and the token writes Groups
// and is refused on Users writes. That every other fixture — the Connector admin
// included, who holds `connector:read` and no more — are refused creating a
// connector or minting a token is `dev-roles.spec.ts`'s.
//
// In the `admin` project, on the Bootstrap Admin's replayed session: it signs no
// one in, so it ends no session another project replays.

const GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
const PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

const RUN = runId();
const CONNECTOR_NAME = `${E2E_PREFIX}group-writer-${RUN}`;

test("the Bootstrap Admin mints a group:write-only token that writes Groups and not Users", async ({
  page,
}) => {
  let scim: APIRequestContext | undefined;
  let groupId: string | undefined;
  let connectorId: string | undefined;
  try {
    await page.goto("/accounts");
    await expect(page.getByRole("heading", { name: "Connectors", exact: true })).toBeVisible();

    await page.getByLabel("New connector name").fill(CONNECTOR_NAME);
    await page.getByRole("button", { name: "Create connector" }).click();
    const section = page.getByRole("region", { name: `Connector ${CONNECTOR_NAME}` });
    await expect(section).toBeVisible();

    // The Superuser may put any token Permission on a token, so each is offered;
    // only group:write is chosen.
    const choices = section.getByRole("group", { name: `Permissions for ${CONNECTOR_NAME}` });
    await expect(choices.getByRole("checkbox")).toHaveCount(4);
    for (const checkbox of await choices.getByRole("checkbox").all()) {
      await expect(checkbox).toBeEnabled();
    }
    const issue = section.getByRole("button", { name: `Issue token for ${CONNECTOR_NAME}` });
    await expect(issue).toBeDisabled();
    await choices.getByRole("checkbox", { name: /^group:write / }).check();
    await issue.click();

    const value = await page.getByLabel("New token value").textContent();
    expect(value).toBeTruthy();
    await expect(page.getByText(/^group:write · expires /)).toBeVisible();
    await expect(section.getByRole("cell", { name: "group:write", exact: true })).toHaveCount(1);
    const listed = (await (await page.request.get("/api/admin/connectors")).json()) as Array<{
      displayName: string;
      id: string;
      tokens: Array<{ permissions: string[] }>;
    }>;
    const mine = listed.find((connector) => connector.displayName === CONNECTOR_NAME);
    expect(mine?.tokens.map((token) => token.permissions)).toEqual([["group:write"]]);
    connectorId = mine?.id;

    scim = await scimApi(value!);

    // Groups writes: create, rename, delete.
    const created = await scim.post("/scim/v2/Groups", {
      data: { schemas: [GROUP_SCHEMA], displayName: `${E2E_PREFIX}group-${RUN}` },
    });
    expect(created.status()).toBe(201);
    groupId = ((await created.json()) as { id: string }).id;
    const renamed = await scim.patch(`/scim/v2/Groups/${groupId}`, {
      data: {
        schemas: [PATCH_OP],
        Operations: [
          { op: "replace", path: "displayName", value: `${E2E_PREFIX}group-renamed-${RUN}` },
        ],
      },
    });
    expect(renamed.status()).toBeLessThan(300);

    // A Users write is refused: the token authenticated, and lacks user:write.
    const userWrite = await scim.post("/scim/v2/Users", {
      data: { schemas: [USER_SCHEMA], userName: `${E2E_PREFIX}refused-${RUN}` },
    });
    expect(userWrite.status()).toBe(403);
    expect(userWrite.headers()["www-authenticate"]).toContain('error="insufficient_scope"');
    // And so is a read: write does not imply read.
    expect((await scim.get(`/scim/v2/Groups/${groupId}`)).status()).toBe(403);

    expect((await scim.delete(`/scim/v2/Groups/${groupId}`)).status()).toBe(204);
    groupId = undefined;
  } finally {
    if (groupId !== undefined) await scim?.delete(`/scim/v2/Groups/${groupId}`);
    await scim?.dispose();
    if (connectorId !== undefined) {
      const deleted = await adminRequest(page, "DELETE", `/api/admin/connectors/${connectorId}`);
      expect([204, 404]).toContain(deleted.status());
    }
  }
});

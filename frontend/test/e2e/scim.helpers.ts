import {
  expect,
  request,
  test,
  type APIRequestContext,
  type Browser,
  type Locator,
  type Page,
} from "@playwright/test";

import { adminRequest, csrfHeaderFor, submitLoginViaApi } from "./auth.helpers";

/**
 * Fixtures for the specs that act on Users and connectors of their OWN: a
 * connector and token minted through the admin API, Users provisioned over
 * SCIM with that token, and the clean-up of both.
 *
 * Every name created here starts with {@link E2E_PREFIX}. That prefix is the
 * whole contract with `sweepLeftovers()`, which `auth.setup.ts` runs before any
 * spec starts: whatever a crashed run left behind is removed then, and never
 * during a run, where a prefix sweep would delete a fixture a parallel spec is
 * still using.
 */

/**
 * The backend itself: SCIM and actuator are not behind the Vite `/api` proxy,
 * which serves the SPA for any other path. The session cookie still rides along,
 * because a cookie is scoped to the host, not the port.
 */
export const BACKEND_URL = process.env.E2E_BACKEND_URL ?? "http://localhost:8080";

export const USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
const PATCH_OP_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

/** What every User and connector a spec creates is named with. */
export const E2E_PREFIX = "e2e-";

/**
 * The development fixture Groups the specs name, by stable id — the ids
 * `backend/src/main/resources/authorization.yaml` maps — spelled once here.
 */
export const FIXTURE_GROUPS = {
  /** Auditors, conferring every read. */
  auditors: "00000000-0000-4000-8000-00000000a003",
  /** Helpdesk, conferring every read and `account:unlock`. */
  helpdesk: "00000000-0000-4000-8000-00000000a006",
} as const;

/** What a connector provisions; the connector write itself flags a change. */
const PROVISIONED_PASSWORD = "Provisioned-Secret-9x";
/** What each User sets for itself, clearing that flag, before a spec acts on it. */
export const OWN_PASSWORD = "Self-Chosen-Secret-7q";

/**
 * A suffix unique to one loaded copy of a spec file. Each Playwright worker
 * loads the file itself, so a timestamp alone can collide between two workers
 * started in the same millisecond; the random tail keeps them apart.
 */
export function runId(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/** A cookie jar of its own, so nothing here touches the session the project replays. */
export function anonymousApi(): Promise<APIRequestContext> {
  return request.newContext({
    baseURL: test.info().project.use.baseURL,
    storageState: { cookies: [], origins: [] },
  });
}

/** A browser context with no session, as a User's own browser. */
export function freshBrowser(browser: Browser) {
  return browser.newContext({
    baseURL: test.info().project.use.baseURL,
    storageState: { cookies: [], origins: [] },
  });
}

/** A bearer-authenticated SCIM client, straight to the backend. */
export function scimApi(token: string): Promise<APIRequestContext> {
  return request.newContext({
    baseURL: BACKEND_URL,
    extraHTTPHeaders: {
      Accept: "application/scim+json",
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/scim+json",
    },
    storageState: { cookies: [], origins: [] },
  });
}

export interface Connector {
  id: string;
  /** The token's one-time presented value. */
  token: string;
}

/** Every Permission a connector token can carry: what a fixture connector is given by default. */
const ALL_TOKEN_PERMISSIONS = ["group:read", "group:write", "user:read", "user:write"];

/** A connector and one token for it, through the admin API of `adminPage`'s session. */
export async function createConnector(
  adminPage: Page,
  displayName: string,
  permissions: readonly string[] = ALL_TOKEN_PERMISSIONS,
): Promise<Connector> {
  expect(displayName.startsWith(E2E_PREFIX), "fixture names carry the e2e prefix").toBe(true);
  const created = await adminRequest(adminPage, "POST", "/api/admin/connectors", { displayName });
  expect(created.ok(), `creating connector ${displayName}`).toBe(true);
  const { id } = (await created.json()) as { id: string };
  const issued = await adminRequest(adminPage, "POST", `/api/admin/connectors/${id}/tokens`, {
    permissions,
  });
  expect(issued.ok(), `issuing a ${permissions.join(",")} token for ${displayName}`).toBe(true);
  return { id, token: ((await issued.json()) as { presentedValue: string }).presentedValue };
}

export async function deleteConnector(adminPage: Page, id: string) {
  const deleted = await adminRequest(adminPage, "DELETE", `/api/admin/connectors/${id}`);
  expect([204, 404], `deleting connector ${id}`).toContain(deleted.status());
}

/** Provisions an active User with `password`, returning its stable id. */
export async function provisionUser(
  scim: APIRequestContext,
  userName: string,
  password: string = PROVISIONED_PASSWORD,
): Promise<string> {
  expect(userName.startsWith(E2E_PREFIX), "fixture names carry the e2e prefix").toBe(true);
  const created = await scim.post("/scim/v2/Users", {
    data: { active: true, password, schemas: [USER_SCHEMA], userName },
  });
  expect(created.status(), `provisioning ${userName}`).toBe(201);
  return ((await created.json()) as { id: string }).id;
}

/**
 * Provisions an active User whose `userName` another server holds — the
 * one exception to {@link E2E_PREFIX}, for a name the spec cannot choose, such
 * as the Practitioner id an EHR signs in. `sweepLeftovers()` cannot see such a
 * name, so any User already holding it, a crashed run's leftover, is deleted
 * first. Safe only for a name no other spec provisions.
 */
export async function provisionServerNamedUser(
  scim: APIRequestContext,
  userName: string,
): Promise<string> {
  const filter = encodeURIComponent(`userName eq "${userName}"`);
  const leftovers = (await (await scim.get(`/scim/v2/Users?filter=${filter}`)).json()) as {
    Resources?: Array<{ id: string }>;
  };
  for (const leftover of leftovers.Resources ?? []) await deprovisionUser(scim, leftover.id);

  const created = await scim.post("/scim/v2/Users", {
    data: { active: true, password: PROVISIONED_PASSWORD, schemas: [USER_SCHEMA], userName },
  });
  expect(created.status(), `provisioning ${userName}`).toBe(201);
  return ((await created.json()) as { id: string }).id;
}

/** The resource's current ETag, for a conditional write. */
async function etagOf(scim: APIRequestContext, path: string): Promise<string> {
  const current = await scim.get(path);
  expect(current.status(), `GET ${path}`).toBe(200);
  return current.headers()["etag"] ?? "";
}

/** A SCIM PATCH under the resource's current ETag. Returns the response unasserted. */
export async function scimPatch(
  scim: APIRequestContext,
  path: string,
  operations: Array<Record<string, unknown>>,
) {
  return scim.patch(path, {
    data: { Operations: operations, schemas: [PATCH_OP_SCHEMA] },
    headers: { "If-Match": await etagOf(scim, path) },
  });
}

/** Deletes a SCIM User under its current ETag; a User already gone is not an error. */
export async function deprovisionUser(scim: APIRequestContext, id: string) {
  const current = await scim.get(`/scim/v2/Users/${id}`);
  if (current.status() === 404) return;
  const deleted = await scim.delete(`/scim/v2/Users/${id}`, {
    headers: { "If-Match": current.headers()["etag"] ?? "" },
  });
  expect(deleted.status(), `deprovisioning ${id}`).toBe(204);
}

/**
 * Sign in as a freshly provisioned User and replace the connector's password
 * with `to`, so its change-required flag starts clear. Through a jar of its
 * own: the login and the change each end every other session the User holds.
 */
export async function settlePassword(
  userName: string,
  from: string = PROVISIONED_PASSWORD,
  to: string = OWN_PASSWORD,
) {
  const api = await anonymousApi();
  try {
    expect((await submitLoginViaApi(api, userName, from)).status()).toBe(200);
    // Fetched after the login: it rotated the session and discarded the pre-login token.
    const changed = await api.post("/api/auth/change-password", {
      data: { currentPassword: from, newPassword: to },
      headers: await csrfHeaderFor(api),
    });
    expect(changed.status(), `${userName} sets its own password`).toBe(204);
  } finally {
    await api.dispose();
  }
}

/**
 * A table row addressed by its row header, exactly: `admin` must not also match
 * `admin2`. The `has` locator is resolved inside each row, so it is built from
 * the page rather than from the table.
 */
export function rowOf(page: Page, table: Locator, name: string): Locator {
  return table.getByRole("row").filter({
    has: page.getByRole("rowheader", { name: new RegExp(`^${name}(?![\\w-])`) }),
  });
}

/**
 * Registers `beforeAll`/`afterAll` hooks giving every test a worker runs from
 * the calling file one shared connector holding every token Permission and its SCIM client, created
 * through the project's own Admin session and deleted afterwards. Call it at
 * the top level of a spec in the `admin` project.
 *
 * One per worker rather than one per test: a connector and token are two admin
 * writes and a token hash each, and nothing a test does depends on the
 * connector being fresh.
 */
export function workerConnector(label: string) {
  const name = `${E2E_PREFIX}${label}-${runId()}`;
  let owner: Page | undefined;
  let connector: Connector | undefined;
  let client: APIRequestContext | undefined;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({
      baseURL: test.info().project.use.baseURL,
      storageState: test.info().project.use.storageState,
    });
    owner = await context.newPage();
    connector = await createConnector(owner, name);
    client = await scimApi(connector.token);
  });

  test.afterAll(async () => {
    await client?.dispose();
    if (owner !== undefined && connector !== undefined) await deleteConnector(owner, connector.id);
    await owner?.context().close();
  });

  return {
    /** The connector's stable id, as the audit trail names it; only valid inside a test. */
    get id(): string {
      expect(connector, "workerConnector's beforeAll has run").toBeTruthy();
      return connector!.id;
    },
    /** The SCIM client; only valid inside a test. */
    get scim(): APIRequestContext {
      expect(client, "workerConnector's beforeAll has run").toBeTruthy();
      return client!;
    },
  };
}

/**
 * Removes every {@link E2E_PREFIX} User and connector, the leftovers of runs
 * that died before their own `finally`. Users go first, through a token minted
 * for the purpose, because a connector is deleted with its tokens.
 *
 * Only safe while no spec is running — `auth.setup.ts` calls it before the
 * other projects start, which their `dependencies` guarantee.
 */
export async function sweepLeftovers(adminPage: Page) {
  const users = (await (await adminPage.request.get("/api/admin/accounts")).json()) as Array<{
    id: string;
    userName: string;
  }>;
  const strays = users.filter((row) => row.userName.startsWith(E2E_PREFIX));

  if (strays.length > 0) {
    const sweeper = await createConnector(adminPage, `${E2E_PREFIX}sweeper-${runId()}`);
    const scim = await scimApi(sweeper.token);
    try {
      for (const stray of strays) await deprovisionUser(scim, stray.id);
    } finally {
      await scim.dispose();
    }
  }

  const connectors = (await (
    await adminPage.request.get("/api/admin/connectors")
  ).json()) as Array<{
    displayName: string;
    id: string;
  }>;
  for (const leftover of connectors.filter((c) => c.displayName.startsWith(E2E_PREFIX))) {
    await deleteConnector(adminPage, leftover.id);
  }
}

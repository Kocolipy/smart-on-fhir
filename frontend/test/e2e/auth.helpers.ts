import { expect, type APIRequestContext, type BrowserContext, type Page } from "@playwright/test";

import { lockoutMaxAttempts } from "./lockoutThreshold";

/** The Bootstrap Admin's configured seed password (`application.yaml`'s fallback). */
export const SEED_PASSWORD = "P@ssw0rd";

/** The seeded Admin's user name. */
export const ADMIN_USERNAME = "admin";

/**
 * The password the seeded Admin holds while the suite runs.
 *
 * Not the seed password: the backend seeds the Bootstrap Admin with a password
 * change required, so on a fresh database `admin` / `P@ssw0rd` is confined to
 * `/change-password`. `auth.setup.ts` makes that change, to this value, and every
 * later Admin sign-in uses it. Password history refuses the seed password from
 * then on, so the change cannot be undone — set `E2E_ADMIN_PASSWORD` to choose
 * the value (at least 12 characters, not containing the user name).
 */
export const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? "E2e-Bootstrap-Secret-4m";

const ADMIN_CREDENTIALS = [ADMIN_USERNAME, ADMIN_PASSWORD] as const;

/**
 * The development fixture Users' password: the backend's
 * `APP_DEV_FIXTURES_PASSWORD`, read from the same variable. `make
 * integration-test` exports `backend/.env` before it runs this suite; unset
 * means `backend/.env.example`'s value. The fixtures exist only when the backend
 * runs with `APP_DEV_FIXTURES_ENABLED=true`.
 */
export const FIXTURE_PASSWORD = process.env.APP_DEV_FIXTURES_PASSWORD ?? "Dev-Fixture-P@ssw0rd";

/**
 * The development fixtures (`backend/src/main/resources/authorization.yaml`):
 * one User per development Group, each with the Group whose stored Permissions
 * it signs in with, whether its session is the Superuser's, and the Permissions
 * it holds — what its Group carries plus the baseline `patient:read` every User
 * holds, sorted by name as `GET /api/auth/me` reports them. The Group
 * Permissions are the `local` profile's fixtures block, which `make
 * integration-test` runs under. The Superuser's User is the Bootstrap Admin, on
 * the password `auth.setup.ts` settles: a direct member of the Admin group,
 * which carries no Permission, its session holds every delegatable one,
 * expanded in code. The Auditor holds every read and changes nothing; the
 * Helpdesk holds the same reads plus the helpdesk Permission `account:unlock`,
 * so it also Unlocks a locked User. Neither administers connectors or sets a
 * Group's Permissions, which only a Superuser does.
 */
export const DEV_FIXTURES = {
  superuser: {
    username: ADMIN_USERNAME,
    password: ADMIN_PASSWORD,
    group: "00000000-0000-4000-8000-00000000a001",
    superuser: true,
    permissions: [
      "account:unlock",
      "audit:read",
      "connector:read",
      "group:read",
      "ops:read",
      "patient:read",
      "user:read",
    ],
  },
  auditor: {
    username: "auditor",
    password: FIXTURE_PASSWORD,
    group: "00000000-0000-4000-8000-00000000a003",
    superuser: false,
    permissions: [
      "audit:read",
      "connector:read",
      "group:read",
      "ops:read",
      "patient:read",
      "user:read",
    ],
  },
  helpdesk: {
    username: "helpdesk",
    password: FIXTURE_PASSWORD,
    group: "00000000-0000-4000-8000-00000000a006",
    superuser: false,
    permissions: [
      "account:unlock",
      "audit:read",
      "connector:read",
      "group:read",
      "ops:read",
      "patient:read",
      "user:read",
    ],
  },
} as const;

export type DevFixture = keyof typeof DEV_FIXTURES;

/**
 * What a fixture's Group carries: its User's Permissions less the baseline
 * `patient:read`, which no Group confers.
 */
export function groupPermissionsOf(fixture: Exclude<DevFixture, "superuser">): string[] {
  return DEV_FIXTURES[fixture].permissions.filter((permission) => permission !== "patient:read");
}

/** Every Permission a Group may carry, sorted by name, as `GET /api/admin/permissions` lists them. */
export const DELEGATABLE_PERMISSIONS = [
  "account:unlock",
  "audit:read",
  "connector:read",
  "group:read",
  "ops:read",
  "user:read",
] as const;

/**
 * Refused attempts that lock an account: the backend's
 * `app.auth.lockout.max-attempts`, read from the same `APP_LOCKOUT_MAX_ATTEMPTS`
 * the backend reads. `make integration-test` exports `backend/.env` before it
 * runs this suite, so the two agree; against a backend started some other way,
 * export the value it runs with. Unset means the backend's own default, whose
 * authority is `backend/src/main/resources/application.yaml` (see
 * `lockoutThreshold.ts`).
 */
export const LOCKOUT_MAX_ATTEMPTS = lockoutMaxAttempts(process.env.APP_LOCKOUT_MAX_ATTEMPTS);

/**
 * The backend's session cookie (`server.servlet.session.cookie.name`).
 *
 * There is no CSRF cookie at all: the token lives in the session and is fetched
 * from `GET /api/auth/csrf`, so removing this cookie (see `expireSession()`)
 * takes the token with it.
 */
export const SESSION_COOKIE = "JSESSIONID";

/**
 * The CSRF header for an unsafe request made through `api`'s cookie jar,
 * fetched exactly as the SPA fetches it: `GET /api/auth/csrf` returns the
 * session's token and the header to send it in, and opens a session on a cold
 * jar. `page.request` is such a context, sharing its page's cookies.
 *
 * Fetch it after anything that changes the session — a login rotates the id and
 * discards the pre-login token.
 */
export async function csrfHeaderFor(api: APIRequestContext): Promise<Record<string, string>> {
  const response = await api.get("/api/auth/csrf");
  expect(response.status(), "GET /api/auth/csrf").toBe(200);
  const { headerName, token } = (await response.json()) as { headerName: string; token: string };
  expect(token, "the backend should have returned a CSRF token").toBeTruthy();
  return { [headerName]: token };
}

/** Fill and submit the login form, without asserting where it lands. */
export async function submitLogin(page: Page, username: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("Username").fill(username);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}

/** Sign in as a named seeded identity and wait for the protected page. */
export async function loginAs(page: Page, username: string, password: string) {
  await submitLogin(page, username, password);

  await expect(page).toHaveURL("/");
  await expect(page.getByText(`Signed in as ${username}`)).toBeVisible();
}

/** Sign in the seeded ADMIN identity used by the existing authenticated specs. */
export async function login(page: Page) {
  const [username, password] = ADMIN_CREDENTIALS;
  await loginAs(page, username, password);
}

/**
 * Sign in as one development fixture's User, through the login page, and wait
 * for the protected page.
 *
 * Signing in ends every other session of that User (one session per User), so
 * a spec doing this for `superuser` must not run beside one replaying
 * `admin.json`.
 */
export async function loginAsFixture(page: Page, fixture: DevFixture) {
  const { username, password } = DEV_FIXTURES[fixture];
  await loginAs(page, username, password);
}

/**
 * Expire the session the way a server-side timeout looks to the SPA: the next
 * request carries no session cookie. A safe request is answered `401`; an
 * unsafe one is first refused `403` (its token belonged to the session), and
 * `apiFetch`'s single re-fetch-and-retry then lands on the same `401`.
 *
 * Only this browser context is touched — the session stays valid on the
 * backend, so a spec using this cannot break one running beside it.
 */
export async function expireSession(context: BrowserContext) {
  const surviving = (await context.cookies()).filter((cookie) => cookie.name !== SESSION_COOKIE);

  await context.clearCookies();
  await context.addCookies(surviving);
}

/**
 * Read the session cookie out of the jar so a spec can put it back later.
 *
 * The counterpart to `expireSession`: that one takes the cookie away to make the
 * *browser* forget a session the backend still honours, this one keeps a copy so
 * a spec can hand a *retired* id back to the backend and watch it be refused.
 */
export async function captureSessionCookie(context: BrowserContext) {
  const captured = (await context.cookies()).find((cookie) => cookie.name === SESSION_COOKIE);
  expect(captured, `the backend should have issued a ${SESSION_COOKIE} cookie`).toBeTruthy();

  return captured!;
}

/**
 * POST credentials to the login endpoint, obeying the CSRF contract, and return
 * the response.
 *
 * Takes its own {@link APIRequestContext} rather than a `Page` on purpose: a spec
 * that drives the login path must not do it through the cookie jar of a browser
 * context replaying a shared session, because an accepted login rotates that
 * session's id on the backend and every spec running beside it would lose it.
 * Build the context with an empty `storageState` — see `lockAccount` in
 * `accounts-admin.spec.ts`.
 */
export async function submitLoginViaApi(
  api: APIRequestContext,
  username: string,
  password: string,
) {
  // The fetch opens the anonymous session the token — and then the login — belongs to.
  const csrf = await csrfHeaderFor(api);

  return api.post("/api/auth/login", {
    data: { username, password },
    // A login refused for the credentials and a login refused for a missing
    // token are both 401/403 shaped, so the token has to be present for the
    // response to mean anything about the password.
    headers: csrf,
  });
}

/** The CSRF header for an unsafe request made through this page's cookie jar. */
async function csrfHeader(page: Page): Promise<Record<string, string>> {
  return csrfHeaderFor(page.request);
}

/**
 * POST an administration action on a User, obeying the CSRF contract. Addressed
 * by the User's stable id, which is what the operations take.
 *
 * Returns the response rather than asserting on it, because both outcomes are
 * worth testing: an `ADMIN` gets the updated row, and a `USER` must get a
 * `403` for the *role* — which is only proven when the token is present, since a
 * missing token earns the same 403 from the CSRF filter first.
 */
export async function postAdminAction(page: Page, userId: string, action: string) {
  return page.request.post(`/api/admin/accounts/${userId}/${action}`, {
    headers: await csrfHeader(page),
  });
}

/** Any unsafe administration request, obeying the CSRF contract. */
export async function adminRequest(
  page: Page,
  method: "POST" | "PUT" | "PATCH" | "DELETE",
  path: string,
  data?: unknown,
) {
  return page.request.fetch(path, { data, headers: await csrfHeader(page), method });
}

/**
 * Each card of the Accounts page and the projection read behind it: the path
 * it is read from, and the array of the page that holds its rows.
 */
export const PROJECTIONS = {
  Groups: { path: "/api/admin/groups", rows: "groups" },
  Users: { path: "/api/admin/accounts", rows: "users" },
} as const;

/** A card of the Accounts page, named as its title reads. */
export type Projection = keyof typeof PROJECTIONS;

/**
 * Every row a projection's read matches, read a page at a time — the largest
 * page the backend serves — until `totalResults` are in. `search` narrows it
 * as the card's search box does.
 */
export async function everyRowOf<R = Record<string, unknown>>(
  page: Page,
  projection: Projection,
  search = "",
): Promise<R[]> {
  const { path, rows: key } = PROJECTIONS[projection];
  const rows: R[] = [];
  for (let startIndex = 1; ;) {
    const answer = await page.request.get(path, {
      params: { count: 200, search, startIndex },
    });
    expect(answer.status(), `reading the ${projection} projection from ${startIndex}`).toBe(200);
    const body = (await answer.json()) as Record<string, unknown> & { totalResults: number };
    const pageRows = body[key] as R[];
    rows.push(...pageRows);
    if (pageRows.length === 0 || rows.length >= body.totalResults) return rows;
    startIndex += pageRows.length;
  }
}

/**
 * A User's row of the Users projection, found by searching it for the
 * `userName` and picking the exact match out of the page — the projection is
 * read a page at a time, so it is searched rather than read whole.
 */
async function listedUser(page: Page, userName: string): Promise<Record<string, unknown>> {
  const answer = await page.request.get(PROJECTIONS.Users.path, {
    params: { count: 200, search: userName },
  });
  expect(answer.status(), `searching the Users projection for ${userName}`).toBe(200);
  const { users } = (await answer.json()) as { users: Array<Record<string, unknown>> };
  const found = users.find((row) => row.userName === userName);
  expect(found, `${userName} should be listed`).toBeTruthy();
  return found!;
}

/** The stable id of a listed User, read from the Users projection by `userName`. */
export async function userIdOf(page: Page, userName: string): Promise<string> {
  return (await listedUser(page, userName)).id as string;
}

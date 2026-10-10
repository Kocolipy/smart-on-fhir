import { describe, expect, it } from "vitest";

import {
  CREDENTIAL_CHANGE_PATH,
  DEFAULT_DESTINATION,
  LOGIN_PATH,
  resolveSessionRoute,
  type SessionRequirement,
  type SessionRoute,
  type SessionRouteInput,
} from "./session-route";
import type { Permission } from "./api";

const input = (overrides: Partial<SessionRouteInput> = {}): SessionRouteInput => ({
  passwordChangeRequired: false,
  pathname: "/showcase",
  requires: "authenticated",
  signInReason: null,
  status: "authenticated",
  ...overrides,
});

/** A confined session exactly as the backend reports one: flagged, and holding no Permission. */
const flagged = (overrides: Partial<SessionRouteInput> = {}): SessionRouteInput =>
  input({ passwordChangeRequired: true, permissions: [], ...overrides });

/** The Accounts page's requirement: any one of its views' Permissions. */
const ACCOUNTS: SessionRequirement = { anyOf: ["user:read", "group:read", "connector:read"] };

const EVERY_PERMISSION: Permission[] = [
  "audit:read",
  "connector:read",
  "connector:token",
  "connector:write",
  "counter:read",
  "counter:write",
  "group:read",
  "group:write",
  "ops:read",
  "user:read",
  "user:write",
];

const toChangePassword: SessionRoute = { kind: "redirect", to: CREDENTIAL_CHANGE_PATH };

describe("resolveSessionRoute for the change-required flag", () => {
  const cases: [string, SessionRouteInput, SessionRoute][] = [
    [
      "waits for a flagged session like any other",
      flagged({ status: "checking" }),
      { kind: "pending" },
    ],
    [
      "renders the change-password route for a flagged session",
      flagged({ pathname: CREDENTIAL_CHANGE_PATH }),
      { kind: "render" },
    ],
    [
      "confines a flagged session on the showcase",
      flagged({ pathname: "/showcase" }),
      toChangePassword,
    ],
    [
      "confines a flagged session on a Permission-guarded route",
      flagged({ pathname: "/accounts", requires: ACCOUNTS }),
      toChangePassword,
    ],
    [
      "confines a flagged session even if Permissions were reported",
      flagged({ pathname: "/accounts", permissions: EVERY_PERMISSION, requires: ACCOUNTS }),
      toChangePassword,
    ],
    [
      "confines a flagged baseline User on the showcase",
      flagged({ pathname: "/showcase", permissions: [] }),
      toChangePassword,
    ],
    [
      "sends a flagged session off the login route to the change",
      flagged({ pathname: LOGIN_PATH, requires: "guest" }),
      toChangePassword,
    ],
    [
      "ignores a recorded return destination while flagged",
      flagged({ pathname: LOGIN_PATH, requires: "guest", returnTo: "/accounts" }),
      toChangePassword,
    ],
    [
      "renders the change-password route for an unflagged baseline User",
      input({ pathname: CREDENTIAL_CHANGE_PATH, permissions: [] }),
      { kind: "render" },
    ],
    [
      "renders the change-password route for an unflagged Superuser",
      input({ pathname: CREDENTIAL_CHANGE_PATH, permissions: EVERY_PERMISSION }),
      { kind: "render" },
    ],
    [
      "lets an unflagged session holding the Permission reach the guarded route",
      input({ pathname: "/accounts", permissions: EVERY_PERMISSION, requires: ACCOUNTS }),
      { kind: "render" },
    ],
    [
      "sends a Guest on the change-password route to login",
      input({ pathname: CREDENTIAL_CHANGE_PATH, status: "guest" }),
      { kind: "redirect", state: { from: CREDENTIAL_CHANGE_PATH }, to: LOGIN_PATH },
    ],
    [
      "returns a User whose change succeeded to login, recording no return destination",
      input({
        pathname: CREDENTIAL_CHANGE_PATH,
        signInReason: "password-changed",
        status: "guest",
      }),
      { kind: "redirect", state: { reason: "password-changed" }, to: LOGIN_PATH },
    ],
    [
      "renders login after a successful change",
      input({
        pathname: LOGIN_PATH,
        requires: "guest",
        signInReason: "password-changed",
        status: "guest",
      }),
      { kind: "render" },
    ],
  ];

  it.each(cases)("%s", (_name, given, expected) => {
    expect(resolveSessionRoute(given)).toEqual(expected);
  });
});

describe("resolveSessionRoute", () => {
  const cases: [string, SessionRouteInput, SessionRoute][] = [
    [
      "waits on a protected route while the session status is unknown",
      input({ status: "checking" }),
      { kind: "pending" },
    ],
    [
      "waits on a guest route while the session status is unknown",
      input({ pathname: LOGIN_PATH, requires: "guest", status: "checking" }),
      { kind: "pending" },
    ],
    [
      "renders a protected route for an authenticated visitor",
      input({ status: "authenticated" }),
      { kind: "render" },
    ],
    [
      "sends a guest to login, recording the return destination",
      input({ pathname: "/showcase", status: "guest" }),
      { kind: "redirect", state: { from: "/showcase" }, to: LOGIN_PATH },
    ],
    [
      "marks the redirect as an Expired session, replaying the page it left",
      input({ pathname: "/showcase", signInReason: "expired", status: "guest" }),
      { kind: "redirect", state: { from: "/showcase", reason: "expired" }, to: LOGIN_PATH },
    ],
    [
      "marks the redirect as an Idle sign-out, replaying the page it left",
      input({ pathname: "/accounts", signInReason: "inactive", status: "guest" }),
      { kind: "redirect", state: { from: "/accounts", reason: "inactive" }, to: LOGIN_PATH },
    ],
    [
      "renders a guest route for a guest",
      input({ pathname: LOGIN_PATH, requires: "guest", status: "guest" }),
      { kind: "render" },
    ],
    [
      "sends an authenticated visitor off a guest route to the default destination",
      input({ pathname: LOGIN_PATH, requires: "guest", status: "authenticated" }),
      { kind: "redirect", to: DEFAULT_DESTINATION },
    ],
    [
      "replays the recorded return destination instead of the default",
      input({
        pathname: LOGIN_PATH,
        requires: "guest",
        returnTo: "/showcase/settings",
        status: "authenticated",
      }),
      { kind: "redirect", to: "/showcase/settings" },
    ],
    [
      "renders login for a Guest whose session expired",
      input({ pathname: LOGIN_PATH, requires: "guest", signInReason: "expired", status: "guest" }),
      { kind: "render" },
    ],
  ];

  it.each(cases)("%s", (_name, given, expected) => {
    expect(resolveSessionRoute(given)).toEqual(expected);
  });

  it("records the visited path, not a fixed one, as the return destination", () => {
    const route = resolveSessionRoute(input({ pathname: "/reports/42", status: "guest" }));
    expect(route).toEqual({
      kind: "redirect",
      state: { from: "/reports/42" },
      to: LOGIN_PATH,
    });
  });

  it("renders a Permission-guarded route for a session holding its Permission", () => {
    expect(
      resolveSessionRoute(
        input({ pathname: "/accounts", permissions: ["user:read"], requires: ACCOUNTS }),
      ),
    ).toEqual({ kind: "render" });
  });

  it("renders it for a session holding any one of the Permissions it names", () => {
    expect(
      resolveSessionRoute(
        input({ pathname: "/accounts", permissions: ["connector:read"], requires: ACCOUNTS }),
      ),
    ).toEqual({ kind: "render" });
  });

  it("redirects a session holding none of its Permissions away from it", () => {
    expect(
      resolveSessionRoute(
        input({
          pathname: "/accounts",
          // Real Permissions, just not this route's: an Auditor and a Monitoring account.
          permissions: ["audit:read", "ops:read", "counter:write"],
          requires: ACCOUNTS,
        }),
      ),
    ).toEqual({ kind: "redirect", to: DEFAULT_DESTINATION });
  });

  it("redirects a baseline session, holding no Permission, away from it", () => {
    expect(
      resolveSessionRoute(input({ pathname: "/accounts", permissions: [], requires: ACCOUNTS })),
    ).toEqual({ kind: "redirect", to: DEFAULT_DESTINATION });
  });

  it("redirects a session whose Permissions are not known yet away from it", () => {
    expect(resolveSessionRoute(input({ pathname: "/accounts", requires: ACCOUNTS }))).toEqual({
      kind: "redirect",
      to: DEFAULT_DESTINATION,
    });
  });

  it("sends a guest on a Permission-guarded route to login with its return destination", () => {
    expect(
      resolveSessionRoute(input({ pathname: "/accounts", requires: ACCOUNTS, status: "guest" })),
    ).toEqual({
      kind: "redirect",
      state: { from: "/accounts" },
      to: LOGIN_PATH,
    });
  });
});

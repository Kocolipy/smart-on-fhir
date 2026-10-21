import { defineConfig, devices } from "@playwright/test";

/**
 * The projects only `make integration-test` declares (frontend/AGENTS.md), each
 * when that run's environment names what it needs, so a bare `npm run
 * test:e2e` against a backend started some other way never sees them. Both
 * replay the Admin session `setup` saves, as the `admin` project does, so they
 * run beside it and ahead of `guest`, whose sign-in as the seeded Admin ends
 * that session (#64).
 */
const adminSessionExtras = [
  // Epic Login. It replays the Admin session only to issue its SCIM token: the
  // one sign-in it makes is of a User it provisions itself, so it ends no
  // session another project replays.
  ...(process.env.E2E_EPIC_FHIR_BASE
    ? [
        {
          name: "epic",
          testMatch: /epic-launch\.spec\.ts/,
          dependencies: ["setup"],
          use: {
            ...devices["Desktop Chrome"],
            storageState: "test/e2e/.auth/admin.json",
          },
        },
      ]
    : []),
  // The packaged SPA, named by the origin of the packaged JAR, which serves the
  // built SPA under the backend's CSP. The Admin session's cookie is scoped to
  // the host, not the port, so it replays there too; it signs no one in.
  ...(process.env.E2E_PACKAGED_BASE_URL
    ? [
        {
          name: "packaged",
          testMatch: /packaged-csp\.spec\.ts/,
          dependencies: ["setup"],
          use: {
            ...devices["Desktop Chrome"],
            baseURL: process.env.E2E_PACKAGED_BASE_URL,
            storageState: "test/e2e/.auth/admin.json",
          },
        },
      ]
    : []),
];

export default defineConfig({
  testDir: "./test/e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  // Capped at 2 locally: the dev box runs tight on memory, and one Chromium
  // per worker is the biggest cost in this suite. CI stays serial.
  workers: process.env.CI ? 1 : 2,
  reporter: "html",
  use: {
    baseURL: "http://localhost:5173",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },

  projects: [
    {
      name: "setup",
      testMatch: /auth\.setup\.ts/,
      use: { ...devices["Desktop Chrome"] },
    },

    // `testMatch` names its specs explicitly rather than globbing `*.spec.ts`,
    // which is what gives test/arch/e2eSpecRouting.test.ts something to
    // enforce: a new spec that nobody routed here fails that arch test instead
    // of being silently skipped at runtime.
    {
      name: "guest",
      testMatch: /(?:smoke|login|dev-roles)\.spec\.ts/,
      // After `admin`, and after every other project replaying `admin.json`
      // (`adminSessionExtras`). The backend keeps one session per User and a
      // successful login ends every other one (#64), so `login.spec.ts` signing
      // in as the seeded Admin revokes the session `admin.json` replays. Run
      // beside the `admin` project, that turned its specs into 401s.
      dependencies: ["admin", ...adminSessionExtras.map(({ name }) => name)],
      use: {
        ...devices["Desktop Chrome"],
        storageState: { cookies: [], origins: [] },
      },
    },
    {
      name: "user",
      testMatch: /roles-user\.spec\.ts/,
      dependencies: ["setup"],
      use: {
        ...devices["Desktop Chrome"],
        storageState: "test/e2e/.auth/user.json",
      },
    },
    {
      name: "admin",
      testMatch:
        /(?:home|session|session-revocation|idle-sign-out|login-lockout|console-errors|roles-admin|accounts-admin|helpdesk|token-permissions|change-password|audit)\.spec\.ts/,
      // After the `user` project, not beside it: this project drives the
      // administration surface, and ordering it last keeps any future spec that
      // acts on a seeded identity from pulling a session out from under the
      // `user` project. `accounts-admin.spec.ts` itself only acts on Users it
      // provisions over SCIM, never on the seeded `user`.
      dependencies: ["setup", "user"],
      use: {
        ...devices["Desktop Chrome"],
        storageState: "test/e2e/.auth/admin.json",
      },
    },
    {
      name: "dormancy",
      testMatch: /dormancy\.spec\.ts/,
      // Last and alone: it signs in as the Account admin fixture User, as
      // `dev-roles.spec.ts` does (#64), and consumes the `dormant`
      // fixture's locked state, which only a backend restart re-arms.
      dependencies: ["guest"],
      use: {
        ...devices["Desktop Chrome"],
        storageState: { cookies: [], origins: [] },
      },
    },
    ...adminSessionExtras,
  ],

  webServer: {
    command: "npm run dev",
    url: "http://localhost:5173",
    reuseExistingServer: !process.env.CI,
    timeout: 120 * 1000,
  },
});

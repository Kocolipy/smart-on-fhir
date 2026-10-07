import { defineConfig, devices } from "@playwright/test";

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
      // Last, after `admin`. The backend keeps one session per User and a
      // successful login ends every other one (#64), so `login.spec.ts` signing
      // in as the seeded Admin revokes the session `admin.json` replays. Run
      // beside the `admin` project, that turned its specs into 401s.
      dependencies: ["admin"],
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
        /(?:showcase|session|session-revocation|idle-sign-out|login-lockout|console-errors|roles-admin|accounts-admin|change-password|audit)\.spec\.ts/,
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
      name: "connector-tokens",
      testMatch: /token-permissions\.spec\.ts/,
      // After `guest`, never beside it: this spec signs in as the Connector admin
      // and Account admin fixture Users, as `dev-roles.spec.ts` does, and a
      // sign-in ends the User's every other session (#64).
      dependencies: ["guest"],
      use: {
        ...devices["Desktop Chrome"],
        storageState: { cookies: [], origins: [] },
      },
    },
    {
      name: "dormancy",
      testMatch: /dormancy\.spec\.ts/,
      // Last and alone: it signs in as the Account admin fixture User, as
      // `token-permissions.spec.ts` does (#64), and consumes the `dormant`
      // fixture's locked state, which only a backend restart re-arms.
      dependencies: ["connector-tokens"],
      use: {
        ...devices["Desktop Chrome"],
        storageState: { cookies: [], origins: [] },
      },
    },
    // Epic Login's E2E conditional gate (frontend/AGENTS.md), run by
    // `make epic-integration-test`. Declared only when that gate's environment
    // names the local SMART launcher, so `npm run test:e2e` — which runs without
    // the launcher or Epic Login — never sees it. It needs only the Admin session
    // `setup` saves, to issue its SCIM token: the one sign-in it makes is of a
    // User it provisions itself, so it ends no session another project replays.
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
  ],

  webServer: {
    command: "npm run dev",
    url: "http://localhost:5173",
    reuseExistingServer: !process.env.CI,
    timeout: 120 * 1000,
  },
});

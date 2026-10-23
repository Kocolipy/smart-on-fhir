/**
 * The lockout threshold the E2E suite expects, kept free of `@playwright/test`
 * so a vitest unit test can import it (`test/lockoutThreshold.test.ts`) without
 * starting a browser.
 */

/**
 * The backend's own default, `max-attempts: ${APP_LOCKOUT_MAX_ATTEMPTS:3}` in
 * `backend/src/main/resources/application.yaml`. That file is the authority:
 * this copy follows it. `LoginLockoutConfigTests` pins the backend side to 3
 * and `test/lockoutThreshold.test.ts` pins this side, so changing one default
 * without the other fails a test.
 */
export const BACKEND_DEFAULT_LOCKOUT_MAX_ATTEMPTS = 3;

/**
 * Refused attempts that lock an account, from the `APP_LOCKOUT_MAX_ATTEMPTS`
 * value the backend also reads. Unset or blank means the backend's default.
 */
export function lockoutMaxAttempts(configured: string | undefined): number {
  if (configured === undefined || configured.trim() === "") {
    return BACKEND_DEFAULT_LOCKOUT_MAX_ATTEMPTS;
  }
  const parsed = Number(configured);
  if (!Number.isInteger(parsed) || parsed < 1) {
    throw new Error(`APP_LOCKOUT_MAX_ATTEMPTS must be a positive integer, got "${configured}"`);
  }
  return parsed;
}

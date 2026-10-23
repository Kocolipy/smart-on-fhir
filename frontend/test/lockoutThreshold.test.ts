import { describe, expect, it } from "vitest";

import { BACKEND_DEFAULT_LOCKOUT_MAX_ATTEMPTS, lockoutMaxAttempts } from "./e2e/lockoutThreshold";

// The E2E lockout specs fall back to this value when APP_LOCKOUT_MAX_ATTEMPTS
// is unset. It must equal the backend's default in
// backend/src/main/resources/application.yaml, which LoginLockoutConfigTests
// pins to 3 on the backend side. If one default moves, so must the other.
describe("E2E lockout threshold", () => {
  it("falls back to the backend's default of 3", () => {
    expect(BACKEND_DEFAULT_LOCKOUT_MAX_ATTEMPTS).toBe(3);
    expect(lockoutMaxAttempts(undefined)).toBe(3);
  });

  it("treats a blank value as unset", () => {
    expect(lockoutMaxAttempts("  ")).toBe(3);
  });

  it("uses a configured value over the default", () => {
    expect(lockoutMaxAttempts("7")).toBe(7);
  });

  it.each(["0", "-1", "2.5", "three"])("refuses %s", (configured) => {
    expect(() => lockoutMaxAttempts(configured)).toThrow(
      `APP_LOCKOUT_MAX_ATTEMPTS must be a positive integer, got "${configured}"`,
    );
  });
});

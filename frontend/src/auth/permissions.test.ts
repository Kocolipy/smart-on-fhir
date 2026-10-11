import { describe, expect, it } from "vitest";

import { holds, holdsAny } from "./permissions";

describe("holds", () => {
  it("is true only for a Permission the session holds", () => {
    const user = { permissions: ["audit:read", "user:read"] as const };
    expect(holds(user, "user:read")).toBe(true);
    expect(holds(user, "audit:read")).toBe(true);
    expect(holds(user, "user:write")).toBe(false);
  });

  it("is false for a session with no Permission, and for no session at all", () => {
    expect(holds({ permissions: [] }, "user:read")).toBe(false);
    expect(holds(null, "user:read")).toBe(false);
    expect(holds(undefined, "user:read")).toBe(false);
  });
});

describe("holdsAny", () => {
  it("is true when any one of the named Permissions is held", () => {
    const user = { permissions: ["connector:read"] as const };
    expect(holdsAny(user, ["user:read", "connector:read"])).toBe(true);
  });

  it("is false when none is, and for an empty list", () => {
    const user = { permissions: ["ops:read"] as const };
    expect(holdsAny(user, ["user:read", "connector:read"])).toBe(false);
    expect(holdsAny(user, [])).toBe(false);
    expect(holdsAny(null, ["ops:read"])).toBe(false);
  });
});

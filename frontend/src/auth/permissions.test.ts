import { describe, expect, it } from "vitest";

import { holds, holdsAny, mayOpen, requirementOf } from "./permissions";

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

  it("is false for a session whose Permissions are not known yet", () => {
    expect(holds({}, "user:read")).toBe(false);
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

describe("requirementOf", () => {
  it("requires only a signed-in session of a page that names no Permissions", () => {
    expect(requirementOf(undefined)).toBe("authenticated");
  });

  it("requires any one of the Permissions a page names", () => {
    expect(requirementOf(["user:read", "audit:read"])).toEqual({
      anyOf: ["user:read", "audit:read"],
    });
  });
});

describe("mayOpen", () => {
  it("opens a page requiring only a signed-in session to every session", () => {
    expect(mayOpen({ permissions: [] }, "authenticated")).toBe(true);
  });

  it("opens a page that names Permissions only to a session holding one of them", () => {
    const user = { permissions: ["audit:read"] as const };
    expect([
      mayOpen(user, { anyOf: ["user:read", "audit:read"] }),
      mayOpen(user, { anyOf: ["user:read"] }),
    ]).toEqual([true, false]);
  });
});

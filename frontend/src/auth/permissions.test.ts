import { describe, expect, it } from "vitest";

import { PERMISSIONS } from "./api";
import { holds, holdsAny, mayOpen, requirementOf, SUPERUSER } from "./permissions";

describe("holds", () => {
  it("is true only for a Permission the session holds", () => {
    const user = { permissions: ["audit:read", "user:read"] as const };
    expect(holds(user, "user:read")).toBe(true);
    expect(holds(user, "audit:read")).toBe(true);
    expect(holds(user, "account:unlock")).toBe(false);
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

describe("holds every one of several Permissions", () => {
  const BOTH = { allOf: ["group:read", "user:read"] } as const;

  it("is true only for a session holding every one of them", () => {
    expect([
      holds({ permissions: ["group:read", "user:read", "audit:read"] }, BOTH),
      holds({ permissions: ["group:read"] }, BOTH),
      holds({ permissions: ["user:read"] }, BOTH),
    ]).toEqual([true, false, false]);
  });

  it("is not met by the Superuser's standing alone, nor by no session", () => {
    expect([
      holds({ permissions: [], superuser: true }, BOTH),
      holds(null, BOTH),
      holds({}, BOTH),
    ]).toEqual([false, false, false]);
  });

  it("is never met by naming nothing", () => {
    expect(holds({ permissions: ["group:read"] }, { allOf: [] })).toBe(false);
  });
});

describe("holds the Superuser", () => {
  it("is true only for a session the backend reported as a Superuser's", () => {
    expect(holds({ permissions: [], superuser: true }, SUPERUSER)).toBe(true);
    expect(holds({ permissions: [], superuser: false }, SUPERUSER)).toBe(false);
  });

  it("is not conferred by holding every Permission", () => {
    expect(holds({ permissions: [...PERMISSIONS], superuser: false }, SUPERUSER)).toBe(false);
  });

  it("is false for no session, and for one whose standing is not known yet", () => {
    expect(holds(null, SUPERUSER)).toBe(false);
    expect(holds(undefined, SUPERUSER)).toBe(false);
    expect(holds({}, SUPERUSER)).toBe(false);
  });

  it("is not a Permission a session's list can hold", () => {
    expect(holds({ permissions: [], superuser: false }, "user:read")).toBe(false);
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

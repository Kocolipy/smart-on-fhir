/**
 * The Permission checks every guard and page reads.
 *
 * Which Permission each request requires is not named here: each API module
 * declares it on the endpoint itself (`./declarations`), the same names
 * `docs/openapi.yaml` declares per operation, and a page reads it off the
 * declaration both to decide whether to offer a control and to send the
 * request. Deciding what to show is a RENDERING decision only: the backend
 * enforces each operation on its own, so hiding a control protects nothing, it
 * only keeps a User from being offered what it would be refused.
 */

import type { Permission } from "./api";

/**
 * The Superuser, as a request can require it: the one session authority that is
 * not a Permission. A Group can carry a Permission and can never carry this, so
 * it has its own name, outside {@link Permission} and outside what a session's
 * `permissions` lists.
 */
export const SUPERUSER = "superuser";

/** What a request requires of the session: one Permission, or the Superuser. */
export type Requirement = Permission | typeof SUPERUSER;

/**
 * Every one of several Permissions, which a request requires together: the
 * read of a Group's members requires `group:read` and `user:read`, since who
 * the members are is Users-projection data. Holding the Superuser does not
 * stand in for any of them — the Superuser's session holds them as Permissions.
 */
export interface AllOf {
  readonly allOf: readonly Permission[];
}

/** What a read requires of the session: one Permission, or every one of several. */
export type ReadRequirement = Permission | AllOf;

/**
 * The session as these checks read it: its Permissions and whether it is the
 * Superuser's, each absent while not known yet. An `AuthUser` is one.
 */
type SessionStanding =
  { permissions?: readonly Permission[]; superuser?: boolean } | null | undefined;

/**
 * What a signed-in page requires of a session: any authenticated one, or one
 * holding at least one of the named Permissions.
 */
export type PageRequirement = "authenticated" | { anyOf: readonly Permission[] };

/**
 * Whether the session meets `requirement`: holds the Permission, is the
 * Superuser's, or holds every one of several Permissions — of which naming
 * none is never met. Holding every Permission is not being the Superuser. No
 * session, and one whose standing is not known yet, holds nothing.
 */
export function holds(user: SessionStanding, requirement: Requirement | AllOf): boolean {
  if (typeof requirement === "object") {
    const { allOf } = requirement;
    return allOf.length > 0 && allOf.every((permission) => holds(user, permission));
  }
  if (requirement === SUPERUSER) return user?.superuser === true;
  return user?.permissions?.includes(requirement) === true;
}

/** Whether the session holds at least one of `permissions`. */
export function holdsAny(user: SessionStanding, permissions: readonly Permission[]) {
  return permissions.some((permission) => holds(user, permission));
}

/**
 * The requirement of a page declaring `permissions` — any one of them — or,
 * declaring none, of every authenticated session. The one reading of a page's
 * `permissions`, shared by its route guard and Home's link to it.
 */
export function requirementOf(permissions: readonly Permission[] | undefined): PageRequirement {
  return permissions ? { anyOf: permissions } : "authenticated";
}

/**
 * Whether the session may open a page with `requirement`. The one rule the
 * route guard applies, and the one Home reads to decide which pages to link.
 */
export function mayOpen(user: SessionStanding, requirement: PageRequirement) {
  return requirement === "authenticated" || holdsAny(user, requirement.anyOf);
}

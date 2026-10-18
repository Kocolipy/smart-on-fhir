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
 * The session as these checks read it: its Permissions, absent while they are
 * not known yet. An `AuthUser` is one.
 */
type PermissionHolder = { permissions?: readonly Permission[] } | null | undefined;

/**
 * What a signed-in page requires of a session: any authenticated one, or one
 * holding at least one of the named Permissions.
 */
export type PageRequirement = "authenticated" | { anyOf: readonly Permission[] };

/**
 * Whether the session holds `permission`. No session, and one whose Permissions
 * are not known yet, holds nothing.
 */
export function holds(user: PermissionHolder, permission: Permission) {
  return user?.permissions?.includes(permission) === true;
}

/** Whether the session holds at least one of `permissions`. */
export function holdsAny(user: PermissionHolder, permissions: readonly Permission[]) {
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
export function mayOpen(user: PermissionHolder, requirement: PageRequirement) {
  return requirement === "authenticated" || holdsAny(user, requirement.anyOf);
}

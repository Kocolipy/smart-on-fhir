/**
 * What the SPA shows each User, decided by Permission.
 *
 * Every administrative view names the one Permission the backend requires for
 * the listing it renders, and every in-page action names the Permission its
 * endpoint requires — the same names `docs/openapi.yaml` declares per
 * operation. This is a RENDERING decision only: the backend enforces each
 * operation on its own, so hiding a control here protects nothing, it only
 * keeps a User from being offered what it would be refused.
 */

import type { AuthUser, Permission } from "./api";

/** The Permission each administrative view on the Accounts page requires. */
export const VIEW_PERMISSIONS = {
  connectors: "connector:read",
  groups: "group:read",
  users: "user:read",
} as const satisfies Record<string, Permission>;

/**
 * The Accounts page renders for a User who may see at least one of its views,
 * and a deep link from anyone else routes away, as the guard routes any page
 * the User lacks the Permission for.
 */
export const ADMINISTRATION_PERMISSIONS: readonly Permission[] = Object.values(VIEW_PERMISSIONS);

/**
 * The Permission each write requires, named once: a page reads it both to
 * decide whether to offer the control and on the operation the control sends,
 * so the two cannot drift apart.
 */
export const WRITE_PERMISSIONS = {
  connectors: "connector:write",
  counter: "counter:write",
  tokens: "connector:token",
  users: "user:write",
} as const satisfies Record<string, Permission>;

/** Whether the session holds `permission`. No session holds nothing. */
export function holds(
  user: Pick<AuthUser, "permissions"> | null | undefined,
  permission: Permission,
) {
  return user?.permissions.includes(permission) === true;
}

/** Whether the session holds at least one of `permissions`. */
export function holdsAny(
  user: Pick<AuthUser, "permissions"> | null | undefined,
  permissions: readonly Permission[],
) {
  return permissions.some((permission) => holds(user, permission));
}

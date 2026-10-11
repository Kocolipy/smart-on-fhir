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

import type { AuthUser, Permission } from "./api";

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

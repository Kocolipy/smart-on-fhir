import type { AuditOperation } from "./audit-api";

/**
 * What the Audit page calls each audit operation, in its rows and in the
 * Operation filter. Keyed by every operation the SPA decodes, so an operation
 * added to `AUDIT_OPERATIONS` fails typecheck here until it has a label. A
 * label is display text only: the filter still sends the operation itself.
 *
 * `fe-hardcoded-credential` reads the `PASSWORD_CHANGE*` keys as credential
 * names; they are audit operation names, and every value here is a public label.
 */
// nosemgrep: fe-hardcoded-credential
export const AUDIT_OPERATION_LABELS: Record<AuditOperation, string> = {
  LOGIN_SUCCESS: "Login succeeded",
  LOGIN_FAILURE: "Login failed",
  LOGOUT: "Logged out",
  LOCKOUT_SET: "Lockout set",
  LOCKOUT_LIFT: "Lockout lifted",
  CONNECTOR_CREATE: "Connector created",
  CONNECTOR_DELETE: "Connector deleted",
  CONNECTOR_TOKEN_ISSUE: "Connector token issued",
  CONNECTOR_TOKEN_ROTATE: "Connector token rotated",
  CONNECTOR_TOKEN_REVOKE: "Connector token revoked",
  SCIM_USER_CREATE: "User created",
  SCIM_USER_LIST: "Users listed",
  SCIM_USER_REPLACE: "User replaced",
  SCIM_USER_DELETE: "User deleted",
  USER_SESSIONS_REVOKE: "User sessions revoked",
  SCIM_GROUP_CREATE: "Group created",
  SCIM_GROUP_REPLACE: "Group replaced",
  SCIM_GROUP_DELETE: "Group deleted",
  SCIM_GROUP_LIST: "Groups listed",
  SCIM_RESOURCE_LIST: "Users and Groups searched",
  SCIM_RESOURCE_SEED: "Recovery resource seeded",
  DORMANCY_LOCKOUT: "Dormancy lockout",
  DORMANCY_MEMBERSHIP_REVOCATION: "Dormancy membership revocation",
  PASSWORD_CHANGE_REQUIRE: "Password change required",
  PASSWORD_CHANGE: "Password changed",
  ACCESS_DENIED: "Access denied",
  PERMISSION_GRANT: "Permission granted",
  PERMISSION_REVOKE: "Permission revoked",
  GROUP_PERMISSIONS_CHANGE: "Group Permissions changed",
  FHIRCAST_TOKEN_RELEASE: "FHIRcast token released",
};

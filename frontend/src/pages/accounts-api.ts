/**
 * The wire shapes and paths of the administration API the Accounts page reads
 * and drives — `/frontend/AGENTS.md`'s page-owned `api.ts`, named for its page
 * because `src/pages/` holds more than one.
 *
 * Nothing here requests anything: every call goes through `useGatedRead` or
 * `useGatedWrite` (both behind `useSessionRequest`) in the component that
 * makes it, so a `401` still ends the session in one place. This file only says what a response looks like and where each
 * operation lives.
 */

import type { Permission } from "@/auth/api";
import { decodeArray, DecodeError, readObject } from "@/lib/decode";

/** A Group a User belongs to directly, as its row reports it. */
export interface DirectGroup {
  id: string;
  displayName: string;
}

/**
 * One row of the **Users projection**, exactly as `GET /api/admin/accounts`
 * reports it.
 *
 * Directory-owned fields — `userName`, `displayName`, `active`, `groups` — are
 * read-only on this page: a connector writes them over SCIM, and the backend
 * has no administration endpoint that would accept a change to them. The rest
 * is application-owned state, and the two operations on a row (Unlock and the
 * forced change) address it by `id`, never by the mutable `userName`.
 *
 * A lockout carries no expiry: it stands until an administrator unlocks the
 * identity, so there is nothing to count down to and no field for it.
 * `lockCause` says why it was imposed — a run of failed logins or dormancy —
 * and is `null` exactly when `locked` is false.
 * `bootstrapAdmin` marks the recovery identity, which can never be locked.
 */
export interface UserRow {
  id: string;
  userName: string;
  displayName: string | null;
  admin: boolean;
  bootstrapAdmin: boolean;
  active: boolean;
  locked: boolean;
  lockCause: LockCause | null;
  hasPassword: boolean;
  passwordChangeRequired: boolean;
  lastAuthenticatedAt: string | null;
  createdAt: string;
  groups: DirectGroup[];
}

/**
 * Why a User is locked (ADR 0011): `FAILURES` when its failed logins reached
 * the limit, `DORMANCY` when the dormancy job found it unused past the lockout
 * window. Either lock stands until Unlock.
 */
const LOCK_CAUSES = ["FAILURES", "DORMANCY"] as const;

export type LockCause = (typeof LOCK_CAUSES)[number];

/** One row of the read-only **Groups projection**, from `GET /api/admin/groups`. */
export interface GroupRow {
  id: string;
  displayName: string;
  memberCount: number;
  adminGroup: boolean;
}

/**
 * The Permissions a connector token can carry: the four directory ones, in
 * the order the backend reports them (sorted by name). A token carrying any
 * other value is refused on decode rather than passed through.
 */
export const TOKEN_PERMISSIONS = [
  "group:read",
  "group:write",
  "user:read",
  "user:write",
] as const satisfies readonly Permission[];

export type TokenPermission = (typeof TOKEN_PERMISSIONS)[number];

/**
 * A token's metadata. There is no field for its value: a listing cannot return
 * what this type cannot hold, which is how "never retrievable again" holds on
 * this side of the wire too.
 */
export interface ConnectorToken {
  id: string;
  /** What it may do over SCIM. Empty only for a token from before tokens carried Permissions. */
  permissions: TokenPermission[];
  issuedAt: string;
  expiresAt: string;
  originalExpiresAt: string;
  revokedAt: string | null;
  active: boolean;
}

export interface Connector {
  id: string;
  displayName: string;
  createdAt: string;
  tokens: ConnectorToken[];
}

/**
 * A token just issued or rotated — the only shape that carries plaintext, and
 * only in the response to the request that minted it.
 */
export interface IssuedToken {
  connectorId: string;
  tokenId: string;
  permissions: TokenPermission[];
  issuedAt: string;
  expiresAt: string;
  presentedValue: string;
}

/** The two operations on a User row, named as the backend's path segments. */
export type UserAction = "unlock" | "force-password-change";

export const USERS_PATH = "/api/admin/accounts";
export const GROUPS_PATH = "/api/admin/groups";
export const CONNECTORS_PATH = "/api/admin/connectors";

/** `encodeURIComponent` although ids are UUIDs: a path segment is never trusted to be one. */
export const userActionPath = (id: string, action: UserAction) =>
  `${USERS_PATH}/${encodeURIComponent(id)}/${action}`;

export const connectorPath = (connectorId: string) =>
  `${CONNECTORS_PATH}/${encodeURIComponent(connectorId)}`;

export const tokensPath = (connectorId: string) => `${connectorPath(connectorId)}/tokens`;

export const tokenActionPath = (
  connectorId: string,
  tokenId: string,
  action: "rotate" | "revoke",
) => `${tokensPath(connectorId)}/${encodeURIComponent(tokenId)}/${action}`;

// ---- decoders ----------------------------------------------------------------------
//
// One per wire type, each taking the parsed body and returning the typed value or
// throwing `DecodeError` (see `@/lib/decode`). A page passes the `jsonDecoder`
// lift of one to `useGatedRead` or `useGatedWrite`, so a body that drifted from these types
// is a `failed` result and the page's failure copy, never a half-rendered row.

const decodeDirectGroup = (value: unknown): DirectGroup => {
  const group = readObject(value, "DirectGroup");
  return { id: group.string("id"), displayName: group.string("displayName") };
};

export const decodeUserRow = (value: unknown): UserRow => {
  const row = readObject(value, "UserRow");
  const locked = row.boolean("locked");
  const lockCause = row.nullableOneOf("lockCause", LOCK_CAUSES);
  if (locked !== (lockCause !== null)) {
    throw new DecodeError("UserRow.lockCause is not null exactly when locked is false");
  }
  return {
    id: row.string("id"),
    userName: row.string("userName"),
    displayName: row.nullableString("displayName"),
    admin: row.boolean("admin"),
    bootstrapAdmin: row.boolean("bootstrapAdmin"),
    active: row.boolean("active"),
    locked,
    lockCause,
    hasPassword: row.boolean("hasPassword"),
    passwordChangeRequired: row.boolean("passwordChangeRequired"),
    lastAuthenticatedAt: row.nullableString("lastAuthenticatedAt"),
    createdAt: row.string("createdAt"),
    groups: row.array("groups", decodeDirectGroup),
  };
};

export const decodeGroupRow = (value: unknown): GroupRow => {
  const row = readObject(value, "GroupRow");
  return {
    id: row.string("id"),
    displayName: row.string("displayName"),
    memberCount: row.integer("memberCount"),
    adminGroup: row.boolean("adminGroup"),
  };
};

/** One token Permission, checked against `TOKEN_PERMISSIONS` rather than trusted. */
const decodeTokenPermission = (value: unknown): TokenPermission => {
  const permission = TOKEN_PERMISSIONS.find((candidate) => candidate === value);
  if (permission === undefined) throw new DecodeError("token permissions hold an unknown value");
  return permission;
};

const decodeConnectorToken = (value: unknown): ConnectorToken => {
  const token = readObject(value, "ConnectorToken");
  return {
    id: token.string("id"),
    permissions: token.array("permissions", decodeTokenPermission),
    issuedAt: token.string("issuedAt"),
    expiresAt: token.string("expiresAt"),
    originalExpiresAt: token.string("originalExpiresAt"),
    revokedAt: token.nullableString("revokedAt"),
    active: token.boolean("active"),
  };
};

export const decodeConnector = (value: unknown): Connector => {
  const connector = readObject(value, "Connector");
  return {
    id: connector.string("id"),
    displayName: connector.string("displayName"),
    createdAt: connector.string("createdAt"),
    tokens: connector.array("tokens", decodeConnectorToken),
  };
};

export const decodeIssuedToken = (value: unknown): IssuedToken => {
  const issued = readObject(value, "IssuedToken");
  return {
    connectorId: issued.string("connectorId"),
    tokenId: issued.string("tokenId"),
    permissions: issued.array("permissions", decodeTokenPermission),
    issuedAt: issued.string("issuedAt"),
    expiresAt: issued.string("expiresAt"),
    presentedValue: issued.string("presentedValue"),
  };
};

/** The three listings, each an array of its row type. */
export const decodeUserRows = (value: unknown): UserRow[] =>
  decodeArray(value, decodeUserRow, "UserRow[]");
export const decodeGroupRows = (value: unknown): GroupRow[] =>
  decodeArray(value, decodeGroupRow, "GroupRow[]");
export const decodeConnectors = (value: unknown): Connector[] =>
  decodeArray(value, decodeConnector, "Connector[]");

/**
 * Timestamps are rendered from the ISO instant rather than through
 * `toLocaleString`, so what an administrator reads does not depend on the
 * machine's locale and a test can assert an exact string.
 */
export const formatDate = (instant: string): string => instant.slice(0, 10);

/** Date and minute, still locale-independent, for the instants where the day is not enough. */
export const formatInstant = (instant: string): string =>
  `${instant.slice(0, 10)} ${instant.slice(11, 16)}`;

/**
 * Whether a signed-in username names this row, compared the way the backend
 * compares them — NFKC, then lower-cased — so a differently-cased session name
 * still recognises its own row and hides the controls the backend would refuse.
 */
export const namesSameUser = (userName: string, signedIn: string | undefined): boolean =>
  signedIn !== undefined &&
  userName.normalize("NFKC").toLowerCase() === signedIn.normalize("NFKC").toLowerCase();

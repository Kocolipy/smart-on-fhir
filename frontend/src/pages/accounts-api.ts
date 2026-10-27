/**
 * The wire shapes and the declared endpoints of the administration API the
 * Accounts page reads and drives — `/frontend/AGENTS.md`'s page-owned
 * `api.ts`, named for its page because `src/pages/` holds more than one.
 *
 * Nothing here requests anything: every call goes through `useGatedRead` or
 * `useGatedWrite` (both behind `useSessionRequest`) in the component that
 * makes it, so a `401` still ends the session in one place. This file says
 * what a response looks like and declares each endpoint — its path, its
 * decoder and the Permission it requires — once.
 */

import { permissionDecoder, type Permission } from "@/auth/api";
import type { ReadDeclaration, WriteDeclaration } from "@/auth/declarations";
import { SUPERUSER, type AllOf } from "@/auth/permissions";
import { decodeArray, DecodeError, jsonDecoder, readObject } from "@/lib/decode";

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
 * is application-owned state, and the one operation on a row, Unlock, addresses
 * it by `id`, never by the mutable `userName`.
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
 * Why a User is locked (ADR 0007): `FAILURES` when its failed logins reached
 * the limit, `DORMANCY` when the dormancy job found it unused past the lockout
 * window. Either lock stands until Unlock.
 *
 * Exported for the SPA–API contract check (`/scripts/spa-contract/`), which holds
 * it to `AccountSummary.lockCause` in `/backend/docs/openapi.yaml` from outside
 * this app's import graph; nothing in `src/` imports it.
 */
// fallow-ignore-next-line unused-export
export const LOCK_CAUSES = ["FAILURES", "DORMANCY"] as const;

export type LockCause = (typeof LOCK_CAUSES)[number];

/**
 * One row of the read-only **Groups projection**, from `GET /api/admin/groups`.
 *
 * `permissions` are what the Group confers on its direct members, sorted by
 * name: data the backend stores per Group, empty for a Group that confers
 * nothing. The Admin group carries none — its direct members are the
 * Superuser, which is not a Permission — so `adminGroup` is what says it
 * confers the Superuser.
 */
export interface GroupRow {
  id: string;
  displayName: string;
  memberCount: number;
  adminGroup: boolean;
  permissions: Permission[];
}

/**
 * One page of the **Users projection**, from `GET /api/admin/accounts`: the
 * Users a search matched — every User without one — in normalized `userName`
 * order, with how many matched in all. `startIndex` is one-based, as SCIM
 * pages are.
 */
export interface UserPage {
  totalResults: number;
  startIndex: number;
  users: UserRow[];
}

/**
 * One page of the **Groups projection**, from `GET /api/admin/groups`: the
 * Groups a search matched — every Group without one — in normalized display
 * name order, with how many matched in all.
 */
export interface GroupPage {
  totalResults: number;
  startIndex: number;
  groups: GroupRow[];
}

/**
 * Which page of a projection to read: from the one-based `startIndex`, of the
 * rows whose name contains `search` — every row when it is empty.
 */
export interface ListingQuery {
  search: string;
  startIndex: number;
}

/**
 * A Group's direct member, as `GET /api/admin/groups/{id}/members` reports
 * it: the Users projection's identity and nothing more — there is no field a
 * credential, or any login state, could arrive in.
 */
export interface GroupMember {
  id: string;
  userName: string;
  displayName: string | null;
  active: boolean;
}

/**
 * One page of a Group's direct members, in the Users projection's order
 * (normalized `userName`), with how many there are in all. `startIndex` is
 * one-based, as SCIM pages are.
 */
export interface GroupMemberPage {
  totalResults: number;
  startIndex: number;
  members: GroupMember[];
}

/**
 * The Permissions a connector token can carry: the four directory ones, in
 * the order the backend reports them (sorted by name). A vocabulary of its own,
 * not a subset of a session's: the write halves are a token's alone. A token
 * carrying any other value is refused on decode rather than passed through.
 */
export const TOKEN_PERMISSIONS = ["group:read", "group:write", "user:read", "user:write"] as const;

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

/** What a token is issued with: its Permissions, and a lifetime in days or the default (`null`). */
export interface TokenRequest {
  permissions: TokenPermission[];
  lifetimeDays: number | null;
}

const USERS_PATH = "/api/admin/accounts";
const GROUPS_PATH = "/api/admin/groups";
const CONNECTORS_PATH = "/api/admin/connectors";

/** `encodeURIComponent` although ids are UUIDs: a path segment is never trusted to be one. */
const connectorPath = (connectorId: string) =>
  `${CONNECTORS_PATH}/${encodeURIComponent(connectorId)}`;

const tokensPath = (connectorId: string) => `${connectorPath(connectorId)}/tokens`;

const tokenActionPath = (connectorId: string, tokenId: string, action: "rotate" | "revoke") =>
  `${tokensPath(connectorId)}/${encodeURIComponent(tokenId)}/${action}`;

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

/** One Permission a Group carries, checked against the session vocabulary rather than trusted. */
const decodeGroupPermission = permissionDecoder("GroupRow.permissions");

export const decodeGroupRow = (value: unknown): GroupRow => {
  const row = readObject(value, "GroupRow");
  return {
    id: row.string("id"),
    displayName: row.string("displayName"),
    memberCount: row.integer("memberCount"),
    adminGroup: row.boolean("adminGroup"),
    permissions: row.array("permissions", decodeGroupPermission),
  };
};

const decodeGroupMember = (value: unknown): GroupMember => {
  const member = readObject(value, "GroupMember");
  return {
    id: member.string("id"),
    userName: member.string("userName"),
    displayName: member.nullableString("displayName"),
    active: member.boolean("active"),
  };
};

export const decodeGroupMemberPage = (value: unknown): GroupMemberPage => {
  const page = readObject(value, "GroupMemberPage");
  return {
    totalResults: page.integer("totalResults"),
    startIndex: page.integer("startIndex"),
    members: page.array("members", decodeGroupMember),
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

/** A page of the Users projection: where it starts, the total, and its rows. */
export const decodeUserPage = (value: unknown): UserPage => {
  const page = readObject(value, "UserPage");
  return {
    totalResults: page.integer("totalResults"),
    startIndex: page.integer("startIndex"),
    users: page.array("users", decodeUserRow),
  };
};

/** A page of the Groups projection: where it starts, the total, and its rows. */
export const decodeGroupPage = (value: unknown): GroupPage => {
  const page = readObject(value, "GroupPage");
  return {
    totalResults: page.integer("totalResults"),
    startIndex: page.integer("startIndex"),
    groups: page.array("groups", decodeGroupRow),
  };
};

/** The connectors listing, an array of its row type. */
export const decodeConnectors = (value: unknown): Connector[] =>
  decodeArray(value, decodeConnector, "Connector[]");

/** One name of the vocabulary a Group may carry, checked against the session vocabulary. */
const decodeDelegatablePermission = permissionDecoder("DelegatablePermission");

/** The vocabulary a Group may carry, `GET /api/admin/permissions`: Permission names. */
const decodeDelegatablePermissions = (value: unknown): Permission[] =>
  decodeArray(value, decodeDelegatablePermission, "DelegatablePermission[]");

// ---- declarations ------------------------------------------------------------------
//
// Every gated request the Accounts page and its connector panel make, each with the
// Permission `docs/openapi.yaml` declares for it. A page reads `permission` to decide
// whether to offer the control, and hands the declaration to `useGatedRead` or
// `useGatedWrite` to send it.

/**
 * How many rows one read of the Users or Groups projection asks for. A
 * Group's member read asks for as many, so every pager on the page moves by
 * the same step.
 */
export const LISTING_PAGE_SIZE = 20;

/**
 * A projection's query string: the page, then the search when there is one,
 * encoded as one value — the backend binds it as a parameter and matches it
 * literally, so nothing in it is filter syntax.
 */
const listingQuery = ({ search, startIndex }: ListingQuery) =>
  `startIndex=${startIndex}&count=${LISTING_PAGE_SIZE}` +
  (search === "" ? "" : `&search=${encodeURIComponent(search)}`);

/**
 * One page of the Users projection, `GET /api/admin/accounts`, searched by
 * `userName` or display name.
 */
export const USERS: ReadDeclaration<UserPage, [query: ListingQuery]> = {
  decode: jsonDecoder(decodeUserPage),
  path: (query) => `${USERS_PATH}?${listingQuery(query)}`,
  permission: "user:read",
};

/** One page of the Groups projection, `GET /api/admin/groups`, searched by display name. */
export const GROUPS: ReadDeclaration<GroupPage, [query: ListingQuery]> = {
  decode: jsonDecoder(decodeGroupPage),
  path: (query) => `${GROUPS_PATH}?${listingQuery(query)}`,
  permission: "group:read",
};

/** How many members one read of a Group's members asks for: a listing page's worth. */
export const GROUP_MEMBERS_PAGE_SIZE = LISTING_PAGE_SIZE;

/**
 * One page of a Group's direct members, `GET /api/admin/groups/{id}/members`,
 * from the one-based `startIndex`. Who a Group's members are is Users-projection
 * data, so the read requires `user:read` beside `group:read`: a session holding
 * `group:read` alone is shown each Group's count and nothing more.
 */
export const GROUP_MEMBERS: ReadDeclaration<
  GroupMemberPage,
  [groupId: string, startIndex: number],
  false,
  AllOf
> = {
  decode: jsonDecoder(decodeGroupMemberPage),
  path: (groupId, startIndex) =>
    `${GROUPS_PATH}/${encodeURIComponent(groupId)}/members?startIndex=${startIndex}&count=${GROUP_MEMBERS_PAGE_SIZE}`,
  permission: { allOf: ["group:read", "user:read"] },
};

/**
 * Every Permission a Group may carry, sorted by name, `GET /api/admin/permissions`:
 * what the Group Permissions editor offers. The Superuser is not one of them.
 */
export const DELEGATABLE_PERMISSIONS: ReadDeclaration<Permission[]> = {
  decode: jsonDecoder(decodeDelegatablePermissions),
  path: () => "/api/admin/permissions",
  permission: "group:read",
};

/** The connectors and their tokens' metadata, `GET /api/admin/connectors`. */
export const CONNECTORS: ReadDeclaration<Connector[]> = {
  decode: jsonDecoder(decodeConnectors),
  path: () => CONNECTORS_PATH,
  permission: "connector:read",
};

/**
 * The Accounts page renders for a User who may see at least one of its
 * listings, and a deep link from anyone else routes away, as the guard routes
 * any page the User lacks the Permission for.
 */
export const ADMINISTRATION_PERMISSIONS: readonly Permission[] = [USERS, GROUPS, CONNECTORS].map(
  (read) => read.permission,
);

/**
 * Unlock, on a User by its stable id; answers the User's new row. Refused
 * `409` for a User serving no lockout, which an Unlock has no effect on.
 */
export const UNLOCK_USER: WriteDeclaration<[id: string], UserRow> = {
  decode: jsonDecoder(decodeUserRow),
  method: "POST",
  path: (id) => `${USERS_PATH}/${encodeURIComponent(id)}/unlock`,
  permission: "account:unlock",
};

/**
 * Replaces a Group's Permissions with exactly `permissions` — the complete set,
 * an empty one clearing it — by the Group's stable id; answers the Group's new
 * row. Superuser-only, and never offered on the Admin group, which the backend
 * refuses `409` (ADR 0015).
 */
export const SET_GROUP_PERMISSIONS: WriteDeclaration<
  [groupId: string, permissions: readonly Permission[]],
  GroupRow
> = {
  body: (_groupId, permissions) => permissions,
  decode: jsonDecoder(decodeGroupRow),
  method: "PUT",
  path: (groupId) => `${GROUPS_PATH}/${encodeURIComponent(groupId)}/permissions`,
  permission: SUPERUSER,
};

/** A new connector by its name; answers the connector. */
export const CREATE_CONNECTOR: WriteDeclaration<[displayName: string], Connector> = {
  body: (displayName) => ({ displayName }),
  decode: jsonDecoder(decodeConnector),
  method: "POST",
  path: () => CONNECTORS_PATH,
  permission: SUPERUSER,
};

/** Deletes a connector, revoking every token it held; answers no content. */
export const DELETE_CONNECTOR: WriteDeclaration<[connectorId: string]> = {
  method: "DELETE",
  path: connectorPath,
  permission: SUPERUSER,
};

/** Issues a token to a connector; answers the token, its plaintext shown this once. */
export const ISSUE_TOKEN: WriteDeclaration<
  [connectorId: string, request: TokenRequest],
  IssuedToken
> = {
  body: (_connectorId, request) => request,
  decode: jsonDecoder(decodeIssuedToken),
  method: "POST",
  path: (connectorId) => tokensPath(connectorId),
  permission: SUPERUSER,
};

/** Replaces a token, ending it at once; answers the replacement, its plaintext shown this once. */
export const ROTATE_TOKEN: WriteDeclaration<[connectorId: string, tokenId: string], IssuedToken> = {
  body: () => ({}),
  decode: jsonDecoder(decodeIssuedToken),
  method: "POST",
  path: (connectorId, tokenId) => tokenActionPath(connectorId, tokenId, "rotate"),
  permission: SUPERUSER,
};

/** Revokes a token; answers no content. */
export const REVOKE_TOKEN: WriteDeclaration<[connectorId: string, tokenId: string]> = {
  method: "POST",
  path: (connectorId, tokenId) => tokenActionPath(connectorId, tokenId, "revoke"),
  permission: SUPERUSER,
};

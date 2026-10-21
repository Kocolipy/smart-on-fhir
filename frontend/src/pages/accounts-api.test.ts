import { describe, expect, it } from "vitest";

import { DecodeError } from "@/lib/decode";
import type { ApiDecoder } from "@/lib/http";

import {
  ADMINISTRATION_PERMISSIONS,
  CONNECTORS,
  CREATE_CONNECTOR,
  DELETE_CONNECTOR,
  GROUPS,
  ISSUE_TOKEN,
  REVOKE_TOKEN,
  ROTATE_TOKEN,
  USER_ACTION,
  USERS,
  decodeConnector,
  decodeConnectors,
  decodeGroupRow,
  decodeGroupRows,
  decodeIssuedToken,
  decodeUserRow,
  decodeUserRows,
  namesSameUser,
} from "./accounts-api";

/**
 * Each wire type as the backend serialises it: every field present, `null`s
 * included, so a test that drops or retypes one field isolates that field.
 */
const DIRECT_GROUP = { id: "g-1", displayName: "Engineering" };

const USER_ROW = {
  id: "u-1",
  userName: "grace",
  displayName: null,
  admin: false,
  bootstrapAdmin: false,
  active: true,
  locked: false,
  lockCause: null,
  hasPassword: true,
  passwordChangeRequired: false,
  lastAuthenticatedAt: null,
  createdAt: "2026-01-02T03:04:05Z",
  groups: [DIRECT_GROUP],
};

const GROUP_ROW = { id: "g-1", displayName: "Engineering", memberCount: 2, adminGroup: false };

const CONNECTOR_TOKEN = {
  id: "t-1",
  permissions: ["group:read", "group:write", "user:read", "user:write"],
  issuedAt: "2026-01-02T03:04:05Z",
  expiresAt: "2026-04-02T03:04:05Z",
  originalExpiresAt: "2026-04-02T03:04:05Z",
  revokedAt: null,
  active: true,
};

const CONNECTOR = {
  id: "c-1",
  displayName: "Okta",
  createdAt: "2026-01-02T03:04:05Z",
  tokens: [CONNECTOR_TOKEN],
};

const ISSUED_TOKEN = {
  connectorId: "c-1",
  tokenId: "t-1",
  permissions: ["user:read"],
  issuedAt: "2026-01-02T03:04:05Z",
  expiresAt: "2026-04-02T03:04:05Z",
  presentedValue: "scim_plaintext",
};

const without = (body: object, key: string) =>
  Object.fromEntries(Object.entries(body).filter(([field]) => field !== key));

const refusal = (run: () => unknown, message: string) =>
  expect(run).toThrow(new DecodeError(message));

/**
 * How one field is checked: a value of the wrong type for it, what the refusal
 * says that value is not, and what a missing field is not (the same, except a
 * closed set, which is first read as a string).
 */
type FieldCheck = [wrong: unknown, expected: string, whenMissing?: string];

/**
 * One decoder's contract: the four cases the issue names, applied to every
 * field so no field's check can be dropped unnoticed. Each refusal names the
 * field that failed, so a case cannot pass on some other field's failure.
 */
function describeDecoder(
  name: string,
  decode: (value: unknown) => unknown,
  valid: Record<string, unknown>,
  fields: Record<string, FieldCheck>,
) {
  describe(name, () => {
    it("decodes a valid body to the same value", () => {
      expect(decode(valid)).toStrictEqual(valid);
    });

    it("drops nothing the wire did not send and adds nothing", () => {
      expect(Object.keys(decode({ ...valid, extra: "ignored" }) as object).sort()).toStrictEqual(
        Object.keys(valid).sort(),
      );
    });

    it("checks every field the type has", () => {
      expect(Object.keys(fields).sort()).toStrictEqual(Object.keys(valid).sort());
    });

    it.each(Object.entries(fields))("refuses a body missing %s", (key, [, expected, missing]) => {
      refusal(() => decode(without(valid, key)), `${name}.${key} is not ${missing ?? expected}`);
    });

    it.each(Object.entries(fields))("refuses a wrong-typed %s", (key, [value, expected]) => {
      refusal(() => decode({ ...valid, [key]: value }), `${name}.${key} is not ${expected}`);
    });

    it.each([
      ["an array", [valid]],
      ["null", null],
      ["a string", "body"],
    ])("refuses %s as a body", (_, value) => {
      refusal(() => decode(value), `${name} is not an object`);
    });
  });
}

const STRING: FieldCheck = [7, "a string"];
const BOOLEAN: FieldCheck = ["true", "a boolean"];

describeDecoder("UserRow", decodeUserRow, USER_ROW, {
  id: STRING,
  userName: STRING,
  displayName: [false, "a string"],
  admin: BOOLEAN,
  bootstrapAdmin: BOOLEAN,
  active: BOOLEAN,
  locked: BOOLEAN,
  lockCause: ["OTHER", "FAILURES | DORMANCY", "a string"],
  hasPassword: BOOLEAN,
  passwordChangeRequired: BOOLEAN,
  lastAuthenticatedAt: [0, "a string"],
  createdAt: [null, "a string"],
  groups: [DIRECT_GROUP, "an array"],
});

describeDecoder("GroupRow", decodeGroupRow, GROUP_ROW, {
  id: STRING,
  displayName: [null, "a string"],
  memberCount: ["2", "an integer"],
  adminGroup: BOOLEAN,
});

describeDecoder("Connector", decodeConnector, CONNECTOR, {
  id: STRING,
  displayName: STRING,
  createdAt: STRING,
  tokens: [null, "an array"],
});

describeDecoder("IssuedToken", decodeIssuedToken, ISSUED_TOKEN, {
  connectorId: STRING,
  tokenId: STRING,
  permissions: [null, "an array"],
  issuedAt: STRING,
  expiresAt: STRING,
  presentedValue: [null, "a string"],
});

// The two element types reached only through a parent: their contract is
// exercised through it, one element at a time.

describeDecoder(
  "DirectGroup",
  (value) => decodeUserRow({ ...USER_ROW, groups: [value] }).groups[0],
  DIRECT_GROUP,
  {
    id: STRING,
    displayName: [null, "a string"],
  },
);

describeDecoder(
  "ConnectorToken",
  (value) => decodeConnector({ ...CONNECTOR, tokens: [value] }).tokens[0],
  CONNECTOR_TOKEN,
  {
    id: STRING,
    permissions: ["user:read", "an array"],
    issuedAt: STRING,
    expiresAt: STRING,
    originalExpiresAt: [undefined, "a string"],
    revokedAt: [true, "a string"],
    active: BOOLEAN,
  },
);

/** Each listing decoder, the row type it reads, and one valid row of it. */
const LISTINGS: [list: string, decode: (value: unknown) => unknown, row: object, type: string][] = [
  ["UserRow[]", decodeUserRows, USER_ROW, "UserRow"],
  ["GroupRow[]", decodeGroupRows, GROUP_ROW, "GroupRow"],
  ["Connector[]", decodeConnectors, CONNECTOR, "Connector"],
];

describe("the lock cause", () => {
  it.each(["FAILURES", "DORMANCY"] as const)("decodes a %s lock", (lockCause) => {
    expect(decodeUserRow({ ...USER_ROW, locked: true, lockCause })).toMatchObject({
      locked: true,
      lockCause,
    });
  });

  it.each([
    ["a lock without a cause", { locked: true, lockCause: null }],
    ["a cause without a lock", { locked: false, lockCause: "DORMANCY" }],
  ])("refuses %s", (_, fields) => {
    refusal(
      () => decodeUserRow({ ...USER_ROW, ...fields }),
      "UserRow.lockCause is not null exactly when locked is false",
    );
  });
});

describe("the listing decoders", () => {
  it.each(LISTINGS)("%s decodes every row", (_, decode, row) => {
    expect(decode([row, row])).toStrictEqual([row, row]);
    expect(decode([])).toStrictEqual([]);
  });

  it.each(LISTINGS)("%s refuses a listing with one malformed row", (_, decode, row, type) => {
    refusal(() => decode([row, without(row, "id")]), `${type}.id is not a string`);
    refusal(() => decode([row, 7]), `${type} is not an object`);
  });

  it.each(LISTINGS)("%s refuses a body that is not a list", (list, decode, row) => {
    refusal(() => decode(row), `${list} is not an array`);
    refusal(() => decode(null), `${list} is not an array`);
  });
});

describe("token permissions", () => {
  it("decodes an empty list, which a token from before Permissions carries", () => {
    expect(
      decodeConnector({ ...CONNECTOR, tokens: [{ ...CONNECTOR_TOKEN, permissions: [] }] }).tokens[0]
        .permissions,
    ).toStrictEqual([]);
  });

  it.each([["audit:read"], ["READ_WRITE"], ["User:Read"], [7], [null]])(
    "refuses %s, which no token can carry",
    (value) => {
      refusal(
        () => decodeIssuedToken({ ...ISSUED_TOKEN, permissions: ["user:read", value] }),
        "token permissions hold an unknown value",
      );
      refusal(
        () =>
          decodeConnector({ ...CONNECTOR, tokens: [{ ...CONNECTOR_TOKEN, permissions: [value] }] }),
        "token permissions hold an unknown value",
      );
    },
  );
});

/** What a declaration's decoder makes of `body`, as `apiFetch` would hand it the response. */
const decodedBy = async (decode: ApiDecoder<unknown> | undefined, body: unknown) =>
  decode?.(Response.json(body));

describe("the listing reads", () => {
  it.each([
    ["USERS", USERS, "user:read", "/api/admin/accounts"],
    ["GROUPS", GROUPS, "group:read", "/api/admin/groups"],
    ["CONNECTORS", CONNECTORS, "connector:read", "/api/admin/connectors"],
  ] as const)(
    "%s reads its listing at its own path, under its own Permission",
    (_, read, permission, path) => {
      expect({ path: read.path(), permission: read.permission }).toEqual({ path, permission });
    },
  );

  it.each([
    ["USERS", USERS, USER_ROW],
    ["GROUPS", GROUPS, GROUP_ROW],
    ["CONNECTORS", CONNECTORS, CONNECTOR],
  ] as const)("%s reads its answer as a list of its own rows", async (_, read, row) => {
    await expect(decodedBy(read.decode, [row])).resolves.toStrictEqual([row]);
  });

  it.each([
    ["USERS", USERS, GROUP_ROW, "UserRow.locked is not a boolean"],
    ["GROUPS", GROUPS, USER_ROW, "GroupRow.displayName is not a string"],
    ["CONNECTORS", CONNECTORS, GROUP_ROW, "Connector.createdAt is not a string"],
  ] as const)("%s refuses another listing's rows", async (_, read, row, message) => {
    await expect(decodedBy(read.decode, [row])).rejects.toThrow(new DecodeError(message));
  });

  it("opens the Accounts page to the Permission of any one of its listings, and nothing else", () => {
    expect([...ADMINISTRATION_PERMISSIONS].sort()).toEqual([
      "connector:read",
      "group:read",
      "user:read",
    ]);
  });
});

describe("the User actions", () => {
  it.each(["unlock", "force-password-change"] as const)(
    "posts %s, with no body, to the User's own path under user:write",
    (action) => {
      expect({
        body: USER_ACTION.body,
        method: USER_ACTION.method,
        path: USER_ACTION.path("u-1", action),
        permission: USER_ACTION.permission,
      }).toEqual({
        body: undefined,
        method: "POST",
        path: `/api/admin/accounts/u-1/${action}`,
        permission: "user:write",
      });
    },
  );

  it("encodes the id as one path segment", () => {
    expect(USER_ACTION.path("a/b?c", "unlock")).toBe("/api/admin/accounts/a%2Fb%3Fc/unlock");
  });

  it("reads its answer as the User's new row", async () => {
    await expect(decodedBy(USER_ACTION.decode, USER_ROW)).resolves.toStrictEqual(USER_ROW);
  });

  it("refuses an answer that is a listing rather than one row", async () => {
    await expect(decodedBy(USER_ACTION.decode, [USER_ROW])).rejects.toThrow(
      new DecodeError("UserRow is not an object"),
    );
  });
});

describe("the connector writes", () => {
  it("creates a connector by posting its name, for the Superuser alone", () => {
    expect({
      body: CREATE_CONNECTOR.body?.("Okta"),
      method: CREATE_CONNECTOR.method,
      path: CREATE_CONNECTOR.path("Okta"),
      permission: CREATE_CONNECTOR.permission,
    }).toEqual({
      body: { displayName: "Okta" },
      method: "POST",
      path: "/api/admin/connectors",
      permission: "superuser",
    });
  });

  it("reads a create's answer as the new connector", async () => {
    await expect(decodedBy(CREATE_CONNECTOR.decode, CONNECTOR)).resolves.toStrictEqual(CONNECTOR);
  });

  it("refuses a create answered with an unreadable connector", async () => {
    await expect(decodedBy(CREATE_CONNECTOR.decode, { displayName: "Okta" })).rejects.toThrow(
      new DecodeError("Connector.id is not a string"),
    );
  });

  it("deletes a connector at its own path, for the Superuser alone, answering no content", () => {
    expect({
      body: DELETE_CONNECTOR.body,
      decode: DELETE_CONNECTOR.decode,
      method: DELETE_CONNECTOR.method,
      path: DELETE_CONNECTOR.path("c/1"),
      permission: DELETE_CONNECTOR.permission,
    }).toEqual({
      body: undefined,
      decode: undefined,
      method: "DELETE",
      path: "/api/admin/connectors/c%2F1",
      permission: "superuser",
    });
  });

  it("issues a token by posting its Permissions and lifetime, for the Superuser alone", () => {
    const request = { lifetimeDays: 30, permissions: ["user:read" as const] };
    expect({
      body: ISSUE_TOKEN.body?.("c-1", request),
      method: ISSUE_TOKEN.method,
      path: ISSUE_TOKEN.path("c/1", request),
      permission: ISSUE_TOKEN.permission,
    }).toEqual({
      body: { lifetimeDays: 30, permissions: ["user:read"] },
      method: "POST",
      path: "/api/admin/connectors/c%2F1/tokens",
      permission: "superuser",
    });
  });

  it("rotates a token by posting an empty body to its own path, for the Superuser alone", () => {
    expect({
      body: ROTATE_TOKEN.body?.("c-1", "t-1"),
      method: ROTATE_TOKEN.method,
      path: ROTATE_TOKEN.path("c/1", "t/1"),
      permission: ROTATE_TOKEN.permission,
    }).toEqual({
      body: {},
      method: "POST",
      path: "/api/admin/connectors/c%2F1/tokens/t%2F1/rotate",
      permission: "superuser",
    });
  });

  it.each([
    ["ISSUE_TOKEN", ISSUE_TOKEN],
    ["ROTATE_TOKEN", ROTATE_TOKEN],
  ] as const)("%s reads its answer as the token just minted", async (_, write) => {
    await expect(decodedBy(write.decode, ISSUED_TOKEN)).resolves.toStrictEqual(ISSUED_TOKEN);
  });

  it.each([
    ["ISSUE_TOKEN", ISSUE_TOKEN],
    ["ROTATE_TOKEN", ROTATE_TOKEN],
  ] as const)("%s refuses an answer carrying no plaintext", async (_, write) => {
    await expect(decodedBy(write.decode, without(ISSUED_TOKEN, "presentedValue"))).rejects.toThrow(
      new DecodeError("IssuedToken.presentedValue is not a string"),
    );
  });

  it("revokes a token at its own path, for the Superuser alone, with no body, answering no content", () => {
    expect({
      body: REVOKE_TOKEN.body,
      decode: REVOKE_TOKEN.decode,
      method: REVOKE_TOKEN.method,
      path: REVOKE_TOKEN.path("c/1", "t/1"),
      permission: REVOKE_TOKEN.permission,
    }).toEqual({
      body: undefined,
      decode: undefined,
      method: "POST",
      path: "/api/admin/connectors/c%2F1/tokens/t%2F1/revoke",
      permission: "superuser",
    });
  });
});

describe("namesSameUser", () => {
  it("recognises the signed-in User by name, compared as the backend compares names", () => {
    expect(namesSameUser("Grace", "grace")).toBe(true);
  });

  it("recognises no row as the signed-in User when the session names no user", () => {
    expect(namesSameUser("grace", undefined)).toBe(false);
  });
});

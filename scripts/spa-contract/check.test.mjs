// Tests for the SPA–API contract check (./check.mjs). Run by `make verify-contract`
// before the check itself, with Node's own runner: `node --test scripts/spa-contract/`.
//
// Every test hands `findMismatches` an SPA and an API document that agree, less
// the one difference it names, and asserts on the findings the check reports.

import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { declaredNames, findMismatches } from "./check.mjs";

/** An API document holding every enum and operation the check reads, agreeing with `spa()`. */
function document() {
  return {
    security: [{ sessionCookie: [] }],
    paths: {
      "/api/admin/accounts": {
        get: { security: [{ sessionCookie: ["user:read"] }] },
      },
      "/api/admin/groups/{id}/permissions": {
        put: { security: [{ sessionCookie: ["superuser"] }] },
      },
      "/api/admin/audit-events": {
        get: { security: [{ sessionCookie: ["audit:read"] }] },
      },
    },
    components: {
      schemas: {
        UserResponse: {
          properties: {
            permissions: {
              items: { type: "string", enum: ["group:read", "user:read"] },
            },
          },
        },
        DelegatablePermission: { type: "string", enum: ["group:read"] },
        ConnectorTokenPermissions: {
          type: "array",
          items: { type: "string", enum: ["user:read", "user:write"] },
        },
        ConnectorTokenSummary: {
          properties: {
            permissions: {
              items: { type: "string", enum: ["user:read", "user:write"] },
            },
          },
        },
        AuditOperation: { type: "string", enum: ["LOGIN_SUCCESS", "LOGOUT"] },
        AccountSummary: {
          properties: { lockCause: { enum: ["FAILURES", "DORMANCY", null] } },
        },
      },
    },
  };
}

/** The SPA's modules, as the check reads them: each module's exports, and the declarations its source names. */
function spa() {
  return {
    modules: {
      "src/auth/api.ts": { PERMISSIONS: ["group:read", "user:read"] },
      "src/pages/accounts-api.ts": {
        TOKEN_PERMISSIONS: ["user:read", "user:write"],
        LOCK_CAUSES: ["FAILURES", "DORMANCY"],
        USERS: { permission: "user:read", path: () => "/api/admin/accounts" },
        SET_GROUP_PERMISSIONS: {
          method: "PUT",
          permission: "superuser",
          path: (groupId) =>
            `/api/admin/groups/${encodeURIComponent(groupId)}/permissions`,
        },
      },
      "src/pages/audit-api.ts": {
        AUDIT_OPERATIONS: ["LOGIN_SUCCESS", "LOGOUT"],
        AUDIT_EVENTS: {
          permission: "audit:read",
          path: (query) => `/api/admin/audit-events?${query.search}`,
        },
      },
    },
    declared: {
      "src/pages/accounts-api.ts": ["USERS", "SET_GROUP_PERMISSIONS"],
      "src/pages/audit-api.ts": ["AUDIT_EVENTS"],
    },
  };
}

const USER_RESPONSE =
  "#/components/schemas/UserResponse/properties/permissions/items";
const DELEGATABLE = "#/components/schemas/DelegatablePermission";
const TOKEN_REQUEST = "#/components/schemas/ConnectorTokenPermissions/items";
const TOKEN_SUMMARY =
  "#/components/schemas/ConnectorTokenSummary/properties/permissions/items";
const AUDIT_OPERATION = "#/components/schemas/AuditOperation";
const LOCK_CAUSE = "#/components/schemas/AccountSummary/properties/lockCause";

const schemas = (doc) => doc.components.schemas;

describe("the SPA's vocabularies", () => {
  it("finds nothing when the SPA and the document agree", () => {
    assert.deepEqual(findMismatches(spa(), document()), []);
  });

  // Each row edits one side and names the findings that edit must produce.
  const cases = [
    [
      "the document gains a session Permission",
      (_spa, doc) =>
        schemas(doc).UserResponse.properties.permissions.items.enum.push(
          "ops:read",
        ),
      `PERMISSIONS (src/auth/api.ts) lacks "ops:read", which ${USER_RESPONSE} has`,
    ],
    [
      "the SPA gains a session Permission",
      (s) => s.modules["src/auth/api.ts"].PERMISSIONS.push("ops:read"),
      `PERMISSIONS (src/auth/api.ts) has "ops:read", which ${USER_RESPONSE} lacks`,
    ],
    [
      "the document gains a delegatable Permission the SPA cannot decode",
      (_spa, doc) => schemas(doc).DelegatablePermission.enum.push("ops:read"),
      `PERMISSIONS (src/auth/api.ts) lacks "ops:read", which ${DELEGATABLE} has`,
    ],
    [
      "the document gains a token Permission on the request",
      (_spa, doc) =>
        schemas(doc).ConnectorTokenPermissions.items.enum.push("group:read"),
      `TOKEN_PERMISSIONS (src/pages/accounts-api.ts) lacks "group:read", which ${TOKEN_REQUEST} has`,
    ],
    [
      "the document loses a token Permission from the listing",
      (_spa, doc) =>
        schemas(
          doc,
        ).ConnectorTokenSummary.properties.permissions.items.enum.pop(),
      `TOKEN_PERMISSIONS (src/pages/accounts-api.ts) has "user:write", which ${TOKEN_SUMMARY} lacks`,
    ],
    [
      "the SPA loses a token Permission",
      (s) => s.modules["src/pages/accounts-api.ts"].TOKEN_PERMISSIONS.shift(),
      [
        `TOKEN_PERMISSIONS (src/pages/accounts-api.ts) lacks "user:read", which ${TOKEN_REQUEST} has`,
        `TOKEN_PERMISSIONS (src/pages/accounts-api.ts) lacks "user:read", which ${TOKEN_SUMMARY} has`,
      ],
    ],
    [
      "the document gains an audit operation",
      (_spa, doc) => schemas(doc).AuditOperation.enum.push("GROUP_DELETE"),
      `AUDIT_OPERATIONS (src/pages/audit-api.ts) lacks "GROUP_DELETE", which ${AUDIT_OPERATION} has`,
    ],
    [
      "the SPA gains an audit operation",
      (s) =>
        s.modules["src/pages/audit-api.ts"].AUDIT_OPERATIONS.push("ROLE_GRANT"),
      `AUDIT_OPERATIONS (src/pages/audit-api.ts) has "ROLE_GRANT", which ${AUDIT_OPERATION} lacks`,
    ],
    [
      "the document gains a lock cause",
      (_spa, doc) =>
        schemas(doc).AccountSummary.properties.lockCause.enum.push("EXPIRY"),
      `LOCK_CAUSES (src/pages/accounts-api.ts) lacks "EXPIRY", which ${LOCK_CAUSE} has`,
    ],
    [
      "the SPA loses a lock cause",
      (s) => s.modules["src/pages/accounts-api.ts"].LOCK_CAUSES.pop(),
      `LOCK_CAUSES (src/pages/accounts-api.ts) lacks "DORMANCY", which ${LOCK_CAUSE} has`,
    ],
    [
      "the document no longer holds an enum the SPA mirrors",
      (_spa, doc) => delete schemas(doc).AuditOperation,
      `openapi.yaml has no enum at ${AUDIT_OPERATION}, which AUDIT_OPERATIONS (src/pages/audit-api.ts) mirrors`,
    ],
    [
      "the SPA no longer exports a set the check reads",
      (s) => delete s.modules["src/pages/accounts-api.ts"].LOCK_CAUSES,
      "src/pages/accounts-api.ts exports no LOCK_CAUSES array of strings",
    ],
  ];

  for (const [name, edit, finding] of cases) {
    it(`names the mismatch when ${name}`, () => {
      const [s, doc] = [spa(), document()];
      edit(s, doc);

      assert.deepEqual(findMismatches(s, doc), [finding].flat());
    });
  }
});

describe("the SPA's request declarations", () => {
  it("names a declaration whose Permission is not the one its operation requires", () => {
    const doc = document();
    doc.paths["/api/admin/accounts"].get.security = [
      { sessionCookie: ["group:read"] },
    ];

    assert.deepEqual(findMismatches(spa(), doc), [
      'USERS (src/pages/accounts-api.ts) requires "user:read" for GET /api/admin/accounts, but openapi.yaml requires sessionCookie: [group:read]',
    ]);
  });

  it("names a Superuser declaration whose operation requires a Permission instead", () => {
    const doc = document();
    doc.paths["/api/admin/groups/{id}/permissions"].put.security = [
      { sessionCookie: ["group:read"] },
    ];

    assert.deepEqual(findMismatches(spa(), doc), [
      'SET_GROUP_PERMISSIONS (src/pages/accounts-api.ts) requires "superuser" for PUT /api/admin/groups/{id}/permissions, but openapi.yaml requires sessionCookie: [group:read]',
    ]);
  });

  it("holds a declaration to the document's own security when its operation names none", () => {
    const doc = document();
    delete doc.paths["/api/admin/accounts"].get.security;

    assert.deepEqual(findMismatches(spa(), doc), [
      'USERS (src/pages/accounts-api.ts) requires "user:read" for GET /api/admin/accounts, but openapi.yaml requires sessionCookie: []',
    ]);
  });

  it("names a declaration whose operation a connector token is required for", () => {
    const doc = document();
    doc.paths["/api/admin/accounts"].get.security = [
      { connectorBearer: ["user:read"] },
    ];

    assert.deepEqual(findMismatches(spa(), doc), [
      'USERS (src/pages/accounts-api.ts) requires "user:read" for GET /api/admin/accounts, but openapi.yaml requires connectorBearer: [user:read]',
    ]);
  });

  it("names a declaration the document has no operation for", () => {
    const doc = document();
    delete doc.paths["/api/admin/groups/{id}/permissions"].put;

    assert.deepEqual(findMismatches(spa(), doc), [
      "SET_GROUP_PERMISSIONS (src/pages/accounts-api.ts) sends PUT /api/admin/groups/ARG/permissions, which no operation in openapi.yaml serves",
    ]);
  });

  it("names a declaration whose path does not resolve to one", () => {
    const s = spa();
    s.modules["src/pages/audit-api.ts"].AUDIT_EVENTS.path = (query) =>
      query.path;

    assert.deepEqual(findMismatches(s, document()), [
      "AUDIT_EVENTS (src/pages/audit-api.ts) builds no absolute path from placeholder arguments",
    ]);
  });

  it("names a declaration its source exports that does not evaluate to one", () => {
    const s = spa();
    delete s.modules["src/pages/accounts-api.ts"].USERS;

    assert.deepEqual(findMismatches(s, document()), [
      "src/pages/accounts-api.ts declares USERS, which did not evaluate to a request declaration",
    ]);
  });

  it("refuses to pass an SPA in which it finds no declaration at all", () => {
    const s = spa();
    delete s.modules["src/pages/accounts-api.ts"].USERS;
    delete s.modules["src/pages/accounts-api.ts"].SET_GROUP_PERMISSIONS;
    delete s.modules["src/pages/audit-api.ts"].AUDIT_EVENTS;
    s.declared = {};

    assert.deepEqual(findMismatches(s, document()), [
      "found no request declaration in the SPA; the check would hold nothing",
    ]);
  });
});

describe("declaredNames", () => {
  it("lists every exported read and write declaration a source names, however its type wraps", () => {
    const source = `
import type { ReadDeclaration, WriteDeclaration } from "@/auth/declarations";

export const USERS: ReadDeclaration<UserRow[]> = { permission: "user:read" };
const LOCAL: ReadDeclaration<Row> = { permission: "user:read" };
export const SET_GROUP_PERMISSIONS: WriteDeclaration<
  [groupId: string, permissions: readonly Permission[]],
  GroupRow
> = { permission: SUPERUSER };
export const PERMISSIONS = ["user:read"] as const;
export const REVOKE_TOKEN: WriteDeclaration<[connectorId: string, tokenId: string]> = {};
`;

    assert.deepEqual(declaredNames(source), [
      "USERS",
      "SET_GROUP_PERMISSIONS",
      "REVOKE_TOKEN",
    ]);
  });
});

// Tests for the SPA–API contract check (./check.mjs). Run by `make verify-contract`
// before the check itself, with Node's own runner: `node --test scripts/spa-contract/`.
//
// Every test hands `findMismatches` an SPA and an API document that agree, less
// the one difference it names, and asserts on the findings the check reports.

import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { declaredNames, discover, findMismatches } from "./check.mjs";

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
      "/api/admin/groups/{id}/members": {
        get: { security: [{ sessionCookie: ["group:read", "user:read"] }] },
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
      "src/pages/accounts/accounts-api.ts": {
        TOKEN_PERMISSIONS: ["user:read", "user:write"],
        LOCK_CAUSES: ["FAILURES", "DORMANCY"],
        USERS: { permission: "user:read", path: () => "/api/admin/accounts" },
        SET_GROUP_PERMISSIONS: {
          method: "PUT",
          permission: "superuser",
          path: (groupId) =>
            `/api/admin/groups/${encodeURIComponent(groupId)}/permissions`,
        },
        GROUP_MEMBERS: {
          permission: { allOf: ["group:read", "user:read"] },
          path: (groupId, startIndex) =>
            `/api/admin/groups/${encodeURIComponent(groupId)}/members?startIndex=${startIndex}`,
        },
      },
      "src/pages/audit/audit-api.ts": {
        AUDIT_OPERATIONS: ["LOGIN_SUCCESS", "LOGOUT"],
        AUDIT_EVENTS: {
          permission: "audit:read",
          path: (query) => `/api/admin/audit-events?${query.search}`,
        },
      },
    },
    declared: {
      "src/pages/accounts/accounts-api.ts": [
        "USERS",
        "SET_GROUP_PERMISSIONS",
        "GROUP_MEMBERS",
      ],
      "src/pages/audit/audit-api.ts": ["AUDIT_EVENTS"],
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
      `TOKEN_PERMISSIONS (src/pages/accounts/accounts-api.ts) lacks "group:read", which ${TOKEN_REQUEST} has`,
    ],
    [
      "the document loses a token Permission from the listing",
      (_spa, doc) =>
        schemas(
          doc,
        ).ConnectorTokenSummary.properties.permissions.items.enum.pop(),
      `TOKEN_PERMISSIONS (src/pages/accounts/accounts-api.ts) has "user:write", which ${TOKEN_SUMMARY} lacks`,
    ],
    [
      "the SPA loses a token Permission",
      (s) => s.modules["src/pages/accounts/accounts-api.ts"].TOKEN_PERMISSIONS.shift(),
      [
        `TOKEN_PERMISSIONS (src/pages/accounts/accounts-api.ts) lacks "user:read", which ${TOKEN_REQUEST} has`,
        `TOKEN_PERMISSIONS (src/pages/accounts/accounts-api.ts) lacks "user:read", which ${TOKEN_SUMMARY} has`,
      ],
    ],
    [
      "the document gains an audit operation",
      (_spa, doc) => schemas(doc).AuditOperation.enum.push("GROUP_DELETE"),
      `AUDIT_OPERATIONS (src/pages/audit/audit-api.ts) lacks "GROUP_DELETE", which ${AUDIT_OPERATION} has`,
    ],
    [
      "the SPA gains an audit operation",
      (s) =>
        s.modules["src/pages/audit/audit-api.ts"].AUDIT_OPERATIONS.push("ROLE_GRANT"),
      `AUDIT_OPERATIONS (src/pages/audit/audit-api.ts) has "ROLE_GRANT", which ${AUDIT_OPERATION} lacks`,
    ],
    [
      "the document gains a lock cause",
      (_spa, doc) =>
        schemas(doc).AccountSummary.properties.lockCause.enum.push("EXPIRY"),
      `LOCK_CAUSES (src/pages/accounts/accounts-api.ts) lacks "EXPIRY", which ${LOCK_CAUSE} has`,
    ],
    [
      "the SPA loses a lock cause",
      (s) => s.modules["src/pages/accounts/accounts-api.ts"].LOCK_CAUSES.pop(),
      `LOCK_CAUSES (src/pages/accounts/accounts-api.ts) lacks "DORMANCY", which ${LOCK_CAUSE} has`,
    ],
    [
      "the document no longer holds an enum the SPA mirrors",
      (_spa, doc) => delete schemas(doc).AuditOperation,
      `openapi.yaml has no enum at ${AUDIT_OPERATION}, which AUDIT_OPERATIONS (src/pages/audit/audit-api.ts) mirrors`,
    ],
    [
      "the SPA no longer exports a set the check reads",
      (s) => delete s.modules["src/pages/accounts/accounts-api.ts"].LOCK_CAUSES,
      "src/pages/accounts/accounts-api.ts exports no LOCK_CAUSES array of strings",
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
  const ACCOUNTS = "src/pages/accounts/accounts-api.ts";
  const AUDIT = "src/pages/audit/audit-api.ts";

  // Each row edits one side and names the findings that edit must produce.
  const cases = [
    [
      "names a declaration whose Permission is not the one its operation requires",
      (_spa, doc) => {
        doc.paths["/api/admin/accounts"].get.security = [
          { sessionCookie: ["group:read"] },
        ];
      },
      'USERS (src/pages/accounts/accounts-api.ts) requires "user:read" for GET /api/admin/accounts, but openapi.yaml requires sessionCookie: [group:read]',
    ],
    [
      "names a Superuser declaration whose operation requires a Permission instead",
      (_spa, doc) => {
        doc.paths["/api/admin/groups/{id}/permissions"].put.security = [
          { sessionCookie: ["group:read"] },
        ];
      },
      'SET_GROUP_PERMISSIONS (src/pages/accounts/accounts-api.ts) requires "superuser" for PUT /api/admin/groups/{id}/permissions, but openapi.yaml requires sessionCookie: [group:read]',
    ],
    [
      "names a declaration requiring two Permissions whose operation requires only one",
      (_spa, doc) => {
        doc.paths["/api/admin/groups/{id}/members"].get.security = [
          { sessionCookie: ["group:read"] },
        ];
      },
      'GROUP_MEMBERS (src/pages/accounts/accounts-api.ts) requires "group:read" and "user:read" for GET /api/admin/groups/{id}/members, but openapi.yaml requires sessionCookie: [group:read]',
    ],
    [
      "names a declaration requiring one Permission whose operation requires two",
      (_spa, doc) => {
        doc.paths["/api/admin/accounts"].get.security = [
          { sessionCookie: ["user:read", "group:read"] },
        ];
      },
      'USERS (src/pages/accounts/accounts-api.ts) requires "user:read" for GET /api/admin/accounts, but openapi.yaml requires sessionCookie: [user:read, group:read]',
    ],
    [
      "holds the Permissions an operation requires together as a set, in any order",
      (_spa, doc) => {
        doc.paths["/api/admin/groups/{id}/members"].get.security = [
          { sessionCookie: ["user:read", "group:read"] },
        ];
      },
      [],
    ],
    [
      "does not read two Permissions offered as alternatives as both required",
      (_spa, doc) => {
        doc.paths["/api/admin/groups/{id}/members"].get.security = [
          { sessionCookie: ["group:read"] },
          { sessionCookie: ["user:read"] },
        ];
      },
      'GROUP_MEMBERS (src/pages/accounts/accounts-api.ts) requires "group:read" and "user:read" for GET /api/admin/groups/{id}/members, but openapi.yaml requires sessionCookie: [group:read] or sessionCookie: [user:read]',
    ],
    [
      "names a declaration whose Permissions name one twice in place of a second",
      (s) => {
        s.modules[ACCOUNTS].GROUP_MEMBERS.permission = {
          allOf: ["group:read", "group:read"],
        };
      },
      'GROUP_MEMBERS (src/pages/accounts/accounts-api.ts) requires "group:read" and "group:read" for GET /api/admin/groups/{id}/members, but openapi.yaml requires sessionCookie: [group:read, user:read]',
    ],
    [
      "does not take a requirement naming no Permission for a declaration",
      (s) => {
        s.modules[ACCOUNTS].GROUP_MEMBERS.permission = { allOf: [] };
      },
      "src/pages/accounts/accounts-api.ts declares GROUP_MEMBERS, which did not evaluate to a request declaration",
    ],
    [
      "holds a declaration to the document's own security when its operation names none",
      (_spa, doc) => {
        delete doc.paths["/api/admin/accounts"].get.security;
      },
      'USERS (src/pages/accounts/accounts-api.ts) requires "user:read" for GET /api/admin/accounts, but openapi.yaml requires sessionCookie: []',
    ],
    [
      "names a declaration whose operation a connector token is required for",
      (_spa, doc) => {
        doc.paths["/api/admin/accounts"].get.security = [
          { connectorBearer: ["user:read"] },
        ];
      },
      'USERS (src/pages/accounts/accounts-api.ts) requires "user:read" for GET /api/admin/accounts, but openapi.yaml requires connectorBearer: [user:read]',
    ],
    [
      "names a declaration the document has no operation for",
      (_spa, doc) => {
        delete doc.paths["/api/admin/groups/{id}/permissions"].put;
      },
      "SET_GROUP_PERMISSIONS (src/pages/accounts/accounts-api.ts) sends PUT /api/admin/groups/ARG/permissions, which no operation in openapi.yaml serves",
    ],
    [
      "names a declaration whose path does not resolve to one",
      (s) => {
        s.modules[AUDIT].AUDIT_EVENTS.path = (query) => query.path;
      },
      "AUDIT_EVENTS (src/pages/audit/audit-api.ts) builds no absolute path from placeholder arguments",
    ],
    [
      "names a declaration its source exports that does not evaluate to one",
      (s) => {
        delete s.modules[ACCOUNTS].USERS;
      },
      "src/pages/accounts/accounts-api.ts declares USERS, which did not evaluate to a request declaration",
    ],
    [
      "refuses to pass an SPA in which it finds no declaration at all",
      (s) => {
        delete s.modules[ACCOUNTS].USERS;
        delete s.modules[ACCOUNTS].SET_GROUP_PERMISSIONS;
        delete s.modules[ACCOUNTS].GROUP_MEMBERS;
        delete s.modules[AUDIT].AUDIT_EVENTS;
        s.declared = {};
      },
      "found no request declaration in the SPA; the check would hold nothing",
    ],
  ];

  for (const [name, edit, finding] of cases) {
    it(name, () => {
      const [s, doc] = [spa(), document()];
      edit(s, doc);

      assert.deepEqual(findMismatches(s, doc), [finding].flat());
    });
  }
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

  it("lists a paged declaration, its generic arguments on one line or across several", () => {
    const source = `
import type { PagedDeclaration } from "@/pages/paged-read";

export const USERS: PagedDeclaration<UserRow, [query: ListingQuery]> = { permission: "user:read" };
export const GROUP_MEMBERS: PagedDeclaration<
  MemberRow,
  [groupId: string],
  { allOf: ["group:read", "user:read"] }
> = { permission: { allOf: ["group:read", "user:read"] } };
`;

    assert.deepEqual(declaredNames(source), ["USERS", "GROUP_MEMBERS"]);
  });
});

describe("discover", () => {
  /** A module whose one request declaration is a paged read, typed across several lines. */
  const PAGED_ONLY = [
    "src/pages/tokens/tokens-api.ts",
    `
import type { PagedDeclaration } from "@/pages/paged-read";

export const TOKENS: PagedDeclaration<
  TokenRow,
  [connectorId: string]
> = { permission: "connector:read", path: (id) => \`/api/admin/connectors/\${id}/tokens\` };
`,
  ];

  it("loads a module whose only request declaration is a paged one, beside the vocabulary modules", () => {
    const { toLoad } = discover([
      PAGED_ONLY,
      ["src/lib/format.ts", "export const format = (x) => String(x);\n"],
    ]);

    assert.deepEqual(toLoad, [
      "src/auth/api.ts",
      "src/pages/accounts/accounts-api.ts",
      "src/pages/audit/audit-api.ts",
      "src/pages/tokens/tokens-api.ts",
    ]);
  });

  it("holds a paged-only module's declaration, loaded as main.mjs loads it, to its operation's security", () => {
    const sources = [PAGED_ONLY];
    const evaluated = {
      ...spa().modules,
      "src/pages/tokens/tokens-api.ts": {
        TOKENS: {
          permission: "connector:read",
          path: (id) => `/api/admin/connectors/${id}/tokens?startIndex=1`,
        },
      },
    };
    const { declared, toLoad } = discover(sources);
    const modules = Object.fromEntries(
      toLoad.map((file) => [file, evaluated[file] ?? {}]),
    );
    const doc = document();
    doc.paths["/api/admin/connectors/{id}/tokens"] = {
      get: { security: [{ sessionCookie: ["superuser"] }] },
    };

    assert.deepEqual(findMismatches({ modules, declared }, doc), [
      'TOKENS (src/pages/tokens/tokens-api.ts) requires "connector:read" for GET /api/admin/connectors/{id}/tokens, but openapi.yaml requires sessionCookie: [superuser]',
    ]);
  });

  it("names a paged declaration its source exports that does not evaluate to one", () => {
    const { declared } = discover([PAGED_ONLY]);
    const s = spa();
    s.modules["src/pages/tokens/tokens-api.ts"] = {
      TOKENS: { permission: "connector:read", path: "/api/admin/connectors" },
    };
    s.declared = { ...s.declared, ...declared };

    assert.deepEqual(findMismatches(s, document()), [
      "src/pages/tokens/tokens-api.ts declares TOKENS, which did not evaluate to a request declaration",
    ]);
  });
});

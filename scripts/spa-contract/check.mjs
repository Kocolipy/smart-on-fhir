// The SPA–API contract check: holds the vocabulary the SPA mirrors by hand against
// the API document, backend/docs/openapi.yaml. Pure — it is handed the SPA's
// evaluated modules and the parsed document, and returns every disagreement as
// one readable line. ./main.mjs does the loading; ./check.test.mjs pins this.

/**
 * Each closed set the SPA decodes strictly, the module that exports it, and
 * the enums in the document it mirrors. `equal` holds the two sets to each
 * other in both directions; `covers` only requires the SPA's set to hold every
 * value of the document's, for a set the SPA also decodes another response with.
 */
export const VOCABULARIES = [
  {
    set: "PERMISSIONS",
    module: "src/auth/api.ts",
    mirrors: [
      {
        pointer:
          "#/components/schemas/UserResponse/properties/permissions/items",
        relation: "equal",
      },
      {
        pointer: "#/components/schemas/DelegatablePermission",
        relation: "covers",
      },
    ],
  },
  {
    set: "TOKEN_PERMISSIONS",
    module: "src/pages/accounts-api.ts",
    mirrors: [
      {
        pointer: "#/components/schemas/ConnectorTokenPermissions/items",
        relation: "equal",
      },
      {
        pointer:
          "#/components/schemas/ConnectorTokenSummary/properties/permissions/items",
        relation: "equal",
      },
    ],
  },
  {
    set: "AUDIT_OPERATIONS",
    module: "src/pages/audit-api.ts",
    mirrors: [
      { pointer: "#/components/schemas/AuditOperation", relation: "equal" },
    ],
  },
  {
    set: "LOCK_CAUSES",
    module: "src/pages/accounts-api.ts",
    mirrors: [
      {
        pointer: "#/components/schemas/AccountSummary/properties/lockCause",
        relation: "equal",
      },
    ],
  },
];

/** The node a `#/a/b` JSON pointer names in `doc`, or `undefined`. */
function resolve(doc, pointer) {
  return pointer
    .slice(2)
    .split("/")
    .map((token) => token.replaceAll("~1", "/").replaceAll("~0", "~"))
    .reduce((node, token) => (node == null ? undefined : node[token]), doc);
}

/** The string values of the enum at `pointer`, following one `$ref`; `null` when there is none. */
function enumAt(doc, pointer) {
  let node = resolve(doc, pointer);
  if (node && typeof node.$ref === "string") node = resolve(doc, node.$ref);
  if (!node || !Array.isArray(node.enum)) return null;
  // A nullable enum lists `null` beside its values; the SPA decodes it apart.
  return node.enum.filter((value) => value !== null);
}

function vocabularyMismatches(spa, doc) {
  const findings = [];
  for (const { set, module, mirrors } of VOCABULARIES) {
    const values = spa.modules[module]?.[set];
    const label = `${set} (${module})`;
    if (
      !Array.isArray(values) ||
      !values.every((value) => typeof value === "string")
    ) {
      findings.push(`${module} exports no ${set} array of strings`);
      continue;
    }
    for (const { pointer, relation } of mirrors) {
      const documented = enumAt(doc, pointer);
      if (documented === null) {
        findings.push(
          `openapi.yaml has no enum at ${pointer}, which ${label} mirrors`,
        );
        continue;
      }
      for (const value of documented.filter(
        (value) => !values.includes(value),
      )) {
        findings.push(`${label} lacks "${value}", which ${pointer} has`);
      }
      if (relation !== "equal") continue;
      for (const value of values.filter(
        (value) => !documented.includes(value),
      )) {
        findings.push(`${label} has "${value}", which ${pointer} lacks`);
      }
    }
  }
  return findings;
}

/**
 * A stand-in for any argument a declaration's path is built from: it reads as
 * `ARG` wherever it becomes text, and any property of it is another stand-in,
 * so `(id) => \`/x/${id}\`` and `(query) => \`/x?${query.search}\`` both
 * resolve without the check knowing an argument's type.
 */
function placeholder() {
  return new Proxy(function stand() {}, {
    get: (_target, key) =>
      key === Symbol.toPrimitive || key === "toString" || key === "valueOf"
        ? () => "ARG"
        : placeholder(),
  });
}

const ARGUMENTS = Array.from({ length: 8 }, placeholder);

/**
 * What a declaration's `permission` requires, as the list of names a session
 * must hold every one of: one Permission (or `superuser`), or the names of an
 * `{ allOf }` — `null` for anything else, an `allOf` naming nothing included.
 */
function requiredBy(permission) {
  if (typeof permission === "string") return [permission];
  const allOf = permission?.allOf;
  return Array.isArray(allOf) &&
    allOf.length > 0 &&
    allOf.every((name) => typeof name === "string")
    ? allOf
    : null;
}

const isDeclaration = (value) =>
  value !== null &&
  typeof value === "object" &&
  typeof value.path === "function" &&
  requiredBy(value.permission) !== null;

/** Every exported request declaration in `spa`, with where it is and how it is sent. */
function declarationsOf(spa) {
  const found = [];
  for (const [module, exports] of Object.entries(spa.modules)) {
    for (const [name, value] of Object.entries(exports)) {
      if (!isDeclaration(value)) continue;
      found.push({
        label: `${name} (${module})`,
        method: value.method ?? "GET",
        required: requiredBy(value.permission),
        value,
      });
    }
  }
  return found;
}

/** The path template in `doc` the concrete `path` addresses with `method`, or `null`. */
function operationFor(doc, method, path) {
  const bare = path.split("?")[0];
  const matches = Object.entries(doc.paths ?? {})
    .filter(([, item]) => item?.[method.toLowerCase()] !== undefined)
    .filter(([template]) => {
      const pattern = template
        .split(/\{[^}]+\}/)
        .map((part) => part.replace(/[.*+?^$()|[\]\\]/g, "\\$&"))
        .join("[^/]+");
      return new RegExp(`^${pattern}$`).test(bare);
    })
    .map(([template]) => template);
  // A literal segment is more specific than a parameter that would also match it.
  matches.sort((a, b) => a.split("{").length - b.split("{").length);
  return matches[0] ?? null;
}

/** An operation's `security`, as one line: `sessionCookie: [user:read]`, or several joined by `or`. */
function describeSecurity(security) {
  if (security.length === 0) return "nothing (security: [])";
  return security
    .map((alternative) =>
      Object.entries(alternative)
        .map(([scheme, scopes]) => `${scheme}: [${scopes.join(", ")}]`)
        .join(" and "),
    )
    .join(" or ");
}

/**
 * The Permissions, or `superuser`, a session must hold every one of for
 * `security` — the scopes of its one `sessionCookie` entry; `null` for
 * anything else: alternatives, another scheme, or an entry naming none.
 */
function sessionRequirement(security) {
  if (security.length !== 1) return null;
  const [alternative] = security;
  const schemes = Object.keys(alternative);
  if (schemes.length !== 1 || schemes[0] !== "sessionCookie") return null;
  const scopes = alternative.sessionCookie;
  return scopes.length > 0 ? scopes : null;
}

/** Whether two lists of names hold the same names, as many times each, in any order. */
function sameNames(a, b) {
  if (a === null || b === null || a.length !== b.length) return false;
  const sorted = (names) => [...names].sort();
  return sorted(a).every((name, index) => name === sorted(b)[index]);
}

/** A path the declaration `value` builds from stand-in arguments, or `null` when it builds none. */
function pathOf(value) {
  try {
    const path = value.path(...ARGUMENTS);
    return typeof path === "string" && path.startsWith("/") ? path : null;
  } catch {
    return null;
  }
}

/** Each name a module's source declares that its exports do not hold as a declaration. */
function unevaluatedDeclarations(spa) {
  return Object.entries(spa.declared).flatMap(([module, names]) =>
    names
      .filter((name) => !isDeclaration(spa.modules[module]?.[name]))
      .map(
        (name) =>
          `${module} declares ${name}, which did not evaluate to a request declaration`,
      ),
  );
}

function declarationMismatches(spa, doc) {
  const declarations = declarationsOf(spa);
  if (declarations.length === 0) {
    return [
      "found no request declaration in the SPA; the check would hold nothing",
    ];
  }
  const findings = unevaluatedDeclarations(spa);
  for (const { label, method, value, required } of declarations) {
    const path = pathOf(value);
    if (path === null) {
      findings.push(
        `${label} builds no absolute path from placeholder arguments`,
      );
      continue;
    }
    const template = operationFor(doc, method, path);
    if (template === null) {
      findings.push(
        `${label} sends ${method} ${path.split("?")[0]}, which no operation in openapi.yaml serves`,
      );
      continue;
    }
    const operation = doc.paths[template][method.toLowerCase()];
    const security = operation.security ?? doc.security ?? [];
    if (!sameNames(required, sessionRequirement(security))) {
      const requires = required.map((name) => `"${name}"`).join(" and ");
      findings.push(
        `${label} requires ${requires} for ${method} ${template}, but openapi.yaml requires ${describeSecurity(security)}`,
      );
    }
  }
  return findings;
}

/**
 * The names a module's source exports as a request declaration — every
 * `export const NAME: ReadDeclaration<…>` or `WriteDeclaration<…>` — read off
 * the text. Deliberately narrow: it only says which exports the evaluated
 * module must hold as declarations, so one that fails to evaluate as one is
 * reported instead of passing unnoticed.
 */
export function declaredNames(source) {
  return [
    ...source.matchAll(
      /^export const (\w+)\s*:\s*(?:Read|Write)Declaration\b/gm,
    ),
  ].map(([, name]) => name);
}

/**
 * Every way the SPA, as `spa` holds it, disagrees with `doc`, the parsed API
 * document; empty when they agree.
 *
 * `spa.modules` maps a source path under `frontend/` to that module's exports;
 * `spa.declared` maps a source path to the names of the request declarations
 * its source exports, read off the text, so a declaration that does not
 * evaluate to one is reported rather than skipped.
 */
export function findMismatches(spa, doc) {
  return [
    ...vocabularyMismatches(spa, doc),
    ...declarationMismatches(spa, doc),
  ];
}

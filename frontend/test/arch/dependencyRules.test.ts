import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

import { cruise, type ICruiseResult } from "dependency-cruiser";
import extractDepcruiseOptions from "dependency-cruiser/config-utl/extract-depcruise-options";
import extractTSConfig from "dependency-cruiser/config-utl/extract-ts-config";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

// Architecture rule `every_dependency_rule_reports_a_planted_import`: every
// rule in test/.dependency-cruiser.cjs, shown reporting a planted import and
// passing one it must allow. The real source complies, so `npm run
// test:arch` alone would never show a rule failing — and a rule whose path
// pattern matches nothing, or whose `$1` back-reference never substitutes,
// passes there just the same.
//
// The fixture is a tree laid out like the app's root — its own `src/`, a
// `node_modules/` of stand-in packages, a package.json naming them as
// dependencies or devDependencies, and a verbatim copy of the app's
// tsconfig.json for the `@/` alias — written to a temporary directory and
// cruised with the real configuration from there (`baseDir`), so every rule's
// `^src/` and `^node_modules/` paths match exactly as they do in the app. The
// tree is built from the cases below and never sits in the repository, so no
// build, typecheck, lint or real depcruise run sees it.

const CONFIG = "test/.dependency-cruiser.cjs";

/** An import, as the resolved paths of its importer and the module it reaches. */
type Edge = readonly [from: string, to: string];

/**
 * A rule's planted imports, each of which it must report, the imports nearest
 * to them that it must allow, and any import the tree needs for the case that
 * the case asserts nothing about.
 */
interface Case {
  planted: Edge[];
  allowed: Edge[];
  context?: Edge[];
}

const CASES: Record<string, Case> = {
  "cy-no-circular": {
    // A cycle is reported once, at the first of its modules; one through a test
    // is reported from its production side only.
    planted: [
      ["src/lib/cycle-a.ts", "src/lib/cycle-b.ts"],
      ["src/lib/cycle-d.ts", "src/lib/cycle-c.test.ts"],
    ],
    allowed: [["src/lib/cycle-c.test.ts", "src/lib/cycle-d.ts"]],
    context: [["src/lib/cycle-b.ts", "src/lib/cycle-a.ts"]],
  },
  "mb-ui-primitives-are-leaves": {
    planted: [["src/components/ui/button.tsx", "src/auth/auth-context.tsx"]],
    allowed: [
      ["src/components/ui/button.tsx", "src/lib/cn.ts"],
      ["src/components/ui/button.tsx", "src/components/ui/slot.tsx"],
    ],
  },
  "mb-lib-is-a-leaf": {
    planted: [["src/lib/leaky.ts", "src/components/ui/slot.tsx"]],
    allowed: [
      ["src/lib/leaky.ts", "src/lib/cn.ts"],
      ["src/lib/cn.test.ts", "src/auth/auth-context.tsx"],
    ],
  },
  "fhircast-only-through-the-facade": {
    planted: [["src/shell/fhircast-panel.tsx", "src/lib/fhircast/protocol.ts"]],
    allowed: [
      ["src/shell/fhircast-panel.tsx", "src/lib/fhircast/index.ts"],
      ["src/lib/fhircast/index.ts", "src/lib/fhircast/protocol.ts"],
    ],
  },
  "mb-no-top-level-catchall-dirs": {
    planted: [
      ["src/lib/formatting.ts", "src/utils/format.ts"],
      ["src/lib/formatting.ts", "src/types/account.ts"],
      ["src/lib/formatting.ts", "src/hooks/use-account.ts"],
    ],
    allowed: [["src/lib/formatting.ts", "src/lib/utils/strings.ts"]],
  },
  "lb-no-css-in-logic": {
    planted: [["src/lib/styled.ts", "src/lib/styles.css"]],
    allowed: [["src/main.tsx", "src/index.css"]],
  },
  "lb-transport-has-no-react": {
    planted: [
      ["src/lib/http.ts", "node_modules/react/index.js"],
      ["src/lib/decode.ts", "node_modules/react-dom/index.js"],
    ],
    allowed: [["src/lib/use-thing.ts", "node_modules/react/index.js"]],
  },
  "styles-not-in-logic-files": {
    planted: [
      ["src/auth/auth-context.tsx", "src/index.css"],
      ["src/pages/accounts/accounts-api.ts", "src/pages/accounts/accounts.css"],
    ],
    allowed: [["src/pages/accounts/accounts.tsx", "src/pages/accounts/accounts.css"]],
  },
  "mb-transport-is-behind-the-session-seam": {
    planted: [["src/pages/accounts/accounts.tsx", "src/lib/http.ts"]],
    allowed: [
      ["src/auth/use-session-request.ts", "src/lib/http.ts"],
      ["src/pages/accounts/accounts.test.tsx", "src/lib/http.ts"],
    ],
  },
  "mb-pages-request-through-the-gated-hooks": {
    planted: [
      ["src/pages/groups/groups.tsx", "src/auth/use-session-request.ts"],
      ["src/shell/shell.tsx", "src/auth/use-session-request.ts"],
    ],
    allowed: [
      ["src/auth/use-gated-read.ts", "src/auth/use-session-request.ts"],
      ["src/pages/groups/groups.test.tsx", "src/auth/use-session-request.ts"],
    ],
  },
  "mb-session-transitions-have-one-caller": {
    planted: [["src/auth/use-session-request.ts", "src/auth/session-transitions.ts"]],
    allowed: [
      ["src/auth/auth-context.tsx", "src/auth/session-transitions.ts"],
      ["src/auth/session-transitions.test.ts", "src/auth/session-transitions.ts"],
    ],
  },
  "mb-auth-does-not-import-up": {
    planted: [
      ["src/auth/use-gated-read.ts", "src/shell/shell.tsx"],
      ["src/auth/use-gated-read.ts", "src/App.tsx"],
    ],
    allowed: [["src/auth/use-gated-read.test.ts", "src/shell/shell.tsx"]],
  },
  "mb-components-do-not-import-up": {
    planted: [
      ["src/components/status-banner.tsx", "src/auth/auth-context.tsx"],
      ["src/components/status-banner.tsx", "src/pages/groups/groups.tsx"],
    ],
    allowed: [["src/components/status-banner.tsx", "src/components/ui/button.tsx"]],
  },
  "mb-pages-do-not-import-the-root": {
    planted: [["src/pages/groups/groups.tsx", "src/App.tsx"]],
    allowed: [["src/pages/groups/groups.test.tsx", "src/App.tsx"]],
  },
  "mb-pages-do-not-import-the-shell": {
    planted: [["src/pages/groups/groups.tsx", "src/shell/shell.tsx"]],
    allowed: [["src/pages/groups/groups.test.tsx", "src/shell/shell.tsx"]],
  },
  "mb-pages-do-not-import-another-page": {
    planted: [["src/pages/accounts/accounts.tsx", "src/pages/groups/groups.tsx"]],
    // The first is the `$1` back-reference at work: its own page's folder.
    allowed: [
      ["src/pages/accounts/accounts.tsx", "src/pages/accounts/account-row.tsx"],
      ["src/pages/accounts/accounts.test.tsx", "src/pages/groups/groups.tsx"],
    ],
  },
  "mb-shared-page-parts-do-not-import-a-page": {
    planted: [["src/pages/listing-body.tsx", "src/pages/accounts/account-row.tsx"]],
    allowed: [["src/pages/listing-body.tsx", "src/pages/pager-move.tsx"]],
  },
  "mb-shell-does-not-import-the-root": {
    planted: [["src/shell/shell.tsx", "src/main.tsx"]],
    allowed: [["src/shell/shell.test.tsx", "src/App.tsx"]],
  },
  "mb-src-layout-is-declared": {
    planted: [["src/stray/thing.ts", "src/lib/cn.ts"]],
    allowed: [
      ["src/main.tsx", "src/App.tsx"],
      ["src/stray/thing.test.ts", "src/lib/cn.ts"],
    ],
  },
  "mb-src-layout-is-declared-for-importers": {
    planted: [["src/lib/uses-stray.ts", "src/stray/thing.ts"]],
    allowed: [
      ["src/main.tsx", "src/index.css"],
      ["src/main.tsx", "src/App.tsx"],
    ],
  },
  "lb-api-modules-are-data": {
    planted: [
      ["src/pages/accounts/accounts-api.ts", "node_modules/react/index.js"],
      ["src/pages/accounts/accounts-api.ts", "src/components/ui/button.tsx"],
      ["src/pages/accounts/accounts-api.ts", "src/shell/shell.tsx"],
    ],
    allowed: [
      ["src/pages/accounts/accounts-api.ts", "src/pages/accounts/account-detail-api.ts"],
      ["src/pages/accounts/accounts-api.ts", "src/lib/decode.ts"],
    ],
  },
  "no-direct-http-in-components": {
    planted: [
      ["src/components/fetcher.tsx", "node_modules/axios/index.js"],
      ["src/pages/accounts/accounts.tsx", "node_modules/ky/index.js"],
    ],
    allowed: [["src/components/fetcher.tsx", "node_modules/clsx/index.js"]],
  },
  "cq-no-devdep-in-prod": {
    planted: [["src/lib/uses-dev-tool.ts", "node_modules/vitest/index.js"]],
    allowed: [
      ["src/lib/fake-backend.testHelpers.ts", "node_modules/vitest/index.js"],
      ["src/lib/cn.test.ts", "node_modules/vitest/index.js"],
    ],
  },
  "cq-no-test-code-in-prod": {
    planted: [
      ["src/lib/leaky.ts", "src/lib/fake-backend.testHelpers.ts"],
      ["src/lib/leaky.ts", "src/lib/cn.test.ts"],
    ],
    allowed: [["src/lib/cn.test.ts", "src/lib/fake-backend.testHelpers.ts"]],
  },
};

/** The fixture's stand-in packages that package.json lists as devDependencies. */
const DEV_DEPENDENCIES = new Set(["vitest"]);

const PACKAGE = /^node_modules\/([^/]+)\//;

/** How the importer names `to`: a bare package name, or the `@/` alias. */
const specifierOf = (to: string): string =>
  PACKAGE.exec(to)?.[1] ?? `@/${to.slice("src/".length).replace(/\.tsx?$/, "")}`;

const edges = Object.values(CASES).flatMap(({ planted, allowed, context = [] }) => [
  ...planted,
  ...allowed,
  ...context,
]);

/** Writes the fixture tree every case's imports describe, under `root`. */
function writeFixture(root: string): void {
  const files = new Map<string, Set<string>>();
  for (const [from, to] of edges) {
    files.set(from, (files.get(from) ?? new Set()).add(specifierOf(to)));
    if (!files.has(to)) files.set(to, new Set());
  }
  const write = (path: string, content: string): void => {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), content);
  };
  const packages = new Set<string>();
  for (const [path, imports] of files) {
    const name = PACKAGE.exec(path)?.[1];
    if (name) packages.add(name);
    write(path, [...imports].map((specifier) => `import "${specifier}";\n`).join(""));
  }
  for (const name of packages) {
    write(`node_modules/${name}/package.json`, JSON.stringify({ name, main: "index.js" }));
  }
  const listed = (dev: boolean) =>
    Object.fromEntries(
      [...packages]
        .filter((name) => DEV_DEPENDENCIES.has(name) === dev)
        .map((name) => [name, "1.0.0"]),
    );
  write(
    "package.json",
    JSON.stringify({ name: "fixture", dependencies: listed(false), devDependencies: listed(true) }),
  );
  write("tsconfig.json", readFileSync(join(process.cwd(), "tsconfig.json"), "utf8"));
}

let fixture: string;
let configured: string[];
let result: ICruiseResult;

beforeAll(async () => {
  fixture = mkdtempSync(join(tmpdir(), "depcruise-rules-"));
  writeFixture(fixture);
  const options = await extractDepcruiseOptions(join(process.cwd(), CONFIG));
  configured = (options.ruleSet?.forbidden ?? []).map(({ name }) => name ?? "");
  const tsConfig = join(fixture, "tsconfig.json");
  const { output } = await cruise(
    ["src"],
    { ...options, baseDir: fixture, tsConfig: { fileName: tsConfig } },
    {},
    { tsConfig: extractTSConfig(tsConfig) },
  );
  result = typeof output === "string" ? (JSON.parse(output) as ICruiseResult) : output;
}, 60_000);

afterAll(() => {
  if (fixture) rmSync(fixture, { recursive: true, force: true });
});

/** Every import `rule` reports, as `from -> to`. */
const reportedUnder = (rule: string): Set<string> =>
  new Set(
    result.summary.violations
      .filter((violation) => violation.rule.name === rule)
      .map(({ from, to }) => `${from} -> ${to}`),
  );

const key = ([from, to]: Edge): string => `${from} -> ${to}`;

describe("dependency-cruiser rules on planted imports", () => {
  it("has a case for every configured rule, and none for a rule it does not configure", () => {
    expect(Object.keys(CASES).sort()).toEqual([...configured].sort());
  });

  it("resolves every planted import, through the alias and into node_modules", () => {
    const unresolved = result.modules.flatMap(({ source, dependencies }) =>
      dependencies
        .filter(({ couldNotResolve }) => couldNotResolve)
        .map(({ module }) => `${source} -> ${module}`),
    );

    expect(unresolved).toEqual([]);
  });

  it.each(Object.entries(CASES))(
    "%s reports its planted imports and allows the rest",
    (rule, { planted, allowed }) => {
      const reported = reportedUnder(rule);

      expect({
        reported: planted.filter((edge) => reported.has(key(edge))),
        allowedButReported: allowed.filter((edge) => reported.has(key(edge))),
      }).toEqual({ reported: planted, allowedButReported: [] });
    },
  );
});

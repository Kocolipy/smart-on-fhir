/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    {
      name: "cy-no-circular",
      comment:
        "Circular dependencies make module load order unpredictable and hide coupling; no cycle may exist in the module graph.",
      severity: "error",
      from: { pathNot: "\\.(test|spec)\\.[tj]sx?$" },
      to: { circular: true },
    },

    {
      name: "mb-ui-primitives-are-leaves",
      comment:
        "src/components/ui/ is the in-house component library's stand-in. It may reach for cn() in src/lib/ and for its siblings, and nothing else — a primitive that imports a page or a feature cannot be swapped out for the published package.",
      severity: "error",
      from: { path: "^src/components/ui/", pathNot: "\\.(test|spec)\\.[tj]sx?$" },
      to: { path: "^src/", pathNot: "^src/(components/ui|lib)/" },
    },

    {
      name: "mb-lib-is-a-leaf",
      comment:
        "src/lib/ holds framework-agnostic helpers every layer may call. It must not import back out of itself, or the dependency direction stops being one-way.",
      severity: "error",
      from: { path: "^src/lib/", pathNot: "\\.(test|spec)\\.[tj]sx?$" },
      to: { path: "^src/", pathNot: "^src/lib/" },
    },

    {
      name: "mb-no-top-level-catchall-dirs",
      comment:
        "There is no src/types, src/hooks or src/utils. Types live in the folder that owns them; shared helpers and hooks live in src/lib (which is what components.json points the shadcn CLI at).",
      severity: "error",
      from: {},
      to: { path: "^src/(types|hooks|utils)/" },
    },

    {
      name: "lb-no-css-in-logic",
      comment:
        "Helpers must not import stylesheets; style imports belong in component files and in the composition root.",
      severity: "error",
      from: { path: "^src/lib/" },
      to: { path: "\\.(css|scss|sass|less)$" },
    },

    {
      name: "lb-transport-has-no-react",
      comment:
        "src/lib/http.ts and decode.ts stay dependency-free — no auth types, no React (docs/ARCHITECTURE.md). mb-lib-is-a-leaf holds the first half; this holds the second, so the transport stays callable outside a component tree.",
      severity: "error",
      from: { path: "^src/lib/(http|decode)\\.ts$" },
      to: { path: "^node_modules/(react|react-dom|react-router|react-router-dom)/" },
    },

    {
      name: "styles-not-in-logic-files",
      comment:
        "The session layer and the *-api.ts request modules hold no markup, so they have no reason to import a stylesheet; style imports belong in component files and the composition root.",
      severity: "error",
      from: { path: "^src/(auth/|pages/[^/]+-api\\.ts$)" },
      to: { path: "\\.(css|scss|sass|less)$" },
    },

    {
      name: "mb-transport-is-behind-the-session-seam",
      comment:
        "src/lib/http.ts classifies a 401 as `unauthenticated`, and responding to that is a session concern. Only src/auth/ may call it: features request through useSessionRequest, which handles the session outcome once and hands back a result with no unauthenticated case to forget. A page that imports apiFetch directly silently opts out of that.",
      severity: "error",
      from: {
        path: "^src/",
        pathNot: ["^src/(auth|lib)/", "\\.(test|spec)\\.[tj]sx?$"],
      },
      to: { path: "^src/lib/http\\.ts$" },
    },

    {
      name: "mb-auth-does-not-import-up",
      comment:
        "Imports point down the tree and may never point back up (docs/ARCHITECTURE.md). src/auth/ sits below the pages and the composition root, so it must not import either.",
      severity: "error",
      from: { path: "^src/auth/", pathNot: "\\.(test|spec)\\.[tj]sx?$" },
      to: { path: "^src/(pages/|App\\.tsx$|main\\.tsx$)" },
    },

    {
      name: "mb-components-do-not-import-up",
      comment:
        "Shared components are reused by every page, so they may not import a page, the session layer in src/auth/, or the composition root (docs/ARCHITECTURE.md: imports never point back up).",
      severity: "error",
      from: { path: "^src/components/", pathNot: "\\.(test|spec)\\.[tj]sx?$" },
      to: { path: "^src/(pages/|auth/|App\\.tsx$|main\\.tsx$)" },
    },

    {
      name: "mb-pages-do-not-import-the-root",
      comment:
        "App.tsx and main.tsx compose the pages; a page that imports either creates a loop through the composition root (docs/ARCHITECTURE.md).",
      severity: "error",
      from: { path: "^src/pages/", pathNot: "\\.(test|spec)\\.[tj]sx?$" },
      to: { path: "^src/(App|main)\\.tsx$" },
    },

    {
      name: "no-direct-http-in-components",
      comment:
        "Components and pages request through useSessionRequest. A third-party HTTP client would bypass the session seam the same way a direct apiFetch import does, so none may be imported here.",
      severity: "error",
      from: { path: "^src/(components|pages)/" },
      to: {
        dependencyTypes: ["npm"],
        path: "^node_modules/(axios|ky|got|node-fetch|cross-fetch|superagent)/",
      },
    },

    {
      name: "cq-no-devdep-in-prod",
      comment:
        "Production source must not import devDependencies; they are absent at runtime. *.testHelpers.ts(x) is exempt as the shared-test-support naming convention — it is excluded from the production TypeScript project the same way *.test.ts(x) is (tsconfig.json), and is deliberately not named *.helpers.ts(x), which stays inside this rule.",
      severity: "error",
      from: {
        path: "^src/",
        pathNot: ["\\.(test|spec)\\.[tj]sx?$", "\\.testHelpers\\.[tj]sx?$"],
      },
      to: { dependencyTypes: ["npm-dev"] },
    },
  ],

  options: {
    doNotFollow: {
      path: "node_modules",
    },

    // node_modules is deliberately in scope. `doNotFollow` already stops the
    // crawl at the package boundary, so this adds leaf nodes only — but those
    // leaves are what carry the `npm` / `npm-dev` dependency types. Narrow
    // this to "^src/" and cq-no-devdep-in-prod stops matching anything and
    // passes vacuously.
    includeOnly: ["^src/", "^node_modules/"],

    // Also how the `@/*` path alias gets resolved; without it every aliased
    // import reads as unresolvable and no rule above matches on it.
    tsConfig: { fileName: "./tsconfig.json" },
    tsPreCompilationDeps: true,

    moduleSystems: ["es6", "cjs"],

    reporterOptions: {
      dot: {
        collapsePattern: "^node_modules/[^/]+/",
      },
      archi: {
        collapsePattern: "^(node_modules|src/[^/]+/[^/]+)/",
      },
    },
  },
};

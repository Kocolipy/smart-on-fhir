import { describe, expect, it } from "vitest";

import { blankComments, readSources, violationsIn } from "./sources";

// Architecture rule `no_web_storage_outside_its_two_keepers` (docs/BACKEND_CONTRACT.md,
// the EHR context and "What the FHIRcast module keeps"). The SPA keeps exactly
// two things in the browser, both per tab in `sessionStorage`: the EHR context
// and the FHIRcast connection. Each keeper decodes what it reads back and is
// cleared on every session end; the CSRF token is never kept at all
// (src/lib/http.ts). `localStorage` and `sessionStorage` are globals, so a page
// that writes to them imports nothing, and dependency-cruiser never sees it.
// The browser profile's dynamic client at the FHIRcast Hub, in IndexedDB, is
// browserKeys.test.ts's rule.
const EHR_CONTEXT = "src/auth/ehr-context.ts";
const FHIRCAST_STORAGE = "src/lib/fhircast/storage.ts";

/** Every module allowed to touch browser storage, and nothing else. */
const STORAGE_MODULES = [EHR_CONTEXT, FHIRCAST_STORAGE];

const WEB_STORAGE = /\b(localStorage|sessionStorage)\b/;

const sources = readSources("src", [".ts", ".tsx"]).filter(
  ({ path }) => !/\.(test|spec|testHelpers)\.[tj]sx?$/.test(path),
);
const files = sources
  .filter(({ path }) => !STORAGE_MODULES.includes(path))
  .map(({ path, code }) => ({ path, code: blankComments(code) }));

describe("browser storage", () => {
  it("finds the sources to check", () => {
    expect(files.length).toBeGreaterThan(0);
  });

  it.each(STORAGE_MODULES)("keeps %s as a storage module", (path) => {
    // Guards the exemption: if the module moves and its entry is not moved
    // with it, the exemption would cover a file that no longer keeps anything.
    const module = sources.find((source) => source.path === path);

    expect(module, `${path} is an exempt storage module.`).toBeDefined();
    expect(WEB_STORAGE.test(blankComments(module?.code ?? ""))).toBe(true);
  });

  it("reports storage reached outside the two keepers, and not in a comment", () => {
    const planted = [
      { path: "src/pages/planted.tsx", code: 'window.localStorage.setItem("token", value);' },
      {
        path: "src/pages/planted-comment.tsx",
        code: blankComments("// sessionStorage is not ours"),
      },
    ];

    expect(violationsIn(planted, WEB_STORAGE)).toEqual([
      'src/pages/planted.tsx:1: window.localStorage.setItem("token", value);',
    ]);
  });

  it("has no no_web_storage_outside_its_two_keepers violation", () => {
    const violations = violationsIn(files, WEB_STORAGE);

    const why =
      `The tab keeps the EHR context (${EHR_CONTEXT}) and the FHIRcast connection ` +
      `(${FHIRCAST_STORAGE}), and nothing else: each decodes what it reads back and is ` +
      "cleared when the session ends. Anything new the tab keeps gets a keeper of its own, " +
      "added here and to docs/BACKEND_CONTRACT.md together.\n";

    expect(violations, why + violations.join("\n")).toEqual([]);
  });
});

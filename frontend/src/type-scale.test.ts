import { readFileSync } from "node:fs";
import { join } from "node:path";

import { compile } from "tailwindcss";
import { describe, expect, it } from "vitest";

// The design doc's named type scale (frontend/docs/DESIGN.md, `typography:`),
// as the utilities src/index.css generates for it. vitest stubs every CSS
// import, so this compiles the token file with Tailwind itself and reads back
// the rule a class in markup would receive.
//
// cwd is the frontend root under vitest and in a Stryker sandbox alike (see
// test/arch/sources.ts), so the token file and Tailwind's own stylesheet are
// read from there.
const ROOT = process.cwd();

async function declarationsOf(className: string): Promise<Record<string, string>> {
  const compiler = await compile(readFileSync(join(ROOT, "src/index.css"), "utf8"), {
    base: ROOT,
    loadStylesheet: async (id, base) => ({
      path: id,
      base,
      content: readFileSync(join(ROOT, "node_modules", id, "index.css"), "utf8"),
    }),
  });
  const css = compiler.build([className]);
  // The pattern is built from test-literal input only: className is always one
  // of TYPE_SCALE's hardcoded keys below.
  // nosemgrep: javascript.lang.security.audit.detect-non-literal-regexp.detect-non-literal-regexp
  const rule = new RegExp(`\\.${className} \\{([^}]*)\\}`).exec(css)?.[1] ?? "";

  return Object.fromEntries(
    rule
      .split(";")
      .map((declaration) => declaration.trim())
      .filter(Boolean)
      .map((declaration) => {
        const [property = "", ...value] = declaration.split(":");
        // A Tailwind text token yields `var(--tw-leading, 36px)`: the scale's
        // value, unless a `leading-*` / `tracking-*` / `font-*` beside it wins.
        const unwrapped = value
          .join(":")
          .trim()
          .replace(/^var\(--tw-[\w-]+, (.*)\)$/, "$1");
        return [property.trim(), unwrapped];
      }),
  );
}

// Transcribed from DESIGN.md by hand, so the expectation cannot drift with the
// token file it checks.
const TYPE_SCALE = {
  "text-body-sm": { "font-size": "12px", "line-height": "18px", "font-weight": "400" },
  "text-body-md": { "font-size": "14px", "line-height": "20px", "font-weight": "400" },
  "text-body-lg": { "font-size": "16px", "line-height": "24px", "font-weight": "400" },
  "text-label-md": {
    "font-size": "13px",
    "line-height": "16px",
    "letter-spacing": "0.02em",
    "font-weight": "600",
  },
  "text-data-mono": { "font-size": "14px", "line-height": "20px", "font-weight": "500" },
  "text-headline-sm": { "font-size": "16px", "line-height": "24px", "font-weight": "600" },
  "text-headline-md": { "font-size": "20px", "line-height": "28px", "font-weight": "600" },
  "text-headline-lg": {
    "font-size": "28px",
    "line-height": "36px",
    "letter-spacing": "-0.02em",
    "font-weight": "700",
  },
};

describe("the design's type scale", () => {
  it.each(Object.entries(TYPE_SCALE))(
    "%s sets the level DESIGN.md specifies",
    async (name, level) => {
      expect(await declarationsOf(name)).toEqual(level);
    },
  );
});

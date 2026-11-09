import { describe, expect, it } from "vitest";

import { readSources } from "./sources";

// Architecture rule `colors_come_from_tokens` (AGENTS.md, Architecture).
// src/index.css holds the light and dark palettes and maps them onto Tailwind
// colour utilities; every other file names a token (`bg-card`,
// `text-muted-foreground`, `var(--color-border)`) instead of a value. A
// literal that slips into a component is invisible to the dark-mode switch and
// to any later rebrand, and nothing else in the pipeline flags it — Tailwind
// compiles arbitrary values like `bg-[#0f172a]` without complaint.
//
// There are two ways to name a value, so there are two patterns. A literal is
// one; Tailwind's own default palette is the other. `src/index.css` imports
// `tailwindcss` without resetting `--color-*`, so every shade the framework
// ships — `bg-red-500`, `text-white`, `bg-black` — compiles and renders the
// same in light and dark mode, with no token behind it and nothing to rebrand.
const TOKEN_SOURCE = "src/index.css";

// Hex (3, 4, 6 or 8 digits) and the four colour functions. The leading `\b`
// keeps identifiers such as `--color-oklch-ish` out.
const COLOR_LITERAL = /#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?|oklch|oklab)\s*\(/;

// A colour utility naming a shade of Tailwind's default palette, or `white` or
// `black`: a utility prefix, then either a palette name with a 50–950 shade or
// one of the two keywords, and an optional `/opacity`. Bounded by non-word,
// non-hyphen characters at both ends, so a variant prefix (`hover:`,
// `dark:`, `data-[state=open]:`) still matches while a token's own name never
// does — `text-foreground`, `bg-destructive/90` and `ring-ring/50` are the
// shapes that must stay out.
const PALETTE_UTILITY =
  /(?<![-\w])(?:bg|text|border(?:-[trblxyse])?|ring|ring-offset|outline|fill|stroke|from|via|to|divide|placeholder|caret|accent|shadow|decoration|inset-shadow)-(?:(?:slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-(?:50|100|200|300|400|500|600|700|800|900|950)|white|black)(?:\/(?:\d{1,3}|\[[^\]]+\]))?(?![-\w])/;

// The in-house shadcn library in source form, exempt from PALETTE_UTILITY
// only: it arrived with the framework's defaults in its overlays and thumbs
// (`bg-black/50`, `bg-white`, `text-white`) and is meant to be swapped for the
// published package, so recolouring it here would be a visual change to
// vendored source. A colour literal is still forbidden there.
const VENDORED_PRIMITIVES = "src/components/ui/";

// Blanks out comments while preserving line count and column positions, so
// reported line numbers still point at the real source.
const blankComments = (source: string): string =>
  source
    .replace(/\/\*[\s\S]*?\*\//g, (match) => match.replace(/[^\n]/g, " "))
    .replace(/(^|[^:])\/\/.*$/gm, (match, prefix: string) => prefix.padEnd(match.length));

const sources = readSources("src", [".ts", ".tsx", ".css"]);
const files = sources
  .filter(({ path }) => path !== TOKEN_SOURCE)
  .map(({ path, code }) => ({ path, code: blankComments(code) }));

// PALETTE_UTILITY is a Tailwind class name, so only the files that carry class
// names are scanned; index.css is where the framework's own palette is
// imported from.
const classNameFiles = files.filter(
  ({ path }) => /\.tsx?$/.test(path) && !path.startsWith(VENDORED_PRIMITIVES),
);

/** Each line of `checked` matching `pattern`, as `path:line: code`. */
const violationsIn = (
  checked: { path: string; code: string }[],
  pattern: RegExp = COLOR_LITERAL,
): string[] =>
  checked.flatMap(({ path, code }) =>
    code
      .split("\n")
      .map((line, index) => ({ line, number: index + 1 }))
      .filter(({ line }) => pattern.test(line))
      .map(({ line, number }) => `${path}:${number}: ${line.trim()}`),
  );

describe("design tokens", () => {
  it("finds the sources to check", () => {
    expect(files.length).toBeGreaterThan(0);
    expect(classNameFiles.length).toBeGreaterThan(0);
  });

  it("keeps src/index.css as the palette", () => {
    // Guards the rule itself: if the palette moves and TOKEN_SOURCE is not
    // moved with it, the exemption above would silently cover a file that no
    // longer defines any colour, and the real palette would start failing.
    const palette = sources.find(({ path }) => path === TOKEN_SOURCE);

    expect(palette, `${TOKEN_SOURCE} is where the light and dark palettes live.`).toBeDefined();
    expect(COLOR_LITERAL.test(palette?.code ?? "")).toBe(true);
  });

  it("reports a colour literal, and not a token or a comment", () => {
    // Proves the rule fires: without it COLOR_LITERAL could reject everything
    // and the case below would pass on any source at all. `bg-card` and
    // `var(--color-border)` are the compliant spellings, and a commented hex
    // may name a value freely.
    const planted = [
      { path: "src/pages/planted-hex.tsx", code: 'className="bg-[#0f172a]"' },
      { path: "src/pages/planted-rgb.css", code: "color: rgb(0 0 0);" },
      { path: "src/pages/planted-oklch.css", code: "color: oklch(0.5 0.1 200);" },
      { path: "src/pages/planted-token.tsx", code: 'className="bg-card"' },
      { path: "src/pages/planted-var.css", code: "border-color: var(--color-border);" },
      { path: "src/pages/planted-comment.tsx", code: blankComments("// #0f172a is the old slate") },
    ];

    expect(violationsIn(planted)).toEqual([
      'src/pages/planted-hex.tsx:1: className="bg-[#0f172a]"',
      "src/pages/planted-rgb.css:1: color: rgb(0 0 0);",
      "src/pages/planted-oklch.css:1: color: oklch(0.5 0.1 200);",
    ]);
  });

  it("reports a default-palette utility, and not a token or a comment", () => {
    // The same proof for PALETTE_UTILITY. The three that must stay out are
    // token utilities whose names contain a palette-shaped tail or an opacity
    // suffix — the shapes a too-greedy pattern would catch.
    const planted = [
      { path: "src/pages/planted-shade.tsx", code: 'className="bg-red-500"' },
      { path: "src/pages/planted-white.tsx", code: 'className="text-white"' },
      { path: "src/pages/planted-variant.tsx", code: 'className="hover:bg-black/50"' },
      { path: "src/pages/planted-card.tsx", code: 'className="bg-card"' },
      { path: "src/pages/planted-fg.tsx", code: 'className="text-primary-foreground"' },
      { path: "src/pages/planted-destructive.tsx", code: 'className="bg-destructive"' },
      {
        path: "src/pages/planted-comment.tsx",
        code: blankComments("// text-white was the old thumb"),
      },
    ];

    expect(violationsIn(planted, PALETTE_UTILITY)).toEqual([
      'src/pages/planted-shade.tsx:1: className="bg-red-500"',
      'src/pages/planted-white.tsx:1: className="text-white"',
      'src/pages/planted-variant.tsx:1: className="hover:bg-black/50"',
    ]);
  });

  it("has no colors_come_from_tokens violation", () => {
    const violations = violationsIn(files);

    const why =
      `Colors come from the tokens in ${TOKEN_SOURCE} — use a Tailwind utility ` +
      "(`bg-card`, `text-muted-foreground`) in TSX and `var(--color-*)` in CSS.\n" +
      "A new shade belongs in the :root / .dark pair and the @theme inline block, " +
      "not at the call site.\n";

    expect(violations, why + violations.join("\n")).toEqual([]);
  });

  it("names no shade of Tailwind's default palette", () => {
    const violations = violationsIn(classNameFiles, PALETTE_UTILITY);

    const why =
      `Tailwind's own palette is not this product's: ${TOKEN_SOURCE} imports the ` +
      "framework without resetting `--color-*`, so `bg-red-500`, `text-white` and " +
      "`bg-black` compile, render the same in both modes, and survive any rebrand " +
      `untouched. Name a token instead (\`bg-destructive\`, \`text-foreground\`). Only ` +
      `${VENDORED_PRIMITIVES} is exempt, as vendored source awaiting the published package.\n`;

    expect(violations, why + violations.join("\n")).toEqual([]);
  });
});

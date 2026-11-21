import { readdirSync, readFileSync } from "node:fs";
import { join, relative, sep } from "node:path";

// vitest resolves its `root` from the working directory and runs every test
// file from there, and a Stryker sandbox does the same inside its copy — so
// cwd is the repo root in both. `import.meta.url` is deliberately not used:
// under vitest it is a served URL, not a `file:` one, and fileURLToPath throws
// on it.
const ROOT = process.cwd();

export interface SourceFile {
  /** Repo-relative, POSIX-separated — the form a violation message prints. */
  path: string;
  code: string;
}

const toPosix = (path: string): string => path.split(sep).join("/");

/**
 * Every file under `dir` whose name ends with one of `suffixes`, read as text.
 *
 * node:fs rather than `import.meta.glob(..., { query: "?raw" })`, which is the
 * obvious alternative: vitest replaces every `.css` import with an empty
 * module (`test.css` is off by default) and that substitution happens *before*
 * the `?raw` query is honoured, so a stylesheet globbed that way arrives as an
 * empty string and any rule reading it passes vacuously.
 */
export function readSources(dir: string, suffixes: string[]): SourceFile[] {
  return readdirSync(join(ROOT, dir), { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && suffixes.some((suffix) => entry.name.endsWith(suffix)))
    .map((entry) => {
      const absolute = join(entry.parentPath, entry.name);

      return { path: toPosix(relative(ROOT, absolute)), code: readFileSync(absolute, "utf8") };
    })
    .sort((a, b) => a.path.localeCompare(b.path));
}

/**
 * `source` with its comments blanked out, line count and column positions
 * kept, so a rule's pattern may appear in a comment freely and a reported line
 * number still points at the real source.
 */
export const blankComments = (source: string): string =>
  source
    .replace(/\/\*[\s\S]*?\*\//g, (match) => match.replace(/[^\n]/g, " "))
    .replace(/(^|[^:])\/\/.*$/gm, (match, prefix: string) => prefix.padEnd(match.length));

/** Each line of `checked` matching `pattern`, as `path:line: code`. */
export const violationsIn = (checked: SourceFile[], pattern: RegExp): string[] =>
  checked.flatMap(({ path, code }) =>
    code
      .split("\n")
      .map((line, index) => ({ line, number: index + 1 }))
      .filter(({ line }) => pattern.test(line))
      .map(({ line, number }) => `${path}:${number}: ${line.trim()}`),
  );

/** The text of a single repo-relative file. */
export function readSource(path: string): string {
  return readFileSync(join(ROOT, path), "utf8");
}

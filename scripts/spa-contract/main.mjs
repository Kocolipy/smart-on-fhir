// Runs the SPA–API contract check (./check.mjs) against the real tree:
//
//   node scripts/spa-contract/main.mjs <frontend dir> <openapi.yaml>
//
// It evaluates the SPA's modules with the frontend's own Vite and config, so
// `@/` and every import resolve exactly as they do in the app, and parses the
// document with the frontend's pinned js-yaml. Both come from
// frontend/node_modules: run `npm ci` in frontend/ first. Exits 1, listing
// every mismatch, when the two disagree. `make verify-contract` runs it.

import { readdir, readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { declaredNames, findMismatches, VOCABULARIES } from "./check.mjs";

const [frontendDir, documentPath] = process.argv
  .slice(2)
  .map((arg) => path.resolve(arg));
if (!frontendDir || !documentPath) {
  console.error("usage: main.mjs <frontend dir> <openapi.yaml>");
  process.exit(2);
}

const fromFrontend = createRequire(path.join(frontendDir, "package.json"));
const importFromFrontend = async (name) =>
  import(pathToFileURL(fromFrontend.resolve(name)).href);

/** Every production source under `src/`, relative to the frontend, with its text. */
async function productionSources() {
  const entries = await readdir(path.join(frontendDir, "src"), {
    recursive: true,
  });
  const files = entries
    .map((entry) => path.posix.join("src", entry.split(path.sep).join("/")))
    .filter(
      (file) =>
        /\.tsx?$/.test(file) && !/\.(test|testHelpers|d)\.tsx?$/.test(file),
    );
  return Promise.all(
    files.map(async (file) => [
      file,
      await readFile(path.join(frontendDir, file), "utf8"),
    ]),
  );
}

const declared = Object.fromEntries(
  (await productionSources())
    .map(([file, source]) => [file, declaredNames(source)])
    .filter(([, names]) => names.length > 0),
);
const toLoad = [
  ...new Set([
    ...VOCABULARIES.map(({ module }) => module),
    ...Object.keys(declared),
  ]),
];

const { createServer } = await importFromFrontend("vite");
const server = await createServer({
  root: frontendDir,
  configFile: path.join(frontendDir, "vite.config.ts"),
  logLevel: "error",
  appType: "custom",
  server: { middlewareMode: true, hmr: false, watch: null },
  // Nothing here runs in a browser, so there is nothing to pre-bundle.
  optimizeDeps: { noDiscovery: true, entries: [] },
});
let modules;
try {
  modules = Object.fromEntries(
    await Promise.all(
      toLoad.map(async (file) => [
        file,
        await server.ssrLoadModule(`/${file}`),
      ]),
    ),
  );
} finally {
  await server.close();
}

const { load } = await importFromFrontend("js-yaml");
const doc = load(await readFile(documentPath, "utf8"));

const findings = findMismatches({ modules, declared }, doc);
const declarationCount = Object.values(declared).flat().length;
if (findings.length > 0) {
  console.error(`The SPA disagrees with ${documentPath}:`);
  for (const finding of findings) console.error(`  - ${finding}`);
  process.exit(1);
}
console.log(
  `SPA–API contract holds: ${VOCABULARIES.length} vocabularies and ${declarationCount} request declarations agree with ${path.basename(documentPath)}`,
);

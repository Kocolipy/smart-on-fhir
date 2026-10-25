// `node write-env-block.mjs <env file> NAME=value...`: scripts/fhircast-up.sh's
// way to keep the managed block in backend/.env (env-block.mjs). Prints
// ENV_BLOCK_CHANGED=true|false, whether the file now differs from before — the
// backend reads .env at start, so a change means a restart — and
// ENV_BLOCK_KEY_GENERATED=true|false. The file is written only when it changes,
// in place, so its mode (a .env is private to its owner) is kept.
import { readFileSync, writeFileSync } from "node:fs";

import { manageEnvBlock } from "./env-block.mjs";

const [file, ...assignments] = process.argv.slice(2);
if (!file) {
  console.error("usage: write-env-block.mjs <env file> NAME=value...");
  process.exit(2);
}
const values = Object.fromEntries(
  assignments.map((assignment) => {
    const at = assignment.indexOf("=");
    if (at < 1) {
      console.error(`write-env-block: "${assignment}" is not NAME=value`);
      process.exit(2);
    }
    return [assignment.slice(0, at), assignment.slice(at + 1)];
  }),
);

try {
  const { text, changed, keyGenerated } = manageEnvBlock(
    readFileSync(file, "utf8"),
    values,
  );
  if (changed) writeFileSync(file, text);
  console.log(`ENV_BLOCK_CHANGED=${changed}`);
  console.log(`ENV_BLOCK_KEY_GENERATED=${keyGenerated}`);
} catch (error) {
  console.error(`write-env-block: ${error.message}`);
  process.exit(1);
}

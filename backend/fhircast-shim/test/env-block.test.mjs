// The managed block `make fhircast-up` keeps in backend/.env, through the one
// function that rewrites it: .env text in, .env text out. Nothing here touches
// a file or Medplum; the last cases hand the result to bash, the way
// scripts/lib.sh's load_backend_env reads the file, to hold the quoting to it.
import assert from "node:assert/strict";
import { createPrivateKey } from "node:crypto";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it } from "node:test";

import {
  BLOCK_BEGIN,
  BLOCK_END,
  generateClientKey,
  manageEnvBlock,
} from "../src/env-block.mjs";

const VALUES = {
  SPRING_PROFILES_ACTIVE: "local",
  APP_EPIC_ENABLED: "true",
  APP_EPIC_FHIR_BASE: "http://127.0.0.1:8103/fhir/R4",
  APP_EPIC_OAUTH_ISSUER: "http://127.0.0.1:8103",
  APP_EPIC_CLIENT_ID: "6d6ba1ff-0000-4000-8000-000000000001",
  APP_EPIC_REDIRECT_URI: "http://localhost:5173/api/auth/epic/callback",
  APP_DEV_FIXTURES_ENABLED: "true",
  APP_DEV_FIXTURES_EPIC_CLINICIAN: "af74a102-ebaf-46ef-b445-4f72157c473f",
  APP_DEV_FIXTURES_EPIC_CLINICIAN_DISPLAY_NAME: "Dr. Local Clinician",
};

/** A key as generateClientKey hands it over: one line, its breaks a literal backslash-n. */
const PEM =
  "-----BEGIN PRIVATE KEY-----\\nAAAA\\nBBBB\\n-----END PRIVATE KEY-----";

/** A key source that counts how often it is asked, to show reuse. */
function keySource() {
  const source = {
    calls: 0,
    newKey() {
      source.calls += 1;
      return { key: PEM, keyId: `local-${source.calls}` };
    },
  };
  return source;
}

const EXISTING = [
  "# my settings",
  "DATABASE_URL=jdbc:sqlserver://localhost:1433;databaseName=app",
  "APP_BOOTSTRAP_PASSWORD='P@ss w0rd'",
  "",
  "APP_EPIC_ENABLED=false",
  "# APP_EPIC_CLIENT_KEY=",
  "",
].join("\n");

describe("manageEnvBlock", () => {
  it("appends the block to a file that has none, leaving every line above it alone", () => {
    const { text, changed } = manageEnvBlock("A=1\nB=2\n", VALUES, keySource());

    assert.equal(changed, true);
    assert.ok(text.startsWith("A=1\nB=2\n\n" + BLOCK_BEGIN + "\n"), text);
    assert.ok(text.endsWith(BLOCK_END + "\n"), text);
  });

  it("holds each managed setting in the block, in the documented order", () => {
    const { text } = manageEnvBlock("", VALUES, keySource());

    const keys = text
      .split("\n")
      .filter((line) => /^[A-Z_]+=/.test(line))
      .map((line) => line.slice(0, line.indexOf("=")));
    assert.deepEqual(keys, [
      "SPRING_PROFILES_ACTIVE",
      "APP_EPIC_ENABLED",
      "APP_EPIC_FHIR_BASE",
      "APP_EPIC_OAUTH_ISSUER",
      "APP_EPIC_CLIENT_ID",
      "APP_EPIC_REDIRECT_URI",
      "APP_EPIC_CLIENT_KEY",
      "APP_EPIC_CLIENT_KEY_ID",
      "APP_DEV_FIXTURES_ENABLED",
      "APP_DEV_FIXTURES_EPIC_CLINICIAN",
      "APP_DEV_FIXTURES_EPIC_CLINICIAN_DISPLAY_NAME",
    ]);
  });

  it("starts the block on its own line when the file's last line has no newline", () => {
    const { text } = manageEnvBlock("A=1", VALUES, keySource());

    assert.ok(text.startsWith("A=1\n\n" + BLOCK_BEGIN + "\n"), text);
  });

  it("rewrites the block where it stands, with the text around it untouched", () => {
    const source = keySource();
    const block = manageEnvBlock("", VALUES, source).text;
    const stale = block.replace(
      "SPRING_PROFILES_ACTIVE=local\n",
      "SPRING_PROFILES_ACTIVE=an-old-profile\n",
    );
    const between = `top=1\n# a comment between\n\n${stale}\nbottom=2\n`;

    const { text, changed } = manageEnvBlock(
      between,
      { ...VALUES, APP_EPIC_CLIENT_ID: "a-new-client" },
      source,
    );

    assert.equal(changed, true);
    assert.ok(
      text.startsWith("top=1\n# a comment between\n\n" + BLOCK_BEGIN),
      text,
    );
    assert.ok(text.includes("APP_EPIC_CLIENT_ID=a-new-client\n"), text);
    assert.ok(text.includes("SPRING_PROFILES_ACTIVE=local\n"), text);
    assert.ok(text.endsWith(BLOCK_END + "\n\nbottom=2\n"), text);
    assert.equal(text.split(BLOCK_BEGIN).length, 2);
  });

  it("changes nothing on a second run with the same values", () => {
    const source = keySource();
    const first = manageEnvBlock(EXISTING, VALUES, source).text;

    const second = manageEnvBlock(first, VALUES, source);

    assert.equal(second.changed, false);
    assert.equal(second.text, first);
  });

  it("reuses the key the block holds rather than generating another", () => {
    const source = keySource();
    const first = manageEnvBlock("", VALUES, source).text;

    const second = manageEnvBlock(
      first,
      { ...VALUES, APP_EPIC_CLIENT_ID: "x" },
      source,
    );

    assert.equal(source.calls, 1);
    assert.equal(second.keyGenerated, false);
    assert.ok(
      second.text.includes("APP_EPIC_CLIENT_KEY_ID=local-1\n"),
      second.text,
    );
  });

  it("reports a key generated on the first run", () => {
    const { keyGenerated } = manageEnvBlock("", VALUES, keySource());

    assert.equal(keyGenerated, true);
  });

  it("generates a key again when the block holds none", () => {
    const source = keySource();
    const keyless = manageEnvBlock("", VALUES, source)
      .text.split("\n")
      .filter((line) => !line.startsWith("APP_EPIC_CLIENT_KEY"))
      .join("\n");

    const { text } = manageEnvBlock(keyless, VALUES, source);

    assert.equal(source.calls, 2);
    assert.ok(text.includes("APP_EPIC_CLIENT_KEY_ID=local-2\n"), text);
  });

  it("comments out a managed setting outside the block, pointing at the block", () => {
    const { text } = manageEnvBlock(EXISTING, VALUES, keySource());

    const lines = text.split("\n");
    const commented = lines.find((line) =>
      line.includes("APP_EPIC_ENABLED=false"),
    );
    assert.ok(commented.startsWith("# "), commented);
    assert.ok(commented.includes("fhircast-up (managed)"), commented);
    assert.ok(!lines.includes("APP_EPIC_ENABLED=false"));
  });

  it("comments out an `export`ed or indented managed setting too", () => {
    const { text } = manageEnvBlock(
      "export APP_EPIC_CLIENT_ID=old\n  SPRING_PROFILES_ACTIVE=local\n",
      VALUES,
      keySource(),
    );

    const outside = text.slice(0, text.indexOf(BLOCK_BEGIN));
    assert.ok(
      !/^\s*(export\s+)?(APP_EPIC_CLIENT_ID|SPRING_PROFILES_ACTIVE)=/m.test(
        outside,
      ),
      outside,
    );
  });

  it("leaves every unrelated line byte for byte, similar names and comments included", () => {
    const unrelated = [
      "# my settings",
      "DATABASE_URL=jdbc:sqlserver://localhost:1433;databaseName=app",
      "APP_BOOTSTRAP_PASSWORD='P@ss w0rd'",
      "APP_EPIC_CONNECT_TIMEOUT=2s",
      "APP_EPIC_CLIENT_NEXT_KEY=keep",
      "MY_APP_EPIC_ENABLED=keep",
      "# APP_EPIC_CLIENT_KEY=",
      "",
      "   ",
      "trailing=space ",
    ];

    const { text } = manageEnvBlock(
      unrelated.join("\n") + "\n",
      VALUES,
      keySource(),
    );

    assert.ok(text.startsWith(unrelated.join("\n") + "\n"), text);
  });

  it("keeps a CRLF file's own line endings outside the block", () => {
    const { text } = manageEnvBlock(
      "A=1\r\nAPP_EPIC_ENABLED=false\r\nB=2\r\n",
      VALUES,
      keySource(),
    );

    assert.ok(text.startsWith("A=1\r\n# "), text);
    assert.ok(text.includes("B=2\r\n"), text);
  });

  it("refuses a file whose block is never closed, rather than guess where it ends", () => {
    assert.throws(
      () => manageEnvBlock(`A=1\n${BLOCK_BEGIN}\nB=2\n`, VALUES, keySource()),
      /never closed/,
    );
  });

  it("refuses a managed setting it was handed no value for", () => {
    const { APP_EPIC_CLIENT_ID: _omitted, ...incomplete } = VALUES;

    assert.throws(
      () => manageEnvBlock("", incomplete, keySource()),
      /APP_EPIC_CLIENT_ID/,
    );
  });
});

describe("the block as bash reads it", () => {
  /** What `set -a; source file` leaves in the environment, as lib.sh's load_backend_env does. */
  function sourced(text) {
    const dir = mkdtempSync(join(tmpdir(), "env-block-"));
    try {
      const file = join(dir, ".env");
      writeFileSync(file, text);
      const run = spawnSync(
        "bash",
        [
          "-c",
          'set -a; source "$1"; set +a; node -e "console.log(JSON.stringify(process.env))"',
          "bash",
          file,
        ],
        { encoding: "utf8", env: { PATH: process.env.PATH } },
      );
      assert.equal(run.status, 0, run.stderr);
      return JSON.parse(run.stdout);
    } finally {
      rmSync(dir, { recursive: true });
    }
  }

  it("hands bash each value as written, the key on one line with its breaks escaped", () => {
    const { text } = manageEnvBlock("", VALUES, keySource());

    const env = sourced(text);

    assert.deepEqual(
      Object.fromEntries(Object.keys(VALUES).map((name) => [name, env[name]])),
      VALUES,
    );
    assert.equal(env.APP_EPIC_CLIENT_KEY, PEM);
  });

  it("generates an EC P-384 key the backend can read once its escapes are undone", () => {
    const { key, keyId } = generateClientKey();

    assert.ok(!key.includes("\n"), "one line");
    const pem = key.replaceAll("\\n", "\n");
    assert.equal(
      createPrivateKey(pem).asymmetricKeyDetails.namedCurve,
      "secp384r1",
    );
    assert.match(keyId, /^local-\d+$/);
  });

  it("writes a value with a space or a quote so that bash reads it back unchanged", () => {
    const awkward = { ...VALUES, APP_EPIC_CLIENT_ID: "it's a $HOME `value`" };
    const { text } = manageEnvBlock("", awkward, keySource());

    assert.equal(sourced(text).APP_EPIC_CLIENT_ID, "it's a $HOME `value`");
  });
});

describe("write-env-block.mjs", () => {
  const CLI = fileURLToPath(new URL("../src/write-env-block.mjs", import.meta.url));
  const ARGS = Object.entries(VALUES).map(([name, value]) => `${name}=${value}`);

  /** Runs the entry point over a .env holding `text`; hands back its output and the file after. */
  function run(text, args = ARGS) {
    const dir = mkdtempSync(join(tmpdir(), "env-block-"));
    try {
      const file = join(dir, ".env");
      writeFileSync(file, text, { mode: 0o600 });
      const result = spawnSync("node", [CLI, file, ...args], { encoding: "utf8" });
      return { ...result, after: readFileSync(file, "utf8"), mode: statSync(file).mode & 0o777 };
    } finally {
      rmSync(dir, { recursive: true });
    }
  }

  it("reports a first run changed, wrote a key, and kept the file private", () => {
    const first = run("A=1\n");

    assert.equal(first.status, 0, first.stderr);
    assert.equal(first.stdout, "ENV_BLOCK_CHANGED=true\nENV_BLOCK_KEY_GENERATED=true\n");
    assert.ok(first.after.includes(BLOCK_BEGIN));
    assert.equal(first.mode, 0o600);
  });

  it("reports a second run unchanged, with the key the first wrote", () => {
    const second = run(run("A=1\n").after);

    assert.equal(second.stdout, "ENV_BLOCK_CHANGED=false\nENV_BLOCK_KEY_GENERATED=false\n");
  });

  it("fails on a value that is not NAME=value, writing nothing", () => {
    const bad = run("A=1\n", ["nonsense"]);

    assert.equal(bad.status, 2);
    assert.equal(bad.after, "A=1\n");
  });

  it("fails naming the missing setting when it is handed too few", () => {
    const short = run("A=1\n", ARGS.slice(1));

    assert.equal(short.status, 1);
    assert.match(short.stderr, /no value for SPRING_PROFILES_ACTIVE/);
    assert.equal(short.after, "A=1\n");
  });
});

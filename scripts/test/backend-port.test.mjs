// The backend's port as the root scripts and the local Epic stand-in read it:
// SERVER_PORT, which application.yaml's `server.port` reads too, and 8080 when
// it is unset or blank. `make integration-test` runs this file first; nothing
// here starts a server or binds a port.
//
//   node --test scripts/test/*.test.mjs
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const LIB = fileURLToPath(new URL("../lib.sh", import.meta.url));
const COMPOSE_FILE = fileURLToPath(
  new URL("../../backend/compose.yaml", import.meta.url),
);

/** An environment holding PATH, HOME and `extra` alone, so the caller's SERVER_PORT never leaks in. */
function isolatedEnv(extra) {
  return { PATH: process.env.PATH, HOME: process.env.HOME, ...extra };
}

/** What lib.sh's backend_port prints under `extra`. */
function backendPort(extra) {
  return execFileSync("bash", ["-c", 'source "$0" && backend_port', LIB], {
    env: isolatedEnv(extra),
    encoding: "utf8",
  }).trim();
}

describe("the scripts' backend port", () => {
  it("is 8080 when SERVER_PORT is absent", () => {
    assert.equal(backendPort({}), "8080");
  });

  it("is 8080 when SERVER_PORT is blank", () => {
    assert.equal(backendPort({ SERVER_PORT: "" }), "8080");
  });

  it("is the port SERVER_PORT names", () => {
    assert.equal(backendPort({ SERVER_PORT: "8091" }), "8091");
  });
});

// An empty env file in place of backend/.env, which compose would otherwise
// read for interpolation, so a developer's own SERVER_PORT cannot decide these.
const scratch = mkdtempSync(join(tmpdir(), "backend-port-"));
const EMPTY_ENV_FILE = join(scratch, "empty.env");
writeFileSync(EMPTY_ENV_FILE, "");
after(() => rmSync(scratch, { recursive: true, force: true }));

/** The JWKS URI the Medplum seed registers this app's ClientApplication with, as compose resolves it. */
function seededJwksUri(extra) {
  const config = execFileSync(
    "docker",
    [
      "compose",
      "--env-file",
      EMPTY_ENV_FILE,
      "-f",
      COMPOSE_FILE,
      "--profile",
      "fhircast",
      "--profile",
      "init",
      "config",
      "--format",
      "json",
    ],
    {
      env: isolatedEnv(extra),
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    },
  );
  return JSON.parse(config).services["medplum-init"].environment
    .SEED_CLIENT_JWKS_URI;
}

describe("the local Epic stand-in's registration of our JWKS", () => {
  it("names the backend on 8080 when SERVER_PORT is absent", () => {
    assert.equal(
      seededJwksUri({}),
      "http://host.docker.internal:8080/api/auth/epic/jwks.json",
    );
  });

  it("names the backend on the port SERVER_PORT names", () => {
    assert.equal(
      seededJwksUri({ SERVER_PORT: "8091" }),
      "http://host.docker.internal:8091/api/auth/epic/jwks.json",
    );
  });
});

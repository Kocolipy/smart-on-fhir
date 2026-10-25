// The seed's Patients, through what the seed does to Medplum. Medplum is the
// system boundary, so each test seeds a fake Medplum on loopback: it answers
// the super admin's login, and keeps the Patients it is sent, searching them
// by identifier within a project, as Medplum's FHIR API does.
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import { afterEach, beforeEach, describe, it } from "node:test";

import { medplumAdmin } from "../src/medplum.mjs";
import { seedPatients } from "../src/seed-patients.mjs";

const ADMIN = { email: "admin@example.com", password: "admin-password" };
const ADMIN_TOKEN = "the-super-admins-medplum-token";
const PROJECT = { id: "project-1" };

/** A fake Medplum holding `patients`, the Patients it was sent, with their ids. */
function fakeMedplum() {
  const patients = [];
  const server = createServer(async (req, res) => {
    const body = await readBody(req);
    const reply = (status, json) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(json));
    };
    const url = new URL(req.url, "http://medplum");
    if (req.method === "POST" && url.pathname === "/auth/login") {
      return reply(200, { login: "admin-login", code: "admin-code" });
    }
    if (req.method === "POST" && url.pathname === "/oauth2/token") {
      return reply(200, { access_token: ADMIN_TOKEN, expires_in: 3600 });
    }
    if (req.headers.authorization !== `Bearer ${ADMIN_TOKEN}`) {
      return reply(401, {});
    }
    if (req.method === "GET" && url.pathname === "/fhir/R4/Patient") {
      const identifier = url.searchParams.get("identifier");
      const project = url.searchParams.get("_project");
      const found = patients.filter(
        (patient) =>
          patient.meta?.project === project &&
          patient.identifier?.some(({ value }) => value === identifier),
      );
      return reply(200, {
        resourceType: "Bundle",
        type: "searchset",
        entry: found.map((resource) => ({ resource })),
      });
    }
    if (req.method === "POST" && url.pathname === "/fhir/R4/Patient") {
      const created = { ...JSON.parse(body), id: randomUUID() };
      patients.push(created);
      return reply(201, created);
    }
    reply(404, {});
  });
  server.patients = patients;
  return server;
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
  });
}

function listen(server) {
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () =>
      resolve(`http://127.0.0.1:${server.address().port}/`),
    );
  });
}

/** Each Patient Medplum holds, as its Epic id beside its name. */
const held = (medplum) =>
  medplum.patients.map(({ identifier, name }) => ({
    epicId: identifier[0].value,
    name: name[0].text,
  }));

describe("the seed's Patients", () => {
  let medplum;
  let admin;

  beforeEach(async () => {
    medplum = fakeMedplum();
    admin = medplumAdmin(await listen(medplum), ADMIN);
  });

  afterEach(() => {
    medplum.close();
  });

  it("are the four backend fixture patients, each under its backend Epic id", async () => {
    await seedPatients(admin, PROJECT);

    assert.deepEqual(held(medplum), [
      { epicId: "eq081-VQEgP8drUUqCWzHfw3", name: "Tan Wei Ming" },
      {
        epicId: "erXuFYUfucBZaryVksYEcMg3",
        name: "Siti Nurhaliza binte Ahmad",
      },
      { epicId: "e0w0LEDCYtfckT6N.CkJKCw3", name: "Kumaravel" },
      { epicId: "egqBHVfQlt4Bw3XGXoxVxHg3", name: "Marcus Anthony de Souza" },
    ]);
  });

  it("carry each name split as the backend fixture splits it", async () => {
    await seedPatients(admin, PROJECT);

    assert.deepEqual(
      medplum.patients.map(({ name }) => name),
      [
        [{ text: "Tan Wei Ming", family: "Tan", given: ["Wei", "Ming"] }],
        [{ text: "Siti Nurhaliza binte Ahmad" }],
        [{ text: "Kumaravel", given: ["Kumaravel"] }],
        [
          {
            text: "Marcus Anthony de Souza",
            family: "de Souza",
            given: ["Marcus", "Anthony"],
          },
        ],
      ],
    );
  });

  it("are made in the local EHR's project", async () => {
    await seedPatients(admin, PROJECT);

    assert.deepEqual(
      medplum.patients.map(({ meta }) => meta.project),
      ["project-1", "project-1", "project-1", "project-1"],
    );
  });

  it("are made once, however often the seed runs", async () => {
    await seedPatients(admin, PROJECT);

    await seedPatients(admin, PROJECT);

    assert.equal(medplum.patients.length, 4);
  });

  it("are made in a project that holds none of them, though another does", async () => {
    await seedPatients(admin, { id: "project-0" });

    await seedPatients(admin, PROJECT);

    assert.equal(
      medplum.patients.filter(({ meta }) => meta.project === "project-1")
        .length,
      4,
    );
  });

  it("name Tan Wei Ming, by his Epic id, for the default launch", async () => {
    const { launchPatient } = await seedPatients(admin, PROJECT);

    assert.equal(launchPatient.epicId, "eq081-VQEgP8drUUqCWzHfw3");
  });

  it("name for the default launch the Patient Medplum already holds, on a later run", async () => {
    const { launchPatient: first } = await seedPatients(admin, PROJECT);

    const { launchPatient: again } = await seedPatients(admin, PROJECT);

    assert.equal(again.id, first.id);
  });
});

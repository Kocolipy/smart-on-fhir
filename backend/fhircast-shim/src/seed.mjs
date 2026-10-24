// Seeds Medplum for this app, idempotently, through Medplum's own REST API as
// its super admin: the compose `medplum-init` one-shot runs it after Medplum
// is healthy, every time, so a changed setting below lands on the next run.
//
//   1. An RS256 signing key. Medplum 5 creates an ES384 one on first start, but
//      the backend verifies an `id_token` as RS256 only (ADR 0012), so the seed
//      adds an RS256 key, generated here and never written anywhere else, and
//      retires every other. Medplum reads its keys at startup alone, so the
//      run that changes them asks for a restart (MEDPLUM_RESTART_REQUIRED).
//   2. A project for the local EHR, apart from the super admin's own.
//   3. The clinician, a Practitioner with a Medplum login in that project: the
//      `fhirUser` an EHR launch signs in, and so the `userName` of the User the
//      backend links it to (ADR 0012 D2, D3).
//   4. This app's ClientApplication in that project: `private_key_jwt` against
//      the backend's JWKS, PKCE required, its redirect and launch URIs.
//
// It prints what a caller needs as KEY=value lines (scripts/lib.sh reads
// them), and nothing secret. Every value below comes from the environment
// backend/compose.yaml sets; none has a default.
import { generateKeyPairSync } from "node:crypto";

import { required } from "./env.mjs";
import { medplumAdmin } from "./medplum.mjs";

const settings = {
  medplumUrl: required("SEED_MEDPLUM_URL"),
  admin: {
    email: required("SEED_MEDPLUM_ADMIN_EMAIL"),
    password: required("SEED_MEDPLUM_ADMIN_PASSWORD"),
  },
  projectName: required("SEED_PROJECT_NAME"),
  clinician: {
    email: required("SEED_CLINICIAN_EMAIL"),
    password: required("SEED_CLINICIAN_PASSWORD"),
    firstName: required("SEED_CLINICIAN_FIRST_NAME"),
    lastName: required("SEED_CLINICIAN_LAST_NAME"),
  },
  client: {
    name: required("SEED_CLIENT_NAME"),
    jwksUri: required("SEED_CLIENT_JWKS_URI"),
    redirectUris: required("SEED_CLIENT_REDIRECT_URIS").split(","),
    launchUri: required("SEED_CLIENT_LAUNCH_URI"),
  },
};

const medplum = medplumAdmin(settings.medplumUrl, settings.admin);

/** Every resource a search answers, across pages. */
async function search(query) {
  const found = [];
  let next = `/fhir/R4/${query}`;
  while (next) {
    const bundle = await medplum.request(next);
    found.push(...(bundle.entry ?? []).map(({ resource }) => resource));
    const link = bundle.link?.find(({ relation }) => relation === "next")?.url;
    next = link ? new URL(link).pathname + new URL(link).search : undefined;
  }
  return found;
}

/** Step 1: whether Medplum signs with one active RS256 key alone, made so if not. */
async function ensureRs256SigningKey() {
  const active = await search("JsonWebKey?active=true");
  if (active.length === 1 && active[0].alg === "RS256") return false;
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const jwk = privateKey.export({ format: "jwk" });
  await medplum.request("/fhir/R4/JsonWebKey", {
    method: "POST",
    body: { resourceType: "JsonWebKey", active: true, alg: "RS256", ...jwk },
  });
  for (const key of active) {
    await medplum.request(`/fhir/R4/JsonWebKey/${key.id}`, {
      method: "PUT",
      body: { ...key, active: false },
    });
  }
  return true;
}

/** Step 2. */
async function ensureProject() {
  const [existing] = await search(
    `Project?name=${encodeURIComponent(settings.projectName)}`,
  );
  if (existing) return existing;
  return medplum.request("/fhir/R4/Project", {
    method: "POST",
    body: {
      resourceType: "Project",
      name: settings.projectName,
      strictMode: true,
    },
  });
}

/** Step 3: the clinician's Practitioner, invited once and found by email after. */
async function ensureClinician(project) {
  const byEmail = `Practitioner?email=${encodeURIComponent(settings.clinician.email)}&_project=${project.id}`;
  const [existing] = await search(byEmail);
  if (existing) return existing;
  await medplum.request(`/admin/projects/${project.id}/invite`, {
    method: "POST",
    body: {
      resourceType: "Practitioner",
      firstName: settings.clinician.firstName,
      lastName: settings.clinician.lastName,
      email: settings.clinician.email,
      password: settings.clinician.password,
      sendEmail: false,
    },
  });
  const [invited] = await search(byEmail);
  if (!invited) throw new Error("the invited clinician has no Practitioner");
  return invited;
}

/** Step 4: created once, then brought back to these settings on every run. */
async function ensureClient(project) {
  const wanted = {
    name: settings.client.name,
    description:
      "smart-on-fhir, as Epic Login registers it (backend/docs/configuration/epic-login.md)",
    jwksUri: settings.client.jwksUri,
    redirectUris: settings.client.redirectUris,
    launchUri: settings.client.launchUri,
    pkceOptional: false,
  };
  const [existing, ...extras] = await search(
    `ClientApplication?name=${encodeURIComponent(settings.client.name)}&_project=${project.id}`,
  );
  // One client by this name, should an interrupted run ever have left two.
  for (const extra of extras) {
    await medplum.request(`/fhir/R4/ClientApplication/${extra.id}`, {
      method: "DELETE",
    });
  }
  if (!existing) {
    return medplum.request(`/admin/projects/${project.id}/client`, {
      method: "POST",
      body: wanted,
    });
  }
  return medplum.request(`/fhir/R4/ClientApplication/${existing.id}`, {
    method: "PUT",
    body: { ...existing, ...wanted },
  });
}

const restartRequired = await ensureRs256SigningKey();
const project = await ensureProject();
const clinician = await ensureClinician(project);
const client = await ensureClient(project);

console.log(`MEDPLUM_RESTART_REQUIRED=${restartRequired}`);
console.log(`MEDPLUM_CLIENT_ID=${client.id}`);
console.log(`MEDPLUM_PRACTITIONER_ID=${clinician.id}`);

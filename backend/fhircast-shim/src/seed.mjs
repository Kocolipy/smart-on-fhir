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
//   3. The clinicians, each a Practitioner with a Medplum login in that
//      project: the `fhirUser` an EHR launch signs in, and so the `userName` of
//      the User the backend links it to (ADR 0012 D2, D3). The clinician, whom
//      `make fhircast-up` hands a developer, and one per Epic Login E2E case
//      that signs in (frontend/test/e2e/epic-launch.spec.ts), each at the
//      Practitioner id compose.yaml gives it, so the id is the same on every
//      volume and every run. Medplum takes an id a client chooses only when
//      that is a UUID, so those are UUIDs. Every clinician signs in with the
//      clinician's password.
//   4. Tan Wei Ming, a Patient in that project carrying his Epic id, one of the
//      backend dev profile's patient fixtures: a launch names him by that id,
//      which Medplum's token response then puts in `patient`.
//   5. This app's ClientApplication in that project: `private_key_jwt` against
//      the backend's JWKS, PKCE required, its redirect and launch URIs.
//   6. A SmartAppLaunch for Tan Wei Ming, the one `make fhircast-up` prints a
//      launch URL for: it names his Patient reference and his Epic id, as
//      frontend/test/e2e/fhircast.helpers.ts' createLaunch does. Medplum does
//      not consume a launch on use (backend/docs/configuration/epic-login.md
//      records how that was found), so one is made once and found by its tag on
//      every run after: the URL stays the same, and a bookmark keeps working.
//      The tag is what tells it from the launches the E2E specs make for the
//      same Patient, since Medplum has no `patient` search parameter on it.
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
    id: required("SEED_CLINICIAN_ID"),
    email: required("SEED_CLINICIAN_EMAIL"),
    password: required("SEED_CLINICIAN_PASSWORD"),
    firstName: required("SEED_CLINICIAN_FIRST_NAME"),
    lastName: required("SEED_CLINICIAN_LAST_NAME"),
  },
  epicLoginClinicians: required("SEED_EPIC_LOGIN_CLINICIANS")
    .split(",")
    .map((pair) => {
      const [email, id, ...rest] = pair.split("=");
      if (!email || !id || rest.length > 0) {
        throw new Error(
          `SEED_EPIC_LOGIN_CLINICIANS holds "${pair}", not email=Practitioner id`,
        );
      }
      return { email, id };
    }),
  patient: {
    epicId: required("SEED_PATIENT_EPIC_ID"),
    family: required("SEED_PATIENT_FAMILY"),
    given: required("SEED_PATIENT_GIVEN").split(" "),
  },
  // Not configuration: what marks the seed's launch among the others.
  launchTag: {
    system: "urn:smart-on-fhir:fhircast-up",
    code: "default-patient-launch",
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

/**
 * Step 3: a clinician's Practitioner at its own id, then a sign-in invited
 * with that Practitioner as its profile, each made once. A volume a seed
 * filled before the ids were fixed holds the sign-in already, under a
 * Practitioner Medplum assigned, so the invite is refused as a member already:
 * that volume is removed and seeded afresh (epic-login.md, "Local Epic stand-in").
 */
async function ensureClinician(
  project,
  { id, email, password, firstName, lastName },
) {
  const profile = { reference: `Practitioner/${id}` };
  const [practitioner] = await search(`Practitioner?_id=${id}`);
  if (!practitioner) {
    await medplum.request(`/fhir/R4/${profile.reference}`, {
      method: "PUT",
      body: {
        resourceType: "Practitioner",
        id,
        meta: { project: project.id },
        name: [{ given: [firstName], family: lastName }],
        telecom: [{ system: "email", use: "work", value: email }],
      },
    });
  }
  const [membership] = await search(
    `ProjectMembership?profile=${profile.reference}&project=Project/${project.id}`,
  );
  if (membership) return;
  await medplum.request(`/admin/projects/${project.id}/invite`, {
    method: "POST",
    body: {
      resourceType: "Practitioner",
      firstName,
      lastName,
      email,
      password,
      sendEmail: false,
      membership: { profile },
    },
  });
}

/** Step 3, Epic Login's: one clinician per case, named for its email's local part. */
async function ensureEpicLoginClinicians(project) {
  for (const { email, id } of settings.epicLoginClinicians) {
    await ensureClinician(project, {
      id,
      email,
      password: settings.clinician.password,
      firstName: "Epic Login",
      lastName: email.slice(0, email.indexOf("@")),
    });
  }
}

/** Step 4: created once in the project, and found by his Epic id after. */
async function ensurePatient(project) {
  const [existing] = await search(
    `Patient?identifier=${encodeURIComponent(settings.patient.epicId)}&_project=${project.id}`,
  );
  if (existing) return existing;
  return medplum.request("/fhir/R4/Patient", {
    method: "POST",
    body: {
      resourceType: "Patient",
      meta: { project: project.id },
      identifier: [{ value: settings.patient.epicId }],
      name: [
        { family: settings.patient.family, given: settings.patient.given },
      ],
    },
  });
}

/**
 * Step 6: the launch, found by its tag. One that names another Patient than
 * the current one, as after the Patient was deleted and made again, is pointed
 * at the current one rather than left stale.
 */
async function ensureLaunch(patient) {
  const wanted = {
    reference: `Patient/${patient.id}`,
    identifier: { value: settings.patient.epicId },
  };
  const tag = settings.launchTag;
  const [existing, ...extras] = await search(
    `SmartAppLaunch?_tag=${encodeURIComponent(`${tag.system}|${tag.code}`)}`,
  );
  // One launch by this tag, should an interrupted run ever have left two.
  for (const extra of extras) {
    await medplum.request(`/fhir/R4/SmartAppLaunch/${extra.id}`, {
      method: "DELETE",
    });
  }
  if (!existing) {
    return medplum.request("/fhir/R4/SmartAppLaunch", {
      method: "POST",
      body: {
        resourceType: "SmartAppLaunch",
        meta: { tag: [tag] },
        patient: wanted,
      },
    });
  }
  if (
    existing.patient?.reference === wanted.reference &&
    existing.patient?.identifier?.value === wanted.identifier.value
  ) {
    return existing;
  }
  return medplum.request(`/fhir/R4/SmartAppLaunch/${existing.id}`, {
    method: "PUT",
    body: { ...existing, patient: wanted },
  });
}

/** Step 5: created once, then brought back to these settings on every run. */
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
await ensureClinician(project, settings.clinician);
await ensureEpicLoginClinicians(project);
const patient = await ensurePatient(project);
const client = await ensureClient(project);
const launch = await ensureLaunch(patient);

console.log(`MEDPLUM_RESTART_REQUIRED=${restartRequired}`);
console.log(`MEDPLUM_CLIENT_ID=${client.id}`);
console.log(`MEDPLUM_LAUNCH_ID=${launch.id}`);

// The seed's Patients (seed.mjs, step 4): the backend's four development
// patient fixtures (backend/.../patient/application/DevPatientFixtures.java),
// each a Patient in the local EHR's project carrying the Epic id its backend
// fixture is held under, so a launch or a FHIRcast event can name any of them
// and the Patient card finds it. Only the name is copied, split as the backend
// splits it; the rest of each Patient is the backend's alone.
//
// Not configuration: the backend's fixtures are fixed, so these are too.

/** The backend's fixtures, in its seeding order, each by its Epic id. */
const FIXTURE_PATIENTS = [
  {
    epicId: "eq081-VQEgP8drUUqCWzHfw3",
    name: { text: "Tan Wei Ming", family: "Tan", given: ["Wei", "Ming"] },
  },
  {
    epicId: "erXuFYUfucBZaryVksYEcMg3",
    name: { text: "Siti Nurhaliza binte Ahmad" },
  },
  {
    epicId: "e0w0LEDCYtfckT6N.CkJKCw3",
    name: { text: "Kumaravel", given: ["Kumaravel"] },
  },
  {
    epicId: "egqBHVfQlt4Bw3XGXoxVxHg3",
    name: {
      text: "Marcus Anthony de Souza",
      family: "de Souza",
      given: ["Marcus", "Anthony"],
    },
  },
];

/** Tan Wei Ming's: the Patient the default launch `make fhircast-up` prints names. */
const LAUNCH_PATIENT_EPIC_ID = "eq081-VQEgP8drUUqCWzHfw3";

/** One fixture, created once in the project and found by its Epic id after. */
async function ensurePatient(medplum, project, { epicId, name }) {
  const [existing] = await medplum.search(
    `Patient?identifier=${encodeURIComponent(epicId)}&_project=${project.id}`,
  );
  if (existing) return existing;
  return medplum.request("/fhir/R4/Patient", {
    method: "POST",
    body: {
      resourceType: "Patient",
      meta: { project: project.id },
      identifier: [{ value: epicId }],
      name: [name],
    },
  });
}

/**
 * Every fixture's Patient in `project`, each made once; answers the one the
 * default launch names, as the launch names it: its Medplum `id` and its
 * `epicId`.
 *
 * @param {ReturnType<import("./medplum.mjs").medplumAdmin>} medplum
 * @param {{id: string}} project
 * @returns {Promise<{launchPatient: {id: string, epicId: string}}>}
 */
export async function seedPatients(medplum, project) {
  let launchPatient;
  for (const fixture of FIXTURE_PATIENTS) {
    const { id } = await ensurePatient(medplum, project, fixture);
    if (fixture.epicId === LAUNCH_PATIENT_EPIC_ID) {
      launchPatient = { id, epicId: fixture.epicId };
    }
  }
  return { launchPatient };
}

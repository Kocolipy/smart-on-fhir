// `node publish.mjs <event> <patient id>`: scripts/fhircast-publish.sh's way to
// publish a FHIRcast event to the local Epic stand-in's hub, as the EHR does
// when the clinician opens or closes a chart. Every setting comes from the
// environment the script reads out of backend/compose.yaml.
//
// The topic is the clinician's: signing in to Medplum as the clinician with the
// `fhircast/` scopes makes its token response name the same `hub.topic` it named
// the app's launch. PUBLISH_TOPIC overrides it. The event is posted as Medplum's
// super admin, as frontend/test/e2e/fhircast.helpers.ts publishes, because the
// shim subscribes as the super admin and Medplum keeps a topic per project.
import { randomBytes, randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";

import { required } from "./env.mjs";

export const EVENTS = ["Patient-open", "Patient-close"];

/** FHIR's id grammar, which the FHIRcast card holds a Patient id to. */
const FHIR_ID = /^[A-Za-z0-9\-.]{1,64}$/;

const FHIRCAST_SCOPE =
  "openid fhircast/Patient-open.read fhircast/Patient-close.read";

/** The event and patient id the command line names, or an error saying what is wrong. */
export function parseArguments([event, patient, ...rest]) {
  if (rest.length > 0 || event === undefined || patient === undefined) {
    throw new Error(
      "usage: publish.mjs <Patient-open|Patient-close> <patient id>",
    );
  }
  if (!EVENTS.includes(event)) {
    throw new Error(
      `the event must be one of ${EVENTS.join(", ")}, not "${event}"`,
    );
  }
  if (!FHIR_ID.test(patient)) {
    throw new Error(
      `"${patient}" is not a FHIR id ([A-Za-z0-9-.], 1 to 64 characters)`,
    );
  }
  return { event, patient };
}

/** The FHIRcast STU3 Event Notification for `event` on Patient `patient`. */
export function notification({
  topic,
  event,
  patient,
  id = randomUUID(),
  now = new Date(),
}) {
  return {
    timestamp: now.toISOString(),
    id,
    event: {
      "hub.topic": topic,
      "hub.event": event,
      context: [
        { key: "patient", resource: { resourceType: "Patient", id: patient } },
      ],
    },
  };
}

/**
 * Signs `user` in to Medplum as Medplum's own client does (`POST /auth/login`
 * with a PKCE challenge, then the code at `/oauth2/token`), answering the
 * token response.
 */
async function signIn(medplumUrl, user, scope) {
  const verifier = randomBytes(32).toString("base64url");
  const login = await call(medplumUrl, "auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      ...user,
      scope,
      codeChallenge: verifier,
      codeChallengeMethod: "plain",
    }),
  });
  return call(medplumUrl, "oauth2/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code: login.code,
      code_verifier: verifier,
    }),
  });
}

async function call(medplumUrl, path, init) {
  const answer = await fetch(new URL(path, medplumUrl), init);
  const text = await answer.text();
  if (!answer.ok)
    throw new Error(`Medplum answered ${answer.status} on ${path}: ${text}`);
  return JSON.parse(text);
}

/** The topic Medplum names for `clinician`. */
async function clinicianTopic(medplumUrl, clinician) {
  const tokens = await signIn(medplumUrl, clinician, FHIRCAST_SCOPE);
  const topic = tokens["hub.topic"];
  if (typeof topic !== "string" || topic === "") {
    throw new Error(`Medplum named no hub.topic for ${clinician.email}`);
  }
  return topic;
}

async function main() {
  const { event, patient } = parseArguments(process.argv.slice(2));
  const medplumUrl = required("PUBLISH_MEDPLUM_URL");
  const topic =
    process.env.PUBLISH_TOPIC?.trim() ||
    (await clinicianTopic(medplumUrl, {
      email: required("PUBLISH_CLINICIAN_EMAIL"),
      password: required("PUBLISH_CLINICIAN_PASSWORD"),
    }));
  const admin = await signIn(
    medplumUrl,
    {
      email: required("PUBLISH_ADMIN_EMAIL"),
      password: required("PUBLISH_ADMIN_PASSWORD"),
    },
    "openid",
  );
  const answer = await fetch(new URL("fhircast/STU3", medplumUrl), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${admin.access_token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(notification({ topic, event, patient })),
  });
  if (answer.status !== 202) {
    throw new Error(
      `the hub answered ${answer.status}, not 202: ${await answer.text()}`,
    );
  }
  console.log(`published ${event} for Patient/${patient} on topic ${topic}`);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`fhircast-publish: ${error.message}`);
    process.exit(1);
  });
}

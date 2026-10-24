import { randomBytes, randomUUID } from "node:crypto";

import { expect, type Page } from "@playwright/test";

/**
 * The local Epic stand-in, from the specs' side: self-hosted Medplum as the
 * SMART EHR launcher and FHIRcast STU3 hub, fronted by the DCR shim
 * (`backend/compose.yaml`, profile `fhircast`). `make integration-test`
 * starts it, points the one JAR's Epic Login at it, and sets every variable
 * below (frontend/README.md lists each). The `epic` and `fhircast` projects
 * both run against it.
 *
 * Medplum is called as its super admin: a FHIRcast topic is scoped to the
 * Medplum project of whoever subscribes and publishes, and the shim subscribes
 * as the super admin, so a publisher must be the super admin too. The super
 * admin also creates the launches, and finds what the seed made: the
 * clinicians a spec signs in as, and Tan Wei Ming.
 */

/** Medplum's base URL, which the shim answers on; its FHIRcast hub is under it. */
const MEDPLUM_URL = process.env.E2E_FHIRCAST_MEDPLUM_URL ?? "";
/** Medplum's FHIR base: the launch `iss`, which must equal the backend's `APP_EPIC_FHIR_BASE`. */
export const FHIR_BASE = process.env.E2E_FHIRCAST_FHIR_BASE ?? "";
/** The seeded clinician's Practitioner id: the `fhirUser` a launch signs in. */
export const PRACTITIONER_ID = process.env.E2E_FHIRCAST_PRACTITIONER_ID ?? "";
/** The Epic Login clinicians, as compose.yaml's `SEED_EPIC_LOGIN_CLINICIANS`: email=Practitioner id, comma-separated. */
const EPIC_LOGIN_CLINICIANS = process.env.E2E_EPIC_CLINICIANS ?? "";

/** Tan Wei Ming's Epic id, one of the dev profile's patient fixtures. */
export const TAN_WEI_MING = "eq081-VQEgP8drUUqCWzHfw3";

const ADMIN = {
  email: process.env.E2E_FHIRCAST_ADMIN_EMAIL ?? "",
  password: process.env.E2E_FHIRCAST_ADMIN_PASSWORD ?? "",
};
const CLINICIAN = {
  email: process.env.E2E_FHIRCAST_CLINICIAN_EMAIL ?? "",
  password: process.env.E2E_FHIRCAST_CLINICIAN_PASSWORD ?? "",
};

/** The FHIRcast STU3 hub URL, as Medplum's token response names it. */
function hubUrl(): string {
  return new URL("fhircast/STU3", MEDPLUM_URL).toString();
}

/** Fails the test with a named variable rather than a confusing request error. */
export function expectStandInConfigured(): void {
  for (const [name, value] of Object.entries({
    E2E_FHIRCAST_MEDPLUM_URL: MEDPLUM_URL,
    E2E_FHIRCAST_FHIR_BASE: FHIR_BASE,
    E2E_FHIRCAST_PRACTITIONER_ID: PRACTITIONER_ID,
    E2E_FHIRCAST_ADMIN_EMAIL: ADMIN.email,
    E2E_FHIRCAST_ADMIN_PASSWORD: ADMIN.password,
    E2E_FHIRCAST_CLINICIAN_EMAIL: CLINICIAN.email,
    E2E_FHIRCAST_CLINICIAN_PASSWORD: CLINICIAN.password,
  })) {
    expect(value, `${name} is set (make integration-test sets it)`).not.toBe("");
  }
}

async function medplumJson(path: string, init: RequestInit): Promise<Record<string, unknown>> {
  const answer = await fetch(new URL(path, MEDPLUM_URL), init);
  const text = await answer.text();
  expect(
    answer.ok,
    `Medplum answers ${init.method ?? "GET"} ${path}, not ${answer.status}: ${text}`,
  ).toBe(true);
  return JSON.parse(text) as Record<string, unknown>;
}

/** An access token for Medplum's super admin, signed in as Medplum's own client does. */
async function medplumAdminToken(): Promise<string> {
  const verifier = randomBytes(32).toString("base64url");
  const login = await medplumJson("auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      ...ADMIN,
      scope: "openid",
      codeChallenge: verifier,
      codeChallengeMethod: "plain",
    }),
  });
  const tokens = await medplumJson("oauth2/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code: String(login.code),
      code_verifier: verifier,
    }),
  });
  return String(tokens.access_token);
}

/** A FHIR request to Medplum as its super admin, its JSON answer asserted ok. */
async function asMedplumAdmin(
  path: string,
  method = "GET",
  body?: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  return medplumJson(`fhir/R4/${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${await medplumAdminToken()}`,
      ...(body === undefined ? {} : { "Content-Type": "application/fhir+json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

/** The one resource a search answers, which the seed made (`backend/fhircast-shim/src/seed.mjs`). */
async function seeded(query: string): Promise<Record<string, unknown>> {
  const found = await asMedplumAdmin(query);
  const entries = (found.entry ?? []) as Array<{ resource: Record<string, unknown> }>;
  expect(entries, `the seed made one ${query}`).toHaveLength(1);
  return entries[0].resource;
}

/** A Patient the seed made in Medplum: his Medplum id, and his Epic id beside it. */
export interface MedplumPatient {
  id: string;
  epicId: string;
}

/** Tan Wei Ming as the seed made him, found by his Epic id. */
export async function tanWeiMing(): Promise<MedplumPatient> {
  const patient = await seeded(`Patient?identifier=${TAN_WEI_MING}`);
  return { id: String(patient.id), epicId: TAN_WEI_MING };
}

/**
 * A SmartAppLaunch: what the EHR names in a launch's `launch`. Its patient is
 * `patient`, by reference and by Epic id: Medplum's token response puts the
 * identifier's value in `patient` ahead of the reference's id, so the backend
 * is handed the Epic id. With none, the token response names no patient at all.
 */
export async function createLaunch(patient?: MedplumPatient): Promise<string> {
  const launch = await asMedplumAdmin("SmartAppLaunch", "POST", {
    resourceType: "SmartAppLaunch",
    ...(patient === undefined
      ? {}
      : {
          patient: { reference: `Patient/${patient.id}`, identifier: { value: patient.epicId } },
        }),
  });
  return String(launch.id);
}

/** Who signs in on Medplum's page. */
export interface MedplumSignIn {
  email: string;
  password: string;
}

/** A clinician the seed made: a Practitioner with a Medplum sign-in in the local EHR's project. */
export interface MedplumClinician extends MedplumSignIn {
  /**
   * The Practitioner id the seed made it at: the `fhirUser` a launch signs
   * in, and so the `userName` of the User it links to (ADR 0012 D2, D3).
   */
  practitionerId: string;
}

/**
 * One of the clinicians the seed made for Epic Login's cases, by the email it
 * signs in with, at the Practitioner id compose.yaml's
 * `SEED_EPIC_LOGIN_CLINICIANS` gives it, which `make integration-test` passes
 * on as `E2E_EPIC_CLINICIANS`; every seeded clinician signs in with the
 * clinician's password.
 */
export function seededClinician(email: string): MedplumClinician {
  const pair = EPIC_LOGIN_CLINICIANS.split(",").find((entry) => entry.startsWith(`${email}=`));
  expect(pair, `E2E_EPIC_CLINICIANS names ${email} (make integration-test sets it)`).toBeDefined();
  return {
    email,
    password: CLINICIAN.password,
    practitionerId: pair!.slice(email.length + 1),
  };
}

/**
 * Signs a clinician in on Medplum's own sign-in page, where Medplum's
 * authorize sends the browser, and grants the scopes the app asked for; the
 * browser then goes on to the app's callback. The seeded clinician unless
 * another is named.
 */
export async function signInAtMedplum(
  page: Page,
  clinician: MedplumSignIn = CLINICIAN,
): Promise<void> {
  await page.getByLabel("Email").fill(clinician.email);
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByLabel(/^Password/).fill(clinician.password);
  await page.getByRole("button", { name: "Sign In" }).click();
  await page.getByRole("button", { name: "Set Scope" }).click();
}

/** The two events the app subscribes to. */
export type PatientEvent = "Patient-open" | "Patient-close";

/**
 * Publishes `event` for the Patient `patientId` to `topic` through Medplum's
 * FHIRcast REST API, as the EHR does when the clinician opens or closes a
 * chart. Answers the notification's `id`.
 */
export async function publishPatientEvent(
  topic: string,
  event: PatientEvent,
  patientId: string,
): Promise<string> {
  const id = randomUUID();
  const answer = await fetch(hubUrl(), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${await medplumAdminToken()}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      timestamp: new Date().toISOString(),
      id,
      event: {
        "hub.topic": topic,
        "hub.event": event,
        context: [{ key: "patient", resource: { resourceType: "Patient", id: patientId } }],
      },
    }),
  });
  expect(answer.status, `the hub accepts the ${event}`).toBe(202);
  return id;
}

/** The id the shim knows a channel endpoint by: the last segment of its websocket URL. */
export function channelEndpointId(endpoint: string): string {
  return new URL(endpoint).pathname.split("/").at(-1) ?? "";
}

/**
 * Closes every socket the shim carries to the hub at the channel endpoint
 * `endpoint` (its websocket URL), as a network fault would: the shim's
 * test-only `POST /dcr/test/drop`.
 */
export async function dropHubSockets(endpoint: string): Promise<void> {
  const query = new URLSearchParams({ endpoint: channelEndpointId(endpoint) }).toString();
  const answer = await fetch(new URL(`dcr/test/drop?${query}`, MEDPLUM_URL), { method: "POST" });
  expect(answer.status, "the shim drops the sockets").toBe(204);
}

/** What the shim's test-only `GET /dcr/test/state` says has run out, on its own clock. */
export interface HubExpiries {
  /** The hub tokens whose lifetime has passed. */
  expiredTokens: string[];
  /** The ids of the channel endpoints whose lease has ended. */
  endedLeases: string[];
}

/** The hub tokens and leases the shim counts as run out by now. */
export async function hubExpiries(): Promise<HubExpiries> {
  const answer = await fetch(new URL("dcr/test/state", MEDPLUM_URL));
  expect(answer.status, "the shim answers its test state").toBe(200);
  const { endedLeases, expiredTokens } = (await answer.json()) as HubExpiries;
  return { endedLeases, expiredTokens };
}

/** What a websocket subscriber receives: the hub's messages, in order. */
export interface HubSubscriber {
  /** The next message whose `predicate` holds, waiting up to `timeout` ms. */
  next(
    predicate: (message: Record<string, unknown>) => boolean,
    timeout?: number,
  ): Promise<Record<string, unknown>>;
  close(): void;
}

/**
 * A websocket subscription to `events` on `topic`, straight to the hub with
 * Medplum's super admin token: the subscribe request, then the socket at the
 * `hub.channel.endpoint` it answers.
 */
export async function subscribeDirectly(
  topic: string,
  events: PatientEvent[],
): Promise<HubSubscriber> {
  const answer = await fetch(hubUrl(), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${await medplumAdminToken()}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: new URLSearchParams({
      "hub.channel.type": "websocket",
      "hub.mode": "subscribe",
      "hub.topic": topic,
      "hub.events": events.join(","),
    }),
  });
  expect(answer.status, "the hub accepts the subscription").toBe(202);
  const { "hub.channel.endpoint": endpoint } = (await answer.json()) as Record<string, string>;

  const socket = new WebSocket(endpoint);
  const received: Record<string, unknown>[] = [];
  const waiting = new Set<() => void>();
  socket.addEventListener("message", ({ data }) => {
    received.push(JSON.parse(String(data)) as Record<string, unknown>);
    for (const wake of waiting) wake();
  });

  return {
    next(predicate, timeout = 10_000) {
      return new Promise((resolve, reject) => {
        const check = () => {
          const found = received.find(predicate);
          if (found === undefined) return;
          waiting.delete(check);
          clearTimeout(timer);
          resolve(found);
        };
        const timer = setTimeout(() => {
          waiting.delete(check);
          reject(new Error(`no matching hub message within ${timeout} ms`));
        }, timeout);
        waiting.add(check);
        check();
      });
    },
    close: () => socket.close(),
  };
}

import { randomBytes, randomUUID } from "node:crypto";

import { expect, type Page } from "@playwright/test";

/**
 * The local Epic stand-in, from the specs' side: self-hosted Medplum as the
 * SMART EHR launcher and FHIRcast STU3 hub, fronted by the shim, which
 * accepts the SMART access token at the hub and holds each subscription to
 * its lease (`backend/compose.yaml`, profile `fhircast`). `make integration-test`
 * starts it, points the one JAR's Epic Login at it, and sets every variable
 * below (frontend/README.md lists each). The `epic` and `fhircast` projects
 * both run against it.
 *
 * Medplum is called as its super admin: a FHIRcast topic is scoped to the
 * Medplum project of whoever subscribes and publishes, and the shim subscribes
 * as the super admin, so a publisher must be the super admin too. The super
 * admin also creates the launches, and finds what the seed made: the
 * clinicians a spec signs in as, and the backend's fixture patients.
 */

/** Medplum's base URL, which the shim answers on; its FHIRcast hub is under it. */
const MEDPLUM_URL = process.env.E2E_FHIRCAST_MEDPLUM_URL ?? "";
/** Medplum's FHIR base: the launch `iss`, which must equal the backend's `APP_EPIC_FHIR_BASE`. */
export const FHIR_BASE = process.env.E2E_FHIRCAST_FHIR_BASE ?? "";
/** The seeded clinician's Practitioner id: the `fhirUser` a launch signs in. */
export const PRACTITIONER_ID = process.env.E2E_FHIRCAST_PRACTITIONER_ID ?? "";
/** The Epic Login clinicians, as compose.yaml's `SEED_EPIC_LOGIN_CLINICIANS`: email=Practitioner id, comma-separated. */
const EPIC_LOGIN_CLINICIANS = process.env.E2E_EPIC_CLINICIANS ?? "";

/** Tan Wei Ming's Epic id, one of the development fixtures. */
export const TAN_WEI_MING = "eq081-VQEgP8drUUqCWzHfw3";

/**
 * A patient id the patient source's mock answers `503` for
 * (`backend/patient-source-mock/mappings/fault-server-error.json`): a fault
 * stub, not a fixture, so the seed makes no Patient under it, and an event
 * names it by id alone.
 */
export const PATIENT_SOURCE_FAULT = "fault-server-error";

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

/** A Patient the seed made, one of the backend's fixtures, found by its Epic id `epicId`. */
async function seededPatient(epicId: string): Promise<MedplumPatient> {
  const patient = await seeded(`Patient?identifier=${encodeURIComponent(epicId)}`);
  return { id: String(patient.id), epicId };
}

/** Tan Wei Ming as the seed made him, found by his Epic id. */
export function tanWeiMing(): Promise<MedplumPatient> {
  return seededPatient(TAN_WEI_MING);
}

/** Siti Nurhaliza binte Ahmad's Epic id, another of the development fixtures. */
const SITI_NURHALIZA = "erXuFYUfucBZaryVksYEcMg3";

/** Siti Nurhaliza binte Ahmad as the seed made her, found by her Epic id. */
export function sitiNurhaliza(): Promise<MedplumPatient> {
  return seededPatient(SITI_NURHALIZA);
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

/** The topic the FHIRcast card subscribed on, as the backend releases it to the page's session. */
export async function releasedTopic(page: Page): Promise<string> {
  const release = await page.request.get("/api/auth/epic/fhircast");
  return ((await release.json()) as { hubTopic: string }).hubTopic;
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

/**
 * The one live Hub Subscription the FHIRcast module keeps in a tab
 * (`sessionStorage`'s `fhircast-connection`), as far as the specs read it: its
 * channel endpoint, the websocket URL the hub answered.
 */
export interface KeptSubscription {
  endpoint: string;
}

/** The Hub Subscription the FHIRcast module keeps in `page`'s tab, asserted kept. */
export async function keptSubscription(page: Page): Promise<KeptSubscription> {
  const kept = await page.evaluate(() => sessionStorage.getItem("fhircast-connection"));
  expect(kept, "the tab keeps its Hub Subscription").not.toBeNull();
  const { subscription } = JSON.parse(kept ?? "{}") as { subscription: KeptSubscription };
  return { endpoint: subscription.endpoint };
}

/**
 * Every websocket `page`'s tab opens to the hub from now on, by URL: one per
 * channel endpoint it opens or reopens. The list fills as the tab opens them.
 */
export function recordHubSockets(page: Page): readonly string[] {
  const sockets: string[] = [];
  page.on("websocket", (socket) => {
    if (new URL(socket.url()).pathname.startsWith("/ws/fhircast/")) sockets.push(socket.url());
  });
  return sockets;
}

/** The id the shim knows a channel endpoint by: the last segment of its websocket URL. */
export function channelEndpointId(endpoint: string): string {
  return new URL(endpoint).pathname.split("/").at(-1) ?? "";
}

/**
 * Closes every socket the shim carries to the hub at the channel endpoint
 * `endpoint` (its websocket URL), as a network fault would: the shim's
 * test-only `POST /shim/test/drop`.
 */
export async function dropHubSockets(endpoint: string): Promise<void> {
  const query = new URLSearchParams({ endpoint: channelEndpointId(endpoint) }).toString();
  const answer = await fetch(new URL(`shim/test/drop?${query}`, MEDPLUM_URL), { method: "POST" });
  expect(answer.status, "the shim drops the sockets").toBe(204);
}

/**
 * Makes the SMART access token `page`'s session was released count as
 * expired at the hub at once, as when its `exp` passes: the shim's test-only
 * `POST /shim/test/expire`, which denies every subscription made with it on
 * its open sockets. The backend's own expiry of the token is untouched.
 */
export async function expireReleasedToken(page: Page): Promise<void> {
  const release = await page.request.get("/api/auth/epic/fhircast");
  const { accessToken } = (await release.json()) as { accessToken: string };
  const answer = await fetch(new URL("shim/test/expire", MEDPLUM_URL), {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ token: accessToken }),
  });
  expect(answer.status, "the shim expires the token").toBe(204);
}

/**
 * The ids of the channel endpoints whose lease has ended, on the shim's own
 * clock, as its test-only `GET /shim/test/state` says.
 */
export async function endedLeases(): Promise<string[]> {
  const answer = await fetch(new URL("shim/test/state", MEDPLUM_URL));
  expect(answer.status, "the shim answers its test state").toBe(200);
  return ((await answer.json()) as { endedLeases: string[] }).endedLeases;
}

/**
 * The `hub.mode` of the first message the hub sends a socket opened at the
 * channel endpoint `endpoint`: `subscribe` while a subscription stands there,
 * `denied` once none does. The socket is closed again either way.
 */
export function firstHubModeAt(endpoint: string): Promise<unknown> {
  const socket = new WebSocket(endpoint);
  return new Promise<unknown>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`no hub message at ${endpoint}`)), 10_000);
    socket.addEventListener("message", ({ data }) => {
      clearTimeout(timer);
      resolve((JSON.parse(String(data)) as Record<string, unknown>)["hub.mode"]);
    });
  }).finally(() => socket.close());
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

import { randomBytes, randomUUID } from "node:crypto";

import { expect, type Page } from "@playwright/test";

/**
 * The local FHIRcast stand-in, from the specs' side: self-hosted Medplum as the
 * SMART EHR launcher and FHIRcast STU3 hub, fronted by the DCR shim
 * (`backend/compose.yaml`, profile `fhircast`). `make integration-test`
 * starts it and sets every variable below (frontend/README.md lists each).
 *
 * Medplum is called as its super admin: a FHIRcast topic is scoped to the
 * Medplum project of whoever subscribes and publishes, and the shim subscribes
 * as the super admin, so a publisher must be the super admin too.
 */

/** Medplum's base URL, which the shim answers on; its FHIRcast hub is under it. */
const MEDPLUM_URL = process.env.E2E_FHIRCAST_MEDPLUM_URL ?? "";
/** Medplum's FHIR base: the launch `iss`, which must equal the backend's `APP_EPIC_FHIR_BASE`. */
export const FHIR_BASE = process.env.E2E_FHIRCAST_FHIR_BASE ?? "";
/** The seeded clinician's Practitioner id: the `fhirUser` a launch signs in. */
export const PRACTITIONER_ID = process.env.E2E_FHIRCAST_PRACTITIONER_ID ?? "";

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
  expect(answer.ok, `Medplum answers ${init.method ?? "GET"} ${path}`).toBe(true);
  return (await answer.json()) as Record<string, unknown>;
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

/**
 * A SmartAppLaunch: what the EHR names in a launch's `launch`. Its patient,
 * by the identifier Medplum's token response puts in `patient`, is `patientId`.
 */
export async function createLaunch(patientId: string): Promise<string> {
  const launch = await medplumJson("fhir/R4/SmartAppLaunch", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${await medplumAdminToken()}`,
      "Content-Type": "application/fhir+json",
    },
    body: JSON.stringify({
      resourceType: "SmartAppLaunch",
      patient: { identifier: { value: patientId } },
    }),
  });
  return String(launch.id);
}

/**
 * Signs the seeded clinician in on Medplum's own sign-in page, where Medplum's
 * authorize sends the browser, and grants the scopes the app asked for; the
 * browser then goes on to the app's callback.
 */
export async function signInAtMedplum(page: Page): Promise<void> {
  await page.getByLabel("Email").fill(CLINICIAN.email);
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByLabel(/^Password/).fill(CLINICIAN.password);
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

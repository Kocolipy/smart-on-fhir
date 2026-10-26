import { expect, test, type Page, type WebSocket } from "@playwright/test";

import { loginAs } from "./auth.helpers";
import { cardTitled } from "./card.helpers";
import {
  channelEndpointId,
  createLaunch,
  dropHubSockets,
  expectStandInConfigured,
  FHIR_BASE,
  firstHubModeAt,
  hubState,
  keptConnection,
  publishPatientEvent,
  recordRegistrations,
  releasedTopic,
  seededClinician,
  signInAtMedplum,
  tanWeiMing,
} from "./fhircast.helpers";
import {
  deprovisionUser,
  E2E_PREFIX,
  freshBrowser,
  OWN_PASSWORD,
  provisionServerNamedUser,
  provisionUser,
  runId,
  settlePassword,
  workerConnector,
} from "./scim.helpers";

/**
 * The FHIRcast card on Home (ADR 0016), end to end through the local Epic
 * stand-in: an EHR launch through Medplum signs the clinician in, the SPA
 * fetches the session's release from the backend, and connects to Medplum's
 * FHIRcast hub through the DCR shim — discovery, registration, the
 * `jwt-bearer` Hub Token, the subscription and the websocket. Once connected,
 * the card shows the last `Patient-open` or `Patient-close` published to the
 * session's topic through Medplum's FHIRcast REST API. The connection lasts
 * the session, not Home: one case leaves Home while connected, publishes from
 * there, and comes back to the same connection showing what it sent.
 *
 * In the `fhircast` project. Each launch signs in a clinician the seed made
 * for this spec alone (compose.yaml's `SEED_EPIC_LOGIN_CLINICIANS`), so the
 * Users it provisions under those Practitioner ids are no other spec's; each
 * is deleted again in a `finally`.
 *
 * The card stays connected: `make integration-test` has the shim issue Hub
 * Tokens and grant leases of 75 s, so one case outlives both, another drops
 * the card's socket through the shim's test-only lever, and another checks
 * that a renewal leaves the hub only the new subscription. A reload resumes
 * the connection rather than registering again, and one case comes back to
 * the tab once its stored Hub Token has run out. No case here
 * drops the hub from the release: Medplum always names its hub on `127.0.0.1`
 * and a well-formed topic, so the drop rules are the backend's FakeEpic tests',
 * and the card's "FHIRcast unavailable" state is the SPA unit tests'.
 */

const directory = workerConnector("fhircast-card");

/** What an EHR opens in the clinician's browser, for a launch with Tan Wei Ming in context. */
async function launchUrl(): Promise<string> {
  const launch = new URLSearchParams({
    iss: FHIR_BASE,
    launch: await createLaunch(await tanWeiMing()),
  });
  return `/api/auth/epic/launch?${launch.toString()}`;
}

/** The FHIRcast card's status line. */
const fhircastStatus = (page: Page) => page.getByRole("status", { name: "FHIRcast" });

test("an EHR launch lands on Home and the FHIRcast card reaches connected", async ({ browser }) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-card@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);

    await expect(page).toHaveURL("/");
    await expect(cardTitled(page, "FHIRcast")).toBeVisible();
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

/** The card's last event, each field's label beside its value. */
const lastEvent = (page: Page) => page.getByRole("group", { name: "Last event" });

/** The value the last event lists under `label`. */
const field = (page: Page, label: string) =>
  lastEvent(page)
    .getByRole("term")
    .filter({ hasText: label })
    .locator("xpath=following-sibling::dd[1]");

test("the card shows the last Patient-open, then the Patient-close that replaces it", async ({
  browser,
}) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-events@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    // The topic the card subscribed on, as the backend releases it to this session.
    const release = await page.request.get("/api/auth/epic/fhircast");
    const { hubTopic } = (await release.json()) as { hubTopic: string };
    const patient = await tanWeiMing();

    await publishPatientEvent(hubTopic, "Patient-open", patient.id);

    await expect(field(page, "Event")).toHaveText("Patient-open");
    await expect(field(page, "Time")).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
    await expect(field(page, "Patient")).toHaveText(patient.id);

    await publishPatientEvent(hubTopic, "Patient-close", patient.id);

    await expect(field(page, "Event")).toHaveText("Patient-close");
    await expect(field(page, "Patient")).toHaveText(patient.id);
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

/**
 * Records, in the page, every text the card's status line shows from now on;
 * answers a reader of the record. Some states last a second at most, too short
 * to be sure of catching by polling the line itself.
 */
async function recordStatuses(page: Page): Promise<() => Promise<string[]>> {
  await page.evaluate(() => {
    const line = document.querySelector('[role="status"][aria-labelledby="fhircast-card-title"]');
    if (line === null) throw new Error("the FHIRcast card has no status line");
    const seen = [line.textContent ?? ""];
    Reflect.set(window, "fhircastStatuses", seen);
    new MutationObserver(() => seen.push(line.textContent ?? "")).observe(line, {
      characterData: true,
      childList: true,
      subtree: true,
    });
  });
  return () => page.evaluate(() => Reflect.get(window, "fhircastStatuses") as string[]);
}

test("an event published once the first Hub Token and lease have expired still reaches the card", async ({
  browser,
}) => {
  // The shim's Hub Tokens and leases last 75 s: this case waits out both.
  test.setTimeout(180_000);
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-renewal@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const hubSockets: WebSocket[] = [];
    page.on("websocket", (socket) => {
      if (new URL(socket.url()).pathname.startsWith("/ws/fhircast/")) hubSockets.push(socket);
    });
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const statuses = await recordStatuses(page);
    // The first Hub Token and lease, which renewal, 15 s in, replaces.
    const firstToken = (await keptConnection(page)).subscription?.hubToken;
    expect(hubSockets, "the card has opened one socket to the hub").toHaveLength(1);
    const firstLease = channelEndpointId(hubSockets[0].url());
    const hubTopic = await releasedTopic(page);
    const patient = await tanWeiMing();

    // Time passing is what this case tests: until the shim refuses the first
    // Hub Token and has ended the first lease.
    await expect
      .poll(
        async () => {
          const { endedLeases, expiredTokens } = await hubState();
          return expiredTokens.includes(firstToken ?? "") && endedLeases.includes(firstLease);
        },
        { intervals: [2_000], timeout: 120_000 },
      )
      .toBe(true);
    await publishPatientEvent(hubTopic, "Patient-open", patient.id);

    await expect(field(page, "Event")).toHaveText("Patient-open");
    // Renewed ahead of time, never lost and won back.
    expect(await statuses()).toEqual(["Connected"]);
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("after a renewal the hub holds only the new subscription", async ({ browser }) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-renewal-cleanup@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const hubSockets: WebSocket[] = [];
    page.on("websocket", (socket) => {
      if (new URL(socket.url()).pathname.startsWith("/ws/fhircast/")) hubSockets.push(socket);
    });
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const first = await keptConnection(page);

    // The renewal, 15 s in: a second socket, at the renewed subscription's endpoint.
    await expect.poll(() => hubSockets.length, { timeout: 40_000 }).toBe(2);
    const renewed = hubSockets[1].url();

    // Both leases still run, so a denial at the first endpoint is its unsubscription.
    await expect.poll(() => firstHubModeAt(hubSockets[0].url())).toBe("denied");
    expect(await firstHubModeAt(renewed), "the renewed subscription stands").toBe("subscribe");
    await expect
      .poll(async () =>
        (await hubState()).revokedTokens.includes(first.subscription?.hubToken ?? ""),
      )
      .toBe(true);
    expect((await keptConnection(page)).subscription?.endpoint).toBe(renewed);
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("a reload connects again without registering at the hub again", async ({ browser }) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-reload@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const registrations = recordRegistrations(page);
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const { clientId } = await keptConnection(page);

    await page.reload();

    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    expect(registrations, "the tab registered once, before the reload").toHaveLength(1);
    expect((await hubState()).clientIds).toContain(clientId);
    expect((await keptConnection(page)).clientId).toBe(clientId);
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("coming back to the tab once its stored Hub Token has expired reconnects with a new Hub Token and the same client", async ({
  browser,
}) => {
  // The shim's Hub Tokens last 75 s: this case waits one out.
  test.setTimeout(180_000);
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-reload-expired@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const registrations = recordRegistrations(page);
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const before = await keptConnection(page);

    // Away from the app, the tab keeps its sessionStorage but nothing renews,
    // until the shim refuses the stored Hub Token: a reload once it expired.
    await page.goto("about:blank");
    await expect
      .poll(
        async () => (await hubState()).expiredTokens.includes(before.subscription?.hubToken ?? ""),
        { intervals: [2_000], timeout: 120_000 },
      )
      .toBe(true);
    await page.goto("/");

    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const after = await keptConnection(page);
    expect([
      after.clientId,
      after.subscription?.hubToken === before.subscription?.hubToken,
      registrations.length,
    ]).toEqual([before.clientId, false, 1]);
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("a dropped socket shows reconnecting, then the card reconnects and shows the next event", async ({
  browser,
}) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-reconnect@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const hubSockets: WebSocket[] = [];
    page.on("websocket", (socket) => {
      if (new URL(socket.url()).pathname.startsWith("/ws/fhircast/")) hubSockets.push(socket);
    });
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const statuses = await recordStatuses(page);
    const hubTopic = await releasedTopic(page);
    const patient = await tanWeiMing();
    const open = hubSockets.filter((socket) => !socket.isClosed());
    expect(open, "the card holds one socket to the hub").toHaveLength(1);

    await dropHubSockets(open[0].url());

    await expect.poll(statuses).toContain("Reconnecting");
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    await publishPatientEvent(hubTopic, "Patient-open", patient.id);
    await expect(field(page, "Event")).toHaveText("Patient-open");
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("the connection lasts while the clinician is on another page, and Home shows what the hub sent meanwhile", async ({
  browser,
}) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-navigation@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const registrations = recordRegistrations(page);
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const hubTopic = await releasedTopic(page);
    const patient = await tanWeiMing();

    await page.getByRole("link", { name: "Change password" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Change password" })).toBeVisible();
    await publishPatientEvent(hubTopic, "Patient-open", patient.id);
    await page.getByRole("link", { name: "Back" }).click();

    await expect(field(page, "Event")).toHaveText("Patient-open");
    await expect(field(page, "Patient")).toHaveText(patient.id);
    await expect(fhircastStatus(page)).toHaveText("Connected");
    expect(registrations, "the tab registered once, before it left Home").toHaveLength(1);
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("a password login shows no FHIRcast card", async ({ browser }) => {
  const userName = `${E2E_PREFIX}fhircast-password-${runId()}`;
  const id = await provisionUser(directory.scim, userName);
  const visitor = await freshBrowser(browser);
  try {
    await settlePassword(userName);
    const page = await visitor.newPage();
    await loginAs(page, userName, OWN_PASSWORD);

    await expect(page.getByRole("heading", { name: "Patient" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "FHIRcast" })).toHaveCount(0);
  } finally {
    await visitor.close();
    await deprovisionUser(directory.scim, id);
  }
});

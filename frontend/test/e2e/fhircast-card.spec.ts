import { expect, test, type Page, type WebSocket } from "@playwright/test";

import { loginAs, submitLogin } from "./auth.helpers";
import { cardTitled } from "./card.helpers";
import {
  createLaunch,
  dropHubSockets,
  endLease,
  expectStandInConfigured,
  expireReleasedToken,
  FHIR_BASE,
  firstHubModeAt,
  keptSubscription,
  publishPatientEvent,
  recordHubSockets,
  releasedTopic,
  seededClinician,
  signInAtMedplum,
  tanWeiMing,
} from "./fhircast.helpers";
import { expectPatientInContext } from "./patient.helpers";
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
 * FHIRcast hub through the shim — the subscription, with the SMART access
 * token as `Bearer`, and the websocket at the endpoint it answers. Once connected,
 * the card shows the last `Patient-open` or `Patient-close` published to the
 * session's topic through Medplum's FHIRcast REST API. The connection lasts
 * the session, not Home: one case leaves Home while connected, publishes from
 * there, and comes back to the same connection showing what it sent.
 *
 * In the `fhircast` project. Each launch signs in a clinician the seed made
 * for this spec alone (compose.yaml's `SEED_EPIC_LOGIN_CLINICIANS`), so the
 * Users it provisions under those Practitioner ids are no other spec's; each
 * is deleted again in a `finally`. That a launch signs the provisioned
 * clinician in and lands on Home with the launch's patient is
 * `epic-launch.spec.ts`'.
 *
 * The card stays connected: `make integration-test` has the shim grant leases
 * of 75 s, renewed 15 s in, so one case has the shim end the first lease once
 * the renewal has replaced it, another drops the card's socket
 * through the shim's test-only lever, and another checks that a renewal
 * leaves the hub only the new subscription. A reload reopens the stored
 * subscription rather than subscribing again, and one case comes back to the
 * tab once its stored lease has ended, at the hub and on the tab's clock.
 * The connection ends with the SMART
 * access token: one case has the shim count it expired at once, through its
 * test-only lever, so the hub denies the subscription on the card's socket.
 * No case here
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

test("an event published once the first lease has ended still reaches the card", async ({
  browser,
}) => {
  // The launch, up to 20 s to connect and the renewal 15 s after it: past the default 30 s.
  test.setTimeout(90_000);
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
    expect(hubSockets, "the card has opened one socket to the hub").toHaveLength(1);
    const hubTopic = await releasedTopic(page);
    const patient = await tanWeiMing();

    // The renewal, 15 s in, replaces the first lease; then that lease ends.
    await expect.poll(() => hubSockets.length, { timeout: 40_000 }).toBe(2);
    await endLease(hubSockets[0].url());
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
  // The launch, up to 20 s to connect and the renewal 15 s after it: past the default 30 s.
  test.setTimeout(90_000);
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

    // The renewal, 15 s in: a second socket, at the renewed subscription's endpoint.
    await expect.poll(() => hubSockets.length, { timeout: 40_000 }).toBe(2);
    const renewed = hubSockets[1].url();

    // Both leases still run, so a denial at the first endpoint is its unsubscription.
    await expect.poll(() => firstHubModeAt(hubSockets[0].url())).toBe("denied");
    expect(await firstHubModeAt(renewed), "the renewed subscription stands").toBe("subscribe");
    expect((await keptSubscription(page)).endpoint).toBe(renewed);
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("a reload reopens the stored subscription's endpoint rather than subscribing again", async ({
  browser,
}) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-reload@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const hubSockets = recordHubSockets(page);
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const before = await keptSubscription(page);
    const openedBefore = hubSockets.length;

    await page.reload();

    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    expect(hubSockets[openedBefore], "the reload's first socket is the stored endpoint").toBe(
      before.endpoint,
    );
  } finally {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  }
});

test("coming back to the tab once its stored lease has ended subscribes again", async ({
  browser,
}) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-reload-expired@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const hubSockets = recordHubSockets(page);
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const before = await keptSubscription(page);

    // Away from the app, the tab keeps its sessionStorage but nothing renews,
    // until the stored lease has ended, at the hub and on the tab's own clock:
    // a reload once it has.
    await page.goto("about:blank");
    await endLease(before.endpoint);
    expect(before.leaseExpiresAt, "the hub stated the lease").not.toBeNull();
    await page.clock.install({ time: before.leaseExpiresAt! + 1_000 });
    const openedAway = hubSockets.length;
    await page.goto("/");

    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const after = await keptSubscription(page);
    expect([after.endpoint === before.endpoint, hubSockets.slice(openedAway)]).toEqual([
      false,
      [after.endpoint],
    ]);
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

test("once the hub denies the subscription for its expired SMART access token, the card says to reopen the application from Epic and the Patient card is unchanged", async ({
  browser,
}) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-token-expiry@example.com");
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  try {
    const page = await clinician.newPage();
    const hubSockets = recordHubSockets(page);
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    await expectPatientInContext(page, "Tan Wei Ming");
    const opened = hubSockets.length;

    await expireReleasedToken(page);

    await expect(fhircastStatus(page)).toHaveText(
      "FHIRcast ended. Reopen the application from Epic.",
    );
    await expectPatientInContext(page, "Tan Wei Ming");
    await expect(page.getByRole("button", { name: "Retry" })).toHaveCount(0);
    expect(hubSockets.length, "the card reopens no socket to the hub").toBe(opened);
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
    const releases: string[] = [];
    page.on("request", (request) => {
      if (new URL(request.url()).pathname === "/api/auth/epic/fhircast")
        releases.push(request.url());
    });
    await page.goto(await launchUrl());
    await signInAtMedplum(page, signIn);
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const released = releases.length;
    const hubTopic = await releasedTopic(page);
    const patient = await tanWeiMing();

    await page.getByRole("link", { name: "Open settings", exact: true }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Settings" })).toBeVisible();
    await publishPatientEvent(hubTopic, "Patient-open", patient.id);
    await page.getByRole("link", { name: "Profile", exact: true }).click();

    await expect(field(page, "Event")).toHaveText("Patient-open");
    await expect(field(page, "Patient")).toHaveText(patient.id);
    await expect(fhircastStatus(page)).toHaveText("Connected");
    expect(releases, "the tab fetched its release once, before it left Home").toHaveLength(
      released,
    );
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

/**
 * A password Login from a `/login` tab left open as a Guest, after an EHR
 * launch in another tab of the same browser: the Login lands on the session
 * the launch signed in, its CSRF refusal retried with that session's token,
 * and leaves nothing of the launch on it. The FHIRcast card does not show for
 * the second User, and no response in the browser releases a SMART access
 * token once they are signed in; asked directly, the release answers `404`,
 * as for any password Login's session.
 */
test("a password login from a stale login tab after an EHR launch releases no SMART access token", async ({
  browser,
}) => {
  expectStandInConfigured();
  const signIn = seededClinician("fhircast-stale-login@example.com");
  const clinicianId = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const userName = `${E2E_PREFIX}fhircast-stale-login-${runId()}`;
  const visitorId = await provisionUser(directory.scim, userName);
  const context = await freshBrowser(browser);
  try {
    await settlePassword(userName);
    // The stale tab: a Guest at /login, holding the CSRF token of the
    // anonymous session its refused attempt was sent under.
    const stale = await context.newPage();
    await submitLogin(stale, `${E2E_PREFIX}nobody-${runId()}`, OWN_PASSWORD);
    await expect(stale.getByRole("alert")).toHaveText("The username or password is incorrect.");

    const launched = await context.newPage();
    await launched.goto(await launchUrl());
    await signInAtMedplum(launched, signIn);
    await expect(fhircastStatus(launched)).toHaveText("Connected", { timeout: 20_000 });

    const released: number[] = [];
    context.on("response", (response) => {
      if (new URL(response.url()).pathname === "/api/auth/epic/fhircast")
        released.push(response.status());
    });
    // Typed into the open form, not through submitLogin: its navigation to
    // /login would reload the tab and fetch a fresh token, so the tab would
    // no longer be stale.
    await stale.getByLabel("Username").fill(userName);
    await stale.getByLabel("Password").fill(OWN_PASSWORD);
    await stale.getByRole("button", { name: "Sign in" }).click();
    await expect(stale.getByText(`Signed in as ${userName}`)).toBeVisible();
    await expect(stale.getByRole("heading", { name: "Patient" })).toBeVisible();

    const release = await stale.request.get("/api/auth/epic/fhircast");
    expect({
      card: await stale.getByRole("heading", { name: "FHIRcast" }).count(),
      released: released.filter((status) => status === 200),
      release: release.status(),
    }).toEqual({ card: 0, released: [], release: 404 });
  } finally {
    await context.close();
    await deprovisionUser(directory.scim, visitorId);
    await deprovisionUser(directory.scim, clinicianId);
  }
});

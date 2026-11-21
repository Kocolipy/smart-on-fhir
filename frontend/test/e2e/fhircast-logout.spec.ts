import { expect, test, type Browser, type Page } from "@playwright/test";

import { cardTitled } from "./card.helpers";
import {
  createLaunch,
  expectStandInConfigured,
  FHIR_BASE,
  firstHubModeAt,
  keptSubscription,
  recordHubSockets,
  recordUnsubscriptions,
  seededClinician,
  signInAtMedplum,
  tanWeiMing,
} from "./fhircast.helpers";
import {
  deprovisionUser,
  freshBrowser,
  provisionServerNamedUser,
  workerConnector,
} from "./scim.helpers";

/**
 * Logout tears the FHIRcast connection down at the hub (ADR 0011, "Revocation
 * at the hub is best-effort"), end to end through the local Epic stand-in: an
 * EHR launch through Medplum signs the clinician in, Home's FHIRcast card
 * subscribes at Medplum's hub through the shim with the hub token it obtained,
 * and "Sign out" then has the SPA unsubscribe there with the same hub token. Each
 * case reads the hub's side: Medplum's answer to a socket at the card's
 * channel endpoint. One case reloads the tab before signing out: the teardown
 * is then the connection's that resumed what the tab kept. Two more sign out
 * once the tab has moved off a subscription — after a renewal, and after a
 * Retry whose first connection's socket the spec closed before the hub
 * confirmed it — and hold every channel endpoint the tab used to being
 * unsubscribed once, read off the requests the tab sends the hub.
 *
 * In the `fhircast` project. Each case signs in a clinician the seed made for
 * it alone (compose.yaml's `SEED_EPIC_LOGIN_CLINICIANS`), so the Users it
 * provisions under those Practitioner ids are no other case's; each is deleted
 * again in a `finally`. The teardown runs after the logout has answered, so
 * each case polls the hub for it.
 */

const directory = workerConnector("fhircast-logout");

/** The FHIRcast card's status line. */
const fhircastStatus = (page: Page) => page.getByRole("status", { name: "FHIRcast" });

/**
 * Launches the clinician `email` the seed made to Home, `prepare`d before it
 * loads, and waits for the card to show `status`. Answers the page, every
 * channel endpoint its tab opens a socket at, and every one it asks the hub
 * to unsubscribe, each as it happens.
 */
async function launchedToTheCard(
  browser: Browser,
  email: string,
  status: string,
  prepare: (page: Page) => Promise<void> = () => Promise.resolve(),
) {
  expectStandInConfigured();
  const signIn = seededClinician(email);
  const id = await provisionServerNamedUser(directory.scim, signIn.practitionerId);
  const clinician = await freshBrowser(browser);
  const tidy = async () => {
    await clinician.close();
    await deprovisionUser(directory.scim, id);
  };
  try {
    const page = await clinician.newPage();
    const channelEndpoints = recordHubSockets(page);
    const unsubscriptions = recordUnsubscriptions(page);
    await prepare(page);
    const launch = new URLSearchParams({
      iss: FHIR_BASE,
      launch: await createLaunch(await tanWeiMing()),
    });
    await page.goto(`/api/auth/epic/launch?${launch.toString()}`);
    await signInAtMedplum(page, signIn);
    await expect(cardTitled(page, "FHIRcast")).toBeVisible();
    await expect(fhircastStatus(page)).toHaveText(status, { timeout: 20_000 });
    return { channelEndpoints, page, tidy, unsubscriptions };
  } catch (failure) {
    await tidy();
    throw failure;
  }
}

/**
 * Launches the clinician `email` the seed made to Home and waits for the card
 * to connect, answering as {@link launchedToTheCard} does.
 */
const launchedToAConnectedCard = (browser: Browser, email: string) =>
  launchedToTheCard(browser, email, "Connected");

/**
 * Signs out, then holds every channel endpoint the tab opened a socket at by
 * then (`opened`, filled as it opens them) to the teardown: the hub holds no
 * subscription at any once it is done, and the tab asked the hub to
 * unsubscribe each once — each subscription moved off before the logout, and
 * every one still standing at it.
 */
async function expectEachUnsubscribedOnceAtLogout(
  page: Page,
  opened: readonly string[],
  unsubscriptions: readonly string[],
) {
  await signOut(page);

  const used = [...new Set(opened)];
  for (const endpoint of used) {
    await expect.poll(() => firstHubModeAt(endpoint)).toBe("denied");
  }
  await expect.poll(() => new Set(unsubscriptions).size).toBe(used.length);
  expect([...unsubscriptions].sort(), "each endpoint the tab used is unsubscribed once").toEqual(
    [...used].sort(),
  );
}

/** Signs out by the layout's Sign out, landing on the login page. */
async function signOut(page: Page) {
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login/);
}

test("after logout the hub holds no subscription at the card's channel endpoint", async ({
  browser,
}) => {
  const { channelEndpoints, page, tidy } = await launchedToAConnectedCard(
    browser,
    "fhircast-logout-unsubscribe@example.com",
  );
  try {
    expect(channelEndpoints, "the card has opened one socket to the hub").toHaveLength(1);
    const [channelEndpoint] = channelEndpoints;
    expect((await keptSubscription(page)).endpoint, "the tab keeps the subscription").toBe(
      channelEndpoint,
    );
    expect(await firstHubModeAt(channelEndpoint), "the subscription stands before logout").toBe(
      "subscribe",
    );

    await signOut(page);

    await expect.poll(() => firstHubModeAt(channelEndpoint)).toBe("denied");
  } finally {
    await tidy();
  }
});

test("after a reload, logout leaves no subscription at any endpoint the tab used", async ({
  browser,
}) => {
  const { channelEndpoints, page, tidy } = await launchedToAConnectedCard(
    browser,
    "fhircast-logout-reload@example.com",
  );
  try {
    await page.reload();
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const used = [...new Set(channelEndpoints)];
    expect(used.length, "the tab opened a socket to the hub").toBeGreaterThan(0);
    for (const endpoint of used) {
      expect(await firstHubModeAt(endpoint), `the subscription at ${endpoint} before logout`).toBe(
        "subscribe",
      );
    }

    await signOut(page);

    for (const endpoint of used) {
      await expect.poll(() => firstHubModeAt(endpoint)).toBe("denied");
    }
  } finally {
    await tidy();
  }
});

test("after a renewal, logout leaves no subscription at any endpoint the tab used, each unsubscribed once", async ({
  browser,
}) => {
  // The launch, up to 20 s to connect and the renewal 15 s after it: past the default 30 s.
  test.setTimeout(90_000);
  const { channelEndpoints, page, tidy, unsubscriptions } = await launchedToAConnectedCard(
    browser,
    "fhircast-logout-token@example.com",
  );
  try {
    // The renewal, 15 s in: a second socket, at the renewed subscription's endpoint.
    await expect.poll(() => channelEndpoints.length, { timeout: 40_000 }).toBe(2);
    const [first, renewed] = channelEndpoints;
    // Both leases still run, so a denial at the first endpoint is its unsubscription.
    await expect.poll(() => firstHubModeAt(first)).toBe("denied");
    expect(await firstHubModeAt(renewed), "the renewed subscription stands").toBe("subscribe");

    await expectEachUnsubscribedOnceAtLogout(page, channelEndpoints, unsubscriptions);
  } finally {
    await tidy();
  }
});

test("after a Retry that moved to a new subscription, logout leaves no subscription at any endpoint the tab used, each unsubscribed once", async ({
  browser,
}) => {
  const opened: string[] = [];
  const { page, tidy, unsubscriptions } = await launchedToTheCard(
    browser,
    "fhircast-logout-revoke@example.com",
    "FHIRcast unavailable",
    // The first socket the tab opens closes before the hub confirms anything,
    // so the subscription the hub accepted is never confirmed; every later
    // one reaches the hub.
    (page) =>
      page.routeWebSocket(/\/ws\/fhircast\//, (socket) => {
        opened.push(socket.url());
        if (opened.length === 1) void socket.close();
        else socket.connectToServer();
      }),
  );
  try {
    await page.getByRole("button", { name: "Retry" }).click();
    await expect(fhircastStatus(page)).toHaveText("Connected", { timeout: 20_000 });
    const used = [...new Set(opened)];
    expect(used, "the Retry subscribed anew, at a second endpoint").toHaveLength(2);
    for (const endpoint of used) {
      expect(await firstHubModeAt(endpoint), `the subscription at ${endpoint} before logout`).toBe(
        "subscribe",
      );
    }

    await expectEachUnsubscribedOnceAtLogout(page, opened, unsubscriptions);
  } finally {
    await tidy();
  }
});

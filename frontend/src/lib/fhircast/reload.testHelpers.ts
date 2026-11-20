/**
 * What the reload suites share: the FHIRcast module loaded afresh over what
 * the tab kept, the page before it connected, and the requests a resumption
 * makes of the hub.
 */
import { expect, onTestFinished, vi } from "vitest";

import {
  CHANNEL_ENDPOINT,
  CONFIGURATION_URL,
  HUB_URL,
  hubOnly,
  type HubOptions,
  hubTokenOf,
  TOKEN_URL,
} from "../fake-hub.testHelpers";
import { type FhircastRelease, fhircastSnapshot } from ".";
import { eventually, RELEASE, startedWith } from "./fhircast.testHelpers";

/** The FHIRcast module, as a page loads it. */
export type Page = typeof import(".");

/**
 * The tab after a reload: the FHIRcast module loaded afresh, holding nothing
 * in memory, over the sessionStorage the page before it left. Its session's
 * FHIRcast is ended once the test is over, as the page before's is.
 */
export async function reloaded(): Promise<Page> {
  vi.resetModules();
  const page = await import(".");
  onTestFinished(() => page.endFhircast());
  return page;
}

/**
 * Connected to a hub answering `options` with `release` in the page before
 * the reload, which then went as a reload takes a page: its socket closed,
 * nothing torn down at the hub, and nothing more heard of it. The hub's
 * denial on the live socket is what stops it so: the one way a connection
 * stops for good with no teardown, what the tab kept left as it was.
 */
export async function connectedBefore(
  options: HubOptions = {},
  release: FhircastRelease = RELEASE,
) {
  const hub = hubOnly(options);
  expect(await startedWith(release)).toBe("connected");
  hub.denySockets("the page is gone");
  await eventually(() => expect(fhircastSnapshot().status).toBe("ended"));
  return hub;
}

/** Started on `page` with `release`, once it says `status`. */
export async function startedOn(
  page: Page,
  status = "connected",
  release: FhircastRelease = RELEASE,
) {
  page.startFhircast(() => Promise.resolve(release));
  await eventually(() => expect(page.fhircastSnapshot().status).toBe(status));
}

/** A subscription, or its unsubscription: both are posted to the hub URL. */
export const AT_HUB = `POST ${HUB_URL}`;

/**
 * What a reload asks the hub first: its configuration, then a hub token for
 * the dynamic client the profile kept from the page before — no registration.
 */
export const HUB_TOKEN_AFTER_RELOAD_ROUTES = [`GET ${CONFIGURATION_URL}`, `POST ${TOKEN_URL}`];

/** The hub token a reload obtains: the second the hub issued, the page before's the first. */
export const HUB_TOKEN_AFTER_RELOAD = hubTokenOf(2);

export const SECOND_ENDPOINT = CHANNEL_ENDPOINT.replace(/1$/, "2");

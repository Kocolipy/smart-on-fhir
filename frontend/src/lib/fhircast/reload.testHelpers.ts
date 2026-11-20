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
import { endFhircast, FHIRCAST_STORAGE_KEYS, type FhircastRelease } from ".";
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
 * nothing more heard of it, and what the tab kept left as it was. A
 * connection stops for good only by its session's end, so the page before is
 * stopped by {@link endFhircast} and what the tab kept is put back after it:
 * its unsubscription is among the hub's requests before any a test reads
 * from here on, and the fake hub still confirms every endpoint it answered.
 */
export async function connectedBefore(
  options: HubOptions = {},
  release: FhircastRelease = RELEASE,
) {
  const hub = hubOnly(options);
  expect(await startedWith(release)).toBe("connected");
  const kept = window.sessionStorage.getItem(FHIRCAST_STORAGE_KEYS.connection);
  await endFhircast();
  if (kept !== null) window.sessionStorage.setItem(FHIRCAST_STORAGE_KEYS.connection, kept);
  await eventually(() => expect(hub.openSockets()).toBe(0));
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

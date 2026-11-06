/**
 * What the reload suites share: the FHIRcast module loaded afresh over what
 * the tab kept, the page before it connected, and the requests a resumption
 * makes of the hub.
 */
import { expect, onTestFinished, vi } from "vitest";

import { CHANNEL_ENDPOINT, HUB_URL, hubOnly, type HubOptions } from "../fake-hub.testHelpers";
import { connectFhircast } from ".";
import { eventually, RELEASE } from "./fhircast.testHelpers";

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
 * nothing torn down at the hub.
 */
export async function connectedBefore(options: HubOptions = {}, release = RELEASE) {
  const hub = hubOnly(options);
  const outcome = await connectFhircast(release);
  expect(outcome.kind).toBe("connected");
  if (outcome.kind === "connected") outcome.close();
  return hub;
}

/** Started on `page` with `release`, once it says `status`. */
export async function startedOn(page: Page, status = "connected", release = RELEASE) {
  page.startFhircast(() => Promise.resolve(release));
  await eventually(() => expect(page.fhircastSnapshot().status).toBe(status));
}

/** A subscription, or its unsubscription: both are posted to the hub URL. */
export const AT_HUB = `POST ${HUB_URL}`;

export const SECOND_ENDPOINT = CHANNEL_ENDPOINT.replace(/1$/, "2");

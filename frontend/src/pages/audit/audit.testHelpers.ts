import { screen } from "@testing-library/react";
import type userEvent from "@testing-library/user-event";

import { holding, json, type Answers, type WireAnswer } from "@/auth/session-adapter.testHelpers";

import { AUDIT_EVENTS_PATH } from "./audit-api";

export const AUDITOR = holding(["audit:read"]);

export const EVENT_1 = {
  id: "e-1",
  // Late in the UTC day: already the next day in Singapore, where it is shown.
  occurredAt: "2026-01-02T19:04:05Z",
  operation: "LOGIN_FAILURE",
  outcome: "FAILURE",
  actorId: null,
  subjectId: "00000000-0000-4000-8000-000000000001",
  resourceType: "User",
  resourceId: "00000000-0000-4000-8000-000000000001",
  changedPaths: [],
  statusClass: "client_error",
  errorCode: "BAD_CREDENTIALS",
  httpMethod: "POST",
  httpPath: "/api/auth/login",
  requestId: "req-1",
  resultCount: null,
  filterShape: null,
  permissions: [],
  loginMethod: "password",
  permissionsBefore: [],
  permissionsAfter: [],
  superuserChanged: null,
  groupIds: [],
};

export const EVENT_2 = {
  id: "e-2",
  occurredAt: "2026-01-03T10:11:12Z",
  operation: "SCIM_USER_LIST",
  outcome: "SUCCESS",
  actorId: "00000000-0000-4000-8000-0000000000c1",
  subjectId: null,
  resourceType: "User",
  resourceId: null,
  changedPaths: [],
  statusClass: "ok",
  errorCode: null,
  httpMethod: "GET",
  httpPath: "/Users",
  requestId: "req-2",
  resultCount: 3,
  filterShape: "userName eq ?",
  permissions: [],
  loginMethod: null,
  permissionsBefore: [],
  permissionsAfter: [],
  superuserChanged: null,
  groupIds: [],
};

function pageOf(events: object[], overrides: Partial<Record<string, unknown>> = {}) {
  return {
    totalResults: events.length,
    startIndex: 1,
    events,
    ...overrides,
  };
}

/** The read starting at `startIndex` with no filter, as the page names it. */
export const at = (startIndex: number) =>
  `GET ${AUDIT_EVENTS_PATH}?startIndex=${startIndex}&count=20`;

/** The same answer to every read of the listing, whatever its filters or page. */
export const everyRead = (answer: WireAnswer): Answers => ({
  [`GET ${AUDIT_EVENTS_PATH}*`]: answer,
});

/** An `AuditEventPage` holding `events`, as the backend answers a read. */
export const answerPage = (events: object[], overrides: Partial<Record<string, unknown>> = {}) =>
  json(pageOf(events, overrides));

/** The read paths the page sent, in order. */
export const readPaths = (requests: () => readonly { path: string }[]) =>
  requests().map(({ path }) => path);

export const table = () => screen.getByRole("table", { name: "Audit events" });

/** Waits for the table to render, then returns it. */
export const findTable = () => screen.findByRole("table", { name: "Audit events" });

export type User = ReturnType<typeof userEvent.setup>;

/** The button that opens the filters, whatever it says is set. */
export const filterTrigger = () => screen.getByRole("button", { name: /^Filter/ });

/** Opens the filters, unless they are open already. */
export async function openFilters(user: User) {
  if (filterTrigger().getAttribute("aria-expanded") !== "true") await user.click(filterTrigger());
}

/** The Operation picker's trigger, whatever it says is selected. */
export const pickerTrigger = () => screen.getByRole("button", { name: /^Operation: / });

export const openPicker = (user: User) => user.click(pickerTrigger());

/** Opens the filters and the picker, and toggles each of `operations` in the one visit. */
export async function pick(user: User, ...operations: string[]) {
  await openFilters(user);
  await openPicker(user);
  for (const name of operations) await user.click(screen.getByRole("option", { name }));
}

export const lastRead = (requests: () => readonly { path: string }[]) =>
  readPaths(requests).slice(-1)[0];

export const unfiltered = `${AUDIT_EVENTS_PATH}?startIndex=1&count=20`;
export const applyButton = () => screen.getByRole("button", { name: "Apply" });

/** Presses Apply, opening the filters first if an earlier Apply closed them. */
export async function apply(user: User) {
  await openFilters(user);
  await user.click(applyButton());
}

export const ACTOR_A = "00000000-0000-4000-8000-00000000000a";

export const outcome = (name: "Any" | "Success" | "Failure") => screen.getByRole("radio", { name });

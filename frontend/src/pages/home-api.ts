/**
 * The counter endpoints the Home page reads and drives —
 * `/frontend/AGENTS.md`'s page-owned `api.ts`, named for its page.
 *
 * Nothing here requests anything: the page sends each declaration through
 * `useGatedRead` or `useGatedWrite` (both behind `useSessionRequest`), so a
 * `401` still ends the session in one place. This file says what a response
 * looks like and declares each endpoint — its path, its decoder and the
 * Permission it requires — once.
 */

import type { ReadDeclaration, WriteDeclaration } from "@/auth/declarations";
import { jsonDecoder, readObject } from "@/lib/decode";

/** Every counter endpoint's `CountResponse`, read down to its one field. */
const decodeCount = jsonDecoder((body: unknown): number =>
  readObject(body, "CountResponse").integer("count"),
);

/** The current count, `GET /api/count`. */
export const COUNTER: ReadDeclaration<number> = {
  decode: decodeCount,
  path: () => "/api/count",
  permission: "counter:read",
};

/** Adds one; answers the new count. */
export const INCREMENT_COUNTER: WriteDeclaration<[], number> = {
  decode: decodeCount,
  method: "POST",
  path: () => "/api/count/increment",
  permission: "counter:write",
};

/** Returns the count to zero; answers the new count. */
export const RESET_COUNTER: WriteDeclaration<[], number> = {
  decode: decodeCount,
  method: "POST",
  path: () => "/api/count/reset",
  permission: "counter:write",
};

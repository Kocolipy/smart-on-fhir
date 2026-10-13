import { DecodeError, jsonDecoder, readObject } from "@/lib/decode";
import { apiFetch, type ApiResult } from "@/lib/http";

/**
 * Every Permission the backend grants: the closed set its `Permission` enum
 * defines, spelled as `/api/auth/me` reports them. A value outside this list is
 * refused, never passed through, because the guards read it.
 */
const PERMISSIONS = [
  "audit:read",
  "connector:read",
  "connector:token",
  "connector:write",
  "counter:read",
  "counter:write",
  "group:read",
  "group:write",
  "ops:read",
  "user:read",
  "user:write",
] as const;

export type Permission = (typeof PERMISSIONS)[number];

export interface AuthUser {
  /**
   * What the session may do, as the backend issued it at sign-in: the union of
   * the Roles its Groups confer. Empty for a User in no mapped Group, and while
   * `passwordChangeRequired` is set. The SPA decides what to SHOW from this;
   * the backend alone decides what is allowed.
   */
  permissions: readonly Permission[];
  /** The change-required flag: this session may only change its password or log out. */
  passwordChangeRequired: boolean;
  /**
   * The backend's idle bound for this session, in seconds. The SPA signs an
   * inactive user out by this figure rather than a copy of its own, so the two
   * cannot drift apart.
   */
  idleTimeoutSeconds: number;
  username: string;
}

/**
 * One Permission name, checked against `PERMISSIONS` rather than trusted: an
 * unknown string would otherwise reach a guard as if it granted something.
 */
function decodePermission(value: unknown): Permission {
  const permission = PERMISSIONS.find((candidate) => candidate === value);
  if (permission === undefined)
    throw new DecodeError("UserResponse.permissions holds an unknown value");
  return permission;
}

/**
 * The `/me` and login `UserResponse`, read the same way for both. A body that
 * does not decode fails the request outright.
 */
const decodeUser = jsonDecoder((body: unknown): AuthUser => {
  const user = readObject(body, "UserResponse");
  return {
    idleTimeoutSeconds: user.integer("idleTimeoutSeconds"),
    passwordChangeRequired: user.boolean("passwordChangeRequired"),
    permissions: user.array("permissions", decodePermission),
    username: user.string("username"),
  };
});

/**
 * The session's own user. It asks the least of any authenticated request, so
 * it is also how a caller asks whether the session still exists.
 */
export const ME_PATH = "/api/auth/me";

/**
 * The outcomes a session action shares with every request: an authorization
 * refusal, a CSRF token that could not be fetched, and any other failure. None
 * of them changes the session.
 */
type RequestFailure = { kind: "forbidden" } | { kind: "csrf-expired" } | { kind: "failed" };

/**
 * What the start-up session check came to. Every request outcome resolves to
 * one of these; none throws.
 *
 * - `signed-in` — the browser already holds a signed-in session, as `user`.
 * - `guest` — `401`: there is no signed-in session. The only verdict that
 *   makes the visitor a Guest.
 * - `forbidden`, `csrf-expired`, `failed` — the check did not answer, so the
 *   session is unknown, not ended: a `5xx` or a network error says nothing
 *   about whether a live session stands, and treating it as a Guest would send
 *   a signed-in User to login, where signing in again ends their other sessions
 *   (One session per User).
 *
 * What a check that did not answer says is `session-action-copy.ts`'s.
 */
export type SessionCheckOutcome =
  { kind: "signed-in"; user: AuthUser } | { kind: "guest" } | RequestFailure;

/** A session check that reached no verdict: the session is unknown. */
export type SessionCheckFailure = Exclude<SessionCheckOutcome, { kind: "signed-in" | "guest" }>;

/** Asks the backend whose session the browser holds, if anyone's. */
export async function getCurrentUser(): Promise<SessionCheckOutcome> {
  const result = await apiFetch(ME_PATH, {}, decodeUser);
  switch (result.kind) {
    case "ok":
      return { kind: "signed-in", user: result.data };
    case "unauthenticated":
      return { kind: "guest" };
    case "forbidden":
    case "csrf-expired":
    case "failed":
      return { kind: result.kind };
  }
}

/**
 * What a login came to. Every request outcome resolves to one of these; none
 * throws.
 *
 * - `signed-in` — the backend signed the User in, rotating the session's id.
 * - `refused` — `401`: the credentials were refused, the same for an unknown
 *   name, a wrong password and a lockout. The backend ended whatever session
 *   the browser held before answering.
 *
 * Both verdicts change the browser's session, which the caller acts on; the
 * rest leave it as it was. What each says is `session-action-copy.ts`'s.
 */
export type LoginOutcome =
  { kind: "signed-in"; user: AuthUser } | { kind: "refused" } | RequestFailure;

/** Signs in. */
export async function login(username: string, password: string): Promise<LoginOutcome> {
  const result = await apiFetch(
    "/api/auth/login",
    {
      body: JSON.stringify({ username, password }),
      headers: { "Content-Type": "application/json" },
      method: "POST",
    },
    decodeUser,
  );

  switch (result.kind) {
    case "ok":
      return { kind: "signed-in", user: result.data };
    case "unauthenticated":
      return { kind: "refused" };
    case "forbidden":
    case "csrf-expired":
    case "failed":
      return { kind: result.kind };
  }
}

/**
 * What a logout came to. Every request outcome resolves to one of these; none
 * throws.
 *
 * - `signed-out` — the session is gone, which the caller acts on.
 * - `csrf-expired`, `failed` — the backend may still hold the session, so it
 *   stands, its CSRF token with it (ADR-0009).
 *
 * What each says is `session-action-copy.ts`'s.
 */
export type LogoutOutcome =
  { kind: "signed-out" } | Extract<RequestFailure, { kind: "csrf-expired" | "failed" }>;

/**
 * Ends the session, and treats a session that had already ended as ended.
 *
 * Logout is CSRF-protected like any unsafe request, so on an expired session
 * the token held is dead too: the backend answers `403`, `apiFetch` re-fetches
 * a token (for a fresh, anonymous session) and retries, and the retry is
 * refused `401` — or `403` again. Neither is an error to the user, who asked to
 * be signed out and is, so both are `signed-out`.
 */
export async function logout(): Promise<LogoutOutcome> {
  const result: ApiResult<void> = await apiFetch("/api/auth/logout", { method: "DELETE" });
  switch (result.kind) {
    case "ok":
    case "unauthenticated":
    case "forbidden":
      return { kind: "signed-out" };
    case "csrf-expired":
    case "failed":
      return { kind: result.kind };
  }
}

/**
 * What a self-service password change came to.
 *
 * - `changed` — `204`: the password was replaced and every session of the User
 *   ended, the submitting one included.
 * - `policy-violation` — `400` naming the unmet rule; `message` is the backend's
 *   statement of that rule, which never contains either submitted value.
 * - `current-password-rejected` — `401` while the session survives it.
 * - `locked` — `401` that ended the session: at the lockout threshold the
 *   backend locks the account and revokes every session it holds.
 * - `failed` — includes a `401` whose session check did not answer, which can
 *   confirm neither of the two above.
 *
 * `changed` and `locked` both mean the session is gone, which the caller acts
 * on; this module only classifies, and `session-action-copy.ts` says the rest.
 * Every request outcome resolves to one of these; none throws.
 */
export type PasswordChangeOutcome =
  | { kind: "changed" }
  | { kind: "policy-violation"; message: string }
  | { kind: "current-password-rejected" }
  | { kind: "locked" }
  | RequestFailure;

/**
 * The `PasswordRuleViolation` body's statement of the rule. Any other body
 * throws, which `apiFetch` reads as no `detail` at all.
 */
const decodeRuleMessage = jsonDecoder((body: unknown): string =>
  readObject(body, "PasswordRuleViolation").string("message"),
);

/**
 * Tells a lockout from a wrong current password.
 *
 * The backend answers both with the same bodiless `401`, as it does for Login,
 * so the status alone cannot say which. What does differ is the session: a
 * wrong password leaves it standing, while reaching the threshold locks the
 * account and revokes every session the User holds, this one included. So the
 * session is asked whether it still exists.
 *
 * Only a probe that answers decides: a session that stands is a wrong
 * password, and one that is gone is a lockout. A probe that does not answer
 * cannot tell the two apart, so it claims neither — reading it as a wrong
 * password could hide a lockout — and the change resolves as `failed`, which
 * leaves the session standing on screen.
 */
async function classifyRejection(): Promise<PasswordChangeOutcome> {
  const probe = await apiFetch(ME_PATH);
  switch (probe.kind) {
    case "ok":
      return { kind: "current-password-rejected" };
    case "unauthenticated":
      return { kind: "locked" };
    case "forbidden":
    case "csrf-expired":
    case "failed":
      return { kind: "failed" };
  }
}

/**
 * Submits the session's own password change.
 *
 * Reached through `src/auth`, not `useSessionRequest`, because a `401` here is
 * an answer about the current password, not necessarily a session that has
 * ended; the seam would end the session on it unconditionally.
 */
export async function changePassword(
  currentPassword: string,
  newPassword: string,
): Promise<PasswordChangeOutcome> {
  const result = await apiFetch(
    "/api/auth/change-password",
    {
      body: JSON.stringify({ currentPassword, newPassword }),
      headers: { "Content-Type": "application/json" },
      method: "POST",
    },
    undefined,
    decodeRuleMessage,
  );

  switch (result.kind) {
    case "ok":
      return { kind: "changed" };
    case "unauthenticated":
      return classifyRejection();
    case "forbidden":
      return { kind: "forbidden" };
    case "csrf-expired":
      return { kind: "csrf-expired" };
    case "failed":
      return result.status === 400 && result.detail !== undefined
        ? { kind: "policy-violation", message: result.detail }
        : { kind: "failed" };
  }
}

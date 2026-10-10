/**
 * The one gated write every page's action goes through.
 *
 * A page names the operation — its path, method, optional JSON body, optional
 * success decoder and optional Permission — what to do on success, and the copy
 * for the statuses its action cares about; the hook owns the rest. It sends the
 * operation through the session seam ({@link useSessionRequest}), so a `401`
 * ends the session there exactly once and the page never sees
 * `unauthenticated`. It owns the pending flag, the single error line, mapping a
 * refusal to copy (a transport refusal through the seam's own
 * {@link refusalMessage}, a `failed` one through the page's copy and this
 * hook's defaults), and withdrawing the error of whatever read(s) the write
 * supersedes the moment it starts.
 *
 * An operation naming a Permission the session does not hold is never sent: it
 * settles as `forbidden` at once, which is what the backend would have
 * answered. That is defence in the hook, mirroring {@link useGatedRead}; a page
 * still decides what to render from `holds(...)`, and the backend still
 * enforces every operation on its own.
 *
 * There is no branch for ADR 0006's protected-resource refusal
 * (`400 scimType: mutability`). That shape is the SCIM write surface's alone —
 * `ScimExceptionHandler`, scoped to the SCIM controllers — and the endpoints
 * this hook's pages call answer through the application's own exception
 * handler, which never returns it; the SPA never calls SCIM. So no failure body
 * is decoded here, and such a refusal would read as the generic `400`.
 *
 * Precedence when a `failed` result's message is resolved: a page's own
 * `messages[status]` wins, then its own `messages.default`, then this hook's
 * per-status default, then this hook's own last resort — so a page's `default`
 * stands for every status it does not name.
 */

import { useState } from "react";

import type { ApiDecoder, ApiRequestInit } from "@/lib/http";

import type { Permission } from "./api";
import { useAuth } from "./auth-context-value";
import { holds } from "./permissions";
import { refusalMessage, useSessionRequest, type SessionResult } from "./use-session-request";

/**
 * A write as data: what to send, and how to read the answer. `body`, when
 * present, is sent as JSON; `apiFetch` adds the CSRF header itself.
 */
export interface WriteOperation<T = void> {
  path: string;
  method: "POST" | "DELETE";
  body?: unknown;
  /** Reads the success body; omit it for an operation answering no content. */
  decode?: ApiDecoder<T>;
  /** The Permission the backend requires; without it the operation is never sent. */
  permission?: Permission;
}

/** The subset of a `GatedRead` a write supersedes: cleared on start, consulted as the fallback. */
export interface SupersededRead {
  readonly error: string | null;
  readonly clearError: () => void;
}

/** Copy for a `failed` refusal, keyed by status; `default` covers everything unmapped. */
export type RefusalMessages = Partial<Record<number, string>> & { default?: string };

export interface RunOptions<T> {
  /** The copy a page cares about for this action; falls back to the hook's own defaults. */
  messages?: RefusalMessages;
  /** Called once, only on success, with the decoded data. */
  onOk?: (data: T) => void | Promise<void>;
  /**
   * Runs after the request settles — success or refusal — while still inside
   * the pending window: a listing reload, say. Supplying it marks the write as
   * one whose `supersedes` is the latest news for as long as this hook lives:
   * `error` then shows the first non-null error among `supersedes` ahead of
   * this request's own, since whatever `after` touches is read live and is
   * necessarily the newer of the two.
   */
  after?: () => Promise<void>;
}

export interface GatedWriteOptions {
  /**
   * The read(s) this write supersedes: each has its error withdrawn the moment
   * a write starts, and the first of their current errors is this write's
   * fallback — shown before any write has run, and again once a write's own
   * outcome has nothing to say.
   */
  supersedes?: readonly SupersededRead[];
}

/** Runs one write. Never rejects: a decoder or handler that throws is the caller's bug. */
export interface RunWrite {
  (operation: WriteOperation & { decode?: undefined }, options?: RunOptions<void>): Promise<void>;
  <T>(
    operation: WriteOperation<T> & { decode: ApiDecoder<T> },
    options?: RunOptions<T>,
  ): Promise<void>;
}

export interface GatedWrite {
  /** Whether a write is in flight; every control a write drives disables on this. */
  pending: boolean;
  /** The single error line: this write's own outcome, or the fallback described above. */
  error: string | null;
  run: RunWrite;
}

/** Copy for a status this hook recognises without a page naming its own. */
const DEFAULT_REFUSAL_MESSAGES: Record<number, string> = {
  400: "Refused: check the values and try again.",
  404: "It no longer exists. Reload the page for the current list.",
  409: "Refused: the request conflicts with the resource's current state.",
};

/** The last resort, when neither the page nor the hook names anything for the status. */
const DEFAULT_REFUSAL_MESSAGE = "Unable to complete the action. Please try again.";

function messageFor(status: number | undefined, messages: RefusalMessages): string {
  if (status !== undefined) {
    const named = messages[status] ?? messages.default ?? DEFAULT_REFUSAL_MESSAGES[status];
    if (named !== undefined) return named;
  }
  return messages.default ?? DEFAULT_REFUSAL_MESSAGE;
}

/** The request as `apiFetch` takes it: just the method, or the method and a JSON body. */
function requestInit({ body, method }: WriteOperation<unknown>): ApiRequestInit {
  if (body === undefined) return { method };
  return { body: JSON.stringify(body), headers: { "Content-Type": "application/json" }, method };
}

/** The first non-null error among the reads a write supersedes, or `null` when none has one. */
function firstError(supersedes: readonly SupersededRead[]): string | null {
  for (const dependency of supersedes) {
    if (dependency.error !== null) return dependency.error;
  }
  return null;
}

export function useGatedWrite({ supersedes = [] }: GatedWriteOptions = {}): GatedWrite {
  const { user } = useAuth();
  const request = useSessionRequest();
  const [ownError, setOwnError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  // Whether the most recent run supplied an `after` step: when it did, that
  // step's own outcome (read live off `supersedes` below, never off a value
  // captured before it ran) is the latest event and is checked first. A page
  // that never passes `after` never flips this, so its own result stays
  // first — the two pages' real precedences, read reactively rather than
  // frozen into state the moment `after` resolves. State, not a ref: the
  // value feeds straight into what this render returns, and a ref read
  // during render is not guaranteed to reflect the latest commit.
  const [afterIsLatest, setAfterIsLatest] = useState(false);

  const send = <T>(operation: WriteOperation<T>): Promise<SessionResult<T | void>> => {
    if (operation.permission !== undefined && !holds(user, operation.permission)) {
      return Promise.resolve({ kind: "forbidden" });
    }
    return request(operation.path, requestInit(operation), operation.decode);
  };

  const run = async <T>(
    operation: WriteOperation<T>,
    { after, messages = {}, onOk }: RunOptions<T> = {},
  ): Promise<void> => {
    for (const dependency of supersedes) dependency.clearError();
    setAfterIsLatest(after !== undefined);
    setOwnError(null);
    setPending(true);
    try {
      // Without a decoder `T` is `void` (the overloads on `RunWrite` hold that).
      const result = (await send(operation)) as SessionResult<T>;
      if (result.kind === "ok") {
        await onOk?.(result.data);
      } else if (result.kind === "failed") {
        setOwnError(messageFor(result.status, messages));
      } else {
        // `forbidden` or `csrf-expired`: the seam's own copy, whatever the page names.
        setOwnError(refusalMessage(result, DEFAULT_REFUSAL_MESSAGE));
      }
      if (after) await after();
    } finally {
      setPending(false);
    }
  };

  const supersededError = firstError(supersedes);
  return {
    error: afterIsLatest ? (supersededError ?? ownError) : (ownError ?? supersededError),
    pending,
    run: run as RunWrite,
  };
}

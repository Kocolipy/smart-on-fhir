/**
 * The one gated write every page's action goes through.
 *
 * A page names a {@link WriteDeclaration} — the path, method, optional JSON
 * body, optional success decoder and Permission its API module declares — with
 * the declaration's arguments, what to do on success, and the copy for the
 * statuses its action cares about; the hook owns the rest. It sends the write
 * through the session seam ({@link useSessionRequest}), so a `401` ends the
 * session there exactly once and the page never sees `unauthenticated`. It
 * owns the pending flag and mapping a refusal to copy (a transport refusal
 * through the seam's own {@link refusalMessage}, a `failed` one through the
 * page's copy and this hook's defaults), and reports the outcome to the page's
 * {@link PageStatus}. A write starting supersedes everything the error line
 * shows, so it withdraws every standing failure there before it sends.
 *
 * A declaration naming a Permission the session does not hold is never sent:
 * it settles as `forbidden` at once, which is what the backend would have
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

import type { ApiRequestInit } from "@/lib/http";

import { useAuth } from "./auth-context-value";
import type { DeclarationArgs, WriteDeclaration } from "./declarations";
import { useStatusReporter, type PageStatus } from "./page-status";
import { holds } from "./permissions";
import { refusalMessage, useSessionRequest, type SessionResult } from "./use-session-request";

/** Copy for a `failed` refusal, keyed by status; `default` covers everything unmapped. */
export type RefusalMessages = Partial<Record<number, string>> & { default?: string };

export type RunOptions<A extends readonly unknown[], T> = DeclarationArgs<A> & {
  /** The copy a page cares about for this action; falls back to the hook's own defaults. */
  messages?: RefusalMessages;
  /** Called once, only on success, with the decoded data. */
  onOk?: (data: T) => void | Promise<void>;
  /**
   * Runs after the request settles — success or refusal — while still inside
   * the pending window: a listing reload, say. Whatever it reports to the page
   * status is newer than the write's own outcome, so it is what the error line
   * shows when it reports a failure.
   */
  after?: () => Promise<void>;
};

export interface GatedWriteOptions {
  /** The page's error line, which this write reports its outcome to. */
  status: PageStatus;
}

/** Runs one write. Never rejects: a decoder or handler that throws is the caller's bug. */
export type RunWrite = <A extends readonly unknown[], T>(
  write: WriteDeclaration<A, T>,
  ...options: A extends readonly [] ? [RunOptions<A, T>?] : [RunOptions<A, T>]
) => Promise<void>;

export interface GatedWrite {
  /** Whether a write is in flight; every control a write drives disables on this. */
  pending: boolean;
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
function requestInit(method: string, body: unknown): ApiRequestInit {
  if (body === undefined) return { method };
  return { body: JSON.stringify(body), headers: { "Content-Type": "application/json" }, method };
}

export function useGatedWrite({ status }: GatedWriteOptions): GatedWrite {
  const { user } = useAuth();
  const request = useSessionRequest();
  const { report, supersedeAll } = useStatusReporter(status);
  const [pending, setPending] = useState(false);

  const send = <A extends readonly unknown[], T>(
    write: WriteDeclaration<A, T>,
    args: A,
  ): Promise<SessionResult<T>> => {
    if (!holds(user, write.permission)) return Promise.resolve({ kind: "forbidden" });
    const init = requestInit(write.method, write.body?.(...args));
    // Without a decoder `T` is `void` (`WriteDeclaration` holds that).
    return request(write.path(...args), init, write.decode) as Promise<SessionResult<T>>;
  };

  const run = async <A extends readonly unknown[], T>(
    write: WriteDeclaration<A, T>,
    options?: RunOptions<A, T>,
  ): Promise<void> => {
    const { after, messages = {}, onOk } = options ?? {};
    // An endpoint with a fixed path takes no arguments, and is handed none.
    const args = (options?.args ?? []) as A;
    supersedeAll();
    setPending(true);
    try {
      const result = await send(write, args);
      if (result.kind === "ok") {
        await onOk?.(result.data);
      } else if (result.kind === "failed") {
        report(messageFor(result.status, messages));
      } else {
        // `forbidden` or `csrf-expired`: the seam's own copy, whatever the page names.
        report(refusalMessage(result, DEFAULT_REFUSAL_MESSAGE));
      }
      if (after) await after();
    } finally {
      setPending(false);
    }
  };

  return { pending, run: run as RunWrite };
}

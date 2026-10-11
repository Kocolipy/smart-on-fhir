/**
 * The one Permission-gated read every page's listing goes through.
 *
 * A page names a path, a decoder, the Permission the backend requires for the
 * read, and the copy for a plain failure; the hook does the rest. Without the
 * Permission no request is sent at all — the read would only be refused — and
 * with it the result lands as data or as a refusal with its message, the
 * message read through the seam's own {@link refusalMessage}.
 *
 * What the page renders is one closed {@link ReadView}: the page switches on
 * it rather than rebuilding "a failure is never shown as empty" from loose
 * flags, and never re-checks the Permission the read already checked.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import type { ApiDecoder } from "@/lib/http";

import type { Permission } from "./api";
import { useAuth } from "./auth-context-value";
import { holds } from "./permissions";
import { refusalMessage, useSessionRequest, type SessionResult } from "./use-session-request";

export interface GatedReadOptions<T> {
  path: string;
  /** Must be a stable reference (module-level): the read reruns when it changes. */
  decode: ApiDecoder<T>;
  /** The Permission the read requires; the request is sent only when the session holds it. */
  permission: Permission;
  /** The copy for a plain `failed` result, which only the page can describe. */
  failureMessage: string;
}

/**
 * What a gated read has to show, as one closed state:
 *
 * - `not-permitted` — the session lacks the read's Permission, so nothing was asked;
 * - `loading` — the first read for the current path is still in flight;
 * - `failed` — the latest read was refused or failed, and nothing was ever read;
 * - `ready` — the latest read succeeded, and `data` is its answer;
 * - `stale` — a re-read was refused or failed, and `data` is the last answer, kept.
 *
 * A re-read in flight is not a first load: the data stays `ready` meanwhile.
 */
export type ReadView<T> =
  | { kind: "not-permitted" }
  | { kind: "loading" }
  | { kind: "failed" }
  | { kind: "ready"; data: T }
  | { kind: "stale"; data: T };

export interface GatedRead<T> {
  /** What the page renders. */
  view: ReadView<T>;
  /** The latest read's refusal copy, or `null`; the page's error line, not its view. */
  error: string | null;
  /** Reads again, keeping the current data — as `stale` — if the re-read is refused. */
  reload: () => Promise<void>;
  /** Replaces the data locally, from a response that already states it. */
  update: (next: (current: T | null) => T) => void;
  /** Withdraws the refusal copy, when a later action supersedes it on the page. */
  clearError: () => void;
}

/**
 * How the latest read for the current key went: `unread` before any has
 * answered, `ok` with its data, or `failed` with whatever data was kept.
 */
type ReadState<T> =
  | { outcome: "unread"; data: null; error: null }
  | { outcome: "ok"; data: T; error: null }
  | { outcome: "failed"; data: T | null; error: string | null };

const UNREAD: ReadState<never> = { data: null, error: null, outcome: "unread" };

/**
 * The read state, tied to what it was read for. When the gate closes or the
 * path moves, the old answer is not this read's answer, so it is dropped during
 * the render that notices — never painted alongside the new key.
 */
function useKeyedState<T>(key: string | null) {
  const [state, setState] = useState<ReadState<T>>(UNREAD);
  const [readFor, setReadFor] = useState(key);
  if (readFor !== key) {
    setReadFor(key);
    setState(UNREAD);
  }
  return [state, setState] as const;
}

/**
 * Opens a read whenever `allowed`, and returns the re-read. The open read is
 * `null` while none is (gated off, or unmounted), and a response lands only
 * while its read is still the open one, so an answer for a path the page has
 * moved off, or for a gate that has since closed, is discarded rather than
 * painted.
 */
function useOpenRead<T>(
  allowed: boolean,
  read: () => Promise<SessionResult<T>>,
  land: (result: SessionResult<T>) => void,
): () => Promise<void> {
  const openRead = useRef<object | null>(null);

  useEffect(() => {
    if (!allowed) return;
    const opened = {};
    openRead.current = opened;
    void read().then((result) => {
      if (opened === openRead.current) land(result);
    });
    return () => {
      openRead.current = null;
    };
  }, [allowed, read, land]);

  return useCallback(async () => {
    const opened = openRead.current;
    if (opened === null) return;
    const result = await read();
    if (opened === openRead.current) land(result);
  }, [read, land]);
}

/** The one view a read state stands for. */
function viewOf<T>(allowed: boolean, state: ReadState<T>): ReadView<T> {
  if (!allowed) return { kind: "not-permitted" };
  switch (state.outcome) {
    case "unread":
      return { kind: "loading" };
    case "ok":
      return { data: state.data, kind: "ready" };
    case "failed":
      return state.data === null ? { kind: "failed" } : { data: state.data, kind: "stale" };
  }
}

export function useGatedRead<T>({
  decode,
  failureMessage,
  path,
  permission,
}: GatedReadOptions<T>): GatedRead<T> {
  const { user } = useAuth();
  const allowed = holds(user, permission);
  const request = useSessionRequest();
  const [state, setState] = useKeyedState<T>(allowed ? path : null);

  const read = useCallback(() => request(path, {}, decode), [decode, path, request]);

  const land = useCallback(
    (result: SessionResult<T>) => {
      if (result.kind === "ok") {
        setState({ data: result.data, error: null, outcome: "ok" });
        return;
      }
      const error = refusalMessage(result, failureMessage);
      setState((current) => ({ data: current.data, error, outcome: "failed" }));
    },
    [failureMessage, setState],
  );

  const reload = useOpenRead(allowed, read, land);

  // Neither needs memoising: `setState` is stable, and both are called from
  // event handlers, never named as a dependency. A response that states the
  // data makes it read; only a read that failed stays failed, its data kept.
  const update = (next: (current: T | null) => T) => {
    setState((current) =>
      current.outcome === "failed"
        ? { ...current, data: next(current.data) }
        : { data: next(current.data), error: null, outcome: "ok" },
    );
  };

  const clearError = () => {
    setState((current) => ({ ...current, error: null }));
  };

  return { clearError, error: state.error, reload, update, view: viewOf(allowed, state) };
}

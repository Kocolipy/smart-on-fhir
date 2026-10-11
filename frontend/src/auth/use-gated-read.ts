/**
 * The one Permission-gated read every page's listing goes through.
 *
 * A page names a {@link ReadDeclaration} — the path, decoder and Permission
 * its API module declares — with the declaration's arguments, the copy for a
 * plain failure, and the {@link PageStatus} its error line reads; the hook does
 * the rest. Without the Permission no request is sent at all — the read would
 * only be refused — and with it the result lands as data, or as a refusal
 * whose message, read through the seam's own {@link refusalMessage}, is
 * reported to the page status.
 *
 * What the page renders is one closed {@link ReadView}: the page switches on
 * it rather than rebuilding "a failure is never shown as empty" from loose
 * flags, and never re-checks the Permission the read already checked. The
 * error line is the page status's, not this hook's: a read reports its outcome
 * there and knows nothing about what else does.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import { useAuth } from "./auth-context-value";
import type { DeclarationArgs, ReadDeclaration } from "./declarations";
import { useStatusReporter, type PageStatus, type StatusReporter } from "./page-status";
import { holds } from "./permissions";
import { refusalMessage, useSessionRequest, type SessionResult } from "./use-session-request";

export type GatedReadOptions<A extends readonly unknown[]> = DeclarationArgs<A> & {
  /** The copy for a plain `failed` result, which only the page can describe. */
  failureMessage: string;
  /** The page's error line, which this read reports its outcome to. */
  status: PageStatus;
};

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
  /** Reads again, keeping the current data — as `stale` — if the re-read is refused. */
  reload: () => Promise<void>;
  /** Replaces the data locally, from a response that already states it. */
  update: (next: (current: T | null) => T) => void;
}

/**
 * How the latest read for the current key went: `unread` before any has
 * answered, `ok` with its data, or `failed` with whatever data was kept.
 */
type ReadState<T> =
  | { outcome: "unread"; data: null }
  | { outcome: "ok"; data: T }
  | { outcome: "failed"; data: T | null };

const UNREAD: ReadState<never> = { data: null, outcome: "unread" };

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
 * painted. Closing a read withdraws whatever it reported: its outcome is no
 * longer news once its path has moved or its gate has closed.
 */
function useOpenRead<T>(
  allowed: boolean,
  read: () => Promise<SessionResult<T>>,
  land: (result: SessionResult<T>) => void,
  report: StatusReporter["report"],
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
      report(null);
    };
  }, [allowed, read, land, report]);

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

export function useGatedRead<T, A extends readonly unknown[]>(
  { decode, path: pathOf, permission }: ReadDeclaration<T, A>,
  options: GatedReadOptions<A>,
): GatedRead<T> {
  const { failureMessage, status } = options;
  // An endpoint with a fixed path takes no arguments, and is handed none.
  const path = pathOf(...((options.args ?? []) as A));
  const { user } = useAuth();
  const allowed = holds(user, permission);
  const request = useSessionRequest();
  const { report } = useStatusReporter(status);
  const [state, setState] = useKeyedState<T>(allowed ? path : null);

  const read = useCallback(() => request(path, {}, decode), [decode, path, request]);

  const land = useCallback(
    (result: SessionResult<T>) => {
      if (result.kind === "ok") {
        setState({ data: result.data, outcome: "ok" });
        report(null);
        return;
      }
      setState((current) => ({ data: current.data, outcome: "failed" }));
      report(refusalMessage(result, failureMessage));
    },
    [failureMessage, report, setState],
  );

  const reload = useOpenRead(allowed, read, land, report);

  // No need to memoise: `setState` is stable, and this is called from event
  // handlers, never named as a dependency. A response that states the data
  // makes it read; only a read that failed stays failed, its data kept.
  const update = (next: (current: T | null) => T) => {
    setState((current) =>
      current.outcome === "failed"
        ? { ...current, data: next(current.data) }
        : { data: next(current.data), outcome: "ok" },
    );
  };

  return { reload, update, view: viewOf(allowed, state) };
}

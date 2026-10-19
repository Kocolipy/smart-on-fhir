/**
 * The one Permission-gated read every page's listing goes through.
 *
 * A page names a {@link ReadDeclaration} — the path, decoder and Permission
 * its API module declares — with the declaration's arguments and the copy for
 * a plain failure; the hook does the rest. Without the Permission no request
 * is sent at all — the read would only be refused — and with it the result
 * lands as data, or as a refusal whose message, read through the seam's own
 * {@link refusalMessage}, is reported to the page status the signed-in layout
 * holds (`page-status.ts`), through a reporter of the read's own.
 *
 * What the page renders is one closed {@link ReadView}: the page switches on
 * it rather than rebuilding "a failure is never shown as empty" from loose
 * flags, and never re-checks the Permission the read already checked. The
 * error line is the page status's, not this hook's: a read reports its outcome
 * there and knows nothing about what else does.
 *
 * Reads on one key follow the page status's own rule, the latest event wins:
 * every read sent supersedes the one in flight, so however the answers arrive,
 * only the newest lands.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import { useAuth } from "./auth-context-value";
import type { DeclarationArgs, ReadDeclaration } from "./declarations";
import { useStatusReporter, type StatusReporter } from "./page-status";
import { holds } from "./permissions";
import { refusalMessage, useSessionRequest, type SessionResult } from "./use-session-request";

export type GatedReadOptions<A extends readonly unknown[]> = DeclarationArgs<A> & {
  /** The copy for a plain `failed` result, which only the page can describe. */
  failureMessage: string;
  /**
   * Reports a refusal (`403`) with `failureMessage` rather than the seam's
   * refusal copy: for a read whose Permission the gate already found held, a
   * page may show a refusal as the read failing — the Patient card does.
   */
  forbiddenAsFailure?: boolean;
};

/**
 * What a gated read has to show, as one closed state:
 *
 * - `not-permitted` — the session lacks the read's Permission, so nothing was asked;
 * - `loading` — the first read for the current path is still in flight;
 * - `failed` — the latest read was refused or failed, and nothing was ever read;
 * - `ready` — the latest read succeeded, and `data` is its answer;
 * - `stale` — a re-read was refused or failed, and `data` is the last answer, kept;
 * - `absent` — the latest read found nothing there (a `404`), which is not a
 *   failure: only a read whose declaration opts in with `absentOnNotFound`
 *   (`N`) has this view, and nothing earlier is kept as `stale` beside it.
 *
 * A re-read in flight is not a first load: the data stays `ready` meanwhile.
 */
export type ReadView<T, N extends boolean = false> =
  | { kind: "not-permitted" }
  | { kind: "loading" }
  | { kind: "failed" }
  | { kind: "ready"; data: T }
  | { kind: "stale"; data: T }
  | (N extends true ? { kind: "absent" } : never);

export interface GatedRead<T, N extends boolean = false> {
  /** What the page renders. */
  view: ReadView<T, N>;
  /**
   * Reads now, for the arguments the next render names, keeping the current
   * data — as `stale` — if the re-read is refused. It always sends while the
   * gate is open (nothing while it is shut), and supersedes any read in
   * flight, so only the newest answer lands. Asked for in the same event as a
   * change of arguments, it is that change's one read, not a second. Settles
   * once its read has answered.
   */
  reload: () => Promise<void>;
  /** Replaces the data locally, from a response that already states it. */
  update: (next: (current: T | null) => T) => void;
}

/**
 * How the latest read for the current key went: `unread` before any has
 * answered, `ok` with its data, `failed` with whatever data was kept, or
 * `absent` — found nothing there, so there is no data to keep.
 */
type ReadState<T> =
  | { outcome: "unread"; data: null }
  | { outcome: "ok"; data: T }
  | { outcome: "failed"; data: T | null }
  | { outcome: "absent"; data: null };

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
 * Sends a read whenever `allowed` — on opening, and again for every re-read —
 * and returns the re-read. **The newest read wins**: each read sent supersedes
 * the one in flight, and a response lands only while its read is still the
 * open one, so an older answer arriving after a newer one is discarded. The
 * open read is `null` while none is (gated off, or unmounted), so an answer
 * for a path the page has moved off, or for a gate that has since closed, is
 * discarded the same way.
 *
 * Every read is sent from the one effect: a re-read bumps the read generation
 * rather than sending, so a re-read asked for in the same event as a change
 * of path is the new path's one read, never a second. Its promise settles once
 * that read has answered, landed or discarded.
 *
 * Closing a read — its path moved, its gate shut, the page gone — withdraws
 * whatever it reported: its outcome is no longer news. A re-read only
 * supersedes, so what the last read reported stands until the next one lands.
 */
function useOpenRead<T>(
  allowed: boolean,
  read: () => Promise<SessionResult<T>>,
  land: (result: SessionResult<T>) => void,
  report: StatusReporter["report"],
): () => Promise<void> {
  const openRead = useRef<object | null>(null);
  // A fresh token per re-read: only its identity matters, as an effect dependency.
  const [generation, setGeneration] = useState<object>({});
  // The re-reads asked for since the last read was sent; the next one answers them.
  const waiting = useRef<(() => void)[]>([]);

  useEffect(() => {
    const settles = waiting.current.splice(0);
    if (!allowed) {
      settles.forEach((settle) => settle());
      return;
    }
    const opened = {};
    openRead.current = opened;
    void read().then((result) => {
      if (opened === openRead.current) land(result);
      settles.forEach((settle) => settle());
    });
    return () => {
      openRead.current = null;
    };
  }, [allowed, read, land, generation]);

  useEffect(() => () => report(null), [allowed, read, land, report]);

  // Made once: it reads only refs and the stable `setGeneration`.
  const [reload] = useState(() => (): Promise<void> => {
    if (openRead.current === null) return Promise.resolve();
    return new Promise<void>((settle) => {
      waiting.current.push(settle);
      setGeneration({});
    });
  });
  return reload;
}

/** The one view a read state stands for, out of every view a read can have. */
function viewOf<T>(allowed: boolean, state: ReadState<T>): ReadView<T, true> {
  if (!allowed) return { kind: "not-permitted" };
  switch (state.outcome) {
    case "unread":
      return { kind: "loading" };
    case "ok":
      return { data: state.data, kind: "ready" };
    case "failed":
      return state.data === null ? { kind: "failed" } : { data: state.data, kind: "stale" };
    case "absent":
      return { kind: "absent" };
  }
}

export function useGatedRead<T, A extends readonly unknown[], N extends boolean = false>(
  { absentOnNotFound, decode, path: pathOf, permission }: ReadDeclaration<T, A, N>,
  options: GatedReadOptions<A>,
): GatedRead<T, N> {
  const { failureMessage, forbiddenAsFailure = false } = options;
  // An endpoint with a fixed path takes no arguments, and is handed none.
  const path = pathOf(...((options.args ?? []) as A));
  const { user } = useAuth();
  const allowed = holds(user, permission);
  const request = useSessionRequest();
  const { report } = useStatusReporter();
  const [state, setState] = useKeyedState<T>(allowed ? path : null);

  const read = useCallback(() => request(path, {}, decode), [decode, path, request]);

  const land = useCallback(
    (result: SessionResult<T>) => {
      switch (result.kind) {
        case "ok":
          setState({ data: result.data, outcome: "ok" });
          report(null);
          return;
        case "failed":
          // Nothing there is an answer, not a failure: there is no data to keep
          // as `stale`, and nothing to report, so an earlier failure's line clears.
          if (absentOnNotFound && result.status === 404) {
            setState({ data: null, outcome: "absent" });
            report(null);
            return;
          }
      }
      setState((current) => ({ data: current.data, outcome: "failed" }));
      report(
        forbiddenAsFailure && result.kind === "forbidden"
          ? failureMessage
          : refusalMessage(result, failureMessage),
      );
    },
    [absentOnNotFound, failureMessage, forbiddenAsFailure, report, setState],
  );

  const reload = useOpenRead(allowed, read, land, report);

  // No need to memoise: `setState` is stable, and this is called from event
  // handlers, never named as a dependency. A response that states the data
  // makes it read — an `absent` one too, as the response says something is
  // there now; only a read that failed stays failed, its data kept.
  const update = (next: (current: T | null) => T) => {
    setState((current) =>
      current.outcome === "failed"
        ? { ...current, data: next(current.data) }
        : { data: next(current.data), outcome: "ok" },
    );
  };

  // A state is `absent` only when the declaration opted in, as its `N` says,
  // so the view is narrowed once, here, to the views this read can have.
  return { reload, update, view: viewOf(allowed, state) as ReadView<T, N> };
}

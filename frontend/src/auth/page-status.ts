/**
 * A page's single error line, and the one rule that decides what it shows.
 *
 * Every gated read and write on a page — and anything else the page refuses
 * locally, such as a filter that does not parse — reports its outcome here
 * rather than holding an error of its own, so no hook needs to know about any
 * other. The rule is **the latest event wins**:
 *
 * - a failure is reported by its source and becomes what the line shows;
 * - a success withdraws its own source's failure, and only that one, so a
 *   failure still standing from another source shows again;
 * - an event that supersedes everything — a write starting — withdraws every
 *   standing failure at once.
 *
 * So the line always shows the most recent failure nothing has since
 * withdrawn, or nothing.
 */

import { useId, useState } from "react";

/** One source's standing failure. */
interface Standing {
  readonly source: string;
  readonly message: string;
}

/** What the page renders, and what reporters are bound to. */
export interface PageStatus {
  /** The error line: the latest standing failure, or `null`. */
  readonly error: string | null;
  /** Records `source`'s outcome: a failure's copy, or `null` for a success. Stable across renders. */
  readonly report: (source: string, message: string | null) => void;
  /** Withdraws every standing failure. Stable across renders. */
  readonly supersedeAll: () => void;
}

/** One source's handle on a page status, stable across renders. */
export interface StatusReporter {
  /** This source's outcome: its failure's copy, or `null` once it has succeeded. */
  readonly report: (message: string | null) => void;
  /** Withdraws every standing failure, this source's and every other's. */
  readonly supersedeAll: () => void;
}

/** A page's error line; the page renders `error` and hands the status to its reads and writes. */
export function usePageStatus(): PageStatus {
  // Oldest first, at most one entry per source: the last entry is the line.
  const [standing, setStanding] = useState<readonly Standing[]>([]);
  // Made once: `setStanding` is stable, so these are too, and a hook naming
  // one as a dependency never reruns for it.
  const [changes] = useState(() => ({
    report: (source: string, message: string | null) => {
      setStanding((current) => {
        const others = current.filter((entry) => entry.source !== source);
        return message === null ? others : [...others, { message, source }];
      });
    },
    supersedeAll: () => setStanding([]),
  }));

  const error = standing.length === 0 ? null : standing[standing.length - 1].message;
  return { ...changes, error };
}

/** A new source on `status`: what one read, one write or one local check reports through. */
export function useStatusReporter({ report, supersedeAll }: PageStatus): StatusReporter {
  const source = useId();
  // Made once: the status's own functions and this source's id never change.
  const [reporter] = useState(() => ({
    report: (message: string | null) => report(source, message),
    supersedeAll,
  }));
  return reporter;
}

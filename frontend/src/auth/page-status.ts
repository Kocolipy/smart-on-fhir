/**
 * The page's single error line, the one rule that decides what it shows, and
 * the one place it exists.
 *
 * Every gated read and write on a page — and anything else that refuses
 * locally, such as a filter that does not parse, or a session action left on
 * the page — reports its outcome here rather than holding an error of its own,
 * so no hook needs to know about any other. The rule is **the latest event
 * wins**:
 *
 * - a failure is reported by its source and becomes what the line shows;
 * - a success withdraws its own source's failure, and only that one, so a
 *   failure still standing from another source shows again;
 * - an event that supersedes everything — a write starting — withdraws every
 *   standing failure at once.
 *
 * So the line always shows the most recent failure nothing has since
 * withdrawn, or nothing.
 *
 * **One line per page is structural, not a convention.** The status exists
 * only inside a {@link PageStatusScope}, which the signed-in layout renders
 * once around every signed-in page, and which refuses to be rendered inside
 * another. Pages, their panels and Sign out reach it only through
 * {@link useStatusReporter}, which reports to it and cannot read it, so nothing
 * below the layout can create a status or draw the line. The scope starts
 * empty on every page (`resetKey`), and a source that unmounts withdraws its
 * failure and reports nothing after — so a failure on one page never follows
 * the user to the next.
 */

import {
  createContext,
  createElement,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
  type ReactNode,
} from "react";

/** One source's standing failure. */
interface Standing {
  readonly source: string;
  readonly message: string;
}

/** The changes a scope accepts; what every reporter on it is bound to. */
interface StatusChanges {
  /** Records `source`'s outcome: a failure's copy, or `null` for a success. */
  readonly report: (source: string, message: string | null) => void;
  /** Withdraws every standing failure. */
  readonly supersedeAll: () => void;
}

/** One source's handle on the page status, stable across renders. */
export interface StatusReporter {
  /** This source's outcome: its failure's copy, or `null` once it has succeeded. */
  readonly report: (message: string | null) => void;
  /** Withdraws every standing failure, this source's and every other's. */
  readonly supersedeAll: () => void;
}

const PageStatusContext = createContext<StatusChanges | null>(null);

/**
 * The standing failures, and the line they make: emptied whenever `resetKey`
 * changes, on the render that sees it change.
 */
function useStanding(resetKey: string): { changes: StatusChanges; error: string | null } {
  // Oldest first, at most one entry per source: the last entry is the line.
  const [standing, setStanding] = useState<readonly Standing[]>([]);
  const [standingFor, setStandingFor] = useState(resetKey);
  if (standingFor !== resetKey) {
    setStandingFor(resetKey);
    setStanding([]);
  }
  // Made once: `setStanding` is stable, so these are too, and a hook naming
  // one as a dependency never reruns for it.
  const [changes] = useState<StatusChanges>(() => ({
    report: (source, message) => {
      setStanding((current) => {
        const others = current.filter((entry) => entry.source !== source);
        return message === null ? others : [...others, { message, source }];
      });
    },
    supersedeAll: () => setStanding([]),
  }));

  const error = standing.length === 0 ? null : standing[standing.length - 1].message;
  return { changes, error };
}

/**
 * The page status, for everything rendered inside it, and its line, for
 * `children` to render once. The signed-in layout renders it, keyed on the
 * path, so the line starts empty on every page. Throws when rendered inside
 * another: a page has one line.
 */
export function PageStatusScope({
  children,
  resetKey,
}: {
  children: (error: string | null) => ReactNode;
  resetKey: string;
}): ReactNode {
  if (useContext(PageStatusContext) !== null) {
    throw new Error("A page status already encloses this one: a page has one error line.");
  }
  const { changes, error } = useStanding(resetKey);
  return createElement(PageStatusContext.Provider, { value: changes }, children(error));
}

/**
 * A new source on the enclosing page status: what one read, one write, one
 * session action or one local check reports through. Throws outside a
 * {@link PageStatusScope}, where there is no line to report to.
 *
 * Once the source unmounts it withdraws its failure and reports nothing more,
 * so an answer landing after the user has left the page does not reach the
 * next one's line.
 */
export function useStatusReporter(): StatusReporter {
  const changes = useContext(PageStatusContext);
  if (changes === null) {
    throw new Error("A page status is reported to only inside the signed-in layout's scope.");
  }
  const source = useId();
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      changes.report(source, null);
    };
  }, [changes, source]);
  // Made once: the scope's changes and this source's id never change.
  const [reporter] = useState<StatusReporter>(() => ({
    report: (message) => {
      if (mounted.current) changes.report(source, message);
    },
    supersedeAll: () => {
      if (mounted.current) changes.supersedeAll();
    },
  }));
  return reporter;
}

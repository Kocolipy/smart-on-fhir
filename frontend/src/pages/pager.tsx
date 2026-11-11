import {
  Pagination,
  PaginationContent,
  PaginationEllipsis,
  PaginationItem,
} from "@/components/ui/pagination";
import type { Page } from "@/lib/paging";
import { PagerMove } from "@/pages/pager-move";

/** Up to this many pages, every page number shows; past it, the ends and the pages beside the one shown. */
const ALL_PAGES_UP_TO = 7;

/**
 * The page numbers a pager offers, in order, with `"gap"` where a run of
 * pages is left out: every page up to {@link ALL_PAGES_UP_TO}, past it the
 * first, the last, and the page shown with its neighbours. A gap always
 * stands for two pages or more.
 */
function pageNumbersOf(current: number, count: number): (number | "gap")[] {
  if (count <= ALL_PAGES_UP_TO) return Array.from({ length: count }, (_, i) => i + 1);
  let from = Math.max(2, Math.min(current - 1, count - 3));
  let to = Math.min(count - 1, Math.max(current + 1, 4));
  // A gap of a single page shows that page instead.
  if (from === 3) from = 2;
  if (to === count - 2) to = count - 1;
  const middle = Array.from({ length: to - from + 1 }, (_, i) => from + i);
  return [
    1,
    ...(from > 2 ? ["gap" as const] : []),
    ...middle,
    ...(to < count - 1 ? ["gap" as const] : []),
    count,
  ];
}

/**
 * Where the page shown sits among every row its read matched, and the moves
 * from it: « back by one page, never before the first; » on from where this
 * page ended — counted from the page's own `startIndex` and row count, so a
 * page that came back short is moved on from its last row; and a number for
 * each page, which moves to that page's first row. Every paged read moves
 * through this one control. A move with nowhere to go — « on the first page,
 * » once no rows lie past this one — is disabled, never hidden.
 *
 * Renders nothing while every row the read matched sits on the first page —
 * there is no page to move to, and the body already shows every row, or its
 * own empty copy. An empty page that is *not* the first still renders: a row
 * removed after the read that showed the page before it can leave the page the
 * operator moved to empty while the listing is not, and « is how they step
 * back.
 */
export function Pager({
  disabled = false,
  label,
  noun,
  onMove,
  page: { rows, startIndex, totalResults },
  pageSize,
}: {
  /** Holds the page still, as while an action that answers onto it is in flight. */
  disabled?: boolean;
  /** The control's accessible name. */
  label: string;
  /** What the rows are, lower-case and plural, as the position line names them: "Showing 1 to 20 of 45 users". */
  noun: string;
  onMove: (startIndex: number) => void;
  /** The page shown, which the position line and the moves are counted from. */
  page: Page<unknown>;
  pageSize: number;
}) {
  const last = startIndex + rows.length - 1;
  if (startIndex <= 1 && last >= totalResults) return null;
  const current = Math.floor((startIndex - 1) / pageSize) + 1;
  const count = Math.max(current, Math.ceil(totalResults / pageSize));
  return (
    <Pagination aria-label={label} className="mx-0 mt-2 items-center justify-between gap-4">
      {/* An empty page's own range (`startIndex`–`last`) would read
          backwards, since `last` falls one short of `startIndex`: say how
          many rows there are in all instead of a range that contradicts itself. */}
      <p className="text-sm text-muted-foreground">
        {rows.length === 0
          ? `Showing 0 of ${totalResults} ${noun}`
          : `Showing ${startIndex} to ${last} of ${totalResults} ${noun}`}
      </p>
      <PaginationContent className="gap-1.5">
        <PaginationItem>
          <PagerMove
            disabled={disabled || startIndex <= 1}
            label="Previous"
            onMove={() => onMove(Math.max(1, startIndex - pageSize))}
          >
            «
          </PagerMove>
        </PaginationItem>
        {pageNumbersOf(current, count).map((number, i) => (
          <PaginationItem key={number === "gap" ? `gap-${i}` : number}>
            {number === "gap" ? (
              <PaginationEllipsis className="size-8" />
            ) : (
              <PagerMove
                current={number === current}
                disabled={disabled}
                label={`Page ${number}`}
                onMove={() => onMove((number - 1) * pageSize + 1)}
              >
                {number}
              </PagerMove>
            )}
          </PaginationItem>
        ))}
        <PaginationItem>
          <PagerMove
            disabled={disabled || last >= totalResults}
            label="Next"
            onMove={() => onMove(last + 1)}
          >
            »
          </PagerMove>
        </PaginationItem>
      </PaginationContent>
    </Pagination>
  );
}

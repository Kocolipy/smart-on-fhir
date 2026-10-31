import { Pagination, PaginationContent, PaginationItem } from "@/components/ui/pagination";
import { PagerMove } from "@/pages/pager-move";

/** A page of a paged read's rows, as a pager counts them: its rows, where it starts, and the total. */
export interface PagedRows<R> {
  rows: readonly R[];
  startIndex: number;
  totalResults: number;
}

/**
 * Where the page shown sits among every row its read matched, and the moves
 * from it: back by one page, never before the first, and on from where this
 * page ended — counted from the page's own `startIndex` and row count, so a
 * page that came back short is moved on from its last row. Every paged read
 * moves through this one control. A move with nowhere to go — Previous on the
 * first page, Next once no rows lie past this one — is disabled, never hidden.
 *
 * Renders nothing for an empty first page — there is no page to move from or
 * to, and the body's own empty copy already says so. An empty page that is
 * *not* the first still renders: a row removed after the read that showed the
 * page before it can leave the page the operator moved to empty while the
 * listing is not, and Previous is how they step back.
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
  /** What the rows are, as the position line names them: "Users 1–20 of 45". */
  noun: string;
  onMove: (startIndex: number) => void;
  /** The page shown, which the position line and the moves are counted from. */
  page: PagedRows<unknown>;
  pageSize: number;
}) {
  if (rows.length === 0 && startIndex === 1) return null;
  const last = startIndex + rows.length - 1;
  return (
    <Pagination aria-label={label} className="mx-0 mt-2 justify-start">
      <PaginationContent className="gap-4">
        <PaginationItem>
          <PagerMove
            direction="previous"
            disabled={disabled || startIndex <= 1}
            onMove={() => onMove(Math.max(1, startIndex - pageSize))}
          />
        </PaginationItem>
        <PaginationItem>
          {/* An empty page's own range (`startIndex`–`last`) would read
              backwards, since `last` falls one short of `startIndex`: say how
              many rows there are in all instead of a range that contradicts itself. */}
          <p className="text-sm text-muted-foreground">
            {rows.length === 0
              ? `${noun} 0 of ${totalResults}`
              : `${noun} ${startIndex}–${last} of ${totalResults}`}
          </p>
        </PaginationItem>
        <PaginationItem>
          <PagerMove
            direction="next"
            disabled={disabled || last >= totalResults}
            onMove={() => onMove(last + 1)}
          />
        </PaginationItem>
      </PaginationContent>
    </Pagination>
  );
}

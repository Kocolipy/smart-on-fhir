import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react";

import { Button } from "@/components/ui/button";

/**
 * One move of a pager — Previous or Next — as every pager renders it: a
 * `Button` with its chevron and its visible name, placed in a shadcn
 * `PaginationItem` by the pager that owns it.
 *
 * Not shadcn's `PaginationPrevious` / `PaginationNext`: they render an `<a>`,
 * and paging here is component state rather than a URL, so an `<a>` without an
 * `href` would be neither a link nor reachable by keyboard. A move with nowhere
 * to go is disabled by its pager, never hidden.
 */
export function PagerMove({
  direction,
  disabled,
  onMove,
}: {
  direction: "previous" | "next";
  disabled: boolean;
  onMove: () => void;
}) {
  return (
    <Button disabled={disabled} onClick={onMove} size="sm" variant="outline">
      {direction === "previous" ? (
        <>
          <ChevronLeftIcon />
          Previous
        </>
      ) : (
        <>
          Next
          <ChevronRightIcon />
        </>
      )}
    </Button>
  );
}

import type { ReactNode } from "react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

/**
 * One button of a pager — «, », or a page number — as every pager renders it:
 * a square `Button` placed in a shadcn `PaginationItem` by the pager that owns
 * it. The page shown is filled with the primary color and marked
 * `aria-current="page"`; every other button is outlined.
 *
 * Not shadcn's `PaginationLink`: it renders an `<a>`, and paging here is
 * component state rather than a URL, so an `<a>` without an `href` would be
 * neither a link nor reachable by keyboard. A move with nowhere to go is
 * disabled by its pager, never hidden.
 */
export function PagerMove({
  children,
  current = false,
  disabled,
  label,
  onMove,
}: {
  /** What the button shows: « , » or the page's number. */
  children: ReactNode;
  /** Whether this is the page shown. */
  current?: boolean;
  disabled: boolean;
  /** The button's accessible name: "Previous", "Next", "Page 2". */
  label: string;
  onMove: () => void;
}) {
  return (
    <Button
      aria-current={current ? "page" : undefined}
      aria-label={label}
      className={cn("size-10 font-normal", current ? "" : "text-muted-foreground")}
      disabled={disabled}
      onClick={current ? undefined : onMove}
      size="icon"
      variant={current ? "default" : "outline"}
    >
      {children}
    </Button>
  );
}

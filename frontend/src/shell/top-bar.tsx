import { Bell, CircleHelp } from "lucide-react";
import { Link } from "react-router-dom";

import type { AuthUser } from "@/auth/api";

import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { SidebarTrigger } from "@/components/ui/sidebar";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { usePatientInContext } from "@/pages/home/patient-in-context";
import { SETTINGS_PAGE } from "@/shell/signed-in-pages";

/**
 * The patient in context, as the top bar names them: their name and NRIC,
 * rendered from the layout's one read of them (`usePatientInContext`). It
 * shows nothing with no patient in context, until the read has answered, and
 * for a session without `patient:read`, a patient that is not there or a read
 * that failed — whose line on the page's error line is the read's, not this.
 */
function PatientInContext() {
  const view = usePatientInContext();
  if (view.kind !== "ready") return null;
  return (
    <section
      aria-label="Patient in context"
      className="flex w-fit max-w-full min-w-0 items-baseline gap-2 rounded-lg bg-muted px-3 py-1"
    >
      {/* A full line height, not leading-none: truncate clips to the line box,
          which would cut the glyphs' ascenders and descenders. */}
      <span className="min-w-0 truncate text-2xl leading-8 font-bold tracking-tight">
        {view.data.name}
      </span>
      <span className="shrink-0 text-sm font-semibold tracking-widest text-muted-foreground uppercase">
        {view.data.nric}
      </span>
    </section>
  );
}

/** The most Groups the bar names on lines of their own before it counts the rest. */
const GROUPS_SHOWN = 2;

/** A Group's line: the muted subtitle style, upper-cased by CSS so the text stays as sent. */
const GROUP_LINE =
  "truncate text-[10px] leading-3 font-bold tracking-wider text-muted-foreground uppercase";

/** Marks each line the bar shows naming who is signed in, which no role or label tells apart. */
const SIGNED_IN_LINE = "signed-in-line";

/**
 * Who is signed in: the User's name exactly as the backend sends it, in the
 * primary colour, and beneath it one line per Group the session was signed in
 * with, in the backend's order. More than {@link GROUPS_SHOWN} Groups show
 * the first ones, then a line counting the rest whose tooltip lists them all.
 * A User in no Group gets the name alone. A screen reader hears one sentence
 * naming the User and every Group instead of the lines, then the counting
 * line as a button that shows all the Groups, which a keyboard reaches too.
 * While the session's User is not known, it names nobody.
 */
function SignedInUser({ user }: { user: AuthUser | null }) {
  const groups = user?.groups ?? [];
  const hidden = groups.length - GROUPS_SHOWN;
  return (
    <div className="flex max-w-40 min-w-0 flex-col items-end text-right" data-slot="signed-in-user">
      <p className="sr-only">
        Signed in as {user?.username}
        {groups.length === 0 ? null : `, in ${groups.join(", ")}`}
      </p>
      <span
        aria-hidden="true"
        className="max-w-full truncate text-sm leading-5 font-semibold text-primary"
        data-testid={SIGNED_IN_LINE}
      >
        {user?.username}
      </span>
      {groups.slice(0, GROUPS_SHOWN).map((group) => (
        <span
          aria-hidden="true"
          className={`max-w-full ${GROUP_LINE}`}
          data-testid={SIGNED_IN_LINE}
          key={group}
        >
          {group}
        </span>
      ))}
      {hidden > 0 ? (
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              aria-label={`Show all ${groups.length} Groups`}
              className={`max-w-full rounded-sm outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 ${GROUP_LINE}`}
              data-testid={SIGNED_IN_LINE}
              type="button"
            >
              +{hidden} more
            </button>
          </TooltipTrigger>
          <TooltipContent align="end">
            <ul>
              {groups.map((group) => (
                <li key={group}>{group}</li>
              ))}
            </ul>
          </TooltipContent>
        </Tooltip>
      ) : null}
    </div>
  );
}

/**
 * The bar across the top of every signed-in page: the patient in the tab's EHR
 * context (`usePatientInContext`) on the left, followed as the FHIRcast hub moves it,
 * and on the right Notifications, a link to Settings, Help and who is signed
 * in ({@link SignedInUser}), kept to the bar's 64px however many Groups there
 * are. On a phone it also leads with the menu button that opens the navigation
 * sidebar. Nothing yet sends notifications, so Notifications says there are
 * none rather than showing an unread mark.
 */
export function TopBar({ user }: { user: AuthUser | null }) {
  return (
    <header className="sticky top-0 z-10 flex h-16 shrink-0 items-center justify-between gap-4 border-b bg-card px-6">
      <div className="flex min-w-0 flex-1 items-center gap-2">
        {/* On a phone the sidebar is a sheet, and this opens it. */}
        <SidebarTrigger aria-label="Open navigation" className="md:hidden" />
        <PatientInContext />
      </div>
      <div className="flex shrink-0 items-center gap-1">
        <Popover>
          <PopoverTrigger asChild>
            <Button aria-label="Notifications" size="icon" variant="ghost">
              <Bell />
            </Button>
          </PopoverTrigger>
          <PopoverContent align="end" className="w-64 text-sm">
            No notifications.
          </PopoverContent>
        </Popover>
        <Button aria-label="Open settings" asChild size="icon" variant="ghost">
          <Link to={SETTINGS_PAGE.path}>
            <SETTINGS_PAGE.icon />
          </Link>
        </Button>
        <Popover>
          <PopoverTrigger asChild>
            <Button aria-label="Help" size="icon" variant="ghost">
              <CircleHelp />
            </Button>
          </PopoverTrigger>
          <PopoverContent align="end" className="w-64 text-sm">
            Press Ctrl+B (⌘B on a Mac) to collapse or expand the sidebar.
          </PopoverContent>
        </Popover>
        <div aria-hidden="true" className="mx-3 h-8 w-px bg-border" />
        <SignedInUser user={user} />
      </div>
    </header>
  );
}

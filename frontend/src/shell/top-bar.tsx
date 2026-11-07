import { Bell, CircleHelp, UserRound } from "lucide-react";
import { Link } from "react-router-dom";

import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { SidebarTrigger } from "@/components/ui/sidebar";
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
      <span
        aria-hidden="true"
        className="mr-1 size-2 shrink-0 self-center rounded-full bg-secondary"
      />
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

/**
 * The bar across the top of every signed-in page: the patient in the tab's EHR
 * context (`usePatientInContext`) on the left, followed as the FHIRcast hub moves it,
 * and on the right Notifications, a link to Settings, Help and who is signed
 * in. On a phone it also leads with the menu button that opens the navigation
 * sidebar. Nothing yet sends notifications, so Notifications says there are
 * none rather than showing an unread mark.
 */
export function TopBar({ username }: { username: string | undefined }) {
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
        <div className="flex flex-col items-center gap-0.5">
          <Avatar className="size-7">
            <AvatarFallback>
              <UserRound className="size-4" />
            </AvatarFallback>
          </Avatar>
          {/* Shown as the bare name; read out, as before, with what it means. */}
          <p className="sr-only">Signed in as {username}</p>
          <span
            aria-hidden="true"
            className="max-w-32 truncate text-[10px] font-bold tracking-wider text-muted-foreground uppercase"
          >
            {username}
          </span>
        </div>
      </div>
    </header>
  );
}

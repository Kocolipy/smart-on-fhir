import { ChevronDown, ChevronLeft, ChevronRight, LogOut, ShieldCheck } from "lucide-react";
import { useState } from "react";
import { Link, matchPath } from "react-router-dom";

import { useAuth } from "@/auth/auth-context-value";
import { mayOpen, requirementOf } from "@/auth/permissions";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  useSidebar,
} from "@/components/ui/sidebar";
import { SIGNED_IN_PAGES, type SidebarGroupName, type SignedInPage } from "@/shell/signed-in-pages";

/**
 * A top-level sidebar item, its label set in capitals. The items stand with no
 * gap between them, the padding filling it, so each is hovered and clicked
 * edge to edge.
 */
const ITEM = "h-auto px-4 py-2.5 uppercase";

/** A link inside a sidebar group, as tightly packed as the items above it. */
const SUB_ITEM = "h-auto px-4 py-2 uppercase";

/** The accent a current page's link carries on its right edge. */
const ACTIVE_ACCENT =
  "data-[active=true]:border-r-4 data-[active=true]:border-sidebar-accent-foreground";

/** A top-level link, or a group of links, in the order the sidebar renders them. */
type Entry =
  | { kind: "link"; page: SignedInPage }
  | { kind: "group"; name: SidebarGroupName; pages: SignedInPage[] };

/** `pages` as the sidebar lays them out: a group stands where its first page would. */
function entriesOf(pages: readonly SignedInPage[]): Entry[] {
  const entries: Entry[] = [];
  for (const page of pages) {
    if (page.group === undefined) {
      entries.push({ kind: "link", page });
      continue;
    }
    const group = entries.find(
      (entry): entry is Extract<Entry, { kind: "group" }> =>
        entry.kind === "group" && entry.name === page.group,
    );
    if (group) group.pages.push(page);
    else entries.push({ kind: "group", name: page.group, pages: [page] });
  }
  return entries;
}

/** The icon a sidebar group's own button carries. */
const GROUP_ICONS = { Admin: ShieldCheck } as const;

/**
 * A collapsible group of links beneath its own label, open from the start.
 * Its button folds and unfolds the links, which grow open and shrink closed
 * (at once with reduced motion); on a sidebar folded to its icons,
 * where no link beneath it shows, the button opens the sidebar out and the
 * group with it.
 */
function LinkGroup({
  name,
  onFollow,
  pages,
  pathname,
}: {
  name: SidebarGroupName;
  onFollow: () => void;
  pages: SignedInPage[];
  pathname: string;
}) {
  const { isMobile, setOpen, state } = useSidebar();
  const [open, setGroupOpen] = useState(true);
  const Icon = GROUP_ICONS[name];
  const folded = !isMobile && state === "collapsed";
  return (
    <Collapsible
      asChild
      className="group/collapsible"
      onOpenChange={(next) => {
        if (folded) {
          setOpen(true);
          setGroupOpen(true);
        } else setGroupOpen(next);
      }}
      open={open}
    >
      <SidebarMenuItem>
        <CollapsibleTrigger asChild>
          <SidebarMenuButton className={ITEM} tooltip={name}>
            <Icon />
            <span>{name}</span>
            <ChevronDown className="ml-auto transition-transform group-data-[state=open]/collapsible:rotate-180" />
          </SidebarMenuButton>
        </CollapsibleTrigger>
        <CollapsibleContent className="overflow-hidden data-[state=closed]:animate-group-close data-[state=open]:animate-group-open motion-reduce:animate-none">
          <SidebarMenuSub className="ml-5 gap-0 px-3">
            {pages.map((page) => (
              <SidebarMenuSubItem key={page.path}>
                <SidebarMenuSubButton
                  asChild
                  className={`${SUB_ITEM} ${ACTIVE_ACCENT}`}
                  isActive={matchPath(page.path, pathname) != null}
                >
                  <Link onClick={onFollow} to={page.path}>
                    <page.icon />
                    <span>{page.homeLink}</span>
                  </Link>
                </SidebarMenuSubButton>
              </SidebarMenuSubItem>
            ))}
          </SidebarMenuSub>
        </CollapsibleContent>
      </SidebarMenuItem>
    </Collapsible>
  );
}

/**
 * The sidebar's collapse control, in its top-right corner: `<` folds it to its
 * icons, `>` opens it out again. On a phone, where the sidebar is a sheet, it
 * closes the sheet.
 */
function CollapseToggle() {
  const { isMobile, open, toggleSidebar } = useSidebar();
  const expanded = isMobile || open;
  return (
    <Button
      aria-expanded={expanded}
      aria-label={expanded ? "Collapse sidebar" : "Expand sidebar"}
      className="size-8"
      onClick={toggleSidebar}
      size="icon"
      variant="ghost"
    >
      {expanded ? <ChevronLeft /> : <ChevronRight />}
    </Button>
  );
}

/**
 * The navigation sidebar: a link to each signed-in page that carries a
 * `homeLink` and whose route guard would open it for this session (`mayOpen`),
 * the current page's marked, and at its foot Sign out, in `destructive` red.
 * A page that names a `group` is linked beneath that group's collapsible
 * button, which stands where the group's first page would and shows only for
 * a session that may open one of its pages.
 * Settings is the top bar's. Every label is set in capitals by CSS alone, so
 * each link's and Sign out's accessible name, and its tooltip, keep their own
 * case.
 *
 * It folds to its icons from the control in its top-right corner, each link
 * then naming its page in a tooltip, and Ctrl/⌘+B toggles it too. Every link
 * and Sign out keep their accessible names while folded. On a phone it is a
 * sheet the top bar's menu button opens, which closes on following a link.
 */
export function NavigationSidebar({
  onSignOut,
  pathname,
  signingOut,
}: {
  onSignOut: () => void;
  pathname: string;
  signingOut: boolean;
}) {
  const { user } = useAuth();
  const { setOpenMobile } = useSidebar();

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader className="h-16 flex-row items-center justify-end group-data-[collapsible=icon]:justify-center">
        <CollapseToggle />
      </SidebarHeader>
      <SidebarContent className="px-2 py-3">
        <SidebarMenu className="gap-0">
          {entriesOf(
            SIGNED_IN_PAGES.filter(
              (page) => page.homeLink && mayOpen(user, requirementOf(page.permissions)),
            ),
          ).map((entry) =>
            entry.kind === "group" ? (
              <LinkGroup
                key={entry.name}
                name={entry.name}
                onFollow={() => setOpenMobile(false)}
                pages={entry.pages}
                pathname={pathname}
              />
            ) : (
              <SidebarMenuItem key={entry.page.path}>
                <SidebarMenuButton
                  asChild
                  className={`${ITEM} ${ACTIVE_ACCENT}`}
                  isActive={matchPath(entry.page.path, pathname) != null}
                  tooltip={entry.page.homeLink}
                >
                  <Link onClick={() => setOpenMobile(false)} to={entry.page.path}>
                    <entry.page.icon />
                    <span>{entry.page.homeLink}</span>
                  </Link>
                </SidebarMenuButton>
              </SidebarMenuItem>
            ),
          )}
        </SidebarMenu>
      </SidebarContent>
      <SidebarFooter className="border-t">
        <SidebarMenu className="gap-0">
          <SidebarMenuItem>
            <SidebarMenuButton
              className="h-auto px-4 py-2.5 text-destructive uppercase hover:bg-destructive/10 hover:text-destructive active:bg-destructive/10 active:text-destructive"
              disabled={signingOut}
              onClick={onSignOut}
              tooltip="Sign out"
            >
              <LogOut />
              <span>{signingOut ? "Signing out…" : "Sign out"}</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
    </Sidebar>
  );
}

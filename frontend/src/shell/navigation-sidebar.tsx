import { ChevronLeft, ChevronRight, LogOut } from "lucide-react";
import { Link, matchPath } from "react-router-dom";

import { useAuth } from "@/auth/auth-context-value";
import { mayOpen, requirementOf } from "@/auth/permissions";
import { Button } from "@/components/ui/button";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from "@/components/ui/sidebar";
import { SIGNED_IN_PAGES } from "@/shell/signed-in-pages";

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
 * the current page's marked, and at its foot Sign out. Settings is the top
 * bar's.
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
        <SidebarMenu>
          {SIGNED_IN_PAGES.filter(
            (entry) => entry.homeLink && mayOpen(user, requirementOf(entry.permissions)),
          ).map((entry) => (
            <SidebarMenuItem key={entry.path}>
              <SidebarMenuButton
                asChild
                className="data-[active=true]:border-r-4 data-[active=true]:border-sidebar-accent-foreground"
                isActive={matchPath(entry.path, pathname) != null}
                tooltip={entry.homeLink}
              >
                <Link onClick={() => setOpenMobile(false)} to={entry.path}>
                  <entry.icon />
                  <span>{entry.homeLink}</span>
                </Link>
              </SidebarMenuButton>
            </SidebarMenuItem>
          ))}
        </SidebarMenu>
      </SidebarContent>
      <SidebarFooter className="border-t">
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton disabled={signingOut} onClick={onSignOut} tooltip="Sign out">
              <LogOut />
              <span>{signingOut ? "Signing out…" : "Sign out"}</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
    </Sidebar>
  );
}

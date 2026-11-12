import type { LucideIcon } from "lucide-react";
import { IdCard, ScrollText, Settings, Users } from "lucide-react";
import type { ReactNode } from "react";

import type { Permission } from "@/auth/api";
import { DEFAULT_DESTINATION, SETTINGS_PATH } from "@/auth/session-route";
import { Accounts } from "@/pages/accounts/accounts";
import { ADMINISTRATION_PERMISSIONS } from "@/pages/accounts/accounts-api";
import { Audit } from "@/pages/audit/audit";
import { AUDIT_EVENTS } from "@/pages/audit/audit-api";
import { ChangePassword } from "@/pages/change-password/change-password";
import { Home } from "@/pages/home/home";

/** A collapsible group of links in the navigation sidebar. */
export type SidebarGroupName = "Admin";

/** A signed-in page, and what the navigation sidebar reads to link it. */
export interface SignedInPage {
  element: ReactNode;
  /**
   * The collapsible sidebar group its link sits in, beneath the group's own
   * label; read only alongside `homeLink`. Omit to link it at the top level.
   */
  group?: SidebarGroupName;
  /** Its label in the navigation sidebar. Omit to leave it off the sidebar. */
  homeLink?: string;
  /** Its icon in the navigation sidebar; read only alongside `homeLink`. */
  icon: LucideIcon;
  path: string;
  /** Renders in the sidebar only for a session holding at least one of these. Omit for every signed-in session. */
  permissions?: readonly Permission[];
  title: string;
  /**
   * Keeps the title for screen readers only. Every page the sidebar or the
   * top bar reaches hides it, the link marking where the user is.
   */
  titleHidden?: boolean;
}

/** Every signed-in page but Home, in the order the sidebar links them. */
const PAGES_BEYOND_HOME: readonly SignedInPage[] = [
  {
    element: <Accounts />,
    group: "Admin",
    homeLink: "Accounts",
    icon: Users,
    path: "/accounts",
    permissions: ADMINISTRATION_PERMISSIONS,
    title: "Accounts",
    titleHidden: true,
  },
  {
    element: <Audit />,
    group: "Admin",
    homeLink: "Audit logs",
    icon: ScrollText,
    path: "/audit",
    permissions: [AUDIT_EVENTS.permission],
    title: "Audit",
    titleHidden: true,
  },
];

/**
 * The landing page, and what the shell falls back to for a path it does not
 * know. Its title is hidden: the patient banner heads the page.
 */
export const HOME_PAGE: SignedInPage = {
  element: <Home />,
  homeLink: "Profile",
  icon: IdCard,
  path: DEFAULT_DESTINATION,
  title: "Profile",
  titleHidden: true,
};

/**
 * Settings, which holds the password change; linked from the top bar rather
 * than the navigation sidebar. It holds both the voluntary change and the one
 * the change-required flag requires: a flagged session is confined to it.
 */
export const SETTINGS_PAGE: SignedInPage = {
  element: <ChangePassword />,
  icon: Settings,
  path: SETTINGS_PATH,
  title: "Settings",
  titleHidden: true,
};

/**
 * Every signed-in page, declared once: `App` turns each entry into a route
 * (behind its Permission guard, if any), `SignedInShell` reads the matching
 * entry for the title, and links each entry that carries a `homeLink` in the
 * navigation sidebar. A new signed-in page
 * is one entry here.
 */
export const SIGNED_IN_PAGES: readonly SignedInPage[] = [
  HOME_PAGE,
  ...PAGES_BEYOND_HOME,
  SETTINGS_PAGE,
];

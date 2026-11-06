import type { LucideIcon } from "lucide-react";
import { IdCard, KeyRound, ScrollText, Settings, Users } from "lucide-react";
import type { ReactNode } from "react";

import type { Permission } from "@/auth/api";
import { CREDENTIAL_CHANGE_PATH, DEFAULT_DESTINATION } from "@/auth/session-route";
import { Accounts } from "@/pages/accounts/accounts";
import { ADMINISTRATION_PERMISSIONS } from "@/pages/accounts/accounts-api";
import { Audit } from "@/pages/audit/audit";
import { AUDIT_EVENTS } from "@/pages/audit/audit-api";
import { ChangePassword } from "@/pages/change-password/change-password";
import { Home } from "@/pages/home/home";

/** A signed-in page, and what the navigation sidebar reads to link it. */
export interface SignedInPage {
  /**
   * The back link, offered unless the session is confined to the password
   * change. Only the password change has one: every other page is a sidebar
   * link away from the rest.
   */
  back?: { label: string; to: string };
  /** Centers the page vertically, for a page with little content. */
  centered?: boolean;
  element: ReactNode;
  /** Its label in the navigation sidebar. Omit to leave it off the sidebar. */
  homeLink?: string;
  /** Its icon in the navigation sidebar; read only alongside `homeLink`. */
  icon: LucideIcon;
  path: string;
  /** Renders in the sidebar only for a session holding at least one of these. Omit for every signed-in session. */
  permissions?: readonly Permission[];
  title: string;
  /**
   * Keeps the title for screen readers only. Every page the sidebar reaches
   * hides it, the sidebar marking where the user is; the password change a
   * confined session is held to shows it.
   */
  titleHidden?: boolean;
}

/** Every signed-in page but Home, in the order the sidebar links them. */
const PAGES_BEYOND_HOME: readonly SignedInPage[] = [
  {
    element: <Accounts />,
    homeLink: "Manage accounts",
    icon: Users,
    path: "/accounts",
    permissions: ADMINISTRATION_PERMISSIONS,
    title: "Accounts",
    titleHidden: true,
  },
  {
    element: <Audit />,
    homeLink: "View audit log",
    icon: ScrollText,
    path: "/audit",
    permissions: [AUDIT_EVENTS.permission],
    title: "Audit",
    titleHidden: true,
  },
  {
    back: { label: "Back", to: DEFAULT_DESTINATION },
    centered: true,
    element: <ChangePassword />,
    icon: KeyRound,
    path: CREDENTIAL_CHANGE_PATH,
    title: "Change password",
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
 * Settings, which holds the password change; linked from the foot of the
 * navigation sidebar and from the top bar rather than among the pages. The
 * change-required flag still confines a session to `/change-password`, the
 * same form on a page of its own.
 */
export const SETTINGS_PAGE: SignedInPage = {
  element: <ChangePassword />,
  icon: Settings,
  path: "/settings",
  title: "Settings",
  titleHidden: true,
};

/**
 * Every signed-in page, declared once: `App` turns each entry into a route
 * (behind its Permission guard, if any), `SignedInShell` reads the matching
 * entry for the title, back link and vertical alignment, and links each entry
 * that carries a `homeLink` in the navigation sidebar. A new signed-in page
 * is one entry here.
 */
export const SIGNED_IN_PAGES: readonly SignedInPage[] = [
  HOME_PAGE,
  ...PAGES_BEYOND_HOME,
  SETTINGS_PAGE,
];

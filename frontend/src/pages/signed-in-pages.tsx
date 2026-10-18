import type { ReactNode } from "react";

import { CREDENTIAL_CHANGE_PATH, DEFAULT_DESTINATION } from "@/auth/session-route";
import { Accounts } from "@/pages/accounts";
import { ADMINISTRATION_PERMISSIONS } from "@/pages/accounts-api";
import { Audit } from "@/pages/audit";
import { AUDIT_EVENTS } from "@/pages/audit-api";
import { ChangePassword } from "@/pages/change-password";
import { Home, type LinkablePage } from "@/pages/home";

/** The Tailwind max-width of a page column; the widths the signed-in pages use. */
type PageWidth = "max-w-md" | "max-w-2xl" | "max-w-6xl";

/** A signed-in page; the `LinkablePage` fields are what Home reads to link it. */
export interface SignedInPage extends LinkablePage {
  /** The back link, offered unless the session is confined to the password change. */
  back?: { label: string; to: string };
  /** Centers the page vertically, for the narrow pages. */
  centered?: boolean;
  element: ReactNode;
  title: string;
  width: PageWidth;
}

/** Every signed-in page but Home, in the order Home links them. */
const PAGES_BEYOND_HOME: readonly SignedInPage[] = [
  {
    back: { label: "Back to home", to: DEFAULT_DESTINATION },
    element: <Accounts />,
    homeLink: "Manage accounts",
    path: "/accounts",
    permissions: ADMINISTRATION_PERMISSIONS,
    title: "Accounts",
    width: "max-w-6xl",
  },
  {
    back: { label: "Back to home", to: DEFAULT_DESTINATION },
    element: <Audit />,
    homeLink: "View audit log",
    path: "/audit",
    permissions: [AUDIT_EVENTS.permission],
    title: "Audit",
    width: "max-w-6xl",
  },
  {
    back: { label: "Back", to: DEFAULT_DESTINATION },
    centered: true,
    element: <ChangePassword />,
    homeLink: "Change password",
    path: CREDENTIAL_CHANGE_PATH,
    title: "Change password",
    width: "max-w-md",
  },
];

/**
 * The landing page, and what the shell falls back to for a path it does not
 * know. It is handed every other page to link rather than reading this
 * registry, so Home imports nothing from here.
 */
export const HOME_PAGE: SignedInPage = {
  centered: true,
  element: <Home pages={PAGES_BEYOND_HOME} />,
  path: DEFAULT_DESTINATION,
  title: "Home",
  width: "max-w-2xl",
};

/**
 * Every signed-in page, declared once: `App` turns each entry into a route
 * (behind its Permission guard, if any), `SignedInShell` reads the matching
 * entry for the title, back link and column layout, and Home links each entry
 * that carries a `homeLink`. A new signed-in page is one entry here.
 */
export const SIGNED_IN_PAGES: readonly SignedInPage[] = [HOME_PAGE, ...PAGES_BEYOND_HOME];

import type { ReactNode } from "react";

import type { Permission } from "@/auth/api";
import { ADMINISTRATION_PERMISSIONS } from "@/auth/permissions";
import { CREDENTIAL_CHANGE_PATH, DEFAULT_DESTINATION } from "@/auth/session-route";
import { Accounts } from "@/pages/accounts";
import { Audit } from "@/pages/audit";
import { ChangePassword } from "@/pages/change-password";
import { Showcase } from "@/pages/showcase";

/** The Tailwind max-width of a page column; the widths the signed-in pages use. */
type PageWidth = "max-w-md" | "max-w-2xl" | "max-w-6xl";

export interface SignedInPage {
  /** The back link, offered unless the session is confined to the password change. */
  back?: { label: string; to: string };
  /** Centers the page vertically, for the narrow pages. */
  centered?: boolean;
  element: ReactNode;
  path: string;
  /** Renders only for a session holding at least one of these. Omit for every signed-in session. */
  permissions?: readonly Permission[];
  title: string;
  width: PageWidth;
}

/** The landing page, and what the shell falls back to for a path it does not know. */
export const SHOWCASE_PAGE: SignedInPage = {
  centered: true,
  element: <Showcase />,
  path: DEFAULT_DESTINATION,
  title: "Front End",
  width: "max-w-2xl",
};

/**
 * Every signed-in page, declared once: `App` turns each entry into a route
 * (behind its Permission guard, if any) and `SignedInShell` reads the matching
 * entry for the title, back link and column layout. A new signed-in page is one
 * entry here.
 */
export const SIGNED_IN_PAGES: readonly SignedInPage[] = [
  SHOWCASE_PAGE,
  {
    back: { label: "Back to counter", to: DEFAULT_DESTINATION },
    element: <Accounts />,
    path: "/accounts",
    permissions: ADMINISTRATION_PERMISSIONS,
    title: "Accounts",
    width: "max-w-6xl",
  },
  {
    back: { label: "Back to showcase", to: DEFAULT_DESTINATION },
    element: <Audit />,
    path: "/audit",
    permissions: ["audit:read"],
    title: "Audit",
    width: "max-w-6xl",
  },
  {
    back: { label: "Back", to: DEFAULT_DESTINATION },
    centered: true,
    element: <ChangePassword />,
    path: CREDENTIAL_CHANGE_PATH,
    title: "Change password",
    width: "max-w-md",
  },
];

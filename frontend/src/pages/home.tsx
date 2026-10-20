import { Link } from "react-router-dom";

import type { Permission } from "@/auth/api";
import { useAuth } from "@/auth/auth-context-value";
import { mayOpen, requirementOf } from "@/auth/permissions";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

import { PatientCard } from "./patient-card";

const LINK_CLASS = "text-sm font-medium underline underline-offset-4";

/** A signed-in page as Home reads it to link it. */
export interface LinkablePage {
  /** Its link's label on Home, which links it for every session that may open it. Omit to leave it off Home. */
  homeLink?: string;
  path: string;
  /** Renders only for a session holding at least one of these. Omit for every signed-in session. */
  permissions?: readonly Permission[];
}

/**
 * Home, the signed-in landing page at `/`.
 *
 * Every signed-in User lands here. The Patient card comes first: the patient in
 * context, read with `patient:read`, a baseline Permission every active User
 * holds. Nothing puts a patient in context yet, so the card shows "No patient
 * in context" and Home asks for nothing. Beneath it the Pages card links each page it is
 * handed (the other `SIGNED_IN_PAGES` entries, in registry order) that carries
 * a link label, exactly when that page's route guard would open it for this
 * session (`mayOpen`). It is handed them rather than importing the registry,
 * which imports Home.
 */
export function Home({ pages }: { pages: readonly LinkablePage[] }) {
  const { user } = useAuth();

  return (
    <div className="flex w-full flex-col gap-4">
      <PatientCard />
      <Card className="w-full">
        <CardHeader>
          <CardTitle>Pages</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-4">
          {pages
            .filter((page) => page.homeLink && mayOpen(user, requirementOf(page.permissions)))
            .map((page) => (
              <Link className={LINK_CLASS} key={page.path} to={page.path}>
                {page.homeLink}
              </Link>
            ))}
        </CardContent>
      </Card>
    </div>
  );
}

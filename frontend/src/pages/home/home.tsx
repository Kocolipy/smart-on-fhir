import { Link } from "react-router-dom";

import type { Permission } from "@/auth/api";
import { useAuth } from "@/auth/auth-context-value";
import { useEhrContext } from "@/auth/ehr-context";
import { mayOpen, requirementOf } from "@/auth/permissions";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

import { FhircastCard } from "./fhircast-card";
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
 * Every signed-in User lands here. The Patient card comes first: the patient of
 * the tab's EHR context (`ehr-context.ts`), which an EHR launch's Launch
 * context seeded and the FHIRcast hub's events move, read with `patient:read`, a baseline Permission every active
 * User holds. With no patient in context — a password Login, or a launch that
 * named none — the card shows "No patient in context" and Home asks for
 * nothing. Beside it, whenever the tab holds an EHR context at all, the
 * FHIRcast card shows the session's FHIRcast connection (`fhircast-card.tsx`);
 * a password session shows none. Home reads the EHR context through its
 * subscribable store (`useEhrContext`), so both re-render when it changes, not
 * only on load. Beneath them the Pages card links each page it is handed (the
 * other `SIGNED_IN_PAGES` entries, in registry order) that carries a link
 * label, exactly when that page's route guard would open it for this session
 * (`mayOpen`). It is handed them rather than importing the registry, which
 * imports Home.
 */
export function Home({ pages }: { pages: readonly LinkablePage[] }) {
  const { user } = useAuth();
  const context = useEhrContext();

  return (
    <div className="flex w-full flex-col gap-4">
      <div className="flex w-full flex-col gap-4 md:flex-row">
        <PatientCard patientId={context?.patient} />
        {context !== null && <FhircastCard />}
      </div>
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

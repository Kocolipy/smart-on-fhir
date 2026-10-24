import { Link } from "react-router-dom";

import type { Permission } from "@/auth/api";
import { useAuth } from "@/auth/auth-context-value";
import { landedFromEpic, launchContext } from "@/auth/launch-context";
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
 * the tab's Launch context (`launch-context.ts`), which an EHR launch put
 * there, read with `patient:read`, a baseline Permission every active User
 * holds. With no patient in context — a password Login, or a launch that named
 * none — the card shows "No patient in context" and Home asks for nothing. The
 * Launch context changes only on load and when the session ends, so it is read
 * as Home renders. Beside it, in a session an EHR launch began
 * (`landedFromEpic`), the FHIRcast card connects to Epic's local FHIRcast hub
 * (`fhircast-card.tsx`); a password session shows none. Beneath them the Pages
 * card links each page it is
 * handed (the other `SIGNED_IN_PAGES` entries, in registry order) that carries
 * a link label, exactly when that page's route guard would open it for this
 * session (`mayOpen`). It is handed them rather than importing the registry,
 * which imports Home.
 */
export function Home({ pages }: { pages: readonly LinkablePage[] }) {
  const { user } = useAuth();

  return (
    <div className="flex w-full flex-col gap-4">
      <div className="flex w-full flex-col gap-4 md:flex-row">
        <PatientCard patientId={launchContext().patient} />
        {landedFromEpic() && <FhircastCard />}
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

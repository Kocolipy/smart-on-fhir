import { useEhrContext } from "@/auth/ehr-context";

import { FhircastCard } from "./fhircast-card";
import { PatientCard } from "./patient-card";

/**
 * Home, the signed-in landing page at `/`.
 *
 * Every signed-in User lands here. The Patient card comes first: the patient of
 * the tab's EHR context (`ehr-context.ts`), which an EHR launch's Launch
 * context seeded and the FHIRcast hub's events move, read with `patient:read`,
 * a baseline Permission every active User holds — read once, by the signed-in
 * layout (`patient-in-context.ts`), for the card and the top bar both. With no
 * patient in context — a password Login, or a launch that named none — the
 * card shows "No patient in context" and nothing is asked for. Beside it, whenever the tab holds an EHR context at all, the
 * FHIRcast card shows the session's FHIRcast connection (`fhircast-card.tsx`);
 * a password session shows none. Home reads the EHR context through its
 * subscribable store (`useEhrContext`), so the FHIRcast card comes and goes as
 * it changes, not only on load. The other signed-in pages are linked from the navigation
 * sidebar (`signed-in-shell.tsx`), not from here.
 */
export function Home() {
  const context = useEhrContext();

  return (
    <div className="flex w-full flex-col gap-4">
      <PatientCard />
      {context !== null && <FhircastCard />}
    </div>
  );
}

import { type ReadView, useGatedRead } from "@/auth/use-gated-read";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { type Clock, completedYears, formatCalendarDate, today, systemClock } from "@/lib/time";

import { DEFAULT_PATIENT_ID, PATIENT, type PatientGender, type PatientSummary } from "./home-api";

const NOTE_CLASS = "text-sm text-muted-foreground";

const GENDER_LABELS: Readonly<Record<PatientGender, string>> = {
  female: "Female",
  male: "Male",
  other: "Other",
  unknown: "Unknown",
};

/**
 * The patient's fields, each a plain text node: whatever the backend sent is
 * shown as the text it is, never interpreted as markup.
 */
function PatientFields({ clock, patient }: { clock: Clock; patient: PatientSummary }) {
  const age = completedYears(patient.birthDate, today(clock));
  const rows: [label: string, value: string][] = [
    ["Name", patient.name],
    ["NRIC", patient.nric],
    ["Gender", GENDER_LABELS[patient.gender]],
    ["Date of birth", `${formatCalendarDate(patient.birthDate)} (${age} y)`],
  ];
  return (
    <dl className="flex flex-col gap-1 text-sm">
      {rows.map(([label, value]) => (
        <div className="flex gap-4" key={label}>
          <dt className="w-28 shrink-0 text-muted-foreground">{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * What the card shows for each state of the read. A failure shows nothing
 * here: its copy is on the page's error line, and the card offers no retry.
 */
function PatientReading({ clock, view }: { clock: Clock; view: ReadView<PatientSummary, true> }) {
  switch (view.kind) {
    case "not-permitted":
      return <p className={NOTE_CLASS}>Your account has no access to patients.</p>;
    case "loading":
      return <p className={NOTE_CLASS}>Loading the patient…</p>;
    case "absent":
      return <p className={NOTE_CLASS}>No patient in context</p>;
    case "failed":
      return null;
    default:
      // `ready`, or `stale` — which this card never produces, as it never re-reads.
      return <PatientFields clock={clock} patient={view.data} />;
  }
}

/**
 * Home's Patient card: the default patient, read once on mount with
 * `patient:read`, until a SMART launch supplies the patient in context.
 *
 * A `404` is "No patient in context", which is not an error; any other
 * failure — a `403` too, since the card reads only once `patient:read` is
 * held — goes to the page's one error line as "Couldn't load patient". Age is derived from the birth
 * date as completed years on today's date in Singapore, read from `clock`.
 */
export function PatientCard({ clock = systemClock }: { clock?: Clock }) {
  const { view } = useGatedRead(PATIENT, {
    args: [DEFAULT_PATIENT_ID],
    failureMessage: "Couldn't load patient",
    forbiddenAsFailure: true,
  });
  return (
    <Card className="w-full">
      <CardHeader>
        <CardTitle>Patient</CardTitle>
      </CardHeader>
      <CardContent>
        <PatientReading clock={clock} view={view} />
      </CardContent>
    </Card>
  );
}

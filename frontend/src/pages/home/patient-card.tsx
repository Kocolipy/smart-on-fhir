import type { ReactNode } from "react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { type Clock, completedYears, formatCalendarDate, today, systemClock } from "@/lib/time";

import type { PatientGender, PatientSummary } from "./home-api";
import { usePatientInContext } from "./patient-in-context";

const NOTE_CLASS = "text-sm text-muted-foreground";

const NO_PATIENT = <p className={NOTE_CLASS}>No patient in context</p>;

const GENDER_LABELS: Readonly<Record<PatientGender, string>> = {
  female: "Female",
  male: "Male",
  other: "Other",
  unknown: "Unknown",
};

/** The card headed Patient, holding `children` — a note, or nothing. */
function PlainCard({ children }: { children: ReactNode }) {
  return (
    <Card className="w-full">
      <CardHeader>
        <CardTitle>Patient</CardTitle>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

/**
 * The loaded patient as a banner: their name as its heading, and beneath it
 * their fields, each a plain text node — whatever the backend sent is shown as
 * the text it is, never interpreted as markup.
 */
function PatientBanner({ clock, patient }: { clock: Clock; patient: PatientSummary }) {
  const rows: [label: string, value: string][] = [
    ["NRIC", patient.nric],
    ["Gender", GENDER_LABELS[patient.gender]],
    ["Age", String(completedYears(patient.birthDate, today(clock)))],
    ["Date of birth", formatCalendarDate(patient.birthDate)],
  ];
  return (
    <section
      aria-label="Patient"
      className="flex w-full flex-col gap-6 rounded-xl bg-linear-to-r from-ring to-primary p-5 text-primary-foreground shadow-md"
    >
      <h2 className="text-4xl font-black tracking-tight">{patient.name}</h2>
      <dl className="flex flex-wrap gap-x-10 gap-y-4 text-sm">
        {rows.map(([label, value]) => (
          <div className="flex flex-col gap-1" key={label}>
            <dt className="text-[10px] font-bold tracking-widest text-primary-foreground/60 uppercase">
              {label}
            </dt>
            <dd className="font-semibold">{value}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

/**
 * Home's Patient card: the patient in context, as a banner headed by their
 * name, or a plain card headed Patient saying "No patient in context" when
 * there is none. It renders from the signed-in layout's one read of the
 * patient in context (`usePatientInContext`), which the top bar renders from
 * too, and reads nothing itself; so it re-renders as that read moves — as the
 * FHIRcast hub's `Patient-open` and `Patient-close` move the tab's EHR context,
 * and as a session ending clears it.
 *
 * A new patient in context is a new read: the card shows "Loading the
 * patient…" at once, never the patient before, and an answer for an earlier
 * patient that arrives late never lands. A `404` is "No patient in context"
 * too, which is not an error; any other failure leaves the card empty, with no
 * retry — its copy, "Couldn't load patient", is on the page's one error line,
 * reported once, by the read. Age is derived from the birth date as completed
 * years on today's date in Singapore, read from `clock`.
 */
export function PatientCard({ clock = systemClock }: { clock?: Clock }) {
  const view = usePatientInContext();
  switch (view.kind) {
    case "none":
    case "absent":
      return <PlainCard>{NO_PATIENT}</PlainCard>;
    case "not-permitted":
      return (
        <PlainCard>
          <p className={NOTE_CLASS}>Your account has no access to patients.</p>
        </PlainCard>
      );
    case "loading":
      return (
        <PlainCard>
          <p className={NOTE_CLASS}>Loading the patient…</p>
        </PlainCard>
      );
    case "failed":
      return <PlainCard>{null}</PlainCard>;
    default:
      // `ready`, or `stale` — which this read never produces: a new patient is
      // a new path, so a new first read, and the same patient is never re-read.
      return <PatientBanner clock={clock} patient={view.data} />;
  }
}

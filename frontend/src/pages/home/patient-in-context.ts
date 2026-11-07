/**
 * The **patient in context**, read once: the one reader of the tab's EHR
 * context's patient (`@/auth/ehr-context`), whose view the top bar and Home's
 * Patient card both render from.
 *
 * The gated read keeps no shared cache, so two readers of one patient would
 * send two `GET /api/patient/{id}` — each a call to the uncached patient
 * source — and report a failure twice. So the signed-in layout renders
 * {@link PatientInContextScope} once, inside its page status, and everything
 * beneath it reads the one view through {@link usePatientInContext}; nothing
 * else reads the patient.
 *
 * With a patient in context it reads `GET /api/patient/{id}` through `PATIENT`
 * with `patient:read`: once when the patient comes into context, and again
 * only when the patient in context changes. A new patient is a new path, so a
 * new first read, and a late answer for the patient before never lands (the
 * newest read wins); the same patient opened again is no new read. A `404` is
 * `absent`, which reports nothing; any other failure — a `403` too, since the
 * read is sent only once `patient:read` is held — goes to the page's one error
 * line as "Couldn't load patient", reported by this read alone. Without a
 * patient in context it reads nothing.
 */

import {
  createContext,
  createElement,
  Fragment,
  memo,
  useContext,
  useLayoutEffect,
  useState,
  type ReactNode,
} from "react";

import { useAuth } from "@/auth/auth-context-value";
import { useEhrContext } from "@/auth/ehr-context";
import { holds } from "@/auth/permissions";
import { type ReadView, useGatedRead } from "@/auth/use-gated-read";

import { PATIENT, type PatientSummary } from "./home-api";

/**
 * What the read of the patient in context has to show: `none` with no patient
 * in context, so nothing was asked, or the gated read's own view of the patient
 * there. Never `stale`: a new patient is a new first read, and the same patient
 * is never re-read.
 */
export type PatientInContextView = { kind: "none" } | ReadView<PatientSummary, true>;

/** The gated read's view, and the patient it is the view of. */
interface Read {
  patientId: string;
  view: ReadView<PatientSummary, true>;
}

const NONE: PatientInContextView = { kind: "none" };

const PatientInContextContext = createContext<PatientInContextView | null>(null);

/**
 * The one read, sent only while a patient is in context: it renders nothing,
 * and hands its view up to the scope each time it renders, before the browser
 * paints. Memoised, so the scope re-rendering with what it was handed never
 * renders it again: it renders, and hands up, only as its own read moves.
 */
const ReadPatient = memo(function ReadPatient({
  onRead,
  patientId,
}: {
  onRead: (read: Read) => void;
  patientId: string;
}) {
  const { view } = useGatedRead(PATIENT, {
    args: [patientId],
    failureMessage: "Couldn't load patient",
    forbiddenAsFailure: true,
  });
  useLayoutEffect(() => onRead({ patientId, view }), [onRead, patientId, view]);
  return null;
});

/**
 * The patient in context, read once for everything rendered inside it. The
 * signed-in layout renders it, inside the page status its failure reports to,
 * around the top bar and the page alike.
 *
 * The read is a sibling of `children`, never their parent, so a patient coming
 * into context or leaving it mounts or unmounts the read alone and never the
 * page. Until the read has handed up its view of a newly opened patient, the
 * view is the one a first read of them starts with — never the patient before.
 */
export function PatientInContextScope({ children }: { children: ReactNode }): ReactNode {
  const patientId = useEhrContext()?.patient;
  const { user } = useAuth();
  // `setRead` is stable, so `ReadPatient` is always handed the same `onRead`.
  const [read, setRead] = useState<Read | null>(null);

  let view: PatientInContextView;
  if (patientId === undefined) view = NONE;
  else if (read?.patientId === patientId) view = read.view;
  else view = holds(user, PATIENT.permission) ? { kind: "loading" } : { kind: "not-permitted" };

  return createElement(
    PatientInContextContext.Provider,
    { value: view },
    createElement(
      Fragment,
      null,
      patientId === undefined ? null : createElement(ReadPatient, { onRead: setRead, patientId }),
      children,
    ),
  );
}

/**
 * The view of the patient in context, re-rendering the caller when it moves.
 * Throws outside a {@link PatientInContextScope}, where nothing reads it.
 */
export function usePatientInContext(): PatientInContextView {
  const view = useContext(PatientInContextContext);
  if (view === null) {
    throw new Error("The patient in context is read only inside the signed-in layout.");
  }
  return view;
}

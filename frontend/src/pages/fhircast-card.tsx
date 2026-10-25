import { useSyncExternalStore } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  fhircastSnapshot,
  retryFhircast,
  watchFhircast,
  type FhircastStatus,
  type PatientEvent,
} from "@/lib/fhircast";
import { formatInstant } from "@/lib/time";

/**
 * Only these generic lines are ever shown: what a hub or Epic said about a
 * refusal, and any response body, is neither rendered nor logged (ADR 0016).
 */
const COPY: Record<FhircastStatus, string> = {
  blocked: "Local hub blocked by browser",
  connected: "Connected",
  connecting: "Connecting",
  disconnected: "Disconnected",
  reconnecting: "Reconnecting",
  unavailable: "FHIRcast unavailable",
};

/** The states the last event stays shown in: connected, and on the way back to it. */
const SHOWS_LAST_EVENT: readonly FhircastStatus[] = ["connected", "reconnecting"];

/**
 * The last event, each field a plain text node: the event's name, its time in
 * Singapore, and the Patient id when the event named one in the grammar.
 */
function LastEvent({ event }: { event: PatientEvent }) {
  const rows: [label: string, value: string][] = [
    ["Event", event.event],
    ["Time", formatInstant(event.timestamp)],
  ];
  if (event.patientId !== null) rows.push(["Patient", event.patientId]);
  return (
    <div aria-label="Last event" role="group">
      <dl className="mt-2 flex flex-col gap-1 text-sm">
        {rows.map(([label, value]) => (
          <div className="flex gap-4" key={label}>
            <dt className="w-28 shrink-0 text-muted-foreground">{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

/**
 * The FHIRcast card, on Home in a tab that holds an EHR context: a pure view
 * of the session's FHIRcast connection, the FHIRcast module's singleton
 * (`@/lib/fhircast`), which the signed-in layout starts and the end of the
 * session stops (`useSessionFhircast`). It shows how far connecting to
 * Epic's local FHIRcast hub got. Connected, it stays connected through
 * renewals unseen; a dropped connection shows as reconnecting, and as
 * disconnected once the module's retries run out, with a Retry that connects
 * again from the start. While connected or reconnecting it shows the last
 * `Patient-open` or `Patient-close` the hub sent. The connection outlives
 * the card, so leaving Home and coming back neither reconnects nor loses the
 * last event, and one sent meanwhile shows on return; a reload, or a Retry,
 * starts with none.
 */
export function FhircastCard() {
  const { last, status } = useSyncExternalStore(watchFhircast, fhircastSnapshot);

  return (
    <Card className="w-full">
      <CardHeader>
        <CardTitle id="fhircast-card-title">FHIRcast</CardTitle>
      </CardHeader>
      <CardContent>
        <p aria-labelledby="fhircast-card-title" className="text-sm" role="status">
          {COPY[status]}
        </p>
        {status === "disconnected" && (
          <Button className="mt-2" onClick={retryFhircast} size="sm" variant="outline">
            Retry
          </Button>
        )}
        {SHOWS_LAST_EVENT.includes(status) && last !== null && <LastEvent event={last} />}
      </CardContent>
    </Card>
  );
}

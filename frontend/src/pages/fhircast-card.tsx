import { useEffect, useState } from "react";

import { useFhircastRelease } from "@/auth/use-fhircast-release";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { connectFhircast, type PatientEvent } from "@/lib/fhircast";
import { formatInstant } from "@/lib/time";

/** The card's states: where connecting to the local hub has got to. */
type FhircastState = "connecting" | "connected" | "unavailable" | "blocked";

/**
 * Only these generic lines are ever shown: what a hub or Epic said about a
 * refusal, and any response body, is neither rendered nor logged (ADR 0016).
 */
const COPY: Record<FhircastState, string> = {
  blocked: "Local hub blocked by browser",
  connected: "Connected",
  connecting: "Connecting",
  unavailable: "FHIRcast unavailable",
};

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
 * The FHIRcast card, on Home in an Epic session: asks the backend for the
 * release, then connects to Epic's local FHIRcast hub through the FHIRcast
 * module (`@/lib/fhircast`), and shows how far it got. Once on mount; the
 * socket is closed when Home goes, and a connection the hub drops shows as
 * unavailable. While connected it shows the last `Patient-open` or
 * `Patient-close` the hub sent, held in this component's state alone: a
 * reload starts with none.
 */
export function FhircastCard() {
  const release = useFhircastRelease();
  const [state, setState] = useState<FhircastState>("connecting");
  const [last, setLast] = useState<PatientEvent | null>(null);

  useEffect(() => {
    // Home gone before the release answered connects to nothing; gone while
    // connecting closes the socket as soon as it is open. A state set after
    // Home has gone changes nothing.
    let mounted = true;
    let close = () => undefined as void;
    void (async () => {
      const released = await release();
      if (!mounted) return;
      if (released === null) {
        setState("unavailable");
        return;
      }
      const outcome = await connectFhircast(released, {
        onEvent: setLast,
        onLost: () => setState("unavailable"),
      });
      if (outcome.kind === "connected") close = outcome.close;
      if (!mounted) close();
      setState(outcome.kind);
    })();
    return () => {
      mounted = false;
      close();
    };
  }, [release]);

  return (
    <Card className="w-full">
      <CardHeader>
        <CardTitle id="fhircast-card-title">FHIRcast</CardTitle>
      </CardHeader>
      <CardContent>
        <p aria-labelledby="fhircast-card-title" className="text-sm" role="status">
          {COPY[state]}
        </p>
        {state === "connected" && last !== null && <LastEvent event={last} />}
      </CardContent>
    </Card>
  );
}

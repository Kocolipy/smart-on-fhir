import { useEffect, useState } from "react";

import { useFhircastRelease } from "@/auth/use-fhircast-release";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { connectFhircast, type PatientEvent } from "@/lib/fhircast";
import { formatInstant } from "@/lib/time";

/** The card's states: where connecting to the local hub, and staying connected, has got to. */
type FhircastState =
  "connecting" | "connected" | "reconnecting" | "disconnected" | "unavailable" | "blocked";

/**
 * Only these generic lines are ever shown: what a hub or Epic said about a
 * refusal, and any response body, is neither rendered nor logged (ADR 0016).
 */
const COPY: Record<FhircastState, string> = {
  blocked: "Local hub blocked by browser",
  connected: "Connected",
  connecting: "Connecting",
  disconnected: "Disconnected",
  reconnecting: "Reconnecting",
  unavailable: "FHIRcast unavailable",
};

/** The states the last event stays shown in: connected, and on the way back to it. */
const SHOWS_LAST_EVENT: readonly FhircastState[] = ["connected", "reconnecting"];

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
 * connection is closed when Home goes. Connected, it stays connected through
 * renewals unseen; a dropped connection shows as reconnecting, and as
 * disconnected once the module's retries run out, with a retry that connects
 * again from the start. While connected or reconnecting it shows the last
 * `Patient-open` or `Patient-close` the hub sent, held in this component's
 * state alone: a reload, or a retry, starts with none.
 */
export function FhircastCard() {
  const release = useFhircastRelease();
  const [state, setState] = useState<FhircastState>("connecting");
  const [last, setLast] = useState<PatientEvent | null>(null);
  // The connection's identity, a fresh object for each Retry: the effect
  // connects again whenever it changes.
  const [connectionKey, setConnectionKey] = useState({});

  useEffect(() => {
    // Home gone before the release answered connects to nothing; gone while
    // connecting closes the socket as soon as it is open. A state set after
    // Home has gone changes nothing.
    let mounted = true;
    let close: () => void = () => undefined;
    void (async () => {
      const released = await release();
      if (!mounted) return;
      if (released === null) {
        setState("unavailable");
        return;
      }
      const outcome = await connectFhircast(released, {
        onConnection: setState,
        onEvent: setLast,
      });
      if (outcome.kind === "connected") close = outcome.close;
      if (!mounted) close();
      setState(outcome.kind);
    })();
    return () => {
      mounted = false;
      close();
    };
  }, [release, connectionKey]);

  const retry = () => {
    setLast(null);
    setState("connecting");
    setConnectionKey({});
  };

  return (
    <Card className="w-full">
      <CardHeader>
        <CardTitle id="fhircast-card-title">FHIRcast</CardTitle>
      </CardHeader>
      <CardContent>
        <p aria-labelledby="fhircast-card-title" className="text-sm" role="status">
          {COPY[state]}
        </p>
        {state === "disconnected" && (
          <Button className="mt-2" onClick={retry} size="sm" variant="outline">
            Retry
          </Button>
        )}
        {SHOWS_LAST_EVENT.includes(state) && last !== null && <LastEvent event={last} />}
      </CardContent>
    </Card>
  );
}

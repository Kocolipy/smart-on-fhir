import { Link } from "react-router-dom";

import type { Permission } from "@/auth/api";
import { useAuth } from "@/auth/auth-context-value";
import { holds, holdsAny } from "@/auth/permissions";
import { CREDENTIAL_CHANGE_PATH } from "@/auth/session-route";
import { type ReadView, useGatedRead } from "@/auth/use-gated-read";
import { useGatedWrite } from "@/auth/use-gated-write";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

import { ADMINISTRATION_PERMISSIONS } from "./accounts-api";
import { AUDIT_EVENTS } from "./audit-api";
import { COUNTER, INCREMENT_COUNTER, RESET_COUNTER } from "./showcase-api";

const LINK_CLASS = "text-sm font-medium underline underline-offset-4";

/**
 * The counter's state and the two changes to it. The current count is read
 * once, on mount, and only with `counter:read` — without it the read would
 * only be refused, so it is never asked for. Both report to the page's one
 * error line, which the signed-in layout owns and renders, where the latest
 * failure shows: a change starting withdraws the read's, and its own refusal
 * replaces it.
 */
function useCounter() {
  const reading = useGatedRead(COUNTER, {
    failureMessage: "Unable to load the counter. Please try again.",
  });
  const write = useGatedWrite();

  const updateCount = (change: typeof INCREMENT_COUNTER) =>
    write.run(change, {
      messages: { default: "Unable to update the counter. Please try again." },
      onOk: (data) => reading.update(() => data),
    });

  return {
    increment: () => void updateCount(INCREMENT_COUNTER),
    isUpdating: write.pending,
    reset: () => void updateCount(RESET_COUNTER),
    view: reading.view,
  };
}

const NOTE_CLASS = "text-sm text-muted-foreground";

/**
 * The count, once read. Nothing else ever stands in for it: while the first
 * read is in flight the page says so, and after a failed one the error line
 * is all there is — a count the page has not read is never shown.
 */
function CounterReading({ view }: { view: ReadView<number> }) {
  if (view.kind === "not-permitted") {
    return (
      <p className={NOTE_CLASS}>You are signed in. Your account has no access to the counter.</p>
    );
  }
  if (view.kind === "loading") return <p className={NOTE_CLASS}>Loading the counter…</p>;
  if (view.kind === "failed") return null;
  // `ready`, or `stale` — which this page never produces, as it never re-reads.
  return (
    <p className={NOTE_CLASS} data-testid="count">
      Clicked {view.data} {view.data === 1 ? "time" : "times"}
    </p>
  );
}

/**
 * Increment and Reset, each offered only with the Permission its own
 * declaration requires (today `counter:write` for both), and only against a
 * count: disabled while the first read is in flight, and not offered at all
 * when it failed, since the count they would change is unknown.
 */
function CounterControls({
  increment,
  isUpdating,
  may,
  reset,
  view,
}: {
  increment: () => void;
  isUpdating: boolean;
  /** Whether the session may send a declared request, so whether its control is offered. */
  may: (request: { permission: Permission }) => boolean;
  reset: () => void;
  view: ReadView<number>;
}) {
  if (view.kind === "not-permitted" || view.kind === "failed") return null;
  const count = view.kind === "loading" ? null : view.data;
  const disabled = isUpdating || count === null;
  return (
    <>
      {may(INCREMENT_COUNTER) ? (
        <Button onClick={increment} disabled={disabled}>
          Increment
        </Button>
      ) : null}
      {may(RESET_COUNTER) ? (
        <Button variant="outline" onClick={reset} disabled={disabled || count === 0}>
          Reset
        </Button>
      ) : null}
    </>
  );
}

/**
 * The original home page, now available to authenticated users at /showcase.
 *
 * Every signed-in User lands here; what it shows follows the session's
 * Permissions. The counter is read only with `counter:read` and changed only
 * with `counter:write` — baseline Permissions every active User holds, so in
 * practice every signed-in User has the counter — and the link to the Accounts
 * page appears only for a User who may see one of its views.
 */
export function Showcase() {
  const { user } = useAuth();
  const { increment, isUpdating, reset, view } = useCounter();

  return (
    <Card className="w-full">
      <CardHeader>
        <CardTitle>Baseline is live</CardTitle>
        <CardDescription>React + Vite + Tailwind, with the tooling gates wired up.</CardDescription>
      </CardHeader>
      <CardContent>
        <CounterReading view={view} />
      </CardContent>
      <CardFooter className="gap-2">
        <CounterControls
          increment={increment}
          isUpdating={isUpdating}
          may={(request) => holds(user, request.permission)}
          reset={reset}
          view={view}
        />
        <div className="ml-auto flex gap-4">
          {holdsAny(user, ADMINISTRATION_PERMISSIONS) ? (
            <Link className={LINK_CLASS} to="/accounts">
              Manage accounts
            </Link>
          ) : null}
          {holds(user, AUDIT_EVENTS.permission) ? (
            <Link className={LINK_CLASS} to="/audit">
              View audit log
            </Link>
          ) : null}
          <Link className={LINK_CLASS} to={CREDENTIAL_CHANGE_PATH}>
            Change password
          </Link>
        </div>
      </CardFooter>
    </Card>
  );
}
